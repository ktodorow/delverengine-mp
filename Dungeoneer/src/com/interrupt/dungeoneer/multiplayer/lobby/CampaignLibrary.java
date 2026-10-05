package com.interrupt.dungeoneer.multiplayer.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Host Campaign Library: independent campaign discovery, creation, and resume. */
public final class CampaignLibrary {
    public static final class Entry {
        private final String campaignId;
        private final int capacity;
        private final int claimedSlots;
        private final boolean saved;

        private Entry(String campaignId, int capacity, int claimedSlots, boolean saved) {
            this.campaignId = campaignId;
            this.capacity = capacity;
            this.claimedSlots = claimedSlots;
            this.saved = saved;
        }

        public String getCampaignId() { return campaignId; }
        public int getCapacity() { return capacity; }
        public int getClaimedSlots() { return claimedSlots; }
        public boolean hasSave() { return saved; }
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
                    store.campaignSaves().exists(campaignId)));
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
        return store.loadOrCreate(campaignId, existing.getCapacity(), avatarCatalog,
                hostIdentity, hostPresentation);
    }
}
