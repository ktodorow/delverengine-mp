package com.interrupt.dungeoneer.multiplayer.economy;

import com.badlogic.gdx.utils.Array;
import com.interrupt.api.steam.SteamApi;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.BagUpgrade;
import com.interrupt.dungeoneer.entities.items.Elixer;
import com.interrupt.dungeoneer.entities.triggers.TriggeredShop;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController;
import com.interrupt.dungeoneer.multiplayer.combat.NativeCombatAuthority;
import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.ItemActionResult;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.overlays.LevelUpOverlay;
import com.interrupt.dungeoneer.overlays.Overlay;
import com.interrupt.dungeoneer.overlays.OverlayManager;
import com.interrupt.dungeoneer.overlays.ShopOverlay;
import com.interrupt.dungeoneer.rpg.Stats;
import com.interrupt.helpers.ShopItem;
import com.interrupt.managers.ItemManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-thread bridge from native rewards, level-ups and shops to Host Campaign Slot economy.
 * Host applies each original rule once; every peer only mirrors accepted state into native UI.
 */
public final class DirectConnectEconomyController implements Player.ProgressionAuthorityListener,
        ItemManager.LootRollSelector, TriggeredShop.ShopAuthority, ShopOverlay.PurchaseAuthority,
        ParticipantProgressResolver, DirectConnectItemController.EconomyBoundary {
    /** Shops are USE triggers; the buyer may drift slightly while the personal overlay is open. */
    public static final float SHOP_PURCHASE_REACH = 3f;
    private static final int MAX_PENDING_PURCHASES = 16;

    private static final class ViewEntry {
        final long shopId, revision;
        final int entryId;
        final ShopItem item;
        ViewEntry(long shopId, int entryId, long revision, ShopItem item) {
            this.shopId = shopId; this.entryId = entryId; this.revision = revision; this.item = item;
        }
    }

    /** Native Delver overlays; headless checks substitute it because overlays load UI textures. */
    public interface NativeInterface {
        void showLevelUp(Player player, Runnable closed);
        void showShop(TriggeredShop shop, String dialogueFile, Array<ShopItem> stock,
                ShopOverlay.PurchaseAuthority authority);
    }

    private static final NativeInterface NATIVE_OVERLAYS = new NativeInterface() {
        @Override public void showLevelUp(Player player, final Runnable closed) {
            if(OverlayManager.instance == null) return;
            OverlayManager.instance.push(new LevelUpOverlay(player) {
                @Override public void onHide() {
                    super.onHide();
                    closed.run();
                }
            });
        }

        @Override public void showShop(TriggeredShop shop, String dialogueFile, Array<ShopItem> stock,
                ShopOverlay.PurchaseAuthority authority) {
            shop.presentShop(dialogueFile, stock, authority);
        }
    };

    private final NativeInterface nativeInterface;
    private final DirectConnectPeer peer;
    private final EconomyHost host;
    private final DirectConnectItemController items;
    private final DirectConnectCombatController combat;
    private final NativeCombatAuthority nativeAuthority;
    private final Map<Long, Map<Integer, ShopItem>> hostStock = new LinkedHashMap<Long, Map<Integer, ShopItem>>();
    private final Map<Long, Array<ShopItem>> stockViews = new LinkedHashMap<Long, Array<ShopItem>>();
    private final Map<String, ViewEntry> viewEntries = new LinkedHashMap<String, ViewEntry>();
    private final Map<ShopItem, ViewEntry> viewItems = new IdentityHashMap<ShopItem, ViewEntry>();
    private final Map<Long, ShopItem> pendingPurchases = new LinkedHashMap<Long, ShopItem>();
    private Game game;
    private long stockGeneration = -1L;
    private long viewSignature = Long.MIN_VALUE;
    private long pendingStatRequest;
    private long levelUpOfferedRevision = -1L;
    private boolean levelUpOpen;
    private int baseInventorySize, baseHotbarSize;

    public DirectConnectEconomyController(DirectConnectPeer peer, DirectConnectItemController items,
            DirectConnectCombatController combat) {
        this(peer, items, combat, NATIVE_OVERLAYS);
    }

    public DirectConnectEconomyController(DirectConnectPeer peer, DirectConnectItemController items,
            DirectConnectCombatController combat, NativeInterface nativeInterface) {
        if(peer == null || items == null || nativeInterface == null) {
            throw new IllegalArgumentException("Economy bridge requires a peer and item bridge.");
        }
        this.nativeInterface = nativeInterface;
        this.peer = peer;
        this.items = items;
        this.combat = combat;
        host = peer instanceof EconomyHost ? (EconomyHost)peer : null;
        nativeAuthority = peer instanceof NativeCombatAuthority ? (NativeCombatAuthority)peer : null;
    }

    /** Runs after item bridge preparation and before native Level.tick. */
    public void prepare(Game current) {
        if(current == null || current.player == null || current.level == null || !items.isAttached()) return;
        if(game != current) attach(current);
        long generation = peer.getNativeWorldGeneration();
        if(stockGeneration != generation) {
            stockGeneration = generation;
            hostStock.clear();
            stockViews.clear();
            viewEntries.clear();
            viewItems.clear();
            pendingPurchases.clear();
            if(host != null) host.getEconomy().beginWorld(generation);
        }
        installHooks();
        applyLocalProgress();
    }

    /** Runs after item bridge update, including during Pause Session. */
    public void update(Game current) {
        if(game == null || current != game) return;
        applyLocalProgress();
        for(ShopOpening opening : peer.drainShopOpenings()) presentOpening(opening);
        refreshStockViews();
        offerLevelUp();
        if(host != null) host.publishEconomy();
    }

    public void dispose() {
        if(game == null) return;
        game.player.setProgressionAuthorityListener(null);
        if(game.itemManager != null && game.itemManager.getLootRollSelector() == this) {
            game.itemManager.setLootRollSelector(null);
        }
        for(Entity entity : items.worldObjects()) {
            if(entity instanceof TriggeredShop) ((TriggeredShop)entity).setShopAuthority(null);
        }
    }

    /** Elixer grants a Campaign Slot stat choice; other consumables keep their native path. */
    public DirectConnectItemController.ConsumableConsumer consumableConsumer(
            final DirectConnectItemController.ConsumableConsumer fallback) {
        return (participant, item, direction) -> item instanceof Elixer
                ? host != null && host.getEconomy().grantStatChoice(participant)
                : fallback != null && fallback.consume(participant, item, direction);
    }

    private void attach(Game current) {
        game = current;
        baseInventorySize = current.player.inventorySize;
        baseHotbarSize = current.player.hotbarSize;
        // Shop-only native constructors are absent from items.dat; every peer catalogues them.
        items.rememberTemplate(new Elixer());
        items.rememberTemplate(TriggeredShop.soulboundUpgrade(BagUpgrade.BagUpgradeType.INVENTORY));
        items.rememberTemplate(TriggeredShop.soulboundUpgrade(BagUpgrade.BagUpgradeType.HOTBAR));
        if(host == null) return;
        for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
            // Like starter kits, every initial Campaign Slot begins from the native Player template.
            ParticipantProgress progress = host.getEconomy().registerParticipant(
                    initialProgress(descriptor.getParticipantId(), current.player));
            if(nativeAuthority != null) nativeAuthority.setNativeParticipantMaximumHealth(
                    descriptor.getParticipantId(), progress.maximumHealth, false);
        }
    }

    private void installHooks() {
        game.player.setProgressionAuthorityListener(this);
        if(host != null && game.itemManager != null && game.itemManager.getLootRollSelector() != this) {
            game.itemManager.setLootRollSelector(this);
        }
        for(Entity entity : items.worldObjects()) {
            if(entity instanceof TriggeredShop) ((TriggeredShop)entity).setShopAuthority(this);
        }
    }

    private static ParticipantProgress initialProgress(ParticipantId participant, Player player) {
        int inventory = clamp(player.inventorySize, 1, ParticipantProgress.MAX_INVENTORY_SIZE);
        return new ParticipantProgress(participant, 0L,
                clamp(player.gold, 0, ParticipantProgress.MAX_GOLD),
                clamp(player.exp, 0, ParticipantProgress.MAX_EXPERIENCE),
                clamp(player.level, 0, ParticipantProgress.MAX_LEVEL),
                stat(player.stats.ATK), stat(player.stats.DEF), stat(player.stats.DEX),
                stat(player.stats.SPD), stat(player.stats.MAG), stat(player.stats.END), 0,
                clamp(player.maxHp, 1, ParticipantProgress.MAX_HEALTH), inventory,
                clamp(player.hotbarSize, 0, inventory));
    }

    private void applyLocalProgress() {
        ParticipantProgress progress = progress(items.getLocalParticipantId());
        if(progress == null) return;
        Player player = game.player;
        player.gold = progress.gold;
        player.exp = progress.experience;
        player.level = progress.level;
        copyStats(progress, player.stats);
        player.maxHp = progress.maximumHealth;
        int belt = progress.hotbarSize - player.hotbarSize;
        int backpack = (progress.inventorySize - progress.hotbarSize) - (player.inventorySize - player.hotbarSize);
        for(int i = 0; i < belt && player.canAddHotbarSlot(); i++) player.addHotbarSlot();
        for(int i = 0; i < backpack && player.canAddInventorySlot(); i++) player.addInventorySlot();
    }

    /** Native LevelUpOverlay appears for each accepted pending choice, never from a local roll. */
    private void offerLevelUp() {
        ParticipantProgress progress = progress(items.getLocalParticipantId());
        if(progress == null || progress.pendingStatChoices < 1 || pendingStatRequest != 0L
                || levelUpOpen || levelUpOfferedRevision == progress.revision) {
            return;
        }
        levelUpOfferedRevision = progress.revision;
        levelUpOpen = true;
        nativeInterface.showLevelUp(game.player, () -> levelUpOpen = false);
    }

    @Override
    public boolean chooseStat(String attribute) {
        CharacterStat stat = CharacterStat.fromNativeAttribute(attribute);
        if(stat == null || pendingStatRequest != 0L) return true;
        pendingStatRequest = items.submitEconomyAction(ItemAction.CHOOSE_STAT, stat.getWireId(), 0);
        if(pendingStatRequest == 0L) levelUpOfferedRevision = -1L;
        return true;
    }

    @Override
    public boolean awardExperience(Entity source, int amount) {
        // Replica deaths never mint experience; the Host native death awards once.
        if(host == null || source == null) return true;
        ParticipantId killer = combat != null && source instanceof Monster
                ? combat.lastParticipantAttacker((Monster)source) : null;
        List<ParticipantId> recipients = EconomyRules.experienceRecipients(source.x, source.y,
                candidates(), killer);
        for(ParticipantId participant : host.getEconomy().awardExperience(recipients, amount)) {
            restoreVitality(participant);
        }
        return true;
    }

    @Override
    public ItemManager.LootRoll selectLootRoll() {
        if(host == null) return null;
        ParticipantProgress progress = progress(EconomyRules.selectLootParticipant(participants(true), Game.rand));
        if(progress == null) return null;
        Stats stats = new Stats();
        copyStats(progress, stats);
        return new ItemManager.LootRoll(stats, progress.level);
    }

    @Override
    public void synchronizeProgress(ParticipantId participant, Player player) {
        ParticipantProgress progress = progress(participant);
        if(progress == null || player == null) return;
        player.level = progress.level;
        copyStats(progress, player.stats);
        player.maxHp = progress.maximumHealth;
    }

    @Override
    public boolean acquireGold(ParticipantContext participant, int amount) {
        return host != null && participant != null && host.getEconomy().divideGold(
                participant.getParticipantId(), amount, participants(false)) != null;
    }

    @Override
    public boolean openShop(TriggeredShop shop, ParticipantContext participant, String dialogueFile) {
        // A client never generates or displays local stock; Host sends the activator's opening.
        if(host == null) return true;
        ParticipantId activator = participant == null ? items.getLocalParticipantId() : participant.getParticipantId();
        long shopId = items.worldObjectIdentity(shop);
        if(shopId == 0L || progress(activator) == null) {
            return activator != null && !activator.equals(items.getLocalParticipantId());
        }
        ensureStock(shop, shopId, activator);
        host.publishEconomy();
        String dialogue = dialogueFile == null || dialogueFile.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > ShopOpening.MAX_DIALOGUE_FILE_BYTES ? "" : dialogueFile;
        host.publishShopOpening(activator, new ShopOpening(shopId, peer.getNativeWorldGeneration(), dialogue));
        return true;
    }

    private void ensureStock(TriggeredShop shop, long shopId, ParticipantId activator) {
        AuthoritativeEconomy economy = host.getEconomy();
        Map<Integer, ShopItem> natives = hostStock.get(shopId);
        if(natives == null) {
            natives = new LinkedHashMap<Integer, ShopItem>();
            hostStock.put(shopId, natives);
        }
        if(shop.shopType == TriggeredShop.ShopType.persistent) {
            ParticipantProgress progress = economy.get(activator);
            int backpackUpgrades = Math.max(0, (progress.inventorySize - progress.hotbarSize)
                    - (baseInventorySize - baseHotbarSize));
            int beltUpgrades = Math.max(0, progress.hotbarSize - baseHotbarSize);
            int index = 0;
            for(ShopItem offer : shop.generateStock(progress.canAddBackpackSlot(),
                    progress.canAddHotbarSlot(), backpackUpgrades, beltUpgrades)) {
                if(!supported(offer)) continue;
                DirectConnectItemController.ItemDescription description = items.describe(offer.item);
                ShopEntryState entry = economy.offerPersonal(shopId, activator, index,
                        description.templateId, description.properties, offer.cost, offer.useOnBuy,
                        offer.achieveOnBuy);
                if(entry == null) break;
                natives.put(entry.entryId, offer);
                index++;
            }
            economy.withdrawPersonalOffers(shopId, activator, index);
            return;
        }
        if(economy.hasStock(shopId)) return;
        economy.openStock(shopId);
        for(ShopItem offer : shop.generateStock(false, false, 0, 0)) {
            if(!supported(offer)) continue;
            DirectConnectItemController.ItemDescription description = items.describe(offer.item);
            ShopEntryState entry = economy.offer(shopId, description.templateId,
                    description.properties, offer.cost, offer.useOnBuy, offer.achieveOnBuy);
            if(entry == null) break;
            natives.put(entry.entryId, offer);
        }
    }

    private static boolean supported(ShopItem offer) {
        return offer != null && offer.item != null && offer.upgrade == null && offer.cost != null
                && offer.cost >= 0 && offer.cost <= ParticipantProgress.MAX_GOLD
                && (!offer.useOnBuy || offer.item instanceof BagUpgrade);
    }

    @Override
    public boolean economyAction(ItemRequest request, ParticipantContext participant) {
        if(host == null || participant == null) return false;
        ParticipantId buyer = participant.getParticipantId();
        AuthoritativeEconomy economy = host.getEconomy();
        if(request.action == ItemAction.CHOOSE_STAT) {
            CharacterStat stat;
            try { stat = CharacterStat.fromWireId(request.entityId); }
            catch(IllegalArgumentException invalid) { return false; }
            if(!economy.chooseStat(buyer, stat)) return false;
            // LevelUpOverlay.applyStats sets current health to the new maximum.
            restoreVitality(buyer);
            return true;
        }
        if(request.action != ItemAction.PURCHASE) return false;
        Entity shop = items.worldObject(request.entityId);
        Map<Integer, ShopItem> natives = hostStock.get(request.entityId);
        ShopItem offer = natives == null ? null : natives.get(request.quantity);
        ShopEntryState entry = economy.entry(request.entityId, request.quantity);
        if(!(shop instanceof TriggeredShop) || offer == null || entry == null
                || !canReachShop(participant, shop)) return false;
        if(entry.useOnBuy) {
            boolean belt = ((BagUpgrade)offer.item).bagUpgradeType == BagUpgrade.BagUpgradeType.HOTBAR;
            if(!economy.canExpandInventory(buyer, belt) || economy.purchase(buyer, request.entityId,
                    request.quantity) != AuthoritativeEconomy.PurchaseOutcome.ACCEPTED) return false;
            economy.expandInventory(buyer, belt);
            host.getItemWorld().registerParticipant(buyer, economy.get(buyer).inventorySize);
            return true;
        }
        if(economy.purchase(buyer, request.entityId, request.quantity)
                != AuthoritativeEconomy.PurchaseOutcome.ACCEPTED) return false;
        Item bought = ItemManager.Copy(offer.item.getClass(), offer.item);
        bought.identified = true;
        items.deliverPurchasedItem(bought, participant);
        return true;
    }

    private static boolean canReachShop(ParticipantContext participant, Entity shop) {
        float dx = participant.getCharacter().getX() - shop.x;
        float dy = participant.getCharacter().getY() - shop.y;
        return dx * dx + dy * dy <= SHOP_PURCHASE_REACH * SHOP_PURCHASE_REACH
                && Math.abs(participant.getCharacter().getZ() - shop.z) <= 1.5f;
    }

    @Override
    public void purchase(ShopItem item) {
        ViewEntry view = viewItems.get(item);
        if(view == null || pendingPurchases.size() >= MAX_PENDING_PURCHASES) return;
        long request = items.submitEconomyAction(ItemAction.PURCHASE, view.shopId, view.entryId);
        if(request > 0L) pendingPurchases.put(request, item);
    }

    @Override
    public void onItemActionResult(ItemActionResult result) {
        if(result.requestId == pendingStatRequest) {
            pendingStatRequest = 0L;
            if(!result.accepted) levelUpOfferedRevision = -1L;
        }
        if(!pendingPurchases.containsKey(result.requestId)) return;
        ShopItem bought = pendingPurchases.remove(result.requestId);
        viewSignature = Long.MIN_VALUE;
        if(!result.accepted) return;
        // Buyer-only native purchase feedback; stock, gold and item delivery arrive as Host state.
        Audio.playSound("ui/ui_buy.mp3", 0.6f);
        if(bought.achieveOnBuy != null) SteamApi.api.achieve(bought.achieveOnBuy);
        if(bought.item instanceof BagUpgrade) ((BagUpgrade)bought.item).presentUpgrade();
    }

    private void presentOpening(ShopOpening opening) {
        Entity entity = items.worldObject(opening.shopId);
        if(!(entity instanceof TriggeredShop) || opening.generation != peer.getNativeWorldGeneration()) return;
        Array<ShopItem> view = stockViews.get(opening.shopId);
        if(view == null) {
            view = new Array<ShopItem>();
            stockViews.put(opening.shopId, view);
        }
        rebuildView(opening.shopId, view);
        nativeInterface.showShop((TriggeredShop)entity, opening.dialogueFile, view, this);
    }

    private void refreshStockViews() {
        if(stockViews.isEmpty()) return;
        long signature = 17L;
        for(ShopEntryState entry : peer.getShopEntries()) signature = signature * 31L + entry.revision;
        ParticipantProgress progress = progress(items.getLocalParticipantId());
        if(progress != null) signature = signature * 31L + progress.gold;
        if(signature == viewSignature) return;
        viewSignature = signature;
        for(Map.Entry<Long, Array<ShopItem>> view : stockViews.entrySet()) rebuildView(view.getKey(), view.getValue());
        Overlay current = OverlayManager.instance == null ? null : OverlayManager.instance.current();
        if(!(current instanceof ShopOverlay)) return;
        for(Array<ShopItem> view : stockViews.values()) {
            if(((ShopOverlay)current).getItems() == view) ((ShopOverlay)current).refreshStock();
        }
    }

    /** Stable ShopItem identities survive refresh, so an open confirmation keeps its target. */
    private void rebuildView(long shopId, Array<ShopItem> view) {
        ParticipantId local = items.getLocalParticipantId();
        List<ShopEntryState> entries = new ArrayList<ShopEntryState>(peer.getShopEntries());
        Collections.sort(entries, new Comparator<ShopEntryState>() {
            public int compare(ShopEntryState a, ShopEntryState b) { return Integer.compare(a.entryId, b.entryId); }
        });
        view.clear();
        for(ShopEntryState entry : entries) {
            if(entry.shopId != shopId || entry.sold || !entry.offeredTo(local)) continue;
            String key = entry.shopId + ":" + entry.entryId;
            ViewEntry cached = viewEntries.get(key);
            if(cached == null || cached.revision != entry.revision) {
                Item item;
                try { item = items.materialize(entry.templateId, entry.properties); }
                catch(IllegalStateException missing) { item = null; }
                if(item == null) {
                    peer.failNativePresentation("Host shop item is unavailable in local content: " + entry.templateId);
                    continue;
                }
                ShopItem shopItem = new ShopItem(item, entry.cost);
                shopItem.useOnBuy = entry.useOnBuy;
                shopItem.achieveOnBuy = entry.achievement;
                if(cached != null) viewItems.remove(cached.item);
                cached = new ViewEntry(entry.shopId, entry.entryId, entry.revision, shopItem);
                viewEntries.put(key, cached);
                viewItems.put(shopItem, cached);
            }
            view.add(cached.item);
        }
    }

    private void restoreVitality(ParticipantId participant) {
        ParticipantProgress progress = progress(participant);
        if(progress != null && nativeAuthority != null) {
            nativeAuthority.setNativeParticipantMaximumHealth(participant, progress.maximumHealth, true);
        }
    }

    private ParticipantProgress progress(ParticipantId participant) {
        if(participant == null) return null;
        if(host != null) return host.getEconomy().get(participant);
        for(ParticipantProgress progress : peer.getParticipantProgress()) {
            if(progress.participantId.equals(participant)) return progress;
        }
        return null;
    }

    private List<EconomyRules.Candidate> candidates() {
        List<EconomyRules.Candidate> result = new ArrayList<EconomyRules.Candidate>();
        List<MovementSnapshot> snapshots = peer.getMovementSnapshots();
        if(snapshots.isEmpty()) return result;
        MovementSnapshot latest = snapshots.get(snapshots.size() - 1);
        for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
            MovementEntityState state = latest.getEntity(descriptor.getEntityId());
            PartyMemberStatus member = member(descriptor.getCampaignSlot());
            if(state == null || member == null) continue;
            result.add(new EconomyRules.Candidate(descriptor.getParticipantId(),
                    state.getX(), state.getY(), living(member)));
        }
        return result;
    }

    /** Living: connected and standing. Otherwise present in the world: connected or Downed. */
    private List<ParticipantId> participants(boolean livingOnly) {
        List<MovementEntityDescriptor> descriptors = new ArrayList<MovementEntityDescriptor>(peer.getMovementEntities());
        Collections.sort(descriptors, new Comparator<MovementEntityDescriptor>() {
            public int compare(MovementEntityDescriptor a, MovementEntityDescriptor b) {
                return Integer.compare(a.getCampaignSlot(), b.getCampaignSlot());
            }
        });
        List<ParticipantId> result = new ArrayList<ParticipantId>();
        for(MovementEntityDescriptor descriptor : descriptors) {
            PartyMemberStatus member = member(descriptor.getCampaignSlot());
            if(member == null) continue;
            boolean eligible = livingOnly ? living(member) : member.getState() == PartyMemberState.CONNECTED
                    || member.getState() == PartyMemberState.DOWNED;
            if(eligible) result.add(descriptor.getParticipantId());
        }
        return result;
    }

    private PartyMemberStatus member(int campaignSlot) {
        if(peer.getPartyStatus() == null) return null;
        for(PartyMemberStatus member : peer.getPartyStatus().getMembers()) {
            if(member.getCampaignSlot() == campaignSlot) return member;
        }
        return null;
    }

    private static boolean living(PartyMemberStatus member) {
        return member.getState() == PartyMemberState.CONNECTED && member.getHealth() > 0;
    }

    private static void copyStats(ParticipantProgress progress, Stats stats) {
        stats.ATK = progress.attack;
        stats.DEF = progress.defense;
        stats.DEX = progress.agility;
        stats.SPD = progress.speed;
        stats.MAG = progress.magic;
        stats.END = progress.endurance;
    }

    private static int stat(int value) {
        return clamp(value, 0, ParticipantProgress.MAX_STAT);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
