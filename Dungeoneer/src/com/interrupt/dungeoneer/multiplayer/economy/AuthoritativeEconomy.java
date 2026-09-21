package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Serial Host economy: personal gold and progression plus shared shop stock. */
public final class AuthoritativeEconomy {
    public static final int MAX_PARTICIPANTS = 4;
    public static final int MAX_ENTRIES_PER_SHOP = 96;
    public static final int MAX_SHOP_ENTRIES = 512;
    /** Shared stock ids stay below this; personal soulbound offers use stable ids above it. */
    public static final int PERSONAL_ENTRY_BASE = 10000;
    public static final int PERSONAL_OFFERS_PER_SLOT = 16;

    public enum PurchaseOutcome {
        ACCEPTED, UNKNOWN_PARTICIPANT, UNKNOWN_ENTRY, SOLD, NOT_OFFERED, INSUFFICIENT_GOLD
    }

    private static final class Ledger {
        long revision;
        int gold, experience, level, attack, defense, agility, speed, magic, endurance;
        int pendingStatChoices, maximumHealth, inventorySize, hotbarSize;

        Ledger(ParticipantProgress initial) {
            gold = initial.gold; experience = initial.experience; level = initial.level;
            attack = initial.attack; defense = initial.defense; agility = initial.agility;
            speed = initial.speed; magic = initial.magic; endurance = initial.endurance;
            pendingStatChoices = initial.pendingStatChoices; maximumHealth = initial.maximumHealth;
            inventorySize = initial.inventorySize; hotbarSize = initial.hotbarSize;
        }

        ParticipantProgress snapshot(ParticipantId participant) {
            return new ParticipantProgress(participant, revision, gold, experience, level, attack,
                    defense, agility, speed, magic, endurance, pendingStatChoices, maximumHealth,
                    inventorySize, hotbarSize);
        }
    }

    private final Map<ParticipantId, Ledger> ledgers = new LinkedHashMap<ParticipantId, Ledger>();
    private final Map<Long, Map<Integer, ShopEntryState>> shops =
            new LinkedHashMap<Long, Map<Integer, ShopEntryState>>();
    private final Map<Long, Integer> nextEntryIds = new LinkedHashMap<Long, Integer>();
    private long revision;
    private long generation = 1L;
    private int shopEntryCount;

    /** Existing Campaign Slot progress is never replaced by a later registration. */
    public synchronized ParticipantProgress registerParticipant(ParticipantProgress initial) {
        if(initial == null) throw new IllegalArgumentException("Initial progress is required.");
        Ledger existing = ledgers.get(initial.participantId);
        if(existing != null) return existing.snapshot(initial.participantId);
        if(ledgers.size() >= MAX_PARTICIPANTS) {
            throw new IllegalStateException("Economy supports four Campaign Slots.");
        }
        Ledger ledger = new Ledger(initial);
        ledger.revision = ++revision;
        ledgers.put(initial.participantId, ledger);
        return ledger.snapshot(initial.participantId);
    }

    public synchronized ParticipantProgress get(ParticipantId participant) {
        Ledger ledger = ledgers.get(participant);
        return ledger == null ? null : ledger.snapshot(participant);
    }

