package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.lobby.*;
import com.interrupt.dungeoneer.multiplayer.network.*;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Lobby edits through production action/session boundary and real TCP/UDP/storage. */
public class LobbyPresentationEditTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void hostEditKeepsOwnershipAndPublishesOnlyOwnConsentInvalidation() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectClient client = null;
        try {
            flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            client = friend(host, '2', "Friend", "humanoid-2", memoryTokens());
            awaitLobby(host, client, 2);
            host.setPlayerReady(true); client.setPlayerReady(true);
            awaitConsent(host, client, true, true);
            CampaignSlot before = roster.getSlot(1);
            int port = host.getBoundPort();
            long request = flow.editPresentation(host, "  Scout  ", "humanoid-3");
            assertTrue(request > 0L);
            awaitConsent(host, client, false, true);
            SlotPresentation edited = new SlotPresentation("Scout", "humanoid-3");
            assertEquals(edited, client.getLobbySnapshot().getSlot(1).getPresentation());
            CampaignSlot stored = store.load("edits", AvatarCatalog.ownedV108Humanoids()).getSlot(1);
            assertEquals(edited, stored.getPresentation());
            assertEquals(before.getLauncherIdentity(), stored.getLauncherIdentity());
            assertEquals(before.getReconnectToken(), stored.getReconnectToken());
            assertEquals(1, stored.getNumber());
            assertEquals("edits", roster.getCampaignId());
            assertEquals(port, host.getBoundPort());
            assertFalse(host.canStartSession());
            assertNull(host.getLocalMovementEntityId());
        }
        finally { if(client != null) client.close(); flow.leave(); }
    }

    @Test public void clientEditUpdatesEveryCardAndGameplayPresentationWithoutReplacingSlot() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.open(() -> friend(host, '2', "Friend", "humanoid-2", memoryTokens()));
            DirectConnectPeer client = flow.getPeer();
            awaitLobby(host, client, 2);
            host.setPlayerReady(true); client.setPlayerReady(true);
            awaitConsent(host, client, true, true);
            CampaignSlot before = roster.getSlot(2);
            assertTrue(flow.editPresentation(client, "Mage", "humanoid-4") > 0L);
            awaitConsent(host, client, true, false);
            assertEquals(new SlotPresentation("Mage", "humanoid-4"), client.getLobbySnapshot().getSlot(2).getPresentation());
            CampaignSlot stored = store.load("edits", AvatarCatalog.ownedV108Humanoids()).getSlot(2);
            assertEquals(before.getLauncherIdentity(), stored.getLauncherIdentity());
            assertEquals(before.getReconnectToken(), stored.getReconnectToken());
            assertEquals(new SlotPresentation("Mage", "humanoid-4"), stored.getPresentation());
            client.setPlayerReady(true); awaitConsent(host, client, true, true);
            host.startSession();
            await(() -> client.getStatus().getPhase() == DirectConnectPhase.READY, "gameplay entry");
            assertEquals("Mage", client.getMovementEntities().get(1).getNickname());
            assertEquals("humanoid-4", client.getMovementEntities().get(1).getAvatarId());
            assertEquals(2, client.getLocalCampaignSlot());
            assertFalse(client.canEditPresentation());
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void rejectedEditsExplainBoundsAndReservedConflictsWhileRetainingPresentationAndConsent() throws Exception {
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.open(() -> friend(host, '2', "Friend", "humanoid-2", memoryTokens()));
            DirectConnectPeer client = flow.getPeer();
            awaitLobby(host, client, 2);
            DirectConnectClient reserved = friend(host, '3', "Absent", "humanoid-3", memoryTokens());
            try { awaitLobby(host, reserved, 3); }
            finally { reserved.close(); }
            awaitLobby(host, client, 2);
            host.setPlayerReady(true); client.setPlayerReady(true);
            awaitConsent(host, client, true, true);
            SlotPresentation before = new SlotPresentation("Friend", "humanoid-2");
            String[][] conflicts = {{"hOsT", "humanoid-2", "Nickname"}, {"ABSENT", "humanoid-2", "Nickname"},
                    {"Friend", "humanoid-3", "Avatar"}, {"Friend", "missing-avatar", "Avatar"},
                    {"", "humanoid-2", "Nickname"}, {"123456789012345678901234567890123", "humanoid-2", "Nickname"},
                    {"Name\nControl", "humanoid-2", "control"}, {"😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀", "humanoid-2", "UTF-8"}};
            for(String[] conflict : conflicts) {
                long request = flow.editPresentation(client, conflict[0], conflict[1]);
                PresentationEditResult result = awaitResult(client, request);
                assertFalse(result.isAccepted());
                assertTrue(result.getReason(), result.getReason().contains(conflict[2]));
                assertEquals(before, result.getPresentation());
                assertEquals(before, host.getLobbySnapshot().getSlot(2).getPresentation());
                assertTrue(host.canStartSession());
                assertEquals(DirectConnectPhase.LOBBY, client.getStatus().getPhase());
            }
            long rejectedHost = host.editPresentation("aBsEnT", "humanoid-1");
            assertFalse(awaitResult(host, rejectedHost).isAccepted());
            assertEquals(new SlotPresentation("Host", "humanoid-1"), host.getLobbySnapshot().getSlot(1).getPresentation());
            assertTrue(host.canStartSession());
            assertEquals(before, store.load("edits", AvatarCatalog.ownedV108Humanoids()).getSlot(2).getPresentation());
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void onlyAcceptedHostResultsBecomeDefaultsAndReconnectRestoresCampaignChoice() throws Exception {
        java.io.File previous = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        MultiplayerProfile.initialize(temporary.newFolder("profile"));
        LauncherIdentity owner = LauncherIdentityStore.loadOrCreate();
        CampaignRosterStore store = store();
        CampaignRoster roster = roster(store);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        ProfileReconnectTokenStore tokens = new ProfileReconnectTokenStore();
        SlotPresentation original = new SlotPresentation("Friend", "humanoid-2");
        try {
            LauncherPresentationStore.save(original);
            flow.open(() -> DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), owner, original, 0, tokens, compatibility()));
            DirectConnectPeer client = flow.getPeer(); awaitLobby(host, client, 2);
            String token = tokens.load("edits");
            SlotPresentation accepted = new SlotPresentation("Mage", "humanoid-4");
            long request = flow.editPresentation(client, "Mage", "humanoid-4");
            assertTrue(awaitResult(client, request).isAccepted());
            assertTrue(flow.pollPresentationEditResult(client).isAccepted());
            assertEquals(accepted, new DirectConnectSessionFlow().prepareConnect().getPresentation());
            long conflict = flow.editPresentation(client, "HOST", "humanoid-4");
            assertFalse(awaitResult(client, conflict).isAccepted());
            assertFalse(flow.pollPresentationEditResult(client).isAccepted());
            assertEquals(accepted, LauncherPresentationStore.load());
            assertEquals(owner, LauncherIdentityStore.loadOrCreate());
            assertEquals(token, tokens.load("edits"));
            flow.leave();
            await(() -> host.getLobbySnapshot().getConnectedCount() == 1, "disconnect");
            LauncherPresentationStore.save(new SlotPresentation("Another default", "humanoid-3"));
            DirectConnectSessionFlow.ConnectSetup defaults = flow.prepareConnect();
            flow.open(() -> DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), owner, defaults.getPresentation(), 0, tokens, compatibility()));
            DirectConnectPeer returned = flow.getPeer(); awaitLobby(host, returned, 2);
            assertEquals(accepted, returned.getLobbySnapshot().getSlot(2).getPresentation());
            assertEquals(token, tokens.load("edits"));
            assertNull("Stale peer result cannot overwrite new defaults", flow.pollPresentationEditResult(client));
            assertEquals(new SlotPresentation("Another default", "humanoid-3"), LauncherPresentationStore.load());
            host.setPlayerReady(true); returned.setPlayerReady(true); awaitConsent(host, returned, true, true);
            long unchanged = flow.editPresentation(returned, "  Mage  ", "humanoid-4");
            assertTrue(awaitResult(returned, unchanged).isAccepted());
            assertTrue("No-op keeps current consent", host.canStartSession());
            assertEquals(accepted, returned.getPresentationEditResult().getPresentation());
        }
        finally { flow.leave(); host.close(); if(previous != null) MultiplayerProfile.initialize(previous); }
    }

    @Test public void failedPresentationSaveKeepsLiveChoiceConsentAndConnection() throws Exception {
        java.io.File root = temporary.newFolder("failed-edit");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster roster = roster(store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectClient client = null;
        java.io.File file = new java.io.File(root, "edits/roster.properties");
        java.io.File preserved = new java.io.File(root, "preserved-roster");
        try {
            flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            client = friend(host, '2', "Friend", "humanoid-2", memoryTokens());
            awaitLobby(host, client, 2);
            host.setPlayerReady(true); client.setPlayerReady(true); awaitConsent(host, client, true, true);
            CampaignSlot originalHost = roster.getSlot(1), originalClient = roster.getSlot(2);
            java.nio.file.Files.move(file.toPath(), preserved.toPath());
            assertTrue(file.mkdir()); // Real filesystem refuses atomic file replacement over directory.
            long hostRequest = flow.editPresentation(host, "Scout", "humanoid-3");
            PresentationEditResult deniedHost = awaitResult(host, hostRequest);
            assertFalse(deniedHost.isAccepted());
            assertTrue(deniedHost.getReason().contains("saved"));
            assertEquals(originalHost.getPresentation(), deniedHost.getPresentation());
            long clientRequest = client.editPresentation("Mage", "humanoid-4");
            PresentationEditResult deniedClient = awaitResult(client, clientRequest);
            assertFalse(deniedClient.isAccepted());
            assertEquals(originalClient.getPresentation(), deniedClient.getPresentation());
            assertSame(originalHost, roster.getSlot(1));
            assertSame(originalClient, roster.getSlot(2));
            awaitConsent(host, client, true, true);
            assertTrue(host.canStartSession());
            assertSame(host, flow.getPeer());
            assertEquals(DirectConnectPhase.LOBBY, client.getStatus().getPhase());
            java.nio.file.Files.delete(file.toPath());
            java.nio.file.Files.move(preserved.toPath(), file.toPath());
            assertEquals(originalHost.getPresentation(), store.load("edits", AvatarCatalog.ownedV108Humanoids()).getSlot(1).getPresentation());
            assertEquals(originalClient.getReconnectToken(), store.load("edits", AvatarCatalog.ownedV108Humanoids()).getSlot(2).getReconnectToken());
        }
        finally {
            if(file.isDirectory()) java.nio.file.Files.delete(file.toPath());
            if(preserved.exists()) java.nio.file.Files.move(preserved.toPath(), file.toPath());
            if(client != null) client.close(); flow.leave();
        }
    }

    private static PresentationEditResult awaitResult(DirectConnectPeer peer, long request) throws Exception {
        await(() -> peer.getPresentationEditResult() != null && peer.getPresentationEditResult().getRequestId() == request,
                "Host result for edit " + request);
        return peer.getPresentationEditResult();
    }

    private CampaignRosterStore store() throws Exception {
        return new CampaignRosterStore(temporary.newFolder(), new SecureRandom());
    }
    private static CampaignRoster roster(CampaignRosterStore store) {
        CampaignRoster roster = CampaignRoster.createNamed("edits", "Friday Delver", 4, 3,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster); return roster;
    }
    private static LauncherIdentity identity(char value) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', value));
    }
    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("edit-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static ReconnectTokenStore memoryTokens() {
        return new ReconnectTokenStore() {
            private volatile String token;
            public String load(String campaign) { return token; }
            public void save(String campaign, String value) { token = value; }
        };
    }
    private static DirectConnectClient friend(DirectConnectHost host, char value, String name, String avatar, ReconnectTokenStore tokens) {
        return DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity(value), new SlotPresentation(name, avatar), 0, tokens, compatibility());
    }
    private static void awaitLobby(DirectConnectHost host, DirectConnectPeer client, int connected) throws Exception {
        await(() -> {
            LobbySnapshot lobby = host.getLobbySnapshot();
            if(lobby.getConnectedCount() != connected || !lobby.equals(client.getLobbySnapshot())) return false;
            for(LobbySnapshot.Slot slot : lobby.getSlots()) if(slot.isConnected() && !slot.isSynchronized()) return false;
            return client.getStatus().getPhase() == DirectConnectPhase.LOBBY;
        }, "synchronized shared lobby");
    }
    private static void awaitConsent(DirectConnectHost host, DirectConnectPeer client, boolean hostReady, boolean clientReady) throws Exception {
        await(() -> host.getLobbySnapshot().getSlot(1).isPlayerReady() == hostReady
                && host.getLobbySnapshot().getSlot(2).isPlayerReady() == clientReady
                && host.getLobbySnapshot().equals(client.getLobbySnapshot()), "Host=" + hostReady + ", Friend=" + clientReady);
    }
    private static void await(java.util.function.BooleanSupplier condition, String expectation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) { if(condition.getAsBoolean()) return; Thread.sleep(10L); }
        fail("Expected " + expectation);
    }
}
