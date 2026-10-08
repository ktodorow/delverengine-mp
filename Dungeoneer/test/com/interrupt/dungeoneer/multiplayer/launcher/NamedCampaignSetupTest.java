package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.lobby.*;
import com.interrupt.dungeoneer.multiplayer.network.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.rules.TemporaryFolder;

import java.net.ServerSocket;
import java.security.SecureRandom;
import java.io.File;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;

import static org.junit.Assert.*;

/** Native setup actions through real application flow, listeners and temporary Campaign Library. */
public class NamedCampaignSetupTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File previousProfile;

    @Before public void profile() throws Exception {
        previousProfile = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        MultiplayerProfile.initialize(temporary.newFolder("profile"));
    }

    @After public void restoreProfile() {
        if(previousProfile != null) MultiplayerProfile.initialize(previousProfile);
    }

    @Test public void occupiedPortKeepsSetupAndDefaultsUntilEditedAttemptOpensLobby() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("failed-bind"), new SecureRandom());
        SlotPresentation defaults = LauncherPresentationStore.load();
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(releases::incrementAndGet);
        try(java.net.DatagramSocket occupied = SocketTestPorts.occupyUdpWithAvailableTcp()) {
            int port = occupied.getLocalPort();
            DirectConnectSessionFlow.HostSetup setup = new DirectConnectSessionFlow.HostSetup(
                    "Friday / friends", 4, 5, "Explorer", "humanoid-4", port);
            try {
                flow.openNewCampaign(setup, store, identity('1'),
                        (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
                fail("Occupied UDP port must fail listener bind.");
            }
            catch(RuntimeException expected) { assertTrue(expected.getMessage().contains("UDP listener")); }
            assertNull(flow.getPeer());
            assertEquals(0, releases.get());
            assertTrue(store.listCampaigns().isEmpty());
            assertEquals(defaults, LauncherPresentationStore.load());
            assertEquals("Friday / friends", setup.getCampaignName());
            assertEquals(5, setup.getStartingLives());
            assertEquals("Explorer", setup.getPresentation().getNickname());
            occupied.close();
            flow.openNewCampaign(new DirectConnectSessionFlow.HostSetup("Saturday / friends", 2, 1,
                    "Scout", "humanoid-2", port), store, identity('1'),
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            assertEquals(1, store.listCampaigns().size());
            assertEquals("Saturday / friends", ((DirectConnectHost)flow.getPeer()).getRoster().getCampaignName());
            assertEquals(1, ((DirectConnectHost)flow.getPeer()).getStartingLives());
            assertEquals(DirectConnectPhase.LISTENING, flow.getPeer().getStatus().getPhase());
            assertEquals(new SlotPresentation("Scout", "humanoid-2"), LauncherPresentationStore.load());
        }
        finally { flow.leave(); }
    }

    @Test public void occupiedTcpKeepsFormAndDefaultsUntilAnotherPortOpensLobby() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("tcp-failure"), new SecureRandom());
        SlotPresentation defaults = LauncherPresentationStore.load();
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(releases::incrementAndGet);
        try(ServerSocket occupied = new ServerSocket(0)) {
            DirectConnectSessionFlow.HostSetup setup = new DirectConnectSessionFlow.HostSetup(
                    "Friday", 2, 4, "Explorer", "humanoid-3", occupied.getLocalPort());
            try {
                flow.openNewCampaign(setup, store, identity('1'),
                        (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
                fail("Occupied TCP must refuse Host bind.");
            }
            catch(RuntimeException expected) { assertTrue(expected.getMessage().contains("TCP listener")); }
            assertNull(flow.getPeer());
            assertEquals(0, releases.get());
            assertTrue(store.listCampaigns().isEmpty());
            assertEquals(defaults, LauncherPresentationStore.load());
            assertEquals("Friday", setup.getCampaignName());
            flow.openNewCampaign(new DirectConnectSessionFlow.HostSetup(setup.getCampaignName(),
                    setup.getCapacity(), setup.getStartingLives(), setup.getPresentation().getNickname(),
                    setup.getPresentation().getAvatarId(), freePort()), store, identity('1'),
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            assertEquals(1, store.listCampaigns().size());
            assertEquals(DirectConnectPhase.LISTENING, flow.getPeer().getStatus().getPhase());
            assertEquals(4, ((DirectConnectHost)flow.getPeer()).getStartingLives());
        }
        finally { flow.leave(); }
    }

    @Test public void setupRejectsInvalidDisplayAndListenerSettingsBeforeOpeningSession() {
        assertInvalid(() -> new DirectConnectSessionFlow.HostSetup("", 2, 3, "Host", "humanoid-1", 37777));
        assertInvalid(() -> new DirectConnectSessionFlow.HostSetup("Friday\nnight", 2, 3, "Host", "humanoid-1", 37777));
        assertInvalid(() -> new DirectConnectSessionFlow.HostSetup(new String(new char[65]).replace('\0', 'x'), 2, 3, "Host", "humanoid-1", 37777));
        for(int port : new int[] { -1, 0, 65536 })
            assertInvalid(() -> new DirectConnectSessionFlow.HostSetup("Friday", 2, 3, "Host", "humanoid-1", port));
        for(int capacity : new int[] { 1, 5 })
            assertInvalid(() -> new DirectConnectSessionFlow.HostSetup("Friday", capacity, 3, "Host", "humanoid-1", 37777));
        for(int lives : new int[] { 0, 6 })
            assertInvalid(() -> new DirectConnectSessionFlow.HostSetup("Friday", 2, lives, "Host", "humanoid-1", 37777));
        assertInvalid(() -> new DirectConnectSessionFlow.HostSetup("Friday", 2, 3, "", "humanoid-1", 37777));
        assertInvalid(() -> new DirectConnectSessionFlow.HostSetup("Friday", 2, 3, "Host", "invented-skin", 37777));
        assertEquals(1, new DirectConnectSessionFlow.HostSetup("Friday", 2, 3, "Host", "humanoid-1", 1).getPort());
        assertEquals(65535, new DirectConnectSessionFlow.HostSetup("Friday", 2, 3, "Host", "humanoid-1", 65535).getPort());
    }

    private static void assertInvalid(Runnable request) {
        try { request.run(); fail("Invalid setup must be rejected."); }
        catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void hostPresentationRemainsEditableDefaultWhenSameIdentityBecomesClient() throws Exception {
        LauncherIdentity identity = LauncherIdentityStore.loadOrCreate();
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("host-defaults"), new SecureRandom());
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        SlotPresentation chosen = new SlotPresentation("Explorer", "humanoid-3");
        try {
            flow.openNewCampaign(new DirectConnectSessionFlow.HostSetup("Friday", 2, 3,
                    chosen.getNickname(), chosen.getAvatarId(), freePort()), store, identity,
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            flow.leave();
            assertEquals(chosen, LauncherPresentationStore.load());
            assertEquals(identity, LauncherIdentityStore.loadOrCreate());
            CampaignRoster other = CampaignRoster.create("other", 2, AvatarCatalog.ownedV108Humanoids(),
                    identity('2'), new SlotPresentation("Friend", "humanoid-2"), new SecureRandom());
            store.save(other);
            DirectConnectHost host = DirectConnectHost.start(0, compatibility(), other, store);
            DirectConnectClient client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                    LauncherIdentityStore.loadOrCreate(), LauncherPresentationStore.load(), 0,
                    new ProfileReconnectTokenStore(), compatibility());
            try {
                awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
                host.approve(identity.getValue());
                awaitPhase(client, DirectConnectPhase.LOBBY);
                assertEquals(chosen, other.getSlot(2).getPresentation());
                assertEquals(identity, other.getSlot(2).getLauncherIdentity());
            }
            finally { client.close(); host.close(); }
        }
        finally { flow.leave(); }
    }

    @Test public void typedCampaignAndHostNamesOpenLobbyWithChosenRulesWithoutStartingPlay() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("campaigns"), new SecureRandom());
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectSessionFlow.HostSetup setup = new DirectConnectSessionFlow.HostSetup(
                "  Friday friends: / dungeon \u21161  ", 4, 5, "  Kristiyan  ", "humanoid-3", freePort());
        try {
            flow.openNewCampaign(setup, store, identity('1'),
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            CampaignRoster roster = store.load(store.listCampaigns().get(0), AvatarCatalog.ownedV108Humanoids());
            assertEquals("Friday friends: / dungeon \u21161", roster.getCampaignName());
            assertNotEquals(roster.getCampaignName(), roster.getCampaignId());
            assertNotEquals("Kristiyan", roster.getCampaignId());
            assertEquals(identity('1'), roster.getSlot(1).getLauncherIdentity());
            assertEquals(new SlotPresentation("Kristiyan", "humanoid-3"), roster.getSlot(1).getPresentation());
            assertEquals(4, roster.getCapacity());
            assertEquals(5, host.getStartingLives());
            assertEquals(setup.getPort(), host.getBoundPort());
            assertEquals(DirectConnectPhase.LISTENING, host.getStatus().getPhase());
            assertFalse(host.isStartingLivesLocked());
            assertFalse(store.campaignSaves().exists(roster.getCampaignId()));
            assertFalse(flow.enter(host, () -> fail("Open Lobby entered gameplay")));
        }
        finally { flow.leave(); }
    }

    @Test public void unstartedCampaignReopensChosenLivesAfterColdStorageReload() throws Exception {
        File root = temporary.newFolder("pregame-reopen");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.openNewCampaign(new DirectConnectSessionFlow.HostSetup("Friends", 3, 5,
                    "Explorer", "humanoid-4", freePort()), store, identity('1'),
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            String campaignId = store.listCampaigns().get(0);
            String token = ((DirectConnectHost)flow.getPeer()).getRoster().getSlot(1).getReconnectToken();
            flow.leave();
            CampaignRosterStore restarted = new CampaignRosterStore(root, new SecureRandom());
            CampaignRoster resumed = restarted.load(campaignId, AvatarCatalog.ownedV108Humanoids());
            flow.open(() -> DirectConnectHost.start(0, compatibility(), resumed, restarted));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            assertEquals(5, host.getStartingLives());
            assertEquals("Friends", host.getRoster().getCampaignName());
            assertEquals(3, host.getRoster().getCapacity());
            assertEquals(token, host.getRoster().getSlot(1).getReconnectToken());
            assertFalse(host.isStartingLivesLocked());
        }
        finally { flow.leave(); }
    }

    @Test public void authoritativeSaveRecoveryAndExportImportKeepFriendlyNameAndOwnership() throws Exception {
        File root = temporary.newFolder("name-lifecycle");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectClient client = null;
        String name = "Friday friends / dungeon [red]";
        String campaignId;
        String token;
        try {
            flow.openNewCampaign(new DirectConnectSessionFlow.HostSetup(name, 2, 4,
                    "Explorer", "humanoid-1", freePort()), store, identity('1'),
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            campaignId = host.getRoster().getCampaignId();
            token = host.getRoster().getSlot(1).getReconnectToken();
            client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                    new SlotPresentation("Friend", "humanoid-2"), 0, new ProfileReconnectTokenStore(), compatibility());
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            host.approve(identity('2').getValue());
            awaitPhase(client, DirectConnectPhase.LOBBY);
            host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            assertEquals(name, host.persistCampaign().getCampaignName());
            flow.leave();
            flow.retry();
            DirectConnectHost retried = (DirectConnectHost)flow.getPeer();
            assertEquals(name, retried.getRoster().getCampaignName());
            assertEquals(4, retried.getStartingLives());
            assertTrue(retried.isStartingLivesLocked());
            assertEquals(token, retried.getRoster().getSlot(1).getReconnectToken());
            flow.leave();
        }
        finally { if(client != null) client.close(); flow.leave(); }

        CampaignSaveStore saves = store.campaignSaves();
        CampaignSave saved = saves.load(campaignId, compatibility());
        assertEquals(name, saved.getCampaignName());
        saves.beginSession(campaignId);
        saves.snapshot(saved);
        saves.endSession(campaignId, false);
        CampaignRosterStore restarted = new CampaignRosterStore(root, new SecureRandom());
        assertEquals(name, restarted.campaignSaves().recover(campaignId, compatibility()).getCampaignName());
        CampaignLibrary library = new CampaignLibrary(restarted, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), new SlotPresentation("Explorer", "humanoid-1"));
        File exported = library.exportCampaign(campaignId, new File(temporary.getRoot(), "friday.delvercampaign"), compatibility());
        CampaignRosterStore importedStore = new CampaignRosterStore(temporary.newFolder("imported"), new SecureRandom());
        CampaignLibrary importedLibrary = new CampaignLibrary(importedStore, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), new SlotPresentation("Explorer", "humanoid-1"));
        CampaignRoster imported = importedLibrary.importCampaign(exported, compatibility());
        assertEquals(name, imported.getCampaignName());
        assertEquals(name, importedLibrary.list().get(0).getCampaignName());
        assertEquals(name, importedStore.campaignSaves().load(campaignId, compatibility()).getCampaignName());
        assertEquals(campaignId, imported.getCampaignId());
        assertEquals(identity('1'), imported.getSlot(1).getLauncherIdentity());
        assertEquals(token, imported.getSlot(1).getReconnectToken());
        assertEquals(4, imported.getStartingLives());
        try { flow.open(() -> DirectConnectHost.start(0, compatibility(), imported, importedStore));
            DirectConnectHost resumed = (DirectConnectHost)flow.getPeer();
            assertEquals(4, resumed.getStartingLives());
            assertTrue(resumed.isStartingLivesLocked());
        }
        finally { flow.leave(); }
    }

    private static int freePort() throws Exception {
        return SocketTestPorts.availableTcpAndUdp();
    }

    private static void awaitPhase(DirectConnectPeer peer, DirectConnectPhase phase) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            if(peer.getStatus().getPhase() == phase) return;
            Thread.sleep(10);
        }
        assertEquals(phase, peer.getStatus().getPhase());
    }

    private static LauncherIdentity identity(char digit) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', digit));
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("setup-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
