package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantKind;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.MonsterSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationState;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;
import com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress;
import com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave;
import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorFingerprint;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectProtocol;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Host Campaign Library plus bounded, all-or-nothing Campaign Save codec. */
public final class CampaignSaveStore {
    private static final int MAGIC = 0x444D5053; // DMPS
    private static final long MAX_SAVE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_ITEMS = 4096;
    private static final int MAX_WORLD_OBJECTS = 4096;

    private final File campaignsRoot;

    public CampaignSaveStore(File campaignsRoot) {
        if(campaignsRoot == null) throw new IllegalArgumentException("Campaign storage root cannot be null.");
        this.campaignsRoot = campaignsRoot;
    }

    public synchronized boolean exists(String campaignId) {
        return saveFile(campaignId).isFile();
    }

    /** Campaign IDs with durable saves. Invalid directory names and unrelated files are ignored. */
    public synchronized List<String> listCampaigns() {
        File[] directories = campaignsRoot.listFiles();
        if(directories == null) return Collections.emptyList();
        List<String> campaigns = new ArrayList<String>();
        for(File directory : directories) {
            if(!directory.isDirectory() || !new File(directory, "campaign.save").isFile()) continue;
            try {
                campaigns.add(CampaignRoster.requireCampaignId(directory.getName()));
            }
            catch(IllegalArgumentException ignored) { }
        }
        Collections.sort(campaigns);
        return Collections.unmodifiableList(campaigns);
    }

    public synchronized CampaignSave load(String campaignId,
            DirectConnectCompatibility expectedCompatibility) {
        if(expectedCompatibility == null) {
            throw new IllegalArgumentException("Expected Campaign compatibility is required.");
        }
        File file = saveFile(campaignId);
        if(!file.isFile()) throw new IllegalStateException("Campaign Save does not exist: " + campaignId);
        if(file.length() < 16L || file.length() > MAX_SAVE_BYTES) {
            throw corrupt(file, "file is truncated or outside size bounds", null);
        }

        CampaignSave loaded;
        try(DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            loaded = read(input);
            if(input.read() != -1) throw corrupt(file, "file has trailing data", null);
        }
        catch(EOFException ex) {
            throw corrupt(file, "file is truncated", ex);
        }
        catch(IOException ex) {
            throw corrupt(file, "file could not be read", ex);
        }
        catch(IllegalArgumentException ex) {
            throw corrupt(file, "saved state is internally inconsistent", ex);
        }

        if(!campaignId.equals(loaded.getCampaignId())) {
            throw corrupt(file, "Campaign identity does not match its profile path", null);
        }
        DirectConnectCompatibility saved = loaded.getCompatibility();
        if(!expectedCompatibility.getBuildId().equals(saved.getBuildId())) {
            throw incompatible(file, "engine build", saved.getBuildId(),
                    expectedCompatibility.getBuildId());
        }
        if(!expectedCompatibility.getContentFormat().equals(saved.getContentFormat())
                || !expectedCompatibility.getContentSha256().equals(saved.getContentSha256())) {
            throw incompatible(file, "owned content", saved.getContentIdentity(),
                    expectedCompatibility.getContentIdentity());
        }
        return loaded;
    }

