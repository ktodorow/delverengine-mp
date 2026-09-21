package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.items.Elixer;
import com.interrupt.dungeoneer.entities.items.Gold;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.triggers.TriggeredShop;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.LocalizedString;
import com.interrupt.dungeoneer.multiplayer.combat.NativeCombatAuthority;
import com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld;
import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.ItemActionResult;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression;
import com.interrupt.dungeoneer.overlays.LevelUpOverlay;
import com.interrupt.dungeoneer.overlays.Overlay;
import com.interrupt.dungeoneer.overlays.OverlayManager;
import com.interrupt.dungeoneer.overlays.ShopOverlay;
import com.interrupt.dungeoneer.ui.*;
import com.interrupt.helpers.ShopItem;
import com.interrupt.managers.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class NativeEconomyRegressionTest {
    private static final long SHOP_ID = 1000000L;
    private static final String[] NICKNAMES = {"Host", "Friend", "Third"};
    private static final String[] AVATARS = {"humanoid-1", "humanoid-2", "humanoid-3"};

    private final ParticipantId alpha = new ParticipantId("campaign-slot-1");
    private final ParticipantId beta = new ParticipantId("campaign-slot-2");
    private final ParticipantId gamma = new ParticipantId("campaign-slot-3");
    private final ParticipantId[] participants = {alpha, beta, gamma};
    private final PartyMemberState[] states = {PartyMemberState.CONNECTED,
            PartyMemberState.CONNECTED, PartyMemberState.CONNECTED};
    private final float[][] positions = {{2f, 2f}, {5f, 2f}, {40f, 40f}};
    private final AuthoritativeEconomy economy = new AuthoritativeEconomy();
    private final AuthoritativeItemWorld itemWorld = new AuthoritativeItemWorld();
    private final List<Object[]> itemRequests = new ArrayList<Object[]>();
    private final List<Object[]> openings = new ArrayList<Object[]>();
    private final List<Object[]> vitality = new ArrayList<Object[]>();
    private final List<ItemActionResult> results = new ArrayList<ItemActionResult>();
    private final List<ParticipantProgress> clientProgress = new ArrayList<ParticipantProgress>();
    private final List<ShopEntryState> clientEntries = new ArrayList<ShopEntryState>();
    private final List<ShopOpening> clientOpenings = new ArrayList<ShopOpening>();
    private final List<Overlay> overlays = new ArrayList<Overlay>();
    private final List<Runnable> levelUps = new ArrayList<Runnable>();
    private final List<Object[]> shops = new ArrayList<Object[]>();
    private final List<String> achievements = new ArrayList<String>();

    private com.badlogic.gdx.Application previousApp;
    private com.interrupt.api.steam.SteamApiInterface previousSteam;
    private Game previousGame, game;
    private HUDManager previousHudManager;
    private Hud previousHud;
    private OverlayManager previousOverlays;
    private HashMap<String, LocalizedString> previousStrings;
    private StubItemManager itemManager;
    private DirectConnectItemController items;
    private DirectConnectEconomyController controller;

    private static final class StubItemManager extends ItemManager {
        int monsterLootRolls;

        @Override public Sword GetRandomWeapon(Integer level) { return sword(); }
        @Override public Item GetRandomRangedWeapon(Integer level) { return sword(); }
        @Override public Armor GetRandomArmor(Integer level) {
            Armor armor = new Armor();
            armor.cost = 40;
            return armor;
        }
        @Override public Item GetMonsterLoot(Integer level, boolean canSpawnGold) {
            monsterLootRolls++;
            return sword();
        }

        static Sword sword() {
            Sword sword = new Sword();
            sword.cost = 40;
            return sword;
        }
    }

    private static final class CountingGold extends Gold {
        int presented;
        CountingGold() { super(5); }
        @Override public void presentPickup(Player player) { presented++; }
    }

    @Before
    public void setup() {
        previousApp = com.badlogic.gdx.Gdx.app;
        previousSteam = com.interrupt.api.steam.SteamApi.api;
        com.badlogic.gdx.Gdx.app = (com.badlogic.gdx.Application)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{com.badlogic.gdx.Application.class}, (p, m, a) -> null);
        com.interrupt.api.steam.SteamApi.api =
                (com.interrupt.api.steam.SteamApiInterface)Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{com.interrupt.api.steam.SteamApiInterface.class},
                        (proxy, method, args) -> {
                            if(method.getName().equals("achieve") && args != null && args.length > 0) {
                                achievements.add((String)args[0]);
                            }
                            if(method.getReturnType() == boolean.class) return false;
                            if(method.getReturnType() == com.badlogic.gdx.utils.Array.class) {
                                return new com.badlogic.gdx.utils.Array<String>();
                            }
                            return null;
                        });
        previousGame = Game.instance;
        previousHudManager = Game.hudManager;
        previousHud = Game.hud;
        previousOverlays = OverlayManager.instance;
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.level = new Level(4, 4);
        game.player.inventory.clear();
        for(int i = 0; i < game.player.inventorySize; i++) game.player.inventory.add(null);
        game.player.gold = 100;
        game.player.level = 1;
        game.player.exp = 0;
        game.player.maxHp = 8;
        game.itemManager = itemManager = new StubItemManager();
        Game.instance = game;
        Game.hudManager = new HUDManager();
        Game.hudManager.quickSlots = new Hotbar() { @Override public void refresh() { } };
        Game.hudManager.backpack = new Hotbar() { @Override public void refresh() { } };
        Game.hud = new Hud() {
            @Override public void refresh() { }
            @Override public void refreshEquipLocations() { }
        };
        OverlayManager.instance = new OverlayManager() {
            @Override public void push(Overlay overlay) { overlays.add(overlay); }
            @Override public void remove(Overlay overlay) { overlays.remove(overlay); }
            @Override public Overlay current() { return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1); }
        };
    }

    @After
    public void restore() {
        com.badlogic.gdx.Gdx.app = previousApp;
        com.interrupt.api.steam.SteamApi.api = previousSteam;
        Game.instance = previousGame;
        Game.hudManager = previousHudManager;
        Game.hud = previousHud;
        OverlayManager.instance = previousOverlays;
        StringManager.localizedStrings = previousStrings;
    }

    @Test
    public void hostSharesNativeKillExperienceWithNearbyLivingParticipantsOnly() throws Exception {
        start(true, alpha).prepare(game);
        Monster monster = monster(2f, 2f);

        assertTrue(game.player.requestExperienceAward(monster, 8));

        assertEquals(8, economy.get(alpha).experience);
        assertEquals(8, economy.get(beta).experience);
        assertEquals(0, economy.get(gamma).experience);
        assertEquals(2, economy.get(beta).level);
        assertEquals(1, economy.get(beta).pendingStatChoices);
        assertEquals("Native Host Player.addExperience must not run", 1, game.player.level);
        assertTrue(levelUps.isEmpty());
        assertTrue(restored(alpha));
        assertTrue(restored(beta));
        assertFalse(restored(gamma));

        controller.update(game);

        assertEquals(2, game.player.level);
        assertEquals(8, game.player.exp);
        assertEquals(1, levelUps.size());
        controller.update(game);
        assertEquals("Open level-up is not offered again", 1, levelUps.size());
    }

    @Test
    public void downedAndDistantParticipantsReceiveNoKillExperience() throws Exception {
        states[1] = PartyMemberState.DOWNED;
        start(true, alpha).prepare(game);

        game.player.requestExperienceAward(monster(5f, 2f), 8);
        assertEquals(8, economy.get(alpha).experience);
        assertEquals(0, economy.get(beta).experience);

        game.player.requestExperienceAward(monster(80f, 80f), 4);
        assertEquals(0, economy.get(gamma).experience);
        assertEquals(8, economy.get(alpha).experience);
    }

    @Test
    public void eachStatBiasedLootRollUsesOneLivingParticipantsCampaignSlotStats() throws Exception {
        economy.registerParticipant(progress(alpha, 0, 1, 90, 0));
        economy.registerParticipant(progress(beta, 0, 5, 0, 90));
        economy.registerParticipant(progress(gamma, 0, 3, 90, 0));
        states[0] = PartyMemberState.DOWNED;
        states[2] = PartyMemberState.DOWNED;
        start(true, alpha).prepare(game);

        assertSame(controller, game.itemManager.getLootRollSelector());
        for(int i = 0; i < 50; i++) {
            ItemManager.LootRoll roll = game.itemManager.selectLootRoll();
            assertEquals(90, roll.stats.DEF);
            assertEquals(0, roll.stats.ATK);
            assertEquals(5, roll.level);
        }
    }

    @Test
    public void monsterDeathLootIsOneHostRollRegardlessOfPartySize() throws Exception {
        start(true, alpha).prepare(game);
        Monster monster = monster(2f, 2f);
        setField(Monster.class, monster, "spawnsLoot", true);
        setField(Monster.class, monster, "random", new Random() {
            @Override public boolean nextBoolean() { return true; }
        });
        int before = game.level.entities.size;

        monster.spawnLoot(game.level);

        assertEquals(1, itemManager.monsterLootRolls);
        assertEquals(before + 1, game.level.entities.size);
    }

    @Test
    public void clientMirrorsHostProgressNeverMintsAndSendsStatChoiceAsIntent() throws Exception {
        clientProgress.add(new ParticipantProgress(beta, 4L, 77, 9, 2, 6, 5, 4, 4, 4, 4, 1, 12,
                game.player.inventorySize, game.player.hotbarSize));
        start(false, beta).prepare(game);

        assertEquals(77, game.player.gold);
        assertEquals(9, game.player.exp);
        assertEquals(2, game.player.level);
        assertEquals(6, game.player.stats.ATK);
        assertEquals(5, game.player.stats.DEF);
        assertEquals(12, game.player.maxHp);

        assertTrue(game.player.requestExperienceAward(monster(5f, 2f), 50));
        controller.prepare(game);
        assertEquals(9, game.player.exp);

        controller.update(game);
        assertEquals(1, levelUps.size());
        // LevelUpOverlay.pickStat asks this boundary before any native stat mutation.
        assertTrue(game.player.requestStatChoice("ATTACK"));
        assertTrue(game.player.requestStatChoice("ATTACK"));
        levelUps.get(0).run();
        controller.update(game);

        assertEquals(6, game.player.stats.ATK);
        assertEquals(1, itemRequests.size());
        assertEquals(ItemAction.CHOOSE_STAT, itemRequests.get(0)[1]);
        assertEquals(Long.valueOf(CharacterStat.ATTACK.getWireId()), itemRequests.get(0)[2]);
        assertEquals("Pending Host decision suppresses another native offer", 1, levelUps.size());

        results.add(new ItemActionResult(1L, CharacterStat.ATTACK.getWireId(), false));
        items.prepare(game);
        controller.update(game);
        assertEquals("Rejected choice returns to native level-up", 2, levelUps.size());
    }

    @Test
    public void clientAppliesHostBagExpansionOnceWithoutLocalPurchaseMutation() throws Exception {
        int inventory = game.player.inventorySize, hotbar = game.player.hotbarSize;
        clientProgress.add(new ParticipantProgress(beta, 2L, 50, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8,
                inventory + 2, hotbar + 1));
        start(false, beta).prepare(game);
        controller.prepare(game);

        assertEquals(inventory + 2, game.player.inventorySize);
        assertEquals(hotbar + 1, game.player.hotbarSize);
    }

    @Test
    public void hostShopStockIsSharedAndFirstAcceptedPurchaseWins() throws Exception {
        start(true, alpha).prepare(game);
        TriggeredShop shop = shop(TriggeredShop.ShopType.weapons);

        assertTrue(controller.openShop(shop, at(beta, 2.5f, 2f), "jeff.dat"));
        List<ShopEntryState> stock = economy.stock(SHOP_ID);
        assertEquals(6, stock.size());
        assertEquals(1, openings.size());
        assertEquals(beta, openings.get(0)[0]);
        assertEquals("jeff.dat", ((ShopOpening)openings.get(0)[1]).dialogueFile);

        assertTrue(controller.openShop(shop, at(alpha, 2f, 2.5f), "jeff.dat"));
        assertEquals(revisions(stock), revisions(economy.stock(SHOP_ID)));
        assertEquals(alpha, openings.get(1)[0]);

        int entry = stock.get(0).entryId;
        assertTrue(controller.economyAction(purchase(beta, 1L, entry), at(beta, 2.5f, 2f)));
        assertFalse(controller.economyAction(purchase(alpha, 1L, entry), at(alpha, 2f, 2.5f)));
        assertEquals(60, economy.get(beta).gold);
        assertEquals(100, economy.get(alpha).gold);
        assertTrue(economy.entry(SHOP_ID, entry).sold);

        int other = stock.get(1).entryId;
        assertFalse(controller.economyAction(purchase(alpha, 2L, other), at(alpha, 20f, 20f)));
        assertFalse(economy.entry(SHOP_ID, other).sold);
        assertEquals(100, economy.get(alpha).gold);
    }

    @Test
    public void soulboundOffersBelongToActivatorAndBagPurchaseExpandsHostCapacity() throws Exception {
        int inventory = game.player.inventorySize;
        start(true, alpha).prepare(game);
        itemWorld.registerParticipant(alpha, inventory);
        TriggeredShop shop = shop(TriggeredShop.ShopType.persistent);

        controller.openShop(shop, at(alpha, 2f, 2f), "hoodie.dat");

        List<ShopEntryState> offers = economy.stock(SHOP_ID);
        assertEquals(2, offers.size());
        ShopEntryState bag = offers.get(0);
        assertEquals(30, bag.cost);
        assertEquals(60, offers.get(1).cost);
        assertEquals(alpha, bag.restrictedTo);
        assertTrue(bag.useOnBuy);
        assertFalse(controller.economyAction(purchase(beta, 1L, bag.entryId), at(beta, 2f, 2f)));
        assertTrue(controller.economyAction(purchase(alpha, 1L, bag.entryId), at(alpha, 2f, 2f)));
        assertEquals(inventory + 1, economy.get(alpha).inventorySize);
        assertEquals(inventory + 1, itemWorld.getCapacity(alpha));
        assertEquals(70, economy.get(alpha).gold);

        controller.openShop(shop, at(alpha, 2f, 2f), "hoodie.dat");
        assertEquals(52, economy.entry(SHOP_ID, bag.entryId).cost);
        assertFalse(economy.entry(SHOP_ID, bag.entryId).sold);
        assertEquals(2, economy.stock(SHOP_ID).size());
    }

    @Test
    public void clientShopShowsHostStockAndPurchaseIsOnlyIntent() throws Exception {
        clientProgress.add(new ParticipantProgress(beta, 1L, 100, 0, 1, 4, 4, 4, 4, 4, 4, 0, 8,
                game.player.inventorySize, game.player.hotbarSize));
        start(false, beta).prepare(game);
        TriggeredShop shop = shop(TriggeredShop.ShopType.weapons);
        String template = items.rememberTemplate(StubItemManager.sword());
        clientEntries.add(new ShopEntryState(SHOP_ID, 1L, 5L, 1, template, ItemProperties.DEFAULT,
                40, false, false, null, "SHOP_JEFF"));
        clientEntries.add(new ShopEntryState(SHOP_ID, 1L, 6L, AuthoritativeEconomy.PERSONAL_ENTRY_BASE,
                template, ItemProperties.DEFAULT, 30, true, false, alpha));

        assertTrue("Client trigger must not generate local stock", controller.openShop(shop, at(beta, 2f, 2f), "jeff.dat"));
        assertTrue(shops.isEmpty());
        assertNull(shop.items);

        clientOpenings.add(new ShopOpening(SHOP_ID, 1L, "jeff2.dat"));
        controller.update(game);
        assertEquals(1, shops.size());
        assertSame(shop, shops.get(0)[0]);
        assertEquals("jeff2.dat", shops.get(0)[1]);
        assertSame(controller, shops.get(0)[3]);
        @SuppressWarnings("unchecked")
        com.badlogic.gdx.utils.Array<ShopItem> stock = (com.badlogic.gdx.utils.Array<ShopItem>)shops.get(0)[2];
        assertEquals("Another Campaign Slot's soulbound offer stays hidden", 1, stock.size);
        ShopItem offer = stock.first();
        assertEquals(Integer.valueOf(40), offer.cost);
        assertTrue(offer.item instanceof Sword);
        assertEquals("SHOP_JEFF", offer.achieveOnBuy);

        controller.purchase(offer);
        Object[] request = itemRequests.get(itemRequests.size() - 1);
        assertEquals(ItemAction.PURCHASE, request[1]);
        assertEquals(Long.valueOf(SHOP_ID), request[2]);
        assertEquals(Integer.valueOf(1), request[4]);
        assertTrue("Click cannot grant purchase achievement", achievements.isEmpty());

        results.add(new ItemActionResult(1L, SHOP_ID, false));
        items.prepare(game);
        assertTrue("Rejected purchase cannot grant achievement", achievements.isEmpty());

        controller.purchase(offer);
        results.add(new ItemActionResult(2L, SHOP_ID, true));
        items.prepare(game);
        assertEquals(Collections.singletonList("SHOP_JEFF"), achievements);
        results.add(new ItemActionResult(2L, SHOP_ID, true));
        items.prepare(game);
        assertEquals("Replayed acceptance cannot repeat achievement", 1, achievements.size());

        controller.update(game);
        assertEquals(100, game.player.gold);
        assertSame("Open confirmation keeps a stable offer identity", offer, stock.first());

        clientEntries.set(0, new ShopEntryState(SHOP_ID, 1L, 7L, 1, template, ItemProperties.DEFAULT,
                40, false, true, null));
        controller.update(game);
        assertEquals(0, stock.size);
    }

    @Test
    public void goldPickupPresentationWaitsForHostAcceptanceAndNeverAddsLocalGold() throws Exception {
        start(false, beta);
        CountingGold accepted = gold(21L);
        CountingGold rejected = gold(22L);

        accepted.doPickup(game.player);
        rejected.doPickup(game.player);

        assertEquals(2, itemRequests.size());
        assertEquals(ItemAction.PICKUP, itemRequests.get(0)[1]);
        assertEquals(0, accepted.presented);
        assertEquals(100, game.player.gold);

        results.add(new ItemActionResult(1L, 21L, true));
        results.add(new ItemActionResult(2L, 22L, false));
        items.prepare(game);

        assertEquals(1, accepted.presented);
        assertEquals(0, rejected.presented);
        assertEquals(100, game.player.gold);
    }

    @Test
    public void elixerGrantsCampaignSlotStatChoiceInsteadOfPotionEffect() throws Exception {
        start(true, alpha).prepare(game);
        final int[] fallback = {0};
        DirectConnectItemController.ConsumableConsumer consumer =
                controller.consumableConsumer((participant, item, direction) -> { fallback[0]++; return true; });

        assertTrue(consumer.consume(beta, new Elixer(), null));
        assertEquals(1, economy.get(beta).pendingStatChoices);
        assertEquals(0, fallback[0]);
        assertTrue(consumer.consume(beta, new Potion(), null));
        assertEquals(1, fallback[0]);

        assertTrue(controller.economyAction(new ItemRequest(beta, 3L, ItemAction.CHOOSE_STAT,
                CharacterStat.DEFENSE.getWireId(), 0, 0), at(beta, 5f, 2f)));
        assertEquals(5, economy.get(beta).defense);
        assertTrue(restored(beta));
        assertFalse(controller.economyAction(new ItemRequest(beta, 4L, ItemAction.CHOOSE_STAT, 99L, 0, 0),
                at(beta, 5f, 2f)));
    }

    @Test
    public void ownedElixerOnClientWaitsForHostInsteadOfOpeningLocalLevelUp() throws Exception {
        start(false, beta);
        Elixer elixer = new Elixer();
        this.<Map<Item, Long>>field("itemIds").put(elixer, 31L);
        game.player.inventory.set(0, elixer);

        elixer.Drink(game.player);

        assertTrue(overlays.isEmpty());
        assertSame(elixer, game.player.inventory.get(0));
    }

    private DirectConnectEconomyController start(boolean hostRole, ParticipantId local) throws Exception {
        DirectConnectPeer peer = peer(hostRole);
        items = new DirectConnectItemController(peer);
        set("game", game);
        set("objectLevel", game.level);
        set("objectGeneration", 1L);
        set("localId", local);
        set("nextRequest", 1L);
        game.player.setItemAuthorityListener(items);
        controller = new DirectConnectEconomyController(peer, items, null,
                new DirectConnectEconomyController.NativeInterface() {
                    @Override public void showLevelUp(Player player, Runnable closed) { levelUps.add(closed); }
                    @Override public void showShop(TriggeredShop shop, String dialogueFile,
                            com.badlogic.gdx.utils.Array<ShopItem> stock, ShopOverlay.PurchaseAuthority authority) {
                        shops.add(new Object[]{shop, dialogueFile, stock, authority});
                    }
                });
        items.setEconomyBoundary(controller);
        return controller;
    }

    private DirectConnectPeer peer(final boolean hostRole) {
        List<Class<?>> interfaces = new ArrayList<Class<?>>();
        interfaces.add(DirectConnectPeer.class);
        if(hostRole) {
            interfaces.add(EconomyHost.class);
            interfaces.add(NativeCombatAuthority.class);
        }
        return (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                interfaces.toArray(new Class<?>[0]), (proxy, method, args) -> {
            switch(method.getName()) {
                case "getMovementEntities": return descriptors();
                case "getMovementSnapshots": return Collections.singletonList(snapshot());
                case "getPartyStatus": return party();
                case "getEconomy": return economy;
                case "getItemWorld": return itemWorld;
                case "publishShopOpening": openings.add(args); return null;
                case "setNativeParticipantMaximumHealth": vitality.add(args); return null;
                case "getParticipantProgress":
                    return hostRole ? economy.progressSnapshot() : new ArrayList<ParticipantProgress>(clientProgress);
                case "getShopEntries":
                    return hostRole ? economy.shopSnapshot() : new ArrayList<ShopEntryState>(clientEntries);
                case "drainShopOpenings": {
                    List<ShopOpening> copy = new ArrayList<ShopOpening>(clientOpenings);
                    clientOpenings.clear();
                    return copy;
                }
                case "drainItemActionResults": {
                    List<ItemActionResult> copy = new ArrayList<ItemActionResult>(results);
                    results.clear();
                    return copy;
                }
                case "submitItemAction": itemRequests.add(args); return null;
                case "getNativeWorldGeneration": return 1L;
                default: break;
            }
            Class<?> type = method.getReturnType();
            if(type == List.class) return Collections.emptyList();
            if(type == boolean.class) return false;
            if(type == int.class) return 0;
            if(type == long.class) return 1L;
            if(type == float.class) return 0f;
            return null;
        });
    }

    private List<MovementEntityDescriptor> descriptors() {
        List<MovementEntityDescriptor> result = new ArrayList<MovementEntityDescriptor>();
        for(int i = 0; i < participants.length; i++) {
            result.add(new MovementEntityDescriptor(1L, new NetworkEntityId(i + 1), participants[i], i + 1,
                    NICKNAMES[i], AVATARS[i]));
        }
        return result;
    }

    private MovementSnapshot snapshot() {
        List<MovementEntityState> entities = new ArrayList<MovementEntityState>();
        for(int i = 0; i < participants.length; i++) {
            entities.add(new MovementEntityState(new NetworkEntityId(i + 1), 1L, 0L,
                    positions[i][0], positions[i][1], 0f, 0f, 0f, 0f, 0f, MovementState.IDLE));
        }
        return new MovementSnapshot(1L, 1L, entities);
    }

    private PartyStatusSnapshot party() {
        List<PartyMemberStatus> members = new ArrayList<PartyMemberStatus>();
        for(int i = 0; i < participants.length; i++) {
            int health = states[i] == PartyMemberState.CONNECTED ? 8 : 0;
            members.add(new PartyMemberStatus(i + 1, new NetworkEntityId(i + 1), NICKNAMES[i], AVATARS[i],
                    health, 8, 3, states[i]));
        }
        return new PartyStatusSnapshot(1L, members);
    }

    private ParticipantProgress progress(ParticipantId participant, int gold, int level, int attack, int defense) {
        return new ParticipantProgress(participant, 0L, gold, 0, level, attack, defense, 0, 0, 0, 0, 0, 8,
                game.player.inventorySize, game.player.hotbarSize);
    }

    private TriggeredShop shop(TriggeredShop.ShopType type) throws Exception {
        TriggeredShop shop = new TriggeredShop();
        shop.shopType = type;
        shop.x = 2f;
        shop.y = 2f;
        shop.z = 0f;
        this.<Map<Long, Entity>>field("objects").put(SHOP_ID, shop);
        this.<Map<Entity, Long>>field("objectIds").put(shop, SHOP_ID);
        return shop;
    }

    private CountingGold gold(long id) throws Exception {
        CountingGold gold = new CountingGold();
        gold.isActive = true;
        gold.x = 1f;
        gold.y = 1f;
        this.<Map<Long, Item>>field("nativeItems").put(id, gold);
        this.<Map<Item, Long>>field("itemIds").put(gold, id);
        game.level.entities.add(gold);
        return gold;
    }

    private static Monster monster(float x, float y) {
        Monster monster = new Monster();
        monster.x = x;
        monster.y = y;
        return monster;
    }

    private ParticipantContext at(ParticipantId participant, float x, float y) {
        return new ParticipantContext(participant, new ParticipantCharacterState(x, y, 0f, 0f),
                new SharedPartyProgression());
    }

    private static ItemRequest purchase(ParticipantId participant, long request, int entry) {
        return new ItemRequest(participant, request, ItemAction.PURCHASE, SHOP_ID, 0, entry);
    }

    private boolean restored(ParticipantId participant) {
        for(Object[] call : vitality) {
            if(participant.equals(call[0]) && Boolean.TRUE.equals(call[2])) return true;
        }
        return false;
    }

    private static List<Long> revisions(List<ShopEntryState> entries) {
        List<Long> result = new ArrayList<Long>();
        for(ShopEntryState entry : entries) result.add(entry.revision);
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) throws Exception {
        Field field = DirectConnectItemController.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T)field.get(items);
    }

    private void set(String name, Object value) throws Exception {
        Field field = DirectConnectItemController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(items, value);
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
