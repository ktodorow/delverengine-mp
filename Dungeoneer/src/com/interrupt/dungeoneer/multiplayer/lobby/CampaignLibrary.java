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
        private final String campaignName;
        private final int capacity;
        private final int claimedSlots;
        private final int startingLives;
        private final String floorId;
        private final long lastSavedTime;
        private final boolean saved;
        private final boolean archived;
        private final boolean recovery;
        private final String summaryError;

        private Entry(String campaignId, String campaignName, int capacity, int claimedSlots,
                int startingLives, String floorId, long lastSavedTime, boolean saved,
                boolean archived, boolean recovery, String summaryError) {
            this.campaignId = campaignId;
            this.campaignName = campaignName;
            this.capacity = capacity;
            this.claimedSlots = claimedSlots;
            this.startingLives = startingLives;
            this.floorId = floorId;
            this.lastSavedTime = lastSavedTime;
            this.saved = saved;
            this.archived = archived;
            this.recovery = recovery;
            this.summaryError = summaryError;
        }

        public String getCampaignId() { return campaignId; }
        public String getCampaignName() { return campaignName; }
        public int getCapacity() { return capacity; }
        public int getClaimedSlots() { return claimedSlots; }
        public int getStartingLives() { return startingLives; }
        public String getFloorId() { return floorId; }
        public long getLastSavedTime() { return lastSavedTime; }
        public boolean hasSave() { return saved; }
        public boolean isArchived() { return archived; }
        public boolean needsRecovery() { return recovery; }
        public String getSummaryError() { return summaryError; }
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
            boolean hasSave = store.campaignSaves().exists(campaignId);
            CampaignSave saved = null;
            String summaryError = null;
            if(hasSave) {
                try { saved = store.campaignSaves().inspect(campaignId); saved.verifyRoster(roster); }
                catch(IllegalStateException failure) { saved = null; summaryError = failure.getMessage(); }
            }
            boolean archived = store.campaignSaves().isTerminal(campaignId)
                    || saved != null && saved.getOutcome() != CampaignSave.Outcome.ACTIVE;
            entries.add(new Entry(campaignId, saved == null ? roster.getCampaignName() : saved.getCampaignName(),
                    roster.getCapacity(), roster.getSlots().size(),
                    saved == null ? roster.getStartingLives() : saved.getStartingLives(),
                    saved == null ? null : saved.getFloorId(),
                    saved == null ? 0L : store.campaignSaves().getLastSavedTime(campaignId), hasSave,
                    archived, !archived && store.campaignSaves().needsRecovery(campaignId), summaryError));
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
        if(store.campaignSaves().exists(campaignId)) {
            CampaignSave saved = store.campaignSaves().inspect(campaignId);
            if(saved.getOutcome() != CampaignSave.Outcome.ACTIVE) {
                throw new IllegalStateException("Campaign Archive is read-only and cannot resume: " + campaignId);
            }
            saved.verifyRoster(existing);
            existing = existing.withMetadata(saved.getCampaignName(), saved.getStartingLives());
        }
        return existing;
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
        return "Campaign " + saved.getCampaignName() + " - " + saved.getOutcome().name()
                + " | Floor " + saved.getFloorId() + " | Roster " + saved.getSlots().size()
                + " | Starting Lives " + saved.getStartingLives() + ". Read-only history; cannot resume.";
    }
}