    public synchronized void save(CampaignSave campaign) {
        if(campaign == null) throw new IllegalArgumentException("Campaign Save cannot be null.");
        File file = saveFile(campaign.getCampaignId());
        File parent = file.getParentFile();
        if(!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create Campaign Save directory: " + parent);
        }
        File temporary;
        try {
            temporary = File.createTempFile("campaign-", ".tmp", parent);
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not create temporary Campaign Save: " + file, ex);
        }
        try {
            try(DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temporary)))) {
                write(output, campaign);
                output.flush();
            }
            if(temporary.length() > MAX_SAVE_BYTES) {
                throw new IllegalStateException("Campaign Save exceeds bounded file size: " + file);
            }
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch(AtomicMoveNotSupportedException ex) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            AtomicProperties.restrictToOwner(file);
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not save Campaign: " + file, ex);
        }
        finally {
            if(temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private CampaignSave read(DataInputStream input) throws IOException {
        if(input.readInt() != MAGIC) throw new IllegalArgumentException("Campaign Save magic is invalid.");
        int format = input.readInt();
        if(format < 2) {
            // Older saves lack late Monster identities, so their floor cannot be rebuilt faithfully.
            throw new IllegalStateException("Campaign Save has older format " + format
                    + " without saved Monster spawns; create a new Campaign.");
        }
        if(format > CampaignSave.FORMAT) {
            throw new IllegalStateException("Campaign Save has unsupported future format " + format
                    + "; this build reads format " + CampaignSave.FORMAT + ".");
        }
        int protocol = input.readInt();
        // Save layout is versioned by FORMAT; an older wire protocol changes nothing on disk.
        if(protocol > DirectConnectProtocol.VERSION) {
            throw new IllegalStateException("Campaign Save protocol " + protocol
                    + " is incompatible with protocol " + DirectConnectProtocol.VERSION + ".");
        }
        DirectConnectCompatibility compatibility = new DirectConnectCompatibility(
                input.readUTF(), input.readUTF(), input.readUTF());
        String campaignId = input.readUTF();
        int capacity = input.readInt();
        int startingLives = input.readInt();
        CampaignSave.Outcome outcome = CampaignSave.Outcome.valueOf(input.readUTF());
        String floorId = input.readUTF();
        long floorSeed = input.readLong();
        SharedFloorFingerprint fingerprint = readFingerprint(input);
        long nativeWorldGeneration = input.readLong();

        int slotCount = boundedCount(input.readInt(), 1, 4, "Campaign Slot");
        List<CampaignSlot> slots = new ArrayList<CampaignSlot>(slotCount);
        for(int index = 0; index < slotCount; index++) {
            slots.add(new CampaignSlot(input.readInt(), new LauncherIdentity(input.readUTF()),
                    input.readUTF(), new SlotPresentation(input.readUTF(), input.readUTF())));
        }

        int participantCount = boundedCount(input.readInt(), 1, 4, "Participant");
        List<CampaignSave.ParticipantState> participants =
                new ArrayList<CampaignSave.ParticipantState>(participantCount);
        for(int index = 0; index < participantCount; index++) {
            int slot = input.readInt();
            PartyMemberStatus party = readParty(input);
            MovementEntityState movement = input.readBoolean() ? readMovement(input) : null;
            ParticipantProgress progress = input.readBoolean() ? readProgress(input) : null;
            participants.add(new CampaignSave.ParticipantState(slot, party, movement,
                    progress, input.readBoolean()));
        }

        int itemCount = boundedCount(input.readInt(), 0, MAX_ITEMS, "physical item");
        List<PhysicalItemState> items = new ArrayList<PhysicalItemState>(itemCount);
        for(int index = 0; index < itemCount; index++) items.add(readItem(input));
        CombatSnapshot combat = input.readBoolean() ? readCombat(input) : null;
        int doorCount = boundedCount(input.readInt(), 0, MAX_WORLD_OBJECTS, "door");
        List<DoorSnapshot> doors = new ArrayList<DoorSnapshot>(doorCount);
        for(int index = 0; index < doorCount; index++) doors.add(readDoor(input));
        int breakableCount = boundedCount(input.readInt(), 0, MAX_WORLD_OBJECTS, "breakable");
        List<BreakableSnapshot> breakables = new ArrayList<BreakableSnapshot>(breakableCount);
        for(int index = 0; index < breakableCount; index++) breakables.add(readBreakable(input));
        int effectsCount = boundedCount(input.readInt(), 0,
                DirectConnectProtocol.MAX_MONSTERS + 4, "native effect actor");
        List<ActorEffectsSnapshot> actorEffects =
                new ArrayList<ActorEffectsSnapshot>(effectsCount);
        for(int index = 0; index < effectsCount; index++) actorEffects.add(readEffects(input));
        int spawnCount = boundedCount(input.readInt(), 0, CombatSnapshot.MAX_MONSTERS,
                "late Monster");
        List<NativeMonsterSpawn> spawns = new ArrayList<NativeMonsterSpawn>(spawnCount);
        for(int index = 0; index < spawnCount; index++) {
            spawns.add(new NativeMonsterSpawn(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readFloat(), input.readFloat(), input.readFloat(),
                    input.readInt(), input.readInt()));
        }
        int spawnerCount = boundedCount(input.readInt(), 0,
                CampaignSave.MAX_CONSUMED_MONSTER_SPAWNERS, "Monster spawner");
        List<String> spawners = new ArrayList<String>(spawnerCount);
        for(int index = 0; index < spawnerCount; index++) spawners.add(input.readUTF());
        // Format 2 has no floor checkpoint; Host then rebuilds the floor and reapplies state.
        byte[] nativeFloor = null;
        if(format >= 3 && input.readBoolean()) {
            nativeFloor = new byte[boundedCount(input.readInt(), 1, NativeFloorSave.MAX_BYTES,
                    "native floor byte")];
            input.readFully(nativeFloor);
        }
        return new CampaignSave(compatibility, campaignId, capacity, startingLives, outcome,
                floorId, floorSeed, fingerprint, nativeWorldGeneration, slots, participants,
                items, combat, doors, breakables, actorEffects, spawns, spawners, nativeFloor);
    }

    private void write(DataOutputStream output, CampaignSave campaign) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(CampaignSave.FORMAT);
        output.writeInt(DirectConnectProtocol.VERSION);
        DirectConnectCompatibility compatibility = campaign.getCompatibility();
        output.writeUTF(compatibility.getBuildId());
        output.writeUTF(compatibility.getContentFormat());
        output.writeUTF(compatibility.getContentSha256());
        output.writeUTF(campaign.getCampaignId());
        output.writeInt(campaign.getCapacity());
        output.writeInt(campaign.getStartingLives());
        output.writeUTF(campaign.getOutcome().name());
        output.writeUTF(campaign.getFloorId());
        output.writeLong(campaign.getFloorSeed());
        writeFingerprint(output, campaign.getFloorFingerprint());
        output.writeLong(campaign.getNativeWorldGeneration());

        output.writeInt(campaign.getSlots().size());
        for(CampaignSlot slot : campaign.getSlots()) {
            output.writeInt(slot.getNumber());
            output.writeUTF(slot.getLauncherIdentity().getValue());
            output.writeUTF(slot.getReconnectToken());
            output.writeUTF(slot.getPresentation().getNickname());
            output.writeUTF(slot.getPresentation().getAvatarId());
        }
        output.writeInt(campaign.getParticipants().size());
        for(CampaignSave.ParticipantState participant : campaign.getParticipants()) {
            output.writeInt(participant.getCampaignSlot());
            writeParty(output, participant.getParty());
            output.writeBoolean(participant.getMovement() != null);
            if(participant.getMovement() != null) writeMovement(output, participant.getMovement());
            output.writeBoolean(participant.getProgress() != null);
            if(participant.getProgress() != null) writeProgress(output, participant.getProgress());
            output.writeBoolean(participant.isHoldingOrb());
        }
        output.writeInt(campaign.getPhysicalItems().size());
        for(PhysicalItemState item : campaign.getPhysicalItems()) writeItem(output, item);
        output.writeBoolean(campaign.getCombat() != null);
        if(campaign.getCombat() != null) writeCombat(output, campaign.getCombat());
        output.writeInt(campaign.getDoors().size());
        for(DoorSnapshot door : campaign.getDoors()) writeDoor(output, door);
        output.writeInt(campaign.getBreakables().size());
        for(BreakableSnapshot breakable : campaign.getBreakables()) {
            writeBreakable(output, breakable);
        }
        output.writeInt(campaign.getActorEffects().size());
        for(ActorEffectsSnapshot effects : campaign.getActorEffects()) {
            writeEffects(output, effects);
        }
        output.writeInt(campaign.getMonsterSpawns().size());
        for(NativeMonsterSpawn spawn : campaign.getMonsterSpawns()) {
            output.writeUTF(spawn.monsterId); output.writeUTF(spawn.theme);
            output.writeUTF(spawn.name);
            output.writeFloat(spawn.x); output.writeFloat(spawn.y); output.writeFloat(spawn.z);
            output.writeInt(spawn.health); output.writeInt(spawn.maximumHealth);
        }
        output.writeInt(campaign.getConsumedMonsterSpawners().size());
        for(String spawner : campaign.getConsumedMonsterSpawners()) output.writeUTF(spawner);
        byte[] nativeFloor = campaign.getNativeFloor();
        output.writeBoolean(nativeFloor != null);
        if(nativeFloor != null) {
            output.writeInt(nativeFloor.length);
            output.write(nativeFloor);
        }
    }

    private static void writeParty(DataOutputStream out, PartyMemberStatus value) throws IOException {
        out.writeInt(value.getCampaignSlot());
        out.writeBoolean(value.getEntityId() != null);
        if(value.getEntityId() != null) out.writeLong(value.getEntityId().getValue());
        out.writeUTF(value.getNickname()); out.writeUTF(value.getAvatarId());
        out.writeInt(value.getHealth()); out.writeInt(value.getMaximumHealth());
        out.writeInt(value.getRemainingLives()); out.writeInt(value.getState().getWireId());
        out.writeInt(value.getBleedoutTicks()); out.writeInt(value.getRevivalTicks());
        out.writeInt(value.getReviverSlot());
    }

    private static PartyMemberStatus readParty(DataInputStream in) throws IOException {
        int slot = in.readInt();
        NetworkEntityId entity = in.readBoolean() ? new NetworkEntityId(in.readLong()) : null;
        String nickname = in.readUTF(), avatar = in.readUTF();
        int health = in.readInt(), maximumHealth = in.readInt(), lives = in.readInt();
        PartyMemberState state = PartyMemberState.fromWireId(in.readInt());
        return new PartyMemberStatus(slot, entity, nickname, avatar, health, maximumHealth,
                lives, state, in.readInt(), in.readInt(), in.readInt());
    }

    private static void writeMovement(DataOutputStream out, MovementEntityState value) throws IOException {
        out.writeLong(value.getEntityId().getValue()); out.writeLong(value.getLifecycleSequence());
        out.writeLong(value.getLastProcessedInputTick());
        out.writeFloat(value.getX()); out.writeFloat(value.getY()); out.writeFloat(value.getZ());
        out.writeFloat(value.getVelocityX()); out.writeFloat(value.getVelocityY());
        out.writeFloat(value.getVelocityZ()); out.writeFloat(value.getRotation());
        out.writeUTF(value.getMovementState().name()); out.writeFloat(value.getLookY());
    }

    private static MovementEntityState readMovement(DataInputStream in) throws IOException {
        return new MovementEntityState(new NetworkEntityId(in.readLong()), in.readLong(),
                in.readLong(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat(), in.readFloat(), MovementState.valueOf(in.readUTF()),
                in.readFloat());
    }

    private static void writeProgress(DataOutputStream out, ParticipantProgress value) throws IOException {
        out.writeUTF(value.participantId.getValue()); out.writeLong(value.revision);
        out.writeInt(value.gold); out.writeInt(value.experience); out.writeInt(value.level);
        out.writeInt(value.attack); out.writeInt(value.defense); out.writeInt(value.agility);
        out.writeInt(value.speed); out.writeInt(value.magic); out.writeInt(value.endurance);
        out.writeInt(value.pendingStatChoices); out.writeInt(value.maximumHealth);
        out.writeInt(value.inventorySize); out.writeInt(value.hotbarSize);
    }

    private static ParticipantProgress readProgress(DataInputStream in) throws IOException {
        return new ParticipantProgress(new ParticipantId(in.readUTF()), in.readLong(),
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt());
    }

    private static void writeItem(DataOutputStream out, PhysicalItemState value) throws IOException {
        out.writeLong(value.entityId); out.writeLong(value.revision); out.writeUTF(value.templateId);
        out.writeBoolean(value.owner != null);
        if(value.owner != null) out.writeUTF(value.owner.getValue());
        out.writeFloat(value.x); out.writeFloat(value.y); out.writeFloat(value.z);
        out.writeInt(value.properties.condition); out.writeInt(value.properties.level);
        out.writeUTF(value.properties.suffix); out.writeUTF(value.properties.prefix);
        out.writeInt(value.properties.quantity); out.writeInt(value.properties.potionType);
        out.writeBoolean(value.consumed); out.writeUTF(value.equipmentSlot);
    }

    private static PhysicalItemState readItem(DataInputStream in) throws IOException {
        long id = in.readLong(), revision = in.readLong(); String template = in.readUTF();
        ParticipantId owner = in.readBoolean() ? new ParticipantId(in.readUTF()) : null;
        float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
        ItemProperties properties = new ItemProperties(in.readInt(), in.readInt(), in.readUTF(),
                in.readUTF(), in.readInt(), in.readInt());
        return new PhysicalItemState(id, revision, template, owner, x, y, z, properties,
                in.readBoolean(), in.readUTF());
    }

    private static void writeCombat(DataOutputStream out, CombatSnapshot value) throws IOException {
        out.writeLong(value.getSequence()); out.writeLong(value.getHostTick());
        out.writeInt(value.getMonsters().size());
        for(MonsterSnapshot monster : value.getMonsters()) {
            out.writeUTF(monster.getId()); out.writeUTF(monster.getTargetId());
            out.writeFloat(monster.getX()); out.writeFloat(monster.getY());
            out.writeFloat(monster.getZ()); out.writeBoolean(monster.isGibbed());
        }
        out.writeInt(value.getCombatants().size());
        for(CombatantSnapshot combatant : value.getCombatants()) {
            out.writeUTF(combatant.getId()); out.writeUTF(combatant.getKind().name());
            out.writeInt(combatant.getHealth()); out.writeInt(combatant.getMaximumHealth());
        }
    }

    private static CombatSnapshot readCombat(DataInputStream in) throws IOException {
        long sequence = in.readLong(), hostTick = in.readLong();
        int monsterCount = boundedCount(in.readInt(), 0, CombatSnapshot.MAX_MONSTERS, "Monster");
        List<MonsterSnapshot> monsters = new ArrayList<MonsterSnapshot>(monsterCount);
        for(int index = 0; index < monsterCount; index++) {
            monsters.add(new MonsterSnapshot(in.readUTF(), in.readUTF(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readBoolean()));
        }
        int combatantCount = boundedCount(in.readInt(), 1, CombatSnapshot.MAX_COMBATANTS,
                "combatant");
        List<CombatantSnapshot> combatants = new ArrayList<CombatantSnapshot>(combatantCount);
        for(int index = 0; index < combatantCount; index++) {
            combatants.add(new CombatantSnapshot(in.readUTF(), CombatantKind.valueOf(in.readUTF()),
                    in.readInt(), in.readInt()));
        }
        return new CombatSnapshot(sequence, hostTick, monsters, combatants);
    }

    private static void writeDoor(DataOutputStream out, DoorSnapshot value) throws IOException {
        out.writeLong(value.entityId); out.writeLong(value.revision); out.writeInt(value.state);
        out.writeBoolean(value.locked); out.writeBoolean(value.active); out.writeBoolean(value.solid);
        out.writeFloat(value.x); out.writeFloat(value.y); out.writeFloat(value.z);
        out.writeFloat(value.rotation); out.writeFloat(value.animation);
    }

    private static DoorSnapshot readDoor(DataInputStream in) throws IOException {
        return new DoorSnapshot(in.readLong(), in.readLong(), in.readInt(), in.readBoolean(),
                in.readBoolean(), in.readBoolean(), in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat());
    }

    private static void writeBreakable(DataOutputStream out, BreakableSnapshot value)
            throws IOException {
        out.writeLong(value.entityId); out.writeLong(value.revision); out.writeInt(value.hp);
        out.writeBoolean(value.active); out.writeBoolean(value.solid);
        out.writeFloat(value.x); out.writeFloat(value.y); out.writeFloat(value.z);
        out.writeFloat(value.velocityX); out.writeFloat(value.velocityY); out.writeFloat(value.velocityZ);
        out.writeFloat(value.rotationX); out.writeFloat(value.rotationY); out.writeFloat(value.rotationZ);
    }

    private static BreakableSnapshot readBreakable(DataInputStream in) throws IOException {
        return new BreakableSnapshot(in.readLong(), in.readLong(), in.readInt(),
                in.readBoolean(), in.readBoolean(), in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat());
    }

    private static void writeEffects(DataOutputStream out, ActorEffectsSnapshot value)
            throws IOException {
        out.writeUTF(value.monsterId); out.writeLong(value.sequence);
        out.writeBoolean(value.invisible); out.writeBoolean(value.floating);
        out.writeFloat(value.flightSpeed);
        out.writeBoolean(value.animation != null);
        if(value.animation != null) {
            out.writeUTF(value.animation.kind.name()); out.writeLong(value.animation.instanceId);
            out.writeFloat(value.animation.time); out.writeBoolean(value.animation.playing);
            out.writeBoolean(value.animation.looping); out.writeInt(value.animation.texture);
        }
        out.writeFloat(value.drunk); out.writeFloat(value.actorTimeScale);
        out.writeFloat(value.worldTimeScale); out.writeInt(value.effects.size());
        for(NativeStatusEffectState effect : value.effects) {
            out.writeLong(effect.instanceId); out.writeUTF(effect.kind.name());
            out.writeFloat(effect.remaining); out.writeFloat(effect.speed);
            out.writeUTF(effect.shader); out.writeBoolean(effect.particles);
            out.writeFloat(effect.elapsed); out.writeFloat(effect.fieldOfView);
            out.writeLong(effect.pulses);
        }
    }

    private static ActorEffectsSnapshot readEffects(DataInputStream in) throws IOException {
        String id = in.readUTF(); long sequence = in.readLong();
        boolean invisible = in.readBoolean(), floating = in.readBoolean();
        float flightSpeed = in.readFloat();
        NativeAnimationState animation = null;
        if(in.readBoolean()) {
            animation = new NativeAnimationState(NativeAnimationState.Kind.valueOf(in.readUTF()),
                    in.readLong(), in.readFloat(), in.readBoolean(), in.readBoolean(), in.readInt());
        }
        float drunk = in.readFloat(), actorScale = in.readFloat(), worldScale = in.readFloat();
        int count = boundedCount(in.readInt(), 0, ActorEffectsSnapshot.MAX_EFFECTS,
                "native status effect");
        List<NativeStatusEffectState> effects = new ArrayList<NativeStatusEffectState>(count);
        for(int index = 0; index < count; index++) {
            effects.add(new NativeStatusEffectState(in.readLong(),
                    NativeStatusEffectState.Kind.valueOf(in.readUTF()), in.readFloat(),
                    in.readFloat(), in.readUTF(), in.readBoolean(), in.readFloat(),
                    in.readFloat(), in.readLong()));
        }
        return new ActorEffectsSnapshot(id, sequence, invisible, effects, drunk,
                actorScale, worldScale, floating, flightSpeed, animation);
    }

    private static void writeFingerprint(DataOutputStream out,
            SharedFloorFingerprint fingerprint) throws IOException {
        out.writeBoolean(fingerprint != null);
        if(fingerprint == null) return;
        for(SharedFloorFingerprint.Category category : SharedFloorFingerprint.Category.values()) {
            out.writeInt(fingerprint.getCount(category)); out.writeLong(fingerprint.getDigest(category));
        }
    }

    private static SharedFloorFingerprint readFingerprint(DataInputStream in) throws IOException {
        if(!in.readBoolean()) return null;
        SharedFloorFingerprint.Category[] categories = SharedFloorFingerprint.Category.values();
        int[] counts = new int[categories.length]; long[] digests = new long[categories.length];
        for(int index = 0; index < categories.length; index++) {
            counts[index] = in.readInt(); digests[index] = in.readLong();
        }
        return new SharedFloorFingerprint(counts, digests);
    }

    private File saveFile(String campaignId) {
        return new File(new File(campaignsRoot, CampaignRoster.requireCampaignId(campaignId)),
                "campaign.save");
    }

    private static int boundedCount(int count, int minimum, int maximum, String label) {
        if(count < minimum || count > maximum) {
            throw new IllegalArgumentException("Saved " + label + " count is outside bounds.");
        }
        return count;
    }

    private static IllegalStateException corrupt(File file, String reason, Throwable cause) {
        return new IllegalStateException("Campaign Save is corrupt (" + reason + "): " + file,
                cause);
    }

    private static IllegalStateException incompatible(File file, String category,
            String saved, String current) {
        return new IllegalStateException("Campaign Save has incompatible " + category + " (saved "
                + saved + ", current " + current + "): " + file);
    }
}
