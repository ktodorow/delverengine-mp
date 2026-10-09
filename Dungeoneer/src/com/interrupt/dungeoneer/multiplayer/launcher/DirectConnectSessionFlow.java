package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectClient;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;

import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.function.Function;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignLibrary;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherPresentationStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherEndpointStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import java.security.SecureRandom;
import java.util.UUID;

/** Application-owned session boundary. All operations run on the application thread. */
public final class DirectConnectSessionFlow {
    /** Provisional typed Client preferences; Host decides Campaign Slot presentation. */
    public static final class ConnectSetup {
        private final LauncherEndpointStore.Endpoint endpoint;
        private final SlotPresentation presentation;

        public ConnectSetup(String address, int port, String nickname, String avatar) {
            endpoint = new LauncherEndpointStore.Endpoint(address, port);
            presentation = new SlotPresentation(nickname, avatar);
            if(!AvatarCatalog.ownedV108Humanoids().contains(avatar))
                throw new IllegalArgumentException("Choose an available Avatar.");
        }

        public String getAddress() { return endpoint.getAddress(); }
        public int getPort() { return endpoint.getPort(); }
        public SlotPresentation getPresentation() { return presentation; }
    }

    /** Typed native form request. Display names never supply paths or ownership credentials. */
    public static final class HostSetup {
        private final String campaignId;
        private final String campaignName;
        private final int capacity;
        private final int startingLives;
        private final SlotPresentation presentation;
        private final int port;

        public HostSetup(String campaignName, int capacity, int startingLives,
                String nickname, String avatar, int port) {
            this(null, campaignName, capacity, startingLives, nickname, avatar, port);
        }

        private HostSetup(String campaignId, String campaignName, int capacity, int startingLives,
                String nickname, String avatar, int port) {
            this.campaignId = campaignId;
            this.campaignName = CampaignRoster.requireCampaignName(campaignName);
            if(capacity < 2 || capacity > 4) throw new IllegalArgumentException("Campaign Capacity must be 2, 3, or 4.");
            com.interrupt.dungeoneer.multiplayer.lives.AuthoritativeLives.requireStartingLives(startingLives);
            if(port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1-65535.");
            presentation = new SlotPresentation(nickname, avatar);
            if(!AvatarCatalog.ownedV108Humanoids().contains(avatar)) throw new IllegalArgumentException("Choose an available Avatar.");
            this.capacity = capacity;
            this.startingLives = startingLives;
            this.port = port;
        }

        public String getCampaignName() { return campaignName; }
        public String getCampaignId() { return campaignId; }
        public boolean isResume() { return campaignId != null; }
        public int getCapacity() { return capacity; }
        public int getStartingLives() { return startingLives; }
        public SlotPresentation getPresentation() { return presentation; }
        public int getPort() { return port; }

        /** Resume edits only Host presentation and listener; immutable saved rules retain identity. */
        public HostSetup withHostOptions(String nickname, String avatar, int port) {
            return new HostSetup(campaignId, campaignName, capacity, startingLives, nickname, avatar, port);
        }
    }

    private DirectConnectPeer peer;
    private Supplier<? extends DirectConnectPeer> request;
    private boolean entered;
    private final Runnable releaseNativeSession;

    public DirectConnectSessionFlow() { this(() -> { }); }

    /** Adapter releases native screens, controllers and input without owning session policy. */
    public DirectConnectSessionFlow(Runnable releaseNativeSession) {
        if(releaseNativeSession == null) throw new IllegalArgumentException("Native session release cannot be null.");
        this.releaseNativeSession = releaseNativeSession;
    }

    public DirectConnectPeer getPeer() { return peer; }

    /** Editable profile defaults; reading form opens no network resources. */
    public ConnectSetup prepareConnect() {
        LauncherEndpointStore.Endpoint endpoint = LauncherEndpointStore.load();
        SlotPresentation presentation = LauncherPresentationStore.load();
        return new ConnectSetup(endpoint.getAddress(), endpoint.getPort(),
                presentation.getNickname(), presentation.getAvatarId());
    }

