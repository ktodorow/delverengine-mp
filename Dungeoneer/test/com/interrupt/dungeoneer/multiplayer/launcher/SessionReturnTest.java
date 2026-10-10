package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.lobby.*;
import com.interrupt.dungeoneer.multiplayer.network.*;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import java.io.File;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Launcher actions observed through actual sessions and durable profile/campaign storage. */
public class SessionReturnTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File previousProfile;

    @Before public void profile() throws Exception {
        previousProfile = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        MultiplayerProfile.initialize(temporary.newFolder("profile"));
    }

    @After public void restoreProfile() {
        if(previousProfile != null) MultiplayerProfile.initialize(previousProfile);
    }

    @Test public void pregameHostCloseExplainsLobbyClosureWithoutClaimingGameplaySave() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow clientFlow = new DirectConnectSessionFlow();
        try {
            clientFlow.openClient(setup(host.getBoundPort()), request -> client(request, identity('2')));
            DirectConnectPeer friend = clientFlow.getPeer();
            awaitPhase(friend, DirectConnectPhase.LOBBY);
            host.close();
            awaitPhase(friend, DirectConnectPhase.DISCONNECTED);
            assertEquals("Host closed lobby. Return to Connect.", clientFlow.getClientProgress());
            assertFalse(store.campaignSaves().exists("friends"));
        }
        finally { clientFlow.leave(); host.close(); }
    }

    @Test public void hostCloseRequiresConfirmationAndReturnsCampaignsWithReusableEndpoint() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
        DirectConnectHost host = (DirectConnectHost)flow.getPeer();
        int port = host.getBoundPort();
        DirectConnectClient friend = client(setup(port), identity('2'));
        try {
            awaitPhase(friend, DirectConnectPhase.LOBBY);
            DirectConnectSessionFlow.SessionReturn prompt = flow.returnToLauncher(host, false);
            assertTrue(prompt.isConfirmationRequired());
            assertTrue(prompt.getMessage().contains("disconnect"));
            assertSame(host, flow.getPeer());
            assertEquals(DirectConnectPhase.LOBBY, friend.getStatus().getPhase());
            DirectConnectSessionFlow.SessionReturn returned = flow.returnToLauncher(host, true);
            assertFalse(returned.isConfirmationRequired());
            assertEquals(DirectConnectSessionFlow.ReturnDestination.CAMPAIGNS, returned.getDestination());
            assertNull(flow.getPeer());
            awaitPhase(friend, DirectConnectPhase.DISCONNECTED);
            try(DirectConnectHost reopened = DirectConnectHost.start(port, compatibility(), roster, store)) {
                assertEquals(port, reopened.getBoundPort());
            }
            assertNull(flow.returnToLauncher(host, true)); // Stale queued confirmation.
            assertFalse(store.campaignSaves().exists("friends"));
        }
        finally { friend.close(); flow.leave(); }
    }

    @Test public void failedCleanShutdownKeepsHostClientAndNativeWorldAliveForRetry() throws Exception {
        File root = temporary.newFolder("shutdown-failure");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = roster(store);
        java.util.concurrent.atomic.AtomicBoolean attached = new java.util.concurrent.atomic.AtomicBoolean(true);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(() -> attached.set(false));
        flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
        attached.set(true);
        DirectConnectHost host = (DirectConnectHost)flow.getPeer();
        DirectConnectClient friend = client(setup(host.getBoundPort()), identity('2'));
        File marker = new File(new File(root, "friends"), "session.running");
        File blocker = new File(marker, "blocked");
        try {
            com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.readyClient(friend);
            com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.startReadySession(host);
            awaitPhase(friend, DirectConnectPhase.READY);
            host.setNativeFloorCapture(() -> new byte[] { 7, 8 });
            host.persistCampaign();
            java.nio.file.Files.delete(marker.toPath());
            assertTrue(marker.mkdir());
            assertTrue(blocker.createNewFile()); // External filesystem refuses clean-stop marker removal.
            try {
                flow.returnToLauncher(host, true);
                fail("Clean shutdown failure must keep live session available.");
            }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("shutdown clean")); }
            assertSame(host, flow.getPeer());
            assertEquals(DirectConnectPhase.READY, host.getStatus().getPhase());
            assertEquals(DirectConnectPhase.READY, friend.getStatus().getPhase());
            assertTrue(attached.get());
            assertFalse(host.isSessionPaused());
            assertArrayEquals(new byte[] { 7, 8 }, store.campaignSaves().load("friends", compatibility()).getNativeFloor());
            java.nio.file.Files.delete(blocker.toPath());
            java.nio.file.Files.delete(marker.toPath());
            try(java.io.FileOutputStream dirty = new java.io.FileOutputStream(marker)) { dirty.write(1); }
            DirectConnectSessionFlow.SessionReturn returned = flow.returnToLauncher(host, true);
            assertEquals(DirectConnectSessionFlow.ReturnDestination.CAMPAIGNS, returned.getDestination());
            assertFalse(attached.get());
            assertFalse(store.campaignSaves().needsRecovery("friends"));
            awaitPhase(friend, DirectConnectPhase.DISCONNECTED);
        }
        finally {
            blocker.delete();
            if(marker.isDirectory()) marker.delete();
            friend.close(); flow.leave();
        }
    }

    @Test public void hostRecoveryFailureReturnsFailureWithRetainedClientFieldsAndCredentials() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectSessionFlow.ConnectSetup selected = setup(host.getBoundPort());
        try {
            flow.openClient(selected, request -> client(request, identity('2')));
            DirectConnectPeer friend = flow.getPeer();
            awaitPhase(friend, DirectConnectPhase.LOBBY);
            String credential = new ProfileReconnectTokenStore().load("friends");
            assertNotNull(credential);
            host.abortCampaignRecovery("Native floor could not resume.");
            awaitPhase(friend, DirectConnectPhase.DISCONNECTED);
            DirectConnectSessionFlow.SessionReturn returned = flow.returnToLauncher(friend, false);
            assertTrue("Host recovery failure must open native failure panel", returned.isFailure());
            assertEquals(DirectConnectSessionFlow.ReturnDestination.CONNECT, returned.getDestination());
            assertTrue(returned.getMessage().contains("Host recovery failed"));
            assertFalse(returned.getMessage().contains(credential));
            assertFalse(returned.getMessage().contains(identity('2').getValue()));
            assertEquals(selected.getAddress(), flow.prepareConnect().getAddress());
            assertEquals(selected.getPort(), flow.prepareConnect().getPort());
            assertEquals("Friend", flow.prepareConnect().getPresentation().getNickname());
            assertEquals("humanoid-2", flow.prepareConnect().getPresentation().getAvatarId());
            assertEquals(credential, new ProfileReconnectTokenStore().load("friends"));
            assertFalse(flow.enter(friend, () -> fail("Stopped Host entered gameplay")));
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void clientLeaveRetainsClaimAndOnlyClosesOwnConnection() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        java.util.concurrent.atomic.AtomicBoolean attached = new java.util.concurrent.atomic.AtomicBoolean(true);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(() -> attached.set(false));
        DirectConnectSessionFlow.ConnectSetup selected = setup(host.getBoundPort());
        DirectConnectClient remaining = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('3'),
                new SlotPresentation("Remaining", "humanoid-3"), 0, new ProfileReconnectTokenStore(), compatibility());
        try {
            flow.openClient(selected, request -> client(request, identity('2')));
            attached.set(true);
            DirectConnectPeer leaving = flow.getPeer();
            ReadyTestSupport.readyClient(remaining);
            ReadyTestSupport.readyClient(leaving);
            ReadyTestSupport.startReadySession(host);
            awaitPhase(leaving, DirectConnectPhase.READY);
            awaitPhase(remaining, DirectConnectPhase.READY);
            String credential = new ProfileReconnectTokenStore().load("friends");
            DirectConnectSessionFlow.SessionReturn returned = flow.returnToLauncher(leaving, false);
            assertEquals(DirectConnectSessionFlow.ReturnDestination.CONNECT, returned.getDestination());
            assertFalse(returned.isConfirmationRequired());
            assertFalse(returned.isFailure());
            assertNull(flow.getPeer());
            assertFalse(attached.get());
            assertEquals(DirectConnectPhase.CLOSED, leaving.getStatus().getPhase());
            assertEquals(DirectConnectPhase.READY, host.getStatus().getPhase());
            assertEquals(DirectConnectPhase.READY, remaining.getStatus().getPhase());
            assertFalse(host.isSessionPaused());
            assertNotNull(roster.findSlot(identity('2')));
            assertEquals(credential, new ProfileReconnectTokenStore().load("friends"));
            assertEquals(selected.getPort(), flow.prepareConnect().getPort());
            assertEquals(selected.getPresentation(), flow.prepareConnect().getPresentation());
        }
        finally { flow.leave(); remaining.close(); host.close(); }
    }

    @Test public void saveQuitPersistsLatestWorldBeforeNativeReleaseAndReturnsBothRoles() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        java.util.concurrent.atomic.AtomicBoolean releasedAfterSave = new java.util.concurrent.atomic.AtomicBoolean();
        DirectConnectSessionFlow hostFlow = new DirectConnectSessionFlow(() -> {
            if(store.campaignSaves().exists("friends")) {
                assertArrayEquals(new byte[] { 9, 4 }, store.campaignSaves().load("friends", compatibility()).getNativeFloor());
                assertFalse(store.campaignSaves().needsRecovery("friends"));
                releasedAfterSave.set(true);
            }
        });
        DirectConnectSessionFlow clientFlow = new DirectConnectSessionFlow();
        hostFlow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
        DirectConnectHost host = (DirectConnectHost)hostFlow.getPeer();
        try {
            clientFlow.openClient(setup(host.getBoundPort()), request -> client(request, identity('2')));
            DirectConnectPeer friend = clientFlow.getPeer();
            ReadyTestSupport.readyClient(friend);
            ReadyTestSupport.startReadySession(host);
            awaitPhase(friend, DirectConnectPhase.READY);
            host.setNativeFloorCapture(() -> new byte[] { 9, 4 });
            DirectConnectSessionFlow.SessionReturn prompt = hostFlow.returnToLauncher(host, false);
            assertTrue(prompt.isConfirmationRequired());
            assertTrue(prompt.getMessage().contains("Save Campaign"));
            assertFalse(releasedAfterSave.get());
            DirectConnectSessionFlow.SessionReturn hostReturn = hostFlow.returnToLauncher(host, true);
            assertFalse(hostReturn.isFailure());
            assertEquals(DirectConnectSessionFlow.ReturnDestination.CAMPAIGNS, hostReturn.getDestination());
            assertTrue(releasedAfterSave.get());
            awaitPhase(friend, DirectConnectPhase.DISCONNECTED);
            DirectConnectSessionFlow.SessionReturn clientReturn = clientFlow.returnToLauncher(friend, false);
            assertFalse(clientReturn.isFailure());
            assertEquals(DirectConnectSessionFlow.ReturnDestination.CONNECT, clientReturn.getDestination());
            assertEquals("Host saved and closed session. Return to Connect.", clientReturn.getMessage());
        }
        finally { clientFlow.leave(); hostFlow.leave(); }
    }

    @Test public void unannouncedTcpLossCanRetrySameEndpointAndStaleReturnCannotCloseReplacement() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try(java.net.ServerSocket brokenEndpoint = new java.net.ServerSocket(0)) {
            int port = brokenEndpoint.getLocalPort();
            brokenEndpoint.setSoTimeout(8000);
            flow.openClient(setup(port), request -> client(request, identity('2')));
            DirectConnectPeer obsolete = flow.getPeer();
            try(java.net.Socket accepted = brokenEndpoint.accept()) { /* Transport disappears without Host notification. */ }
            awaitStopped(obsolete);
            DirectConnectSessionFlow.SessionReturn failed = flow.returnToLauncher(obsolete, false);
            assertTrue(failed.isFailure());
            assertEquals(DirectConnectSessionFlow.ReturnDestination.CONNECT, failed.getDestination());
            assertTrue(failed.getMessage().contains("Connection failed"));
            assertTrue(failed.getMessage().contains("TCP/UDP"));
            assertEquals(port, flow.prepareConnect().getPort());
            brokenEndpoint.close();
            try(DirectConnectHost host = DirectConnectHost.start(port, compatibility(), roster, store)) {
                flow.retry();
                DirectConnectPeer replacement = flow.getPeer();
                awaitPhase(replacement, DirectConnectPhase.LOBBY);
                assertNull(flow.returnToLauncher(obsolete, true));
                assertSame(replacement, flow.getPeer());
                assertFalse(flow.enter(obsolete, () -> fail("Obsolete connection entered gameplay")));
                flow.cancelClient();
                assertNull(flow.getPeer());
                flow.openClient(setup(port), request -> client(request, identity('2')));
                awaitPhase(flow.getPeer(), DirectConnectPhase.LOBBY);
                assertNull(flow.returnToLauncher(replacement, false));
            }
        }
        finally { flow.leave(); }
    }

    private static void awaitStopped(DirectConnectPeer peer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            DirectConnectPhase phase = peer.getStatus().getPhase();
            if(phase == DirectConnectPhase.FAILED || phase == DirectConnectPhase.DISCONNECTED) return;
            Thread.sleep(10L);
        }
        fail("Connection did not report transport loss: " + peer.getStatus().getMessage());
    }

    @Test public void blockedUdpKeepsConcreteReasonFieldsAndClaimForEditedRetry() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try(DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
                TcpOnlyEndpoint firewall = new TcpOnlyEndpoint(host.getBoundPort())) {
            flow.openClient(setup(firewall.port()), request -> client(request, identity('2')));
            DirectConnectPeer blocked = flow.getPeer();
            awaitPhase(blocked, DirectConnectPhase.FAILED);
            DirectConnectSessionFlow.SessionReturn failed = flow.returnToLauncher(blocked, false);
            assertTrue(failed.isFailure());
            assertTrue(failed.getMessage().contains("UDP registration timed out while TCP remained connected"));
            assertEquals(firewall.port(), flow.prepareConnect().getPort());
            String credential = new ProfileReconnectTokenStore().load("friends");
            assertNotNull(credential);
            assertFalse(failed.getMessage().contains(credential));
            flow.openClient(setup(host.getBoundPort()), request -> client(request, identity('2')));
            awaitPhase(flow.getPeer(), DirectConnectPhase.LOBBY);
            assertNotNull(roster.findSlot(identity('2')));
            assertEquals(credential, new ProfileReconnectTokenStore().load("friends"));
            assertNull(flow.returnToLauncher(blocked, true));
        }
        finally { flow.leave(); }
    }

    /** External TCP relay with bound, deliberately unforwarded UDP: real peers, real firewall failure. */
    private static final class TcpOnlyEndpoint implements AutoCloseable {
        private final java.net.ServerSocket listener;
        private final java.net.DatagramSocket droppedUdp;
        private final Thread relay;
        private volatile java.net.Socket downstream, upstream;
        private volatile Thread forward;

        TcpOnlyEndpoint(int hostPort) throws Exception {
            listener = new java.net.ServerSocket(0);
            try {
                listener.setSoTimeout(8000);
                droppedUdp = new java.net.DatagramSocket(listener.getLocalPort());
            }
            catch(java.io.IOException failure) {
                listener.close();
                throw failure;
            }
            relay = new Thread(() -> {
                try {
                    downstream = listener.accept();
                    upstream = new java.net.Socket("127.0.0.1", hostPort);
                    forward = new Thread(() -> pump(downstream, upstream), "test-firewall-to-host");
                    forward.setDaemon(true);
                    forward.start();
                    pump(upstream, downstream);
                }
                catch(java.io.IOException closed) { /* Endpoint is closed by bounded test cleanup. */ }
            }, "test-firewall-to-client");
            relay.setDaemon(true);
            relay.start();
        }

        int port() { return listener.getLocalPort(); }

        private static void pump(java.net.Socket source, java.net.Socket destination) {
            try {
                byte[] bytes = new byte[4096];
                int read;
                while((read = source.getInputStream().read(bytes)) != -1) {
                    destination.getOutputStream().write(bytes, 0, read);
                    destination.getOutputStream().flush();
                }
            }
            catch(java.io.IOException closed) { /* Real transport teardown ends relay. */ }
            finally {
                try { destination.close(); } catch(java.io.IOException ignored) { }
            }
        }

        @Override public void close() throws Exception {
            listener.close();
            droppedUdp.close();
            if(downstream != null) downstream.close();
            if(upstream != null) upstream.close();
            relay.join(2000L);
            if(forward != null) forward.join(2000L);
        }
    }

    private CampaignRosterStore store() throws Exception {
        return new CampaignRosterStore(temporary.newFolder("campaigns"), new SecureRandom());
    }

    private static CampaignRoster roster(CampaignRosterStore store) {
        CampaignRoster roster = CampaignRoster.create("friends", 3, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        return roster;
    }

    private static DirectConnectSessionFlow.ConnectSetup setup(int port) {
        return new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", port, "Friend", "humanoid-2");
    }

    private static DirectConnectClient client(DirectConnectSessionFlow.ConnectSetup setup, LauncherIdentity identity) {
        return DirectConnectClient.connect(setup.getAddress(), setup.getPort(), identity,
                setup.getPresentation(), 0, new ProfileReconnectTokenStore(), compatibility());
    }

    private static LauncherIdentity identity(char digit) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', digit));
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("return-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void awaitPhase(DirectConnectPeer peer, DirectConnectPhase expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            if(peer.getStatus().getPhase() == expected) return;
            Thread.sleep(10L);
        }
        fail("Expected " + expected + ", got " + peer.getStatus().getPhase() + ": " + peer.getStatus().getMessage());
    }
}
