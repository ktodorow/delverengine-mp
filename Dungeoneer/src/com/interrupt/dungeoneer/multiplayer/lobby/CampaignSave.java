package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantKind;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn;
import com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress;
import com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave;
import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorFingerprint;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.lives.AuthoritativeLives;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Detached, validated Host-owned Campaign aggregate. Contains no engine or network objects. */
public final class CampaignSave {
    /**
     * 2: late Monster spawns and consumed floor MonsterSpawners, needed to rebuild the floor.
     * 3: Host Active Floor checkpoint in Delver's own level format (absent in format 2).
     * 4: Party Progression and Party Keys.
     */
    public static final int FORMAT = 4;
    public static final int MAX_CONSUMED_MONSTER_SPAWNERS = 4096;
    public static final int MAX_SPAWNER_KEY_BYTES = 512;

    public enum Outcome { ACTIVE, DEFEATED, COMPLETED }

    public static final class ParticipantState {
        private final int campaignSlot;
        private final PartyMemberStatus party;
        private final MovementEntityState movement;
        private final ParticipantProgress progress;
        private final boolean holdingOrb;

        public ParticipantState(int campaignSlot, PartyMemberStatus party,
                MovementEntityState movement, ParticipantProgress progress,
                boolean holdingOrb) {
            if(campaignSlot < 1 || campaignSlot > 4 || party == null
                    || party.getCampaignSlot() != campaignSlot) {
                throw new IllegalArgumentException("Saved Participant does not match its Campaign Slot.");
            }
            ParticipantId expected = participantId(campaignSlot);
            if(progress != null && !expected.equals(progress.participantId)) {
                throw new IllegalArgumentException("Saved progress belongs to another Campaign Slot.");
            }
            if(movement != null && movement.getEntityId().getValue() != campaignSlot) {
                throw new IllegalArgumentException("Saved movement Entity ID is not stable for its Campaign Slot.");
            }
            this.campaignSlot = campaignSlot;
            this.party = party;
            this.movement = movement;
            this.progress = progress;
            this.holdingOrb = holdingOrb;
        }

        public int getCampaignSlot() { return campaignSlot; }
        public PartyMemberStatus getParty() { return party; }
        public MovementEntityState getMovement() { return movement; }
        public ParticipantProgress getProgress() { return progress; }
        public boolean isHoldingOrb() { return holdingOrb; }
    }

    private final DirectConnectCompatibility compatibility;
    private final String campaignId;
    private final int capacity;
    private final int startingLives;
    private final Outcome outcome;
    private final String floorId;
    private final long floorSeed;
    private final SharedFloorFingerprint floorFingerprint;
    private final long nativeWorldGeneration;
    private final List<CampaignSlot> slots;
    private final List<ParticipantState> participants;
    private final List<PhysicalItemState> physicalItems;
    private final CombatSnapshot combat;
    private final List<DoorSnapshot> doors;
    private final List<BreakableSnapshot> breakables;
    private final List<ActorEffectsSnapshot> actorEffects;
    private final List<NativeMonsterSpawn> monsterSpawns;
    private final List<String> consumedMonsterSpawners;
    private final byte[] nativeFloor;
    private final int partyKeys;
    private final long keyRevision;
    private final com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot partyProgression;

    public CampaignSave(DirectConnectCompatibility compatibility, String campaignId,
            int capacity, int startingLives, Outcome outcome, String floorId,
            long floorSeed, SharedFloorFingerprint floorFingerprint,
            long nativeWorldGeneration, List<CampaignSlot> slots,
            List<ParticipantState> participants, List<PhysicalItemState> physicalItems,
            CombatSnapshot combat, List<DoorSnapshot> doors,
            List<BreakableSnapshot> breakables,
            List<ActorEffectsSnapshot> actorEffects) {
        this(compatibility, campaignId, capacity, startingLives, outcome, floorId, floorSeed,
                floorFingerprint, nativeWorldGeneration, slots, participants, physicalItems,
                combat, doors, breakables, actorEffects,
                Collections.<NativeMonsterSpawn>emptyList(), Collections.<String>emptyList());
    }

    public CampaignSave(DirectConnectCompatibility compatibility, String campaignId,
            int capacity, int startingLives, Outcome outcome, String floorId,
            long floorSeed, SharedFloorFingerprint floorFingerprint,
            long nativeWorldGeneration, List<CampaignSlot> slots,
            List<ParticipantState> participants, List<PhysicalItemState> physicalItems,
            CombatSnapshot combat, List<DoorSnapshot> doors,
            List<BreakableSnapshot> breakables,
            List<ActorEffectsSnapshot> actorEffects, List<NativeMonsterSpawn> monsterSpawns,
            List<String> consumedMonsterSpawners) {
        this(compatibility, campaignId, capacity, startingLives, outcome, floorId, floorSeed,
                floorFingerprint, nativeWorldGeneration, slots, participants, physicalItems,
                combat, doors, breakables, actorEffects, monsterSpawns, consumedMonsterSpawners,
                null);
    }

