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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Host Campaign Library plus bounded, all-or-nothing Campaign Save codec. */
public final class CampaignSaveStore {
    private static final int MAGIC = 0x444D5053; // DMPS
    private static final long MAX_SAVE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_ITEMS = 4096;
    private static final int MAX_WORLD_OBJECTS = 4096;

    private final File campaignsRoot;
    private final Map<String, FileChannel> sessionChannels = new HashMap<String, FileChannel>();
    private final Map<String, FileLock> sessionLocks = new HashMap<String, FileLock>();
    interface AtomicReplacement { void replace(Path source, Path target) throws IOException; }
    private final AtomicReplacement replacement;

    public CampaignSaveStore(File campaignsRoot) {
        this(campaignsRoot, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    CampaignSaveStore(File campaignsRoot, AtomicReplacement replacement) {
        if(campaignsRoot == null) throw new IllegalArgumentException("Campaign storage root cannot be null.");
        this.campaignsRoot = campaignsRoot;
        this.replacement = replacement;
    }

    public synchronized boolean exists(String campaignId) {
        return saveFile(campaignId).isFile() || isArchived(campaignId);
    }

    /** Campaign IDs with durable saves. Invalid directory names and unrelated files are ignored. */
    public synchronized List<String> listCampaigns() {
        File[] directories = campaignsRoot.listFiles();
        if(directories == null) return Collections.emptyList();
        List<String> campaigns = new ArrayList<String>();
        for(File directory : directories) {
            if(!directory.isDirectory()) continue;
            try {
                String id = CampaignRoster.requireCampaignId(directory.getName());
                if(exists(id)) campaigns.add(id);
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
        File file = currentFile(campaignId);
        CampaignSave loaded = loadFile(file, campaignId, expectedCompatibility);
        if(isTerminal(campaignId) && loaded.getOutcome() == CampaignSave.Outcome.ACTIVE) {
            throw new IllegalStateException("Terminal Campaign archive write is incomplete; active recovery/export blocked. Keep files for repair: " + campaignId);
        }
        int format = fileFormat(file);
        if(format < CampaignSave.FORMAT && !isArchived(campaignId)) {
            File backup = campaignFile(campaignId, "campaign.save.before-format-" + CampaignSave.FORMAT);
            // An existing backup is never replaced by a later migration attempt.
            if(!backup.exists()) atomicCopy(file, backup);
            writeFile(file, loaded);
        }
        if(loaded.getOutcome() != CampaignSave.Outcome.ACTIVE && !isArchived(campaignId)) save(loaded);
        return loaded;
    }

    /** Display-only inspection uses same bounded codec without migration or compatibility writes. */
    public synchronized CampaignSave inspect(String campaignId) {
        return loadFile(currentFile(campaignId), campaignId, null);
    }

    /** File time means last saved, never last played; unavailable is zero. */
    public synchronized long getLastSavedTime(String campaignId) {
        File file = currentFile(campaignId);
        return file.isFile() ? file.lastModified() : 0L;
    }

    private CampaignSave loadFile(File file, String campaignId,
            DirectConnectCompatibility expectedCompatibility) {
        if(!file.isFile()) throw new IllegalStateException("Campaign Save does not exist: " + campaignId);
        if(file.length() < 16L || file.length() > MAX_SAVE_BYTES) {
            throw corrupt(file, "file is truncated or outside size bounds", null);
        }

        CampaignSave loaded;
        try(DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            loaded = read(input, expectedCompatibility);
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

        if(campaignId != null && !campaignId.equals(loaded.getCampaignId())) {
            throw corrupt(file, "Campaign identity does not match its profile path", null);
        }
        if(expectedCompatibility == null) return loaded;
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
        String id = campaign.getCampaignId();
        if(isArchived(id)) throw new IllegalStateException("Campaign Archive is read-only: " + id);
        if(campaign.getOutcome() != CampaignSave.Outcome.ACTIVE) {
            markTerminal(id, campaign.getOutcome());
            File archive = campaignFile(id, "campaign.archive");
            writeFile(archive, campaign);
            archive.setReadOnly();
            return;
        }
        File file = saveFile(id);
        if(campaignFile(id, "terminal.outcome").exists()) {
            throw new IllegalStateException("Terminal Campaign cannot resume active play: " + id);
        }
        if(file.isFile()) {
            boolean valid = false;
            try { loadFile(file, id, campaign.getCompatibility()); valid = true; }
            catch(IllegalStateException invalid) { /* Keep last valid backup if primary is damaged. */ }
            if(valid) {
                if(fileFormat(file) < CampaignSave.FORMAT) {
                    File migrationBackup = campaignFile(id, "campaign.save.before-format-" + CampaignSave.FORMAT);
                    if(!migrationBackup.exists()) atomicCopy(file, migrationBackup);
                }
                atomicCopy(file, campaignFile(id, "campaign.previous"));
            }
        }
        writeFile(file, campaign);
    }

    private void writeFile(File file, CampaignSave campaign) {
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
            try(FileOutputStream stream = new FileOutputStream(temporary);
                    DataOutputStream output = new DataOutputStream(new BufferedOutputStream(stream))) {
                write(output, campaign);
                output.flush();
                stream.getFD().sync();
            }
            if(temporary.length() > MAX_SAVE_BYTES) {
                throw new IllegalStateException("Campaign Save exceeds bounded file size: " + file);
            }
            replacement.replace(temporary.toPath(), file.toPath());
            AtomicProperties.restrictToOwner(file);
        }
        catch(IOException ex) {
            throw new IllegalStateException("Could not save Campaign: " + file, ex);
        }
        finally {
            if(temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    public synchronized boolean isArchived(String id) {
        return campaignFile(id, "campaign.archive").isFile();
    }

    /** Durable outcome latch precedes archive write, so disk failure cannot revive a defeated run. */
    public synchronized void markTerminal(String id, CampaignSave.Outcome outcome) {
        if(outcome == CampaignSave.Outcome.ACTIVE) throw new IllegalArgumentException("Terminal outcome required.");
        File file = campaignFile(id, "terminal.outcome");
        if(file.exists()) return;
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try(FileOutputStream output = new FileOutputStream(file)) {
                output.write(outcome.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            AtomicProperties.restrictToOwner(file);
        }
        catch(IOException failure) { throw new IllegalStateException("Could not preserve terminal Campaign outcome: " + id, failure); }
    }

    public synchronized boolean isTerminal(String id) {
        return isArchived(id) || campaignFile(id, "terminal.outcome").exists();
    }

    /** Dirty marker survives process death; lock prevents two Hosts writing one Campaign. */
    public synchronized void beginSession(String id) {
        if(isTerminal(id)) throw new IllegalStateException("Terminal Campaign cannot resume: " + id);
        if(sessionLocks.containsKey(id)) throw new IllegalStateException("Campaign is already running: " + id);
        if(needsRecovery(id)) throw new IllegalStateException("Campaign needs crash recovery in Campaign Library: " + id);
        File marker = campaignFile(id, "session.running");
        FileChannel channel = null;
        try {
            Files.createDirectories(marker.getParentFile().toPath());
            channel = FileChannel.open(campaignFile(id, "session.lock").toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if(lock == null) throw new IllegalStateException("Campaign is running in another Host: " + id);
            sessionChannels.put(id, channel);
            sessionLocks.put(id, lock);
            try(FileOutputStream output = new FileOutputStream(marker)) {
                output.write(1); output.getFD().sync();
            }
            AtomicProperties.restrictToOwner(marker);
        }
        catch(IOException | RuntimeException failure) {
            endSession(id, false);
            if(channel != null) try { channel.close(); } catch(IOException ignored) { }
            throw new IllegalStateException("Could not begin Campaign session: " + id, failure);
        }
    }

    public synchronized void endSession(String id, boolean clean) {
        try {
            if(clean) Files.deleteIfExists(campaignFile(id, "session.running").toPath());
        }
        catch(IOException failure) { throw new IllegalStateException("Could not mark Campaign shutdown clean: " + id, failure); }
        finally {
            FileLock lock = sessionLocks.remove(id);
            FileChannel channel = sessionChannels.remove(id);
            try { if(lock != null) lock.release(); } catch(IOException ignored) { }
            try { if(channel != null) channel.close(); } catch(IOException ignored) { }
        }
    }

    public synchronized boolean needsRecovery(String id) {
        return !isTerminal(id) && !sessionLocks.containsKey(id)
                && campaignFile(id, "session.running").isFile();
    }

    /** Three bounded internal slots, never exposed as selectable checkpoints. */
    public synchronized void snapshot(CampaignSave campaign) {
        String id = campaign.getCampaignId();
        if(isArchived(id)) return;
        File oldest = campaignFile(id, "recovery-0.save");
        for(int index = 1; index < 3; index++) {
            File candidate = campaignFile(id, "recovery-" + index + ".save");
            if(!candidate.exists() || candidate.lastModified() < oldest.lastModified()) oldest = candidate;
        }
        writeFile(oldest, campaign);
        long timestamp = System.currentTimeMillis();
        for(int index = 0; index < 3; index++) {
            File other = campaignFile(id, "recovery-" + index + ".save");
            if(!other.equals(oldest)) timestamp = Math.max(timestamp, other.lastModified() + 1L);
        }
        if(!oldest.setLastModified(timestamp)) throw new IllegalStateException("Could not order Campaign recovery snapshots.");
    }

    /** Recovery always chooses newest valid compatible record; terminal primary wins. */
    public synchronized CampaignSave recover(String id, DirectConnectCompatibility compatibility) {
        if(!needsRecovery(id)) throw new IllegalStateException("Recovery is available only after unclean Host shutdown.");
        try(FileChannel channel = FileChannel.open(campaignFile(id, "session.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch(java.nio.channels.OverlappingFileLockException running) {
                throw new IllegalStateException("Campaign is still running in another Host; recovery blocked.", running);
            }
            if(lock == null) throw new IllegalStateException("Campaign is still running in another Host; recovery blocked.");
            try { return recoverLocked(id, compatibility); }
            finally { lock.release(); }
        }
        catch(IOException failure) { throw new IllegalStateException("Could not lock Campaign for recovery.", failure); }
    }

    private CampaignSave recoverLocked(String id, DirectConnectCompatibility compatibility) {
        List<File> candidates = new ArrayList<File>();
        for(int index = 0; index < 3; index++) candidates.add(campaignFile(id, "recovery-" + index + ".save"));
        candidates.add(saveFile(id));
        candidates.add(campaignFile(id, "campaign.previous"));
        Collections.sort(candidates, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        RuntimeException last = null;
        for(File candidate : candidates) {
            if(!candidate.isFile()) continue;
            CampaignSave saved;
            try { saved = loadFile(candidate, id, compatibility); }
            catch(RuntimeException failure) { last = failure; continue; }
            save(saved);
            endSession(id, true);
            return saved;
        }
        throw new IllegalStateException("No valid compatible Campaign recovery remains; keep files and restore a Host export.", last);
    }

    public synchronized File exportCampaign(String id, LauncherIdentity host, File destination,
            DirectConnectCompatibility compatibility) {
        if(needsRecovery(id) || sessionLocks.containsKey(id)) {
            throw new IllegalStateException("End or recover Campaign before exporting it.");
        }
        CampaignSave campaign = load(id, compatibility);
        requireHost(campaign, host);
        if(destination == null || !destination.isAbsolute() || destination.exists()) {
            throw new IllegalStateException("Choose an unused absolute path for Campaign Export.");
        }
        atomicCopy(currentFile(id), destination);
        return destination;
    }

    public synchronized CampaignSave readExport(File source, LauncherIdentity host,
            DirectConnectCompatibility compatibility) {
        if(source == null || !source.isFile()) throw new IllegalStateException("Campaign Export file does not exist.");
        CampaignSave campaign = loadFile(source, null, compatibility);
        requireHost(campaign, host);
        return campaign;
    }

    private static void requireHost(CampaignSave campaign, LauncherIdentity host) {
        for(CampaignSlot slot : campaign.getSlots()) {
            if(slot.getNumber() == 1 && slot.getLauncherIdentity().equals(host)) return;
        }
        throw new IllegalStateException("Campaign belongs to another Host Launcher Identity; restore original Host Identity Recovery File first.");
    }

    private File currentFile(String id) {
        return isArchived(id) ? campaignFile(id, "campaign.archive") : saveFile(id);
    }

    private File campaignFile(String id, String name) {
        return new File(new File(campaignsRoot, CampaignRoster.requireCampaignId(id)), name);
    }

    private int fileFormat(File file) {
        try(DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            input.readInt(); return input.readInt();
        }
        catch(IOException failure) { throw new IllegalStateException("Could not read Campaign format: " + file, failure); }
    }

    private void atomicCopy(File source, File destination) {
        File temporary = null;
        try {
            Files.createDirectories(destination.getParentFile().toPath());
            temporary = File.createTempFile("campaign-copy-", ".tmp", destination.getParentFile());
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try(FileOutputStream output = new FileOutputStream(temporary, true)) { output.getFD().sync(); }
            // Recovery ordering follows captured state, not time an old backup was copied.
            Files.setLastModifiedTime(temporary.toPath(), Files.getLastModifiedTime(source.toPath()));
            replacement.replace(temporary.toPath(), destination.toPath());
            AtomicProperties.restrictToOwner(destination);
        }
        catch(IOException failure) { throw new IllegalStateException("Could not preserve Campaign file: " + destination, failure); }
        finally { if(temporary != null && temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
    }

    private CampaignSave read(DataInputStream input, DirectConnectCompatibility expected) throws IOException {
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
        // Explicit predecessor migration; content identity remains checked by loadFile.
        if(expected != null && format < 5 && protocol <= 47
                && compatibility.getBuildId().equals("mp-v108-prototype-campaign-library-51")
                && expected.getBuildId().equals("mp-v108-prototype-campaign-library-52")) {
            compatibility = new DirectConnectCompatibility(expected.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256());
        }
        if(expected != null && format == 5 && protocol <= 49
                && compatibility.getBuildId().equals("mp-v108-prototype-late-admission-53")
                && expected.getBuildId().equals("mp-v108-prototype-dormant-floors-54")) {
            compatibility = new DirectConnectCompatibility(expected.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256());
        }
        if(expected != null && format <= 6 && protocol <= 49 && expected.getBuildId().equals("mp-v108-prototype-party-travel-55")
                && (compatibility.getBuildId().equals("mp-v108-prototype-dormant-floors-54")
                    || format <= 5 && compatibility.getBuildId().equals("mp-v108-prototype-late-admission-53"))) {
            compatibility = new DirectConnectCompatibility(expected.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256());
        }
        if(expected != null && format <= 7 && protocol <= 50
                && (expected.getBuildId().equals("mp-v108-prototype-named-campaigns-56")
                    || expected.getBuildId().equals("mp-v108-prototype-shared-lobby-57")
                    || expected.getBuildId().equals("mp-v108-prototype-manual-ready-58"))
                && (compatibility.getBuildId().equals("mp-v108-prototype-party-travel-55")
                    || format <= 6 && compatibility.getBuildId().equals("mp-v108-prototype-dormant-floors-54")
                    || format <= 5 && compatibility.getBuildId().equals("mp-v108-prototype-late-admission-53"))) {
            compatibility = new DirectConnectCompatibility(expected.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256());
        }
        // Shared lobby changes wire only; named-campaign save layout and gameplay remain identical.
        if(expected != null && format == 8 && protocol <= 50
                && compatibility.getBuildId().equals("mp-v108-prototype-named-campaigns-56")
                && (expected.getBuildId().equals("mp-v108-prototype-shared-lobby-57")
                    || expected.getBuildId().equals("mp-v108-prototype-manual-ready-58"))) {
            compatibility = new DirectConnectCompatibility(expected.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256());
        }
        // Manual Ready is ephemeral wire state; existing campaign body remains unchanged.
        if(expected != null && format == 8 && protocol == 51
                && compatibility.getBuildId().equals("mp-v108-prototype-shared-lobby-57")
                && expected.getBuildId().equals("mp-v108-prototype-manual-ready-58")) {
            compatibility = new DirectConnectCompatibility(expected.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256());
        }
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
        int partyKeys = format >= 4 ? input.readInt() : 0;
        long keyRevision = format >= 4 ? input.readLong() : 0L;
        com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot progression = format >= 4
                ? com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.readFrom(input)
                : com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.empty();
        if(format >= 5) {
            int knowledgeCount = boundedCount(input.readInt(), 1, 4, "personal knowledge");
            if(knowledgeCount != participants.size()) throw new IllegalArgumentException("Missing Slot knowledge.");
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for(int index = 0; index < knowledgeCount; index++) {
                int slot = input.readInt();
                if(!seen.add(slot)) throw new IllegalArgumentException("Duplicate Slot knowledge.");
                com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge knowledge =
                        com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge.readFrom(input);
                boolean found = false;
                for(int participantIndex = 0; participantIndex < participants.size(); participantIndex++) {
                    CampaignSave.ParticipantState old = participants.get(participantIndex);
                    if(old.getCampaignSlot() != slot) continue;
                    participants.set(participantIndex, new CampaignSave.ParticipantState(slot, old.getParty(),
                            old.getMovement(), old.getProgress(), old.isHoldingOrb(), knowledge));
                    found = true; break;
                }
                if(!found) throw new IllegalArgumentException("Knowledge belongs to absent Slot.");
            }
        }
        com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping mapping = format >= 5
                ? com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping.readFrom(input)
                : com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping.fromItems(items);
        CampaignSave saved = new CampaignSave(compatibility, campaignId, capacity, startingLives, outcome,
                floorId, floorSeed, fingerprint, nativeWorldGeneration, slots, participants,
                items, combat, doors, breakables, actorEffects, spawns, spawners, nativeFloor,
                partyKeys, keyRevision, progression, mapping);
        if(format < 6) return saved;
        String activeArea = input.readUTF();
        java.util.Map<Long, Long> timers = readDropTimers(input);
        int floorCount = boundedCount(input.readInt(), 0,
                com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState.MAX_DORMANT_FLOORS,
                "Dormant Floor");
        List<com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState> dormant = new ArrayList<>();
        for(int index = 0; index < floorCount; index++) dormant.add(readFloor(input));
        saved = saved.withFloorHistory(activeArea, dormant, timers);
        if(format < 7) return saved;
        int scatterCount = boundedCount(input.readInt(), 0, (floorCount + 1) * MAX_ITEMS, "scatter provenance");
        java.util.Map<Long, Integer> owners = new java.util.LinkedHashMap<>();
        for(int index = 0; index < scatterCount; index++) {
            long item = input.readLong(); int slot = input.readInt();
            if(owners.put(item, slot) != null) throw new IllegalArgumentException("Duplicate scatter identity.");
        }
        saved = saved.withScatterOwners(owners);
        return format >= 8 ? saved.withCampaignName(input.readUTF()) : saved;
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
        output.writeInt(campaign.getPartyKeys());
        output.writeLong(campaign.getKeyRevision());
        campaign.getPartyProgression().writeTo(output);
        output.writeInt(campaign.getParticipants().size());
        for(CampaignSave.ParticipantState participant : campaign.getParticipants()) {
            output.writeInt(participant.getCampaignSlot());
            participant.getPersonalKnowledge().writeTo(output);
        }
        campaign.getPotionMapping().writeTo(output);
        output.writeUTF(campaign.getActiveAreaKey());
        writeDropTimers(output, campaign.getDropTimers());
        output.writeInt(campaign.getDormantFloors().size());
        for(com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState floor : campaign.getDormantFloors())
            writeFloor(output, floor);
        output.writeInt(campaign.getScatterOwners().size());
        for(java.util.Map.Entry<Long, Integer> entry : campaign.getScatterOwners().entrySet()) {
            output.writeLong(entry.getKey()); output.writeInt(entry.getValue());
        }
        output.writeUTF(campaign.getCampaignName());
    }

    private static java.util.Map<Long, Long> readDropTimers(DataInputStream in) throws IOException {
        int count = boundedCount(in.readInt(), 0, MAX_ITEMS, "floor drop timer");
        java.util.Map<Long, Long> timers = new java.util.LinkedHashMap<>();
        for(int index = 0; index < count; index++) {
            long id = in.readLong(), remaining = in.readLong();
            if(timers.put(id, remaining) != null) throw new IllegalArgumentException("Duplicate floor drop timer.");
        }
        return timers;
    }

    private static void writeDropTimers(DataOutputStream out, java.util.Map<Long, Long> timers) throws IOException {
        out.writeInt(timers.size());
        for(java.util.Map.Entry<Long, Long> timer : timers.entrySet()) {
            out.writeLong(timer.getKey()); out.writeLong(timer.getValue());
        }
    }

    private com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState readFloor(DataInputStream in) throws IOException {
        String areaKey = in.readUTF(), floorId = in.readUTF();
        long seed = in.readLong();
        SharedFloorFingerprint fingerprint = readFingerprint(in);
        byte[] nativeFloor = new byte[boundedCount(in.readInt(), 1, NativeFloorSave.MAX_BYTES, "Dormant native byte")];
        in.readFully(nativeFloor);
        int count = boundedCount(in.readInt(), 0, MAX_ITEMS, "Dormant item");
        List<PhysicalItemState> items = new ArrayList<>();
        for(int index = 0; index < count; index++) items.add(readItem(in));
        CombatSnapshot combat = in.readBoolean() ? readCombat(in) : null;
        count = boundedCount(in.readInt(), 0, MAX_WORLD_OBJECTS, "Dormant door");
        List<DoorSnapshot> doors = new ArrayList<>();
        for(int index = 0; index < count; index++) doors.add(readDoor(in));
        count = boundedCount(in.readInt(), 0, MAX_WORLD_OBJECTS, "Dormant breakable");
        List<BreakableSnapshot> breakables = new ArrayList<>();
        for(int index = 0; index < count; index++) breakables.add(readBreakable(in));
        count = boundedCount(in.readInt(), 0, DirectConnectProtocol.MAX_MONSTERS, "Dormant effect actor");
        List<ActorEffectsSnapshot> effects = new ArrayList<>();
        for(int index = 0; index < count; index++) effects.add(readEffects(in));
        count = boundedCount(in.readInt(), 0, CombatSnapshot.MAX_MONSTERS, "Dormant Monster spawn");
        List<NativeMonsterSpawn> spawns = new ArrayList<>();
        for(int index = 0; index < count; index++) spawns.add(new NativeMonsterSpawn(
                in.readUTF(), in.readUTF(), in.readUTF(), in.readFloat(), in.readFloat(), in.readFloat(),
                in.readInt(), in.readInt()));
        count = boundedCount(in.readInt(), 0, CampaignSave.MAX_CONSUMED_MONSTER_SPAWNERS, "Dormant spawner");
        List<String> spawners = new ArrayList<>();
        for(int index = 0; index < count; index++) spawners.add(in.readUTF());
        return new com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState(areaKey,
                floorId, seed, fingerprint, nativeFloor, items, combat, doors, breakables, effects,
                spawns, spawners, readDropTimers(in));
    }

    private void writeFloor(DataOutputStream out, com.interrupt.dungeoneer.multiplayer.floor.CampaignFloorState floor) throws IOException {
        out.writeUTF(floor.getAreaKey()); out.writeUTF(floor.getFloorId()); out.writeLong(floor.getSeed());
        writeFingerprint(out, floor.getFingerprint());
        byte[] nativeFloor = floor.getNativeFloor();
        out.writeInt(nativeFloor.length); out.write(nativeFloor);
        out.writeInt(floor.getWorldItems().size());
        for(PhysicalItemState item : floor.getWorldItems()) writeItem(out, item);
        out.writeBoolean(floor.getCombat() != null);
        if(floor.getCombat() != null) writeCombat(out, floor.getCombat());
        out.writeInt(floor.getDoors().size());
        for(DoorSnapshot door : floor.getDoors()) writeDoor(out, door);
        out.writeInt(floor.getBreakables().size());
        for(BreakableSnapshot object : floor.getBreakables()) writeBreakable(out, object);
        out.writeInt(floor.getActorEffects().size());
        for(ActorEffectsSnapshot effect : floor.getActorEffects()) writeEffects(out, effect);
        out.writeInt(floor.getMonsterSpawns().size());
        for(NativeMonsterSpawn spawn : floor.getMonsterSpawns()) {
            out.writeUTF(spawn.monsterId); out.writeUTF(spawn.theme); out.writeUTF(spawn.name);
            out.writeFloat(spawn.x); out.writeFloat(spawn.y); out.writeFloat(spawn.z);
            out.writeInt(spawn.health); out.writeInt(spawn.maximumHealth);
        }
        out.writeInt(floor.getConsumedMonsterSpawners().size());
        for(String spawner : floor.getConsumedMonsterSpawners()) out.writeUTF(spawner);
        writeDropTimers(out, floor.getDropTimers());
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