    /** Keep native form installed while connecting; validated attempts become editable defaults. */
    public void openClient(ConnectSetup setup, Function<ConnectSetup, DirectConnectClient> connect) {
        if(setup == null || connect == null) throw new IllegalArgumentException("Connect setup and network entry are required.");
        if(peer != null) {
            DirectConnectPhase phase = peer.getStatus().getPhase();
            if(!(peer instanceof DirectConnectClient) || entered
                    || (phase != DirectConnectPhase.REJECTED && phase != DirectConnectPhase.FAILED
                    && phase != DirectConnectPhase.DISCONNECTED && phase != DirectConnectPhase.CLOSED))
                throw new IllegalStateException("Cancel current attempt before connecting.");
            peer.close();
            peer = null;
        }
        request = () -> {
            DirectConnectClient client = connect.apply(setup);
            try {
                LauncherEndpointStore.save(setup.getAddress(), setup.getPort());
                LauncherPresentationStore.save(setup.getPresentation());
                return client;
            }
            catch(RuntimeException failure) {
                try { client.close(); } catch(RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        };
        try { peer = request.get(); }
        catch(RuntimeException failure) { throw failure; }
        catch(Exception failure) { throw new IllegalStateException("Connection could not open: " + failure.getMessage(), failure); }
    }

    /** Back/Cancel ends only this pre-entry Client attempt; current native form owns navigation. */
    public void cancelClient() {
        if(peer != null) {
            if(!(peer instanceof DirectConnectClient) || entered)
                throw new IllegalStateException("Leave gameplay through session controls.");
            peer.close();
            peer = null;
        }
        entered = false;
        request = null;
    }

    /** Participant-facing progress comes from actual TCP/UDP/session status. */
    public String getClientProgress() {
        if(peer == null) return "Enter Host address and presentation.";
        switch(peer.getStatus().getPhase()) {
            case STARTING:
            case CONNECTING: return "Connecting to Host...";
            case HANDSHAKING:
            case CLAIMING_SLOT: return "Authenticating with Host...";
            case AWAITING_APPROVAL: return "Waiting for Host admission...";
            case REGISTERING_UDP: return "Authenticating UDP...";
            case LOBBY: return "Connected. Waiting for Host to start.";
            case SYNCHRONIZING: return "Synchronizing campaign...";
            case READY: return "Connection ready.";
            case REJECTED: return "Connection rejected: " + peer.getStatus().getMessage();
            case FAILED:
            case DISCONNECTED: return "Connection failed: " + peer.getStatus().getMessage()
                    + " Check Host address and TCP/UDP port; edit and retry.";
            case CLOSED: return "Connection cancelled.";
            default: return peer.getStatus().getMessage();
        }
    }

    /** Selection restores campaign defaults but opens no listener. Same request feeds native form. */
    public HostSetup prepareCampaign(CampaignLibrary library, String campaignId, int port) {
        if(peer != null) throw new IllegalStateException("Leave current session before selecting a Campaign.");
        CampaignRoster roster = library.resume(campaignId);
        SlotPresentation host = roster.getSlot(1).getPresentation();
        return new HostSetup(roster.getCampaignId(), roster.getCampaignName(), roster.getCapacity(),
                roster.getStartingLives(), host.getNickname(), host.getAvatarId(), port);
    }

    /** Revalidate durable rules/ownership on every open and retry; bind failure retains setup view. */
    public void openSavedCampaign(HostSetup setup, CampaignLibrary library, CampaignRosterStore store,
            BiFunction<HostSetup, CampaignRoster, DirectConnectHost> openHost) {
        if(peer != null) throw new IllegalStateException("Leave current session before opening a Campaign.");
        if(setup == null || !setup.isResume() || library == null || store == null || openHost == null)
            throw new IllegalArgumentException("Saved setup, Campaign Library, storage and listener are required.");
        request = () -> {
            CampaignRoster roster = library.resume(setup.getCampaignId());
            if(roster.getCapacity() != setup.getCapacity() || roster.getStartingLives() != setup.getStartingLives()
                    || !roster.getCampaignName().equals(setup.getCampaignName()))
                throw new IllegalStateException("Saved Campaign settings changed; return to Campaigns and select again.");
            roster.updateHostPresentation(roster.getSlot(1).getLauncherIdentity(), setup.getPresentation());
            DirectConnectHost host = openHost.apply(setup, roster);
            try {
                store.save(roster);
                LauncherPresentationStore.save(roster.getSlot(1).getPresentation());
                return host;
            }
            catch(RuntimeException failure) {
                try { host.close(); } catch(RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        };
        try { peer = request.get(); }
        catch(RuntimeException failure) { throw failure; }
        catch(Exception failure) { throw new IllegalStateException("Lobby could not open: " + failure.getMessage(), failure); }
    }

    /** Failed bind keeps native setup alive and creates no durable orphan campaign. */
    public void openNewCampaign(HostSetup setup, CampaignRosterStore store, LauncherIdentity identity,
            BiFunction<HostSetup, CampaignRoster, DirectConnectHost> openHost) {
        if(peer != null) throw new IllegalStateException("Leave current session before creating a Campaign.");
        if(setup == null || setup.isResume() || store == null || identity == null || openHost == null)
            throw new IllegalArgumentException("Host setup, storage, identity and listener are required.");
        CampaignRoster roster = CampaignRoster.createNamed(UUID.randomUUID().toString(),
                setup.getCampaignName(), setup.getCapacity(), setup.getStartingLives(),
                AvatarCatalog.ownedV108Humanoids(), identity, setup.getPresentation(), new SecureRandom());
        request = () -> {
            DirectConnectHost host = openHost.apply(setup, roster);
            try {
                if(!host.isStartingLivesLocked()) host.setStartingLives(setup.getStartingLives());
                LauncherPresentationStore.save(setup.getPresentation());
                store.save(roster);
                return host;
            }
            catch(RuntimeException failure) {
                try { host.close(); } catch(RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        };
        try { peer = request.get(); }
        catch(RuntimeException failure) { throw failure; }
        catch(Exception failure) { throw new IllegalStateException("Lobby could not open: " + failure.getMessage(), failure); }
    }

    public void open(Supplier<? extends DirectConnectPeer> request) {
        if(request == null) throw new IllegalArgumentException("Session request cannot be null.");
        leave();
        this.request = request;
        try { peer = request.get(); }
        catch(RuntimeException failure) { throw failure; }
        catch(Exception failure) {
            // Netty can propagate a checked bind/connect exception through Supplier.get().
            throw new IllegalStateException("Session could not open: " + failure.getMessage(), failure);
        }
    }

    public void retry() {
        if(request == null) throw new IllegalStateException("No session request to retry.");
        open(request);
    }

    /** Queued native entry belongs to exactly one current, started session. */
    public boolean enter(DirectConnectPeer expected, Runnable installWorld) {
        if(expected == null || peer != expected || entered) return false;
        DirectConnectPhase phase = expected.getStatus().getPhase();
        if(phase != DirectConnectPhase.READY && phase != DirectConnectPhase.SYNCHRONIZING) return false;
        entered = true;
        installWorld.run();
        return true;
    }

    public void leave() {
        if(peer != null) {
            DirectConnectPhase phase = peer.getStatus().getPhase();
            if(peer instanceof DirectConnectHost
                    && (phase == DirectConnectPhase.READY || phase == DirectConnectPhase.SYNCHRONIZING)) {
                ((DirectConnectHost)peer).saveAndQuit();
            }
            else peer.close();
            peer = null;
            entered = false;
        }
        // Failed opens and cancelled attempts can still own a native error/library view.
        releaseNativeSession.run();
    }
}