    public CampaignSave(DirectConnectCompatibility compatibility, String campaignId,
            int capacity, int startingLives, Outcome outcome, String floorId,
            long floorSeed, SharedFloorFingerprint floorFingerprint,
            long nativeWorldGeneration, List<CampaignSlot> slots,
            List<ParticipantState> participants, List<PhysicalItemState> physicalItems,
            CombatSnapshot combat, List<DoorSnapshot> doors,
            List<BreakableSnapshot> breakables,
            List<ActorEffectsSnapshot> actorEffects, List<NativeMonsterSpawn> monsterSpawns,
            List<String> consumedMonsterSpawners, byte[] nativeFloor) {
        this(compatibility, campaignId, capacity, startingLives, outcome, floorId, floorSeed,
                floorFingerprint, nativeWorldGeneration, slots, participants, physicalItems,
                combat, doors, breakables, actorEffects, monsterSpawns, consumedMonsterSpawners,
                nativeFloor, 0, 0L);
    }

    public CampaignSave(DirectConnectCompatibility compatibility, String campaignId,
            int capacity, int startingLives, Outcome outcome, String floorId,
            long floorSeed, SharedFloorFingerprint floorFingerprint,
            long nativeWorldGeneration, List<CampaignSlot> slots,
            List<ParticipantState> participants, List<PhysicalItemState> physicalItems,
            CombatSnapshot combat, List<DoorSnapshot> doors,
            List<BreakableSnapshot> breakables,
            List<ActorEffectsSnapshot> actorEffects, List<NativeMonsterSpawn> monsterSpawns,
            List<String> consumedMonsterSpawners, byte[] nativeFloor,
            int partyKeys, long keyRevision) {
        this(compatibility, campaignId, capacity, startingLives, outcome, floorId, floorSeed,
                floorFingerprint, nativeWorldGeneration, slots, participants, physicalItems,
                combat, doors, breakables, actorEffects, monsterSpawns, consumedMonsterSpawners,
                nativeFloor, partyKeys, keyRevision,
                com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.empty());
    }

