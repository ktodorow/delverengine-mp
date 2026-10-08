package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;

/** Host-local persistent Campaign Roster store. It never reads original single-player saves. */
public final class CampaignRosterStore {
    private static final String FORMAT = "2";

    private final File campaignsRoot;
    private final SecureRandom random;
    private final CampaignSaveStore campaignSaves;

    public CampaignRosterStore() {
        if(!MultiplayerProfile.isInitialized()) {
            throw new IllegalStateException("Multiplayer profile is not initialized.");
        }
        campaignsRoot = MultiplayerProfile.resolveWritableFile("saves/campaigns").file();
        random = new SecureRandom();
        campaignSaves = new CampaignSaveStore(campaignsRoot);
    }

    public CampaignRosterStore(File campaignsRoot, SecureRandom random) {
        if(campaignsRoot == null) throw new IllegalArgumentException("Campaign storage root cannot be null.");
        if(random == null) throw new IllegalArgumentException("Secure random source cannot be null.");
        this.campaignsRoot = campaignsRoot;
        this.random = random;
        campaignSaves = new CampaignSaveStore(campaignsRoot);
    }

    public synchronized CampaignRoster loadOrCreate(String campaignId, int capacity,
            AvatarCatalog avatarCatalog, LauncherIdentity hostIdentity,
            SlotPresentation hostPresentation) {
        File file = rosterFile(campaignId);
        if(!file.exists()) {
            CampaignRoster created = CampaignRoster.create(campaignId, capacity, avatarCatalog,
                    hostIdentity, hostPresentation, random);
            save(created);
            return created;
        }

        CampaignRoster loaded = load(campaignId, avatarCatalog);
        if(loaded.getCapacity() != capacity) {
            throw new IllegalStateException("Campaign Capacity is locked at "
                    + loaded.getCapacity() + " for " + campaignId + ".");
        }
        loaded.updateHostPresentation(hostIdentity, hostPresentation);
        save(loaded);
        return loaded;
    }

    public synchronized boolean exists(String campaignId) {
        return rosterFile(campaignId).isFile();
    }

    /** Campaign IDs with Host-owned rosters, including campaigns not yet started. */
    public synchronized List<String> listCampaigns() {
        File[] directories = campaignsRoot.listFiles();
        if(directories == null) return Collections.emptyList();
        List<String> campaigns = new ArrayList<String>();
        for(File directory : directories) {
            if(!directory.isDirectory() || !new File(directory, "roster.properties").isFile()) {
                continue;
            }
            try { campaigns.add(CampaignRoster.requireCampaignId(directory.getName())); }
            catch(IllegalArgumentException ignored) { }
        }
        Collections.sort(campaigns);
        return Collections.unmodifiableList(campaigns);
    }

    public synchronized CampaignRoster load(String campaignId, AvatarCatalog avatarCatalog) {
        File file = rosterFile(campaignId);
        if(!file.isFile()) throw new IllegalStateException("Campaign Roster does not exist: " + campaignId);
        Properties properties = AtomicProperties.load(file, "Campaign Roster");
        boolean legacy = "1".equals(properties.getProperty("format"));
        if(!legacy && !FORMAT.equals(properties.getProperty("format"))) {
            throw new IllegalStateException("Campaign Roster has unsupported format: " + file);
        }
        if(!campaignId.equals(properties.getProperty("campaignId"))) {
            throw new IllegalStateException("Campaign Roster identity does not match its profile path.");
        }

        int capacity = parseInt(properties.getProperty("capacity"), "Campaign Capacity");
        List<CampaignSlot> slots = new ArrayList<CampaignSlot>();
        for(int number = 1; number <= capacity; number++) {
            String prefix = "slot." + number + ".";
            String identity = properties.getProperty(prefix + "launcherIdentity");
            if(identity == null) continue;
            try {
                slots.add(new CampaignSlot(number, new LauncherIdentity(identity),
                        require(properties, prefix + "reconnectToken"),
                        new SlotPresentation(require(properties, prefix + "nickname"),
                                require(properties, prefix + "avatar"))));
            }
            catch(IllegalArgumentException ex) {
                throw new IllegalStateException("Campaign Roster contains invalid Campaign Slot "
                        + number + ": " + file, ex);
            }
        }
        try {
            CampaignRoster loaded = CampaignRoster.restore(campaignId, capacity, avatarCatalog, slots).withMetadata(
                    legacy ? campaignId : require(properties, "campaignName"),
                    legacy ? 3 : parseInt(require(properties, "startingLives"), "Starting Lives"));
            if(legacy) save(loaded);
            return loaded;
        }
        catch(IllegalArgumentException ex) {
            throw new IllegalStateException("Campaign Roster is corrupt: " + file, ex);
        }
    }

