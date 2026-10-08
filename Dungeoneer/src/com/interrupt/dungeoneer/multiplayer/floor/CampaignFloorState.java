package com.interrupt.dungeoneer.multiplayer.floor;

import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantKind;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn;
import com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detached Host-local area checkpoint. No Level, player body, clock or simulation survives here. */
public final class CampaignFloorState {
    public static final int MAX_DORMANT_FLOORS = 128;
    private final String areaKey, floorId;
    private final long seed;
    private final SharedFloorFingerprint fingerprint;
    private final byte[] nativeFloor;
    private final List<PhysicalItemState> worldItems;
    private final CombatSnapshot combat;
    private final List<DoorSnapshot> doors;
    private final List<BreakableSnapshot> breakables;
    private final List<ActorEffectsSnapshot> actorEffects;
    private final List<NativeMonsterSpawn> monsterSpawns;
    private final List<String> consumedMonsterSpawners;
    private final Map<Long, Long> dropTimers;

    public CampaignFloorState(String areaKey, String floorId, long seed,
            SharedFloorFingerprint fingerprint, byte[] nativeFloor, List<PhysicalItemState> worldItems,
            CombatSnapshot combat, List<DoorSnapshot> doors, List<BreakableSnapshot> breakables,
            List<ActorEffectsSnapshot> actorEffects, List<NativeMonsterSpawn> monsterSpawns,
            List<String> consumedMonsterSpawners, Map<Long, Long> dropTimers) {
        this.areaKey = requireAreaKey(areaKey);
        this.floorId = requireAreaKey(floorId);
        if(seed == 0L || nativeFloor == null || nativeFloor.length == 0
                || nativeFloor.length > NativeFloorSave.MAX_BYTES)
            throw new IllegalArgumentException("Dormant Floor needs native checkpoint and seed.");
        if(worldItems == null || doors == null || breakables == null || actorEffects == null
                || monsterSpawns == null || consumedMonsterSpawners == null)
            throw new IllegalArgumentException("Dormant Floor state is incomplete.");
        Set<Long> itemIds = new HashSet<>();
        if(worldItems.size() > AuthoritativeItemWorld.MAX_ITEMS)
            throw new IllegalArgumentException("Dormant Floor item count exceeds bounds.");
        for(PhysicalItemState item : worldItems) {
            if(item == null || item.owner != null || !itemIds.add(item.entityId))
                throw new IllegalArgumentException("Dormant Floor item ownership/identity is invalid.");
        }
        if(combat != null) for(CombatantSnapshot actor : combat.getCombatants()) {
            if(actor.getKind() == CombatantKind.PARTICIPANT)
                throw new IllegalArgumentException("Participant cannot occupy Dormant Floor.");
        }
        Set<String> actors = new HashSet<>();
        for(ActorEffectsSnapshot effect : actorEffects) {
            if(effect == null || effect.monsterId.startsWith("participant:")
                    || !actors.add(effect.monsterId) || combat == null
                    || combat.getCombatant(effect.monsterId) == null)
                throw new IllegalArgumentException("Dormant Floor status actor is invalid.");
        }
        Set<Long> objects = new HashSet<>();
        if(doors.size() > 4096 || breakables.size() > 4096)
            throw new IllegalArgumentException("Dormant Floor world object count exceeds bounds.");
        for(DoorSnapshot door : doors) if(door == null || !objects.add(door.entityId))
            throw new IllegalArgumentException("Duplicate Dormant Floor object.");
        for(BreakableSnapshot object : breakables) if(object == null || !objects.add(object.entityId))
            throw new IllegalArgumentException("Duplicate Dormant Floor object.");
        Set<String> spawns = new HashSet<>();
        if(monsterSpawns.size() > CombatSnapshot.MAX_MONSTERS)
            throw new IllegalArgumentException("Dormant Floor spawn count exceeds bounds.");
        for(NativeMonsterSpawn spawn : monsterSpawns) if(spawn == null || !spawns.add(spawn.monsterId))
            throw new IllegalArgumentException("Duplicate Dormant Floor Monster spawn.");
        if(consumedMonsterSpawners.size() > CampaignSave.MAX_CONSUMED_MONSTER_SPAWNERS)
            throw new IllegalArgumentException("Dormant Floor spawner count exceeds bounds.");
        Set<String> spawners = new HashSet<>();
        for(String spawner : consumedMonsterSpawners) {
            if(spawner == null || spawner.isEmpty() || !spawners.add(spawner)
                    || spawner.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            > CampaignSave.MAX_SPAWNER_KEY_BYTES)
                throw new IllegalArgumentException("Dormant Floor spawner is invalid.");
        }
        this.dropTimers = validateDropTimers(worldItems, dropTimers);
        this.seed = seed;
        this.fingerprint = fingerprint;
        this.nativeFloor = nativeFloor.clone();
        this.worldItems = immutable(worldItems);
        this.combat = combat;
        this.doors = immutable(doors);
        this.breakables = immutable(breakables);
        this.actorEffects = immutable(actorEffects);
        this.monsterSpawns = immutable(monsterSpawns);
        this.consumedMonsterSpawners = immutable(consumedMonsterSpawners);
    }

