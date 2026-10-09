package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot;

/** Explicit player consent for gameplay fixtures; always uses production session actions. */
public final class ReadyTestSupport {
    private ReadyTestSupport() { }

    public static void readyClient(DirectConnectPeer client) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(!client.canSetPlayerReady() && System.nanoTime() < deadline) Thread.sleep(10L);
        if(!client.canSetPlayerReady())
            throw new AssertionError("Client cannot consent: " + client.getStatus().getMessage());
        client.setPlayerReady(true);
        while(System.nanoTime() < deadline) {
            LobbySnapshot lobby = client.getLobbySnapshot();
            LobbySnapshot.Slot local = lobby == null ? null : lobby.getSlot(client.getLocalCampaignSlot());
            if(local != null && local.isPlayerReady()) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Host did not publish Client Ready consent.");
    }

    public static void startReadySession(DirectConnectHost host) {
        if(host.canSetPlayerReady()) host.setPlayerReady(true);
        host.startSession();
    }
}