    public synchronized void save(CampaignRoster roster) {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty("campaignId", roster.getCampaignId());
        properties.setProperty("campaignName", roster.getCampaignName());
        properties.setProperty("startingLives", Integer.toString(roster.getStartingLives()));
        properties.setProperty("capacity", Integer.toString(roster.getCapacity()));
        for(CampaignSlot slot : roster.getSlots()) {
            String prefix = "slot." + slot.getNumber() + ".";
            properties.setProperty(prefix + "launcherIdentity",
                    slot.getLauncherIdentity().getValue());
            properties.setProperty(prefix + "reconnectToken", slot.getReconnectToken());
            properties.setProperty(prefix + "nickname",
                    slot.getPresentation().getNickname());
            properties.setProperty(prefix + "avatar",
                    slot.getPresentation().getAvatarId());
        }
        File file = rosterFile(roster.getCampaignId());
        backupLegacyRoster(file);
        AtomicProperties.store(file, properties,
                "Delver Multiplayer Host-owned Campaign Roster");
    }

    /** Complete backup before atomic replacement, including callers that save without loading. */
    private static void backupLegacyRoster(File file) {
        if(!file.isFile() || !"1".equals(AtomicProperties.load(file, "Campaign Roster").getProperty("format"))) return;
        File backup = new File(file.getParentFile(), "roster.properties.before-format-2");
        java.nio.file.Path staging = null;
        try {
            if(backup.exists()) {
                if(!backup.isFile() || !java.util.Arrays.equals(Files.readAllBytes(file.toPath()),
                        Files.readAllBytes(backup.toPath()))) throw new IOException("Existing roster backup differs from original.");
                return;
            }
            staging = Files.createTempFile(file.getParentFile().toPath(), "roster-backup-", ".tmp");
            Files.copy(file.toPath(), staging, StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(staging, backup.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch(java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(staging, backup.toPath()); }
        }
        catch(IOException failure) {
            throw new IllegalStateException("Could not back up Campaign Roster; original retained: " + file, failure);
        }
        finally {
            if(staging != null) try { Files.deleteIfExists(staging); } catch(IOException ignored) { }
        }
    }

    SecureRandom getRandom() {
        return random;
    }

    public CampaignSaveStore campaignSaves() {
        return campaignSaves;
    }

    /** Stage save and derived roster together; import never overwrites an existing Campaign. */
    public synchronized CampaignRoster importCampaign(File source, LauncherIdentity host,
            AvatarCatalog avatars, DirectConnectCompatibility compatibility) {
        CampaignSave saved = campaignSaves.readExport(source, host, compatibility);
        CampaignRoster roster = CampaignRoster.restore(saved.getCampaignId(), saved.getCapacity(),
                avatars, saved.getSlots()).withMetadata(saved.getCampaignName(), saved.getStartingLives());
        File destination = new File(campaignsRoot, saved.getCampaignId());
        if(destination.exists()) throw new IllegalStateException("Campaign already exists: " + saved.getCampaignId());
        File staging = null;
        try {
            Files.createDirectories(campaignsRoot.toPath());
            staging = Files.createTempDirectory(campaignsRoot.toPath(), "campaign-import-").toFile();
            CampaignRosterStore staged = new CampaignRosterStore(staging, random);
            staged.save(roster);
            staged.campaignSaves().save(saved);
            Files.move(new File(staging, saved.getCampaignId()).toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE);
            return roster;
        }
        catch(IOException failure) { throw new IllegalStateException("Could not import Campaign; original export remains intact.", failure); }
        finally {
            if(staging != null) {
                File stagedCampaign = new File(staging, saved.getCampaignId());
                for(String name : new String[] { "campaign.save", "campaign.archive", "terminal.outcome", "roster.properties" }) {
                    File file = new File(stagedCampaign, name);
                    if(file.exists()) { file.setWritable(true, true); if(!file.delete()) file.deleteOnExit(); }
                }
                if(stagedCampaign.exists() && !stagedCampaign.delete()) stagedCampaign.deleteOnExit();
                if(!staging.delete()) staging.deleteOnExit();
            }
        }
    }

    private File rosterFile(String campaignId) {
        return new File(new File(campaignsRoot, CampaignRoster.requireCampaignId(campaignId)),
                "roster.properties");
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if(value == null) throw new IllegalArgumentException("Missing Campaign Roster field: " + key);
        return value;
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value);
        }
        catch(RuntimeException ex) {
            throw new IllegalStateException(label + " is invalid in Campaign Roster.", ex);
        }
    }
}
