package com.interrupt.dungeoneer.multiplayer.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.File;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;

/** Host Campaign Library: independent campaign discovery, creation, and resume. */
public final class CampaignLibrary {
    public static final class Entry {
        private final String campaignId;
        private final int capacity;
        private final int claimedSlots;
        private final boolean saved;
        private final boolean archived;
        private final boolean recovery;

        private Entry(String campaignId, int capacity, int claimedSlots, boolean saved,
                boolean archived, boolean recovery) {
            this.campaignId = campaignId;
            this.capacity = capacity;
            this.claimedSlots = claimedSlots;
            this.saved = saved;
            this.archived = archived;
            this.recovery = recovery;
        }

        public String getCampaignId() { return campaignId; }
        public int getCapacity() { return capacity; }
        public int getClaimedSlots() { return claimedSlots; }
        public boolean hasSave() { return saved; }
        public boolean isArchived() { return archived; }
        public boolean needsRecovery() { return recovery; }
    }

    private final CampaignRosterStore store;
    private final AvatarCatalog avatarCatalog;
    private final LauncherIdentity hostIdentity;
    private final SlotPresentation hostPresentation;

    public CampaignLibrary(CampaignRosterStore store, AvatarCatalog avatarCatalog,
            LauncherIdentity hostIdentity, SlotPresentation hostPresentation) {
        if(store == null) throw new IllegalArgumentException("Campaign store cannot be null.");
        if(avatarCatalog == null) throw new IllegalArgumentException("Avatar catalog cannot be null.");
        if(hostIdentity == null) throw new IllegalArgumentException("Host identity cannot be null.");
        if(hostPresentation == null) throw new IllegalArgumentException("Host presentation cannot be null.");
        this.store = store;
        this.avatarCatalog = avatarCatalog;
        this.hostIdentity = hostIdentity;
        this.hostPresentation = hostPresentation;
    }

    public List<Entry> list() {
        List<Entry> entries = new ArrayList<Entry>();
        for(String campaignId : store.listCampaigns()) {
            CampaignRoster roster = store.load(campaignId, avatarCatalog);
            entries.add(new Entry(campaignId, roster.getCapacity(), roster.getSlots().size(),
                    store.campaignSaves().exists(campaignId),
                    store.campaignSaves().isTerminal(campaignId),
                    store.campaignSaves().needsRecovery(campaignId)));
        }
        return Collections.unmodifiableList(entries);
    }

    public CampaignRoster create(String campaignId, int capacity) {
        campaignId = CampaignRoster.requireCampaignId(campaignId);
        if(store.exists(campaignId)) {
            throw new IllegalStateException("Campaign already exists: " + campaignId);
        }
        return store.loadOrCreate(campaignId, capacity, avatarCatalog,
                hostIdentity, hostPresentation);
    }

    public CampaignRoster resume(String campaignId) {
        campaignId = CampaignRoster.requireCampaignId(campaignId);
        CampaignRoster existing = store.load(campaignId, avatarCatalog);
        CampaignSlot host = existing.getSlot(1);
        if(host == null || !hostIdentity.equals(host.getLauncherIdentity())) {
            throw new IllegalStateException("Campaign " + campaignId
                    + " belongs to another Host Launcher Identity.");
        }
        if(store.campaignSaves().isTerminal(campaignId)) {
            throw new IllegalStateException("Campaign Archive is read-only and cannot resume: " + campaignId);
        }
        if(store.campaignSaves().needsRecovery(campaignId)) {
            throw new IllegalStateException("Unclean Host shutdown: press R to recover latest valid Campaign state.");
        }
        return store.loadOrCreate(campaignId, existing.getCapacity(), avatarCatalog,
                hostIdentity, hostPresentation);
    }

    public CampaignRoster recover(String id, DirectConnectCompatibility compatibility) {
        CampaignRoster roster = store.load(id, avatarCatalog);
        if(!hostIdentity.equals(roster.getSlot(1).getLauncherIdentity())) {
            throw new IllegalStateException("Campaign belongs to another Host Launcher Identity.");
        }
        store.campaignSaves().recover(id, compatibility);
        return resume(id);
    }

    public File exportCampaign(String id, File destination, DirectConnectCompatibility compatibility) {
        return store.campaignSaves().exportCampaign(id, hostIdentity, destination, compatibility);
    }

    public CampaignRoster importCampaign(File source, DirectConnectCompatibility compatibility) {
        return store.importCampaign(source, hostIdentity, avatarCatalog, compatibility);
    }

    public String describeArchive(String id, DirectConnectCompatibility compatibility) {
        CampaignSave saved = store.campaignSaves().load(id, compatibility);
        if(saved.getOutcome() == CampaignSave.Outcome.ACTIVE) {
            return "Terminal outcome preserved; final archive write failed. Keep Campaign files for repair.";
        }
        return "Campaign " + id + " - " + saved.getOutcome().name()
                + " | Floor " + saved.getFloorId() + " | Roster " + saved.getSlots().size()
                + " | Starting Lives " + saved.getStartingLives() + ". Read-only history; cannot resume.";
    }
}
