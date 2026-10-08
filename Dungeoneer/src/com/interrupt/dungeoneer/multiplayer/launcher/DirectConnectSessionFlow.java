package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;

import java.util.function.Supplier;
import java.util.function.BiFunction;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherPresentationStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import java.security.SecureRandom;
import java.util.UUID;

/** Application-owned session boundary. All operations run on the application thread. */
public final class DirectConnectSessionFlow {
    /** Typed native form request. Display names never supply paths or ownership credentials. */
    public static final class HostSetup {
        private final String campaignName;
        private final int capacity;
        private final int startingLives;
        private final SlotPresentation presentation;
        private final int port;

        public HostSetup(String campaignName, int capacity, int startingLives,
                String nickname, String avatar, int port) {
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
        public int getCapacity() { return capacity; }
        public int getStartingLives() { return startingLives; }
        public SlotPresentation getPresentation() { return presentation; }
        public int getPort() { return port; }
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

    /** Failed bind keeps native setup alive and creates no durable orphan campaign. */
    public void openNewCampaign(HostSetup setup, CampaignRosterStore store, LauncherIdentity identity,
            BiFunction<HostSetup, CampaignRoster, DirectConnectHost> openHost) {
        if(peer != null) throw new IllegalStateException("Leave current session before creating a Campaign.");
        if(setup == null || store == null || identity == null || openHost == null)
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
