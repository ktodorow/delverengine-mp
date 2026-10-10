package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.lobby.*;
import com.interrupt.dungeoneer.multiplayer.network.*;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.security.SecureRandom;
import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.readyClient;
import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.startReadySession;

import static org.junit.Assert.*;

/** Native Connect actions through actual TCP/UDP sessions and durable profile. */
public class NativeConnectSetupTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File previousProfile;

    @Before public void profile() throws Exception {
        previousProfile = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        MultiplayerProfile.initialize(temporary.newFolder("profile"));
    }

    @After public void restoreProfile() {
        if(previousProfile != null) MultiplayerProfile.initialize(previousProfile);
    }

    @Test public void typedConnectJoinsActualHostAndReloadsEditableDefaultsWithSameIdentity() throws Exception {
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("campaigns"), new SecureRandom());
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        assertEquals(37777, flow.prepareConnect().getPort());
        assertEquals("Participant", flow.prepareConnect().getPresentation().getNickname());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        try {
            DirectConnectSessionFlow.ConnectSetup setup = new DirectConnectSessionFlow.ConnectSetup(
                    " 127.0.0.1 ", host.getBoundPort(), "  Friend  ", "humanoid-2");
            flow.openClient(setup, request -> DirectConnectClient.connect(request.getAddress(), request.getPort(),
                    LauncherIdentityStore.loadOrCreate(), request.getPresentation(), 0,
                    new ProfileReconnectTokenStore(), compatibility()));
            DirectConnectClient client = (DirectConnectClient)flow.getPeer();
            readyClient(client);
            assertEquals(2, host.getConnectedParticipantCount());
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), host.getRoster().getSlot(2).getPresentation());
            startReadySession(host);
            awaitPhase(client, DirectConnectPhase.READY);
            awaitEntry(flow, client);
            assertTrue(flow.enter(client, () -> { }));
            flow.leave();
            DirectConnectSessionFlow.ConnectSetup recalled = new DirectConnectSessionFlow().prepareConnect();
            assertEquals("127.0.0.1", recalled.getAddress());
            assertEquals(host.getBoundPort(), recalled.getPort());
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), recalled.getPresentation());
            assertEquals(identity, LauncherIdentityStore.loadOrCreate());
            assertNotNull(new ProfileReconnectTokenStore().load("friends"));
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void corruptEditablePreferencesFallBackWithoutReplacingPrivateIdentity() throws Exception {
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        LauncherEndpointStore.save("friends.example", 41234);
        LauncherPresentationStore.save(new SlotPresentation("Scout", "humanoid-3"));
        File endpoint = MultiplayerProfile.resolveWritableFile("settings/multiplayer-endpoint.properties").file();
        byte[] corruptEndpoint = ("address=" + '\\' + "uGGGG\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(endpoint.toPath(), corruptEndpoint);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        assertEquals("127.0.0.1", flow.prepareConnect().getAddress());
        assertEquals(37777, flow.prepareConnect().getPort());
        assertEquals(new SlotPresentation("Scout", "humanoid-3"), flow.prepareConnect().getPresentation());
        assertArrayEquals("Reading fallback cannot rewrite damaged preference", corruptEndpoint,
                java.nio.file.Files.readAllBytes(endpoint.toPath()));
        LauncherEndpointStore.save("friends.example", 41234);
        File presentation = MultiplayerProfile.resolveWritableFile("settings/multiplayer-presentation.properties").file();
        java.nio.file.Files.write(presentation.toPath(), ("nickname=" + '\\' + "uGGGG\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("friends.example", flow.prepareConnect().getAddress());
        assertEquals(41234, flow.prepareConnect().getPort());
        assertEquals(new SlotPresentation("Participant", "humanoid-1"), flow.prepareConnect().getPresentation());
        assertEquals(identity, LauncherIdentityStore.loadOrCreate());
        assertNull(flow.getPeer());
    }

    @Test public void typedValidationKeepsExistingUnicodeBoundsAndSeparateEndpointFields() {
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        for(String invalid : new String[] { "", " ", "host:37777", "https://host", "two hosts", "host\nname" })
            invalid(() -> new DirectConnectSessionFlow.ConnectSetup(invalid, 37777, "Friend", "humanoid-2"));
        for(int invalidPort : new int[] { 0, -1, 65536 })
            invalid(() -> new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", invalidPort, "Friend", "humanoid-2"));
        for(String invalid : new String[] { "", " ", repeat("a", 33), repeat("界", 22), "Friend\nName" })
            invalid(() -> new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", 37777, invalid, "humanoid-2"));
        invalid(() -> new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", 37777, "Friend", "custom-avatar"));
        assertEquals("::1", new DirectConnectSessionFlow.ConnectSetup(" [::1] ", 37777, "Friend", "humanoid-2").getAddress());
        assertEquals("friends.example", new DirectConnectSessionFlow.ConnectSetup("friends.example", 65535, repeat("界", 21), "humanoid-4").getAddress());
        assertEquals(repeat("😀", 16), new DirectConnectSessionFlow.ConnectSetup("192.168.1.9", 1, repeat("😀", 16), "humanoid-3").getPresentation().getNickname());
        assertNull(flow.getPeer());
        assertEquals(37777, flow.prepareConnect().getPort());
    }

    @Test public void hostRejectsProvisionalNicknameAndAvatarThenAcceptsEditedRetry() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("rejections"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            open(flow, host.getBoundPort(), "hOsT", "humanoid-2");
            DirectConnectPeer nicknameRejected = flow.getPeer();
            awaitPhase(nicknameRejected, DirectConnectPhase.REJECTED);
            assertTrue(nicknameRejected.getStatus().getMessage().contains("NICKNAME_TAKEN"));
            assertEquals("hOsT", flow.prepareConnect().getPresentation().getNickname());
            assertNull(new ProfileReconnectTokenStore().load("friends"));
            assertEquals(1, host.getRoster().getSlots().size());
            open(flow, host.getBoundPort(), "Friend", "humanoid-1");
            DirectConnectPeer avatarRejected = flow.getPeer();
            awaitPhase(avatarRejected, DirectConnectPhase.REJECTED);
            assertTrue(avatarRejected.getStatus().getMessage().contains("AVATAR_UNAVAILABLE"));
            assertEquals(new SlotPresentation("Friend", "humanoid-1"), flow.prepareConnect().getPresentation());
            assertFalse(flow.enter(nicknameRejected, () -> fail("Rejected nickname attempt entered retry")));
            open(flow, host.getBoundPort(), "Friend", "humanoid-2");
            DirectConnectPeer accepted = flow.getPeer();
            readyClient(accepted);
            startReadySession(host);
            awaitPhase(accepted, DirectConnectPhase.READY);
            awaitEntry(flow, accepted);
            assertTrue(flow.enter(accepted, () -> { }));
            assertFalse(flow.enter(avatarRejected, () -> fail("Rejected Avatar attempt entered retry")));
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), host.getRoster().getSlot(2).getPresentation());
            assertEquals(identity, LauncherIdentityStore.loadOrCreate());
        }
        finally { flow.leave(); host.close(); }
    }

    private static void open(DirectConnectSessionFlow flow, int port, String nickname, String avatar) {
        flow.openClient(new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", port, nickname, avatar),
                request -> DirectConnectClient.connect(request.getAddress(), request.getPort(),
                        LauncherIdentityStore.loadOrCreate(), request.getPresentation(), 0,
                        new ProfileReconnectTokenStore(), compatibility()));
    }

    @Test public void cancellingAuthenticationAndAdmittedConnectionEndsAttemptBeforeSameWindowRetry() throws Exception {
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try(java.net.ServerSocket stalled = new java.net.ServerSocket(0)) {
            stalled.setSoTimeout(8000);
            open(flow, stalled.getLocalPort(), "Friend", "humanoid-2");
            try(java.net.Socket acceptedSocket = stalled.accept()) {
                DirectConnectPeer cancelledHandshake = flow.getPeer();
                awaitPhase(cancelledHandshake, DirectConnectPhase.HANDSHAKING);
                assertEquals("Authenticating with Host...", flow.getClientProgress());
                flow.cancelClient();
                assertNull(flow.getPeer());
                assertEquals(DirectConnectPhase.CLOSED, cancelledHandshake.getStatus().getPhase());
                assertFalse(flow.enter(cancelledHandshake, () -> fail("Cancelled handshake entered gameplay")));
                try { flow.retry(); fail("Back/Cancel must not restart cancelled request."); }
                catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("No session request")); }
                assertEquals(stalled.getLocalPort(), flow.prepareConnect().getPort());
            }
        }
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("cancel-retry"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        try {
            open(flow, host.getBoundPort(), "Friend", "humanoid-2");
            DirectConnectPeer cancelledConnection = flow.getPeer();
            readyClient(cancelledConnection);
            flow.cancelClient();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(host.getConnectedParticipantCount() != 1 && System.nanoTime() < deadline) Thread.sleep(10L);
            assertTrue(host.getPendingClaims().isEmpty());
            assertEquals(1, host.getConnectedParticipantCount());
            assertEquals(2, host.getRoster().getSlots().size());
            open(flow, host.getBoundPort(), "Friend", "humanoid-2");
            DirectConnectPeer current = flow.getPeer();
            readyClient(current);
            assertEquals("Connected. Waiting for Host to start.", flow.getClientProgress());
            startReadySession(host);
            awaitPhase(current, DirectConnectPhase.READY);
            assertFalse(flow.enter(cancelledConnection, () -> fail("Cancelled claim entered replacement")));
            awaitEntry(flow, current);
            assertTrue(flow.enter(current, () -> { }));
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void readyAttemptCancelAndChangedDefaultsCannotReplaceReservedSlotOrToken() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("reserved-return"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        try {
            open(flow, host.getBoundPort(), "Friend", "humanoid-2");
            DirectConnectPeer first = flow.getPeer();
            readyClient(first);
            startReadySession(host);
            awaitPhase(first, DirectConnectPhase.READY);
            com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId originalEntity = first.getLocalMovementEntityId();
            String credential = new ProfileReconnectTokenStore().load("friends");
            flow.cancelClient(); // Ready packet may race Back before posted native entry.
            assertFalse(flow.enter(first, () -> fail("Cancelled ready attempt entered world")));
            awaitConnected(host, 1);
            DirectConnectSessionFlow.ConnectSetup provisional = new DirectConnectSessionFlow.ConnectSetup(
                    "127.0.0.1", host.getBoundPort(), "Host", "humanoid-1");
            flow.openClient(provisional, request -> DirectConnectClient.connect(request.getAddress(), request.getPort(),
                    identity, request.getPresentation(), 0, new ReconnectTokenStore() {
                        public String load(String campaign) { return null; }
                        public void save(String campaign, String token) { fail("Rejected identity cannot replace credential"); }
                    }, compatibility()));
            DirectConnectPeer denied = flow.getPeer();
            awaitPhase(denied, DirectConnectPhase.REJECTED);
            assertTrue(denied.getStatus().getMessage().contains("RECONNECT_DENIED"));
            assertEquals(credential, new ProfileReconnectTokenStore().load("friends"));
            open(flow, host.getBoundPort(), "Host", "humanoid-1");
            DirectConnectPeer reclaimed = flow.getPeer();
            awaitPhase(reclaimed, DirectConnectPhase.READY);
            assertEquals(2, reclaimed.getLocalCampaignSlot());
            assertEquals(originalEntity, reclaimed.getLocalMovementEntityId());
            assertEquals(identity, host.getRoster().getSlot(2).getLauncherIdentity());
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), host.getRoster().getSlot(2).getPresentation());
            assertEquals(new SlotPresentation("Host", "humanoid-1"), flow.prepareConnect().getPresentation());
            assertEquals(credential, new ProfileReconnectTokenStore().load("friends"));
            assertFalse(flow.enter(denied, () -> fail("Wrong-token attempt entered reclaimed slot")));
            awaitEntry(flow, reclaimed);
            assertTrue(flow.enter(reclaimed, () -> { }));
            assertTrue(host.getPendingClaims().isEmpty());
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void handshakeTimeoutRetainsEditableFieldsAndCanBeCancelled() throws Exception {
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try(java.net.ServerSocket stalled = new java.net.ServerSocket(0)) {
            stalled.setSoTimeout(8000);
            open(flow, stalled.getLocalPort(), "Scout", "humanoid-3");
            try(java.net.Socket acceptedSocket = stalled.accept()) {
                DirectConnectPeer timedOut = flow.getPeer();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while(timedOut.getStatus().getPhase() != DirectConnectPhase.FAILED && System.nanoTime() < deadline) Thread.sleep(10L);
                assertEquals(DirectConnectPhase.FAILED, timedOut.getStatus().getPhase());
                assertTrue(flow.getClientProgress().contains("edit and retry"));
                assertEquals(stalled.getLocalPort(), flow.prepareConnect().getPort());
                assertEquals(new SlotPresentation("Scout", "humanoid-3"), flow.prepareConnect().getPresentation());
                flow.cancelClient();
                assertNull(flow.getPeer());
                assertFalse(flow.enter(timedOut, () -> fail("Timed-out connection entered world")));
            }
        }
        finally { flow.leave(); }
    }

    @Test public void hostDefaultsAndClientDefaultsShareOrdinaryProfileIdentity() {
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        LauncherPresentationStore.save(new SlotPresentation("Explorer", "humanoid-4"));
        DirectConnectSessionFlow.ConnectSetup client = new DirectConnectSessionFlow().prepareConnect();
        assertEquals(new SlotPresentation("Explorer", "humanoid-4"), client.getPresentation());
        assertEquals(37777, client.getPort());
        assertEquals(identity, LauncherIdentityStore.loadOrCreate());
        LauncherEndpointStore.save("friend.example", 41234);
        assertEquals(new SlotPresentation("Explorer", "humanoid-4"), LauncherPresentationStore.load());
        assertEquals(identity, LauncherIdentityStore.loadOrCreate());
    }

    @Test public void failedPreferenceWriteClosesOpenedClientWithoutClaimingHostCapacity() throws Exception {
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        LauncherPresentationStore.save(new SlotPresentation("Previous", "humanoid-4"));
        File preference = MultiplayerProfile.resolveWritableFile("settings/multiplayer-presentation.properties").file();
        assertTrue(preference.delete());
        assertTrue(preference.mkdir()); // Real filesystem failure, not mocked storage.
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("preference-failure"), new SecureRandom());
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster(store), store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        java.util.concurrent.atomic.AtomicReference<DirectConnectClient> opened = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            try {
                flow.openClient(new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", host.getBoundPort(), "Friend", "humanoid-2"), request -> {
                    DirectConnectClient client = DirectConnectClient.connect(request.getAddress(), request.getPort(), identity,
                            request.getPresentation(), 0, new ProfileReconnectTokenStore(), compatibility());
                    opened.set(client);
                    return client;
                });
                fail("Failed preference write must remain on editable form.");
            }
            catch(RuntimeException expected) { assertNotNull(expected.getMessage()); }
            assertNull(flow.getPeer());
            assertNotNull(opened.get());
            assertEquals(DirectConnectPhase.CLOSED, opened.get().getStatus().getPhase());
            assertEquals(1, host.getRoster().getSlots().size());
            assertEquals(identity, LauncherIdentityStore.loadOrCreate());
        }
        finally { flow.leave(); host.close(); }
    }

    private static void awaitConnected(DirectConnectHost host, int count) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(host.getConnectedParticipantCount() != count && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(count, host.getConnectedParticipantCount());
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder();
        for(int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static void invalid(Runnable action) {
        try { action.run(); fail("Invalid typed setup must remain editable before networking."); }
        catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }

    private CampaignRoster roster(CampaignRosterStore store) {
        CampaignRoster roster = CampaignRoster.create("friends", 4, AvatarCatalog.ownedV108Humanoids(),
                new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', '1')),
                new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        return roster;
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor(
                "connect-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
}
