package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.ProfileReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectClient;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.ServerSocket;
import java.net.DatagramSocket;
import java.security.SecureRandom;

import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.readyClient;
import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.startReadySession;

import static org.junit.Assert.*;

/** Actual TCP/UDP sessions behind the application's reusable launcher boundary. */
public class DirectConnectSessionFlowTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void stoppedHostReopensSamePortWithoutRestartingApplication() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("campaigns"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.create("friends", 2, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        int port;
        try(ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.open(() -> DirectConnectHost.start(port, compatibility(), roster, store));
            DirectConnectHost first = (DirectConnectHost)flow.getPeer();
            flow.leave();
            assertEquals(DirectConnectPhase.CLOSED, first.getStatus().getPhase());
            assertNull(flow.getPeer());
            flow.retry();
            DirectConnectHost reopened = (DirectConnectHost)flow.getPeer();
            assertNotSame(first, reopened);
            assertEquals(port, reopened.getBoundPort());
            assertEquals(DirectConnectPhase.LISTENING, reopened.getStatus().getPhase());
        }
        finally { flow.leave(); }
    }

    @Test public void failedHostSaveKeepsLiveSessionAndPreviousCampaign() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("failed-save"), new SecureRandom());
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
        DirectConnectHost host = (DirectConnectHost)flow.getPeer();
        DirectConnectClient friend = client(host.getBoundPort());
        try {
            admitAndStart(host, friend);
            host.setNativeFloorCapture(() -> new byte[] { 7, 8 });
            host.persistCampaign();
            host.setNativeFloorCapture(() -> { throw new IllegalStateException("Native capture refused"); });
            try { flow.leave(); fail("Failed save must not discard live campaign."); }
            catch(IllegalStateException expected) { assertEquals("Native capture refused", expected.getMessage()); }
            assertSame(host, flow.getPeer());
            assertEquals(DirectConnectPhase.READY, host.getStatus().getPhase());
            assertEquals(DirectConnectPhase.READY, friend.getStatus().getPhase());
            assertFalse(host.isSessionPaused());
            assertArrayEquals(new byte[] { 7, 8 }, store.campaignSaves().load("friends", compatibility()).getNativeFloor());
        }
        finally {
            host.setNativeFloorCapture(() -> new byte[] { 7, 8 });
            friend.close();
            flow.leave();
        }
    }

    @Test public void onlyCurrentReadyAttemptCanEnterGameplayOnce() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("entry-guard"), new SecureRandom());
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
        DirectConnectHost first = (DirectConnectHost)flow.getPeer();
        java.util.List<DirectConnectPeer> entered = new java.util.ArrayList<DirectConnectPeer>();
        assertFalse(flow.enter(first, () -> entered.add(first))); // No pregame entry.
        DirectConnectClient friend = client(first.getBoundPort());
        try {
            admitAndStart(first, friend);
            awaitEntry(flow, first);
            assertTrue(flow.enter(first, () -> entered.add(first)));
            assertFalse(flow.enter(first, () -> entered.add(first)));
            flow.leave();
            assertFalse(flow.enter(first, () -> entered.add(first))); // Queued callback after Cancel.
            flow.retry();
            DirectConnectHost next = (DirectConnectHost)flow.getPeer();
            startReadySession(next); // Saved campaign permits Host-only resume.
            assertFalse(flow.enter(first, () -> entered.add(first))); // Queued callback after replacement.
            awaitEntry(flow, next);
            assertTrue(flow.enter(next, () -> entered.add(next)));
            assertEquals(java.util.Arrays.asList(first, next), entered);
        }
        finally { friend.close(); flow.leave(); }
    }

    @Test public void replacementReleasesPriorSessionViewBeforeOpeningNextListener() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("release-view"), new SecureRandom());
        CampaignRoster roster = roster(store);
        // Native window is an external boundary; its displayed session must be retired.
        java.util.concurrent.atomic.AtomicReference<DirectConnectPeer> displayed = new java.util.concurrent.atomic.AtomicReference<DirectConnectPeer>();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(() -> displayed.set(null));
        try {
            flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            DirectConnectHost first = (DirectConnectHost)flow.getPeer();
            displayed.set(first);
            int port = first.getBoundPort();
            flow.open(() -> {
                assertNull("Previous session still owns native view", displayed.get());
                assertEquals(DirectConnectPhase.CLOSED, first.getStatus().getPhase());
                return DirectConnectHost.start(port, compatibility(), roster, store);
            });
            assertEquals(port, ((DirectConnectHost)flow.getPeer()).getBoundPort());
        }
        finally { flow.leave(); }
    }

    @Test public void returningToSessionClearsPendingNativeItemInput() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("release-input"), new SecureRandom());
        CampaignRoster roster = roster(store);
        com.interrupt.dungeoneer.GameInput input = new com.interrupt.dungeoneer.GameInput();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(input::clear);
        try {
            flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            input.scrolled(1);
            assertTrue(input.doNextItemAction());
            flow.leave();
            assertFalse("Retired gameplay scroll must not change next session's hotbar", input.doNextItemAction());
        }
        finally { flow.leave(); }
    }

    @Test public void failedBindRetiresFailureViewAndReleasesTcpBeforeSamePortRetry() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("bind-retry"), new SecureRandom());
        CampaignRoster roster = roster(store);
        java.util.concurrent.atomic.AtomicReference<String> displayed = new java.util.concurrent.atomic.AtomicReference<String>();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(() -> displayed.set(null));
        DatagramSocket occupiedUdp = SocketTestPorts.occupyUdpWithAvailableTcp();
        int port = occupiedUdp.getLocalPort();
        try {
            try {
                flow.open(() -> {
                    assertNull("Prior failure screen still owns native view", displayed.get());
                    return DirectConnectHost.start(port, compatibility(), roster, store);
                });
                fail("Occupied UDP port must refuse Host bind.");
            }
            catch(Exception expected) {
                assertTrue("Launcher failure must reach existing screen error handlers", expected instanceof RuntimeException);
                assertTrue("Fixture must fail UDP after opening TCP", expected.getMessage().contains("UDP listener"));
                assertNull(flow.getPeer());
            }
            displayed.set("Host bind failed");
            occupiedUdp.close();
            flow.retry();
            assertEquals(port, ((DirectConnectHost)flow.getPeer()).getBoundPort());
            assertEquals(DirectConnectPhase.LISTENING, flow.getPeer().getStatus().getPhase());
        }
        finally { occupiedUdp.close(); flow.leave(); }
    }

    @Test public void refusedClientRetriesThenLeavesAndRejoinsSameCampaignSlot() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("client-retry-host"), new SecureRandom());
        CampaignRoster roster = roster(store);
        com.interrupt.dungeoneer.owned.MultiplayerProfile.initialize(temporary.newFolder("client-profile"));
        ReconnectTokenStore tokens = new ProfileReconnectTokenStore();
        DatagramSocket reservedUdp = SocketTestPorts.occupyUdpWithAvailableTcp();
        int port = reservedUdp.getLocalPort();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectHost host = null;
        try {
            flow.open(() -> client(port, tokens));
            DirectConnectPeer refused = flow.getPeer();
            awaitPhase(refused, DirectConnectPhase.FAILED);
            reservedUdp.close();
            host = DirectConnectHost.start(port, compatibility(), roster, store);
            flow.retry();
            DirectConnectClient joined = (DirectConnectClient)flow.getPeer();
            admitAndStart(host, joined);
            assertEquals(2, joined.getLocalCampaignSlot());
            assertNotNull(new ProfileReconnectTokenStore().load("friends"));
            assertFalse(flow.enter(refused, () -> fail("Refused attempt entered replacement world")));
            flow.leave();
            assertEquals(DirectConnectPhase.CLOSED, joined.getStatus().getPhase());
            awaitConnected(host, 1);
            assertEquals(DirectConnectPhase.READY, host.getStatus().getPhase());
            assertFalse(host.isSessionPaused());
            flow.retry();
            DirectConnectClient rejoined = (DirectConnectClient)flow.getPeer();
            awaitPhase(rejoined, DirectConnectPhase.READY); // Persisted token; no new approval.
            assertEquals(2, rejoined.getLocalCampaignSlot());
            assertTrue(host.getPendingClaims().isEmpty());
            assertFalse(flow.enter(joined, () -> fail("Departed attempt entered replacement world")));
            awaitEntry(flow, rejoined);
            assertTrue(flow.enter(rejoined, () -> { }));
        }
        finally { reservedUdp.close(); flow.leave(); if(host != null) host.close(); }
    }

    @Test public void cancelledAdmittedClientRetainsSlotButCannotEnterRetriedSession() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("cancelled-claim"), new SecureRandom());
        CampaignRoster roster = roster(store);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        int port = host.getBoundPort();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        ReconnectTokenStore tokens = new ReconnectTokenStore() {
            private volatile String token;
            public String load(String campaign) { return token; }
            public void save(String campaign, String value) { token = value; }
        };
        try {
            flow.open(() -> client(port, tokens));
            DirectConnectPeer cancelled = flow.getPeer();
            awaitPhase(cancelled, DirectConnectPhase.LOBBY);
            flow.leave();
            assertEquals(DirectConnectPhase.CLOSED, cancelled.getStatus().getPhase());
            awaitConnected(host, 1);
            assertEquals(2, host.getRoster().getSlots().size());
            flow.retry();
            DirectConnectClient joined = (DirectConnectClient)flow.getPeer();
            admitAndStart(host, joined);
            assertEquals(2, joined.getLocalCampaignSlot());
            assertFalse(flow.enter(cancelled, () -> fail("Cancelled attempt entered replacement world")));
            awaitEntry(flow, joined);
            assertTrue(flow.enter(joined, () -> { }));
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void hostLeaveCommitsLatestCheckpointBeforeRetiringNativeWorld() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("save-before-release"), new SecureRandom());
        CampaignRoster roster = roster(store);
        java.util.concurrent.atomic.AtomicBoolean nativeAttached = new java.util.concurrent.atomic.AtomicBoolean();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(() -> nativeAttached.set(false));
        flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
        DirectConnectHost host = (DirectConnectHost)flow.getPeer();
        DirectConnectClient friend = client(host.getBoundPort());
        try {
            admitAndStart(host, friend);
            nativeAttached.set(true);
            host.setNativeFloorCapture(() -> new byte[] { 7, 8 });
            host.persistCampaign();
            host.setNativeFloorCapture(() -> {
                assertTrue("Native world retired before Host captured final checkpoint", nativeAttached.get());
                return new byte[] { 9, 10 };
            });
            flow.leave();
            assertFalse(nativeAttached.get());
            assertNull(flow.getPeer());
            assertEquals(DirectConnectPhase.CLOSED, host.getStatus().getPhase());
            assertArrayEquals(new byte[] { 9, 10 }, store.campaignSaves().load("friends", compatibility()).getNativeFloor());
        }
        finally { friend.close(); flow.leave(); }
    }

    private CampaignRoster roster(CampaignRosterStore store) {
        CampaignRoster roster = CampaignRoster.create("friends", 2, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        return roster;
    }

    private static DirectConnectClient client(int port) {
        return client(port, new ReconnectTokenStore() {
                    private String token;
                    public String load(String campaign) { return token; }
                    public void save(String campaign, String token) { this.token = token; }
                });
    }

    private static DirectConnectClient client(int port, ReconnectTokenStore tokens) {
        return DirectConnectClient.connect("127.0.0.1", port, identity('2'),
                new SlotPresentation("Friend", "humanoid-2"), 0, tokens, compatibility());
    }

    private static void awaitConnected(DirectConnectHost host, int expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            if(host.getConnectedParticipantCount() == expected) return;
            Thread.sleep(10L);
        }
        assertEquals(expected, host.getConnectedParticipantCount());
    }

    private static void admitAndStart(DirectConnectHost host, DirectConnectClient client) throws Exception {
        readyClient(client);
        startReadySession(host);
        awaitPhase(client, DirectConnectPhase.READY);
    }

    private static void awaitEntry(DirectConnectSessionFlow flow, DirectConnectPeer peer) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            if(flow.canEnter(peer)) return;
            Thread.sleep(10L);
        }
        fail("Native entry baseline unavailable: " + peer.getStatus().getPhase());
    }

    private static void awaitPhase(DirectConnectPeer peer, DirectConnectPhase expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            if(peer.getStatus().getPhase() == expected) return;
            Thread.sleep(10L);
        }
        fail("Expected " + expected + ", got " + peer.getStatus().getPhase() + ": " + peer.getStatus().getMessage());
    }

    private static LauncherIdentity identity(char digit) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', digit));
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor(
                "lifecycle-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