    public static CampaignFloorState fromCampaign(String areaKey, CampaignSave campaign) {
        List<PhysicalItemState> items = new ArrayList<>();
        for(PhysicalItemState item : campaign.getPhysicalItems()) if(item.owner == null) items.add(item);
        List<ActorEffectsSnapshot> effects = new ArrayList<>();
        for(ActorEffectsSnapshot effect : campaign.getActorEffects())
            if(!effect.monsterId.startsWith("participant:")) effects.add(effect);
        CombatSnapshot floorCombat = null;
        if(campaign.getCombat() != null) {
            List<CombatantSnapshot> actors = new ArrayList<>();
            for(CombatantSnapshot actor : campaign.getCombat().getCombatants())
                if(actor.getKind() != CombatantKind.PARTICIPANT) actors.add(actor);
            if(!actors.isEmpty()) floorCombat = new CombatSnapshot(campaign.getCombat().getSequence(),
                    campaign.getCombat().getHostTick(), campaign.getCombat().getMonsters(), actors);
        }
        return new CampaignFloorState(areaKey, campaign.getFloorId(), campaign.getFloorSeed(),
                campaign.getFloorFingerprint(), campaign.getNativeFloor(), items, floorCombat,
                campaign.getDoors(), campaign.getBreakables(), effects, campaign.getMonsterSpawns(),
                campaign.getConsumedMonsterSpawners(), campaign.getDropTimers());
    }

    public static String requireAreaKey(String key) {
        if(key == null || key.trim().isEmpty() || key.length() > 256)
            throw new IllegalArgumentException("Campaign area identity is invalid.");
        return key;
    }

    public static Map<Long, Long> validateDropTimers(List<PhysicalItemState> items, Map<Long, Long> timers) {
        if(timers == null || timers.size() > AuthoritativeItemWorld.MAX_ITEMS)
            throw new IllegalArgumentException("Floor drop timers are outside bounds.");
        Map<Long, PhysicalItemState> byId = new HashMap<>();
        for(PhysicalItemState item : items) byId.put(item.entityId, item);
        for(Map.Entry<Long, Long> timer : timers.entrySet()) {
            PhysicalItemState item = byId.get(timer.getKey());
            if(item == null || item.owner != null || item.consumed || timer.getValue() == null
                    || timer.getValue() < 0L || timer.getValue() > 21600L)
                throw new IllegalArgumentException("Floor drop timer has invalid item or remaining time.");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(timers));
    }

    public String getAreaKey() { return areaKey; }
    public String getFloorId() { return floorId; }
    public long getSeed() { return seed; }
    public SharedFloorFingerprint getFingerprint() { return fingerprint; }
    public byte[] getNativeFloor() { return nativeFloor.clone(); }
    public List<PhysicalItemState> getWorldItems() { return worldItems; }
    public CombatSnapshot getCombat() { return combat; }
    public List<DoorSnapshot> getDoors() { return doors; }
    public List<BreakableSnapshot> getBreakables() { return breakables; }
    public List<ActorEffectsSnapshot> getActorEffects() { return actorEffects; }
    public List<NativeMonsterSpawn> getMonsterSpawns() { return monsterSpawns; }
    public List<String> getConsumedMonsterSpawners() { return consumedMonsterSpawners; }
    public Map<Long, Long> getDropTimers() { return dropTimers; }
    private static <T> List<T> immutable(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }
}
