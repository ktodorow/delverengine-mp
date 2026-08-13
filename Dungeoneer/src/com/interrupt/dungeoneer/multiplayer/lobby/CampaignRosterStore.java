package com.interrupt.dungeoneer.multiplayer.lobby;

import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Host-local persistent Campaign Roster store. It never reads original single-player saves. */
public final class CampaignRosterStore {
    private static final String FORMAT = "1";

    private final File campaignsRoot;
    private final SecureRandom random;

    public CampaignRosterStore() {
        if(!MultiplayerProfile.isInitialized()) {
            throw new IllegalStateException("Multiplayer profile is not initialized.");
        }
        campaignsRoot = MultiplayerProfile.resolveWritableFile("saves/campaigns").file();
        random = new SecureRandom();
    }

    public CampaignRosterStore(File campaignsRoot, SecureRandom random) {
        if(campaignsRoot == null) throw new IllegalArgumentException("Campaign storage root cannot be null.");
        if(random == null) throw new IllegalArgumentException("Secure random source cannot be null.");
        this.campaignsRoot = campaignsRoot;
        this.random = random;
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

    public synchronized CampaignRoster load(String campaignId, AvatarCatalog avatarCatalog) {
        File file = rosterFile(campaignId);
        if(!file.isFile()) throw new IllegalStateException("Campaign Roster does not exist: " + campaignId);
        Properties properties = AtomicProperties.load(file, "Campaign Roster");
        if(!FORMAT.equals(properties.getProperty("format"))) {
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
            return CampaignRoster.restore(campaignId, capacity, avatarCatalog, slots);
        }
        catch(IllegalArgumentException ex) {
            throw new IllegalStateException("Campaign Roster is corrupt: " + file, ex);
        }
    }

    public synchronized void save(CampaignRoster roster) {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty("campaignId", roster.getCampaignId());
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
        AtomicProperties.store(rosterFile(roster.getCampaignId()), properties,
                "Delver Multiplayer Host-owned Campaign Roster");
    }

    SecureRandom getRandom() {
        return random;
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