    public CampaignSave(DirectConnectCompatibility compatibility, String campaignId,
            int capacity, int startingLives, Outcome outcome, String floorId,
            long floorSeed, SharedFloorFingerprint floorFingerprint,
            long nativeWorldGeneration, List<CampaignSlot> slots,
            List<ParticipantState> participants, List<PhysicalItemState> physicalItems,
            CombatSnapshot combat, List<DoorSnapshot> doors,
            List<BreakableSnapshot> breakables,
            List<ActorEffectsSnapshot> actorEffects, List<NativeMonsterSpawn> monsterSpawns,
            List<String> consumedMonsterSpawners, byte[] nativeFloor,
            int partyKeys, long keyRevision,
            com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot partyProgression) {
        if(partyProgression == null) throw new IllegalArgumentException("Saved Party Progression is required.");
        if(partyKeys < 0 || partyKeys > 1000000 || keyRevision < 0
                || keyRevision == 0 && partyKeys != 0) {
            throw new IllegalArgumentException("Saved Party Keys are invalid.");
        }
        if(compatibility == null) throw new IllegalArgumentException("Campaign compatibility is required.");
        if(nativeFloor != null && (nativeFloor.length == 0
                || nativeFloor.length > NativeFloorSave.MAX_BYTES)) {
            throw new IllegalArgumentException("Saved native floor is empty or outside bounds.");
        }
        this.campaignId = CampaignRoster.requireCampaignId(campaignId);
        if(capacity < 2 || capacity > 4) {
            throw new IllegalArgumentException("Saved Campaign Capacity must be two, three, or four.");
        }
        if(startingLives < AuthoritativeLives.MINIMUM_STARTING_LIVES
                || startingLives > AuthoritativeLives.MAXIMUM_STARTING_LIVES) {
            throw new IllegalArgumentException("Saved starting Lives must be 1-5.");
        }
        if(outcome == null || floorId == null || floorId.trim().isEmpty()
                || floorId.length() > 256 || floorSeed == 0L || nativeWorldGeneration < 1L) {
            throw new IllegalArgumentException("Saved Campaign root state is invalid.");
        }
        if(slots == null || slots.isEmpty() || slots.size() > capacity
                || participants == null || participants.size() != slots.size()
                || physicalItems == null || doors == null || breakables == null
                || actorEffects == null || monsterSpawns == null
                || consumedMonsterSpawners == null) {
            throw new IllegalArgumentException("Saved Campaign collections are incomplete.");
        }

        Set<Integer> slotNumbers = new HashSet<Integer>();
        Set<LauncherIdentity> identities = new HashSet<LauncherIdentity>();
        Set<String> tokens = new HashSet<String>();
        for(CampaignSlot slot : slots) {
            if(slot == null || slot.getNumber() > capacity || !slotNumbers.add(slot.getNumber())
                    || !identities.add(slot.getLauncherIdentity())
                    || !tokens.add(slot.getReconnectToken())) {
                throw new IllegalArgumentException("Saved Campaign Slot identity is duplicate or invalid.");
            }
        }
        if(!slotNumbers.contains(1)) {
            throw new IllegalArgumentException("Saved Campaign has no Host Campaign Slot.");
        }
        Set<Integer> participantSlots = new HashSet<Integer>();
        for(ParticipantState participant : participants) {
            if(participant == null || !slotNumbers.contains(participant.getCampaignSlot())
                    || !participantSlots.add(participant.getCampaignSlot())) {
                throw new IllegalArgumentException("Saved Participant identity is duplicate or unknown.");
            }
        }
        Set<Long> itemIds = new HashSet<Long>();
        for(PhysicalItemState item : physicalItems) {
            if(item == null || !itemIds.add(item.entityId)) {
                throw new IllegalArgumentException("Saved physical item identity is duplicate or invalid.");
            }
            if(item.owner != null) {
                int ownerSlot = campaignSlot(item.owner);
                if(ownerSlot == 0 || !slotNumbers.contains(ownerSlot)) {
                    throw new IllegalArgumentException("Saved physical item owner is not in Campaign Roster.");
                }
            }
        }
        Set<Long> doorIds = new HashSet<Long>();
        for(DoorSnapshot door : doors) {
            if(door == null || !doorIds.add(door.entityId)) {
                throw new IllegalArgumentException("Saved door identity is duplicate or invalid.");
            }
        }
        Set<Long> breakableIds = new HashSet<Long>();
        for(BreakableSnapshot breakable : breakables) {
            if(breakable == null || !breakableIds.add(breakable.entityId)
                    || doorIds.contains(breakable.entityId)) {
                throw new IllegalArgumentException(
                        "Saved breakable identity is duplicate or conflicts with a door.");
            }
        }
        Set<String> effectActors = new HashSet<String>();
        for(ActorEffectsSnapshot effects : actorEffects) {
            if(effects == null || !effectActors.add(effects.monsterId)) {
                throw new IllegalArgumentException(
                        "Saved native effect actor identity is duplicate or invalid.");
            }
            if(effects.monsterId.startsWith("participant:")) {
                int slot = campaignSlot(new ParticipantId(
                        effects.monsterId.substring("participant:".length())));
                if(slot == 0 || !slotNumbers.contains(slot)) {
                    throw new IllegalArgumentException(
                            "Saved native effect Participant is not in Campaign Roster.");
                }
            }
            else if(combat == null || combat.getCombatant(effects.monsterId) == null) {
                throw new IllegalArgumentException(
                        "Saved native effect actor is absent from combat state.");
            }
        }
        if(monsterSpawns.size() > CombatSnapshot.MAX_MONSTERS) {
            throw new IllegalArgumentException("Saved late Monster count is outside bounds.");
        }
        Set<String> spawnedMonsters = new HashSet<String>();
        for(NativeMonsterSpawn spawn : monsterSpawns) {
            if(spawn == null || !spawnedMonsters.add(spawn.monsterId)) {
                throw new IllegalArgumentException("Saved late Monster identity is duplicate or invalid.");
            }
        }
        if(consumedMonsterSpawners.size() > MAX_CONSUMED_MONSTER_SPAWNERS
                || new HashSet<String>(consumedMonsterSpawners).size()
                        != consumedMonsterSpawners.size()) {
            throw new IllegalArgumentException("Saved Monster spawners are duplicate or outside bounds.");
        }
        for(String spawner : consumedMonsterSpawners) {
            if(spawner == null || spawner.isEmpty()
                    || spawner.getBytes(StandardCharsets.UTF_8).length > MAX_SPAWNER_KEY_BYTES) {
                throw new IllegalArgumentException("Saved Monster spawner identity is invalid.");
            }
        }
        if(combat != null) {
            for(CombatantSnapshot combatant : combat.getCombatants()) {
                if(combatant.getKind() != CombatantKind.PARTICIPANT) continue;
                String prefix = "participant:";
                if(!combatant.getId().startsWith(prefix)) {
                    throw new IllegalArgumentException("Saved Participant combat identity is invalid.");
                }
                int slot = campaignSlot(new ParticipantId(
                        combatant.getId().substring(prefix.length())));
                if(slot == 0 || !slotNumbers.contains(slot)) {
                    throw new IllegalArgumentException(
                            "Saved Participant combat state is not in Campaign Roster.");
                }
            }
        }

        this.compatibility = compatibility;
        this.capacity = capacity;
        this.startingLives = startingLives;
        this.outcome = outcome;
        this.floorId = floorId;
        this.floorSeed = floorSeed;
        this.floorFingerprint = floorFingerprint;
        this.nativeWorldGeneration = nativeWorldGeneration;
        this.slots = immutable(slots);
        this.participants = immutable(participants);
        this.physicalItems = immutable(physicalItems);
        this.combat = combat;
        this.doors = immutable(doors);
        this.breakables = immutable(breakables);
        this.actorEffects = immutable(actorEffects);
        this.monsterSpawns = immutable(monsterSpawns);
        this.consumedMonsterSpawners = immutable(consumedMonsterSpawners);
        this.nativeFloor = nativeFloor == null ? null : nativeFloor.clone();
        this.partyKeys = partyKeys;
        this.keyRevision = keyRevision;
        this.partyProgression = partyProgression;
    }

