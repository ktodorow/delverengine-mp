package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;

import java.util.function.Supplier;

/** Application-owned session boundary. All operations run on the application thread. */
public final class DirectConnectSessionFlow {
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