    public synchronized List<ParticipantProgress> progressSnapshot() {
        List<ParticipantProgress> result = new ArrayList<ParticipantProgress>();
        for(Map.Entry<ParticipantId, Ledger> entry : ledgers.entrySet()) {
            result.add(entry.getValue().snapshot(entry.getKey()));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized long getRevision() { return revision; }

    /**
     * Splits one acquired pile among active Participants. Remainder coins go one each starting
     * with the acquirer, so the Party total always equals the native pile. Null rejects pickup.
     */
    public synchronized Map<ParticipantId, Integer> divideGold(ParticipantId acquirer, int amount,
            List<ParticipantId> activeParticipants) {
        if(acquirer == null || amount < 1 || amount > ParticipantProgress.MAX_GOLD
                || !ledgers.containsKey(acquirer)) return null;
        List<ParticipantId> active = new ArrayList<ParticipantId>();
        if(activeParticipants != null) for(ParticipantId participant : activeParticipants) {
            if(ledgers.containsKey(participant) && !active.contains(participant)) active.add(participant);
        }
        if(!active.contains(acquirer)) active.add(acquirer);
        int start = active.indexOf(acquirer);
        List<ParticipantId> recipients = new ArrayList<ParticipantId>();
        for(int i = 0; i < active.size(); i++) recipients.add(active.get((start + i) % active.size()));
        int share = amount / recipients.size(), remainder = amount % recipients.size();
        Map<ParticipantId, Integer> shares = new LinkedHashMap<ParticipantId, Integer>();
        for(int i = 0; i < recipients.size(); i++) {
            int value = share + (i < remainder ? 1 : 0);
            if((long)ledgers.get(recipients.get(i)).gold + value > ParticipantProgress.MAX_GOLD) return null;
            shares.put(recipients.get(i), value);
        }
        for(Map.Entry<ParticipantId, Integer> entry : shares.entrySet()) {
            if(entry.getValue() == 0) continue;
            Ledger ledger = ledgers.get(entry.getKey());
            ledger.gold += entry.getValue();
            ledger.revision = ++revision;
        }
        return Collections.unmodifiableMap(shares);
    }

    /** Player.addExperience per recipient: full award, at most one level per award. */
    public synchronized List<ParticipantId> awardExperience(Collection<ParticipantId> recipients,
            int amount) {
        List<ParticipantId> leveled = new ArrayList<ParticipantId>();
        if(recipients == null || amount < 1) return leveled;
        for(ParticipantId participant : new LinkedHashSet<ParticipantId>(recipients)) {
            Ledger ledger = ledgers.get(participant);
            if(ledger == null) continue;
            ledger.experience = (int)Math.min(ParticipantProgress.MAX_EXPERIENCE,
                    (long)ledger.experience + amount);
            if(ledger.level < ParticipantProgress.MAX_LEVEL
                    && ledger.experience >= (ledger.level * 4) * (ledger.level * 2)) {
                ledger.level++;
                if(ledger.pendingStatChoices < ParticipantProgress.MAX_PENDING_STAT_CHOICES) {
                    ledger.pendingStatChoices++;
                }
                leveled.add(participant);
            }
            ledger.revision = ++revision;
        }
        return leveled;
    }

    /** Elixer grants one LevelUpOverlay choice without a level. */
    public synchronized boolean grantStatChoice(ParticipantId participant) {
        Ledger ledger = ledgers.get(participant);
        if(ledger == null || ledger.pendingStatChoices >= ParticipantProgress.MAX_PENDING_STAT_CHOICES) {
            return false;
        }
        ledger.pendingStatChoices++;
        ledger.revision = ++revision;
        return true;
    }

    public synchronized boolean chooseStat(ParticipantId participant, CharacterStat stat) {
        Ledger ledger = ledgers.get(participant);
        if(ledger == null || stat == null || ledger.pendingStatChoices < 1) return false;
        switch(stat) {
            case ATTACK: if(ledger.attack >= ParticipantProgress.MAX_STAT) return false; ledger.attack++; break;
            case SPEED: if(ledger.speed >= ParticipantProgress.MAX_STAT) return false; ledger.speed++; break;
            case HEALTH: if(ledger.endurance >= ParticipantProgress.MAX_STAT) return false; ledger.endurance++; break;
            case MAGIC: if(ledger.magic >= ParticipantProgress.MAX_STAT) return false; ledger.magic++; break;
            case AGILITY: if(ledger.agility >= ParticipantProgress.MAX_STAT) return false; ledger.agility++; break;
            default: if(ledger.defense >= ParticipantProgress.MAX_STAT) return false; ledger.defense++; break;
        }
        ledger.pendingStatChoices--;
        ledger.maximumHealth = Math.max(1, Math.min(ParticipantProgress.MAX_HEALTH,
                ParticipantProgress.nativeMaximumHealth(ledger.endurance, ledger.level)));
        ledger.revision = ++revision;
        return true;
    }

    public synchronized boolean canExpandInventory(ParticipantId participant, boolean hotbar) {
        Ledger ledger = ledgers.get(participant);
        if(ledger == null) return false;
        ParticipantProgress progress = ledger.snapshot(participant);
        return hotbar ? progress.canAddHotbarSlot() : progress.canAddBackpackSlot();
    }

    /** BagUpgrade.UpgradeInventory: each native slot upgrade adds one inventory capacity. */
    public synchronized boolean expandInventory(ParticipantId participant, boolean hotbar) {
        if(!canExpandInventory(participant, hotbar)) return false;
        Ledger ledger = ledgers.get(participant);
        ledger.inventorySize++;
        if(hotbar) ledger.hotbarSize++;
        ledger.revision = ++revision;
        return true;
    }

    public synchronized long getGeneration() { return generation; }

    /** Shop stock belongs to one native world generation. */
    public synchronized void beginWorld(long nextGeneration) {
        if(nextGeneration < 1L) throw new IllegalArgumentException("Invalid world generation.");
        if(nextGeneration == generation) return;
        generation = nextGeneration;
        shops.clear();
        nextEntryIds.clear();
        shopEntryCount = 0;
        revision++;
    }

    public synchronized boolean hasStock(long shopId) { return shops.containsKey(shopId); }

    /** Marks shop stock as generated even when native generation produced no entries. */
    public synchronized void openStock(long shopId) {
        if(shopId < 1L) throw new IllegalArgumentException("Invalid shop.");
        if(!shops.containsKey(shopId)) shops.put(shopId, new LinkedHashMap<Integer, ShopEntryState>());
    }

    /** Shared finite stock; generated once per shop and world generation. Null when bounds are full. */
    public synchronized ShopEntryState offer(long shopId, String templateId, ItemProperties properties,
            int cost, boolean useOnBuy) {
        return offer(shopId, templateId, properties, cost, useOnBuy, null);
    }

    public synchronized ShopEntryState offer(long shopId, String templateId, ItemProperties properties,
            int cost, boolean useOnBuy, String achievement) {
        openStock(shopId);
        Integer next = nextEntryIds.get(shopId);
        int entryId = next == null ? 1 : next;
        if(entryId >= PERSONAL_ENTRY_BASE) return null;
        ShopEntryState entry = put(shopId, entryId, templateId, properties, cost, useOnBuy, null,
                achievement);
        if(entry != null) nextEntryIds.put(shopId, entryId + 1);
        return entry;
    }

    /**
     * Native persistent shops recompute offers on each opening. Personal offers reuse one stable
     * identity per Campaign Slot and offer index, so reopening replaces instead of growing stock.
     */
    public synchronized ShopEntryState offerPersonal(long shopId, ParticipantId participant,
            int offerIndex, String templateId, ItemProperties properties, int cost, boolean useOnBuy) {
        return offerPersonal(shopId, participant, offerIndex, templateId, properties, cost,
                useOnBuy, null);
    }

    public synchronized ShopEntryState offerPersonal(long shopId, ParticipantId participant,
            int offerIndex, String templateId, ItemProperties properties, int cost, boolean useOnBuy,
            String achievement) {
        int slot = personalSlot(participant);
        if(slot < 0 || offerIndex < 0 || offerIndex >= PERSONAL_OFFERS_PER_SLOT) return null;
        openStock(shopId);
        return put(shopId, PERSONAL_ENTRY_BASE + slot * PERSONAL_OFFERS_PER_SLOT + offerIndex,
                templateId, properties, cost, useOnBuy, participant, achievement);
    }

    /** Settles this Participant's personal offers from offerIndex onward after a smaller regeneration. */
    public synchronized void withdrawPersonalOffers(long shopId, ParticipantId participant, int fromIndex) {
        int slot = personalSlot(participant);
        Map<Integer, ShopEntryState> entries = shops.get(shopId);
        if(slot < 0 || entries == null) return;
        for(int index = Math.max(0, fromIndex); index < PERSONAL_OFFERS_PER_SLOT; index++) {
            int entryId = PERSONAL_ENTRY_BASE + slot * PERSONAL_OFFERS_PER_SLOT + index;
            ShopEntryState entry = entries.get(entryId);
            if(entry != null && !entry.sold) entries.put(entryId, entry.settled(++revision));
        }
    }

    private int personalSlot(ParticipantId participant) {
        int slot = 0;
        for(ParticipantId registered : ledgers.keySet()) {
            if(registered.equals(participant)) return slot;
            slot++;
        }
        return -1;
    }

    private ShopEntryState put(long shopId, int entryId, String templateId, ItemProperties properties,
            int cost, boolean useOnBuy, ParticipantId restrictedTo, String achievement) {
        Map<Integer, ShopEntryState> entries = shops.get(shopId);
        boolean replacing = entries.containsKey(entryId);
        if(!replacing && (entries.size() >= MAX_ENTRIES_PER_SHOP || shopEntryCount >= MAX_SHOP_ENTRIES)) {
            return null;
        }
        ShopEntryState entry = new ShopEntryState(shopId, generation, revision + 1L, entryId,
                templateId, properties, cost, useOnBuy, false, restrictedTo, achievement);
        revision++;
        entries.put(entryId, entry);
        if(!replacing) shopEntryCount++;
        return entry;
    }

    public synchronized ShopEntryState entry(long shopId, int entryId) {
        Map<Integer, ShopEntryState> entries = shops.get(shopId);
        return entries == null ? null : entries.get(entryId);
    }

    public synchronized List<ShopEntryState> stock(long shopId) {
        Map<Integer, ShopEntryState> entries = shops.get(shopId);
        return entries == null ? Collections.<ShopEntryState>emptyList()
                : Collections.unmodifiableList(new ArrayList<ShopEntryState>(entries.values()));
    }

    public synchronized List<ShopEntryState> shopSnapshot() {
        List<ShopEntryState> result = new ArrayList<ShopEntryState>();
        for(Map<Integer, ShopEntryState> entries : shops.values()) result.addAll(entries.values());
        return Collections.unmodifiableList(result);
    }

    /** First accepted purchase settles the entry and spends only the buyer's personal gold. */
    public synchronized PurchaseOutcome purchase(ParticipantId buyer, long shopId, int entryId) {
        Ledger ledger = ledgers.get(buyer);
        if(ledger == null) return PurchaseOutcome.UNKNOWN_PARTICIPANT;
        Map<Integer, ShopEntryState> entries = shops.get(shopId);
        ShopEntryState entry = entries == null ? null : entries.get(entryId);
        if(entry == null) return PurchaseOutcome.UNKNOWN_ENTRY;
        if(entry.sold) return PurchaseOutcome.SOLD;
        if(!entry.offeredTo(buyer)) return PurchaseOutcome.NOT_OFFERED;
        if(ledger.gold < entry.cost) return PurchaseOutcome.INSUFFICIENT_GOLD;
        entries.put(entryId, entry.settled(++revision));
        ledger.gold -= entry.cost;
        ledger.revision = ++revision;
        return PurchaseOutcome.ACCEPTED;
    }
}