    public DirectConnectCompatibility getCompatibility() { return compatibility; }
    public String getCampaignId() { return campaignId; }
    public int getCapacity() { return capacity; }
    public int getStartingLives() { return startingLives; }
    public Outcome getOutcome() { return outcome; }
    public String getFloorId() { return floorId; }
    public long getFloorSeed() { return floorSeed; }
    public SharedFloorFingerprint getFloorFingerprint() { return floorFingerprint; }
    public long getNativeWorldGeneration() { return nativeWorldGeneration; }
    public List<CampaignSlot> getSlots() { return slots; }
    public List<ParticipantState> getParticipants() { return participants; }
    public List<PhysicalItemState> getPhysicalItems() { return physicalItems; }
    public CombatSnapshot getCombat() { return combat; }
    public List<DoorSnapshot> getDoors() { return doors; }
    public List<BreakableSnapshot> getBreakables() { return breakables; }
    public List<ActorEffectsSnapshot> getActorEffects() { return actorEffects; }
    /** Monsters that joined the floor after it was built; a rebuilt floor does not contain them. */
    public List<NativeMonsterSpawn> getMonsterSpawns() { return monsterSpawns; }
    /** Floor MonsterSpawners that already added their Monsters and must not fire again. */
    public List<String> getConsumedMonsterSpawners() { return consumedMonsterSpawners; }
    /** Host floor as last checkpointed, or null when the floor must be rebuilt (format 2). */
    public byte[] getNativeFloor() { return nativeFloor == null ? null : nativeFloor.clone(); }
    public boolean hasNativeFloor() { return nativeFloor != null; }
    public int getPartyKeys() { return partyKeys; }
    public long getKeyRevision() { return keyRevision; }
    public com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot getPartyProgression() { return partyProgression; }

    public ParticipantState getParticipant(int campaignSlot) {
        for(ParticipantState participant : participants) {
            if(participant.getCampaignSlot() == campaignSlot) return participant;
        }
        return null;
    }

    /** Rejects roster mutation or cross-Campaign application before runtime state changes. */
    public void verifyRoster(CampaignRoster roster) {
        if(roster == null || !campaignId.equals(roster.getCampaignId())
                || capacity != roster.getCapacity() || roster.getSlots().size() != slots.size()) {
            throw new IllegalStateException("Campaign Save does not match current Campaign Roster.");
        }
        for(CampaignSlot saved : slots) {
            CampaignSlot current = roster.getSlot(saved.getNumber());
            if(current == null || !saved.getLauncherIdentity().equals(current.getLauncherIdentity())
                    || !saved.getReconnectToken().equals(current.getReconnectToken())) {
                throw new IllegalStateException("Campaign Save ownership does not match Campaign Slot "
                        + saved.getNumber() + ".");
            }
        }
    }

    static ParticipantId participantId(int campaignSlot) {
        return new ParticipantId("campaign-slot-" + campaignSlot);
    }

    static int campaignSlot(ParticipantId participant) {
        if(participant == null || !participant.getValue().startsWith("campaign-slot-")) return 0;
        try { return Integer.parseInt(participant.getValue().substring("campaign-slot-".length())); }
        catch(RuntimeException invalid) { return 0; }
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
