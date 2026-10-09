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

import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.readyClient;
import static com.interrupt.dungeoneer.multiplayer.network.ReadyTestSupport.startReadySession;

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

    @Test public void terminalCardsRemainReadableAndCannotPrepareOrdinaryResume() throws Exception {
        for(CampaignSave.Outcome outcome : new CampaignSave.Outcome[] { CampaignSave.Outcome.COMPLETED, CampaignSave.Outcome.DEFEATED }) {
            File root = temporary.newFolder(outcome.name());
            CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
            CampaignRoster roster = saveCampaign(store, "Friday friends", 4, 5);
            CampaignSave saved = store.campaignSaves().load(roster.getCampaignId(), compatibility());
            CampaignSave terminal = new CampaignSave(saved.getCompatibility(), saved.getCampaignId(), saved.getCapacity(),
                    saved.getStartingLives(), outcome, saved.getFloorId(), saved.getFloorSeed(), saved.getFloorFingerprint(),
                    saved.getNativeWorldGeneration(), saved.getSlots(), saved.getParticipants(), saved.getPhysicalItems(),
                    saved.getCombat(), saved.getDoors(), saved.getBreakables(), saved.getActorEffects(), saved.getMonsterSpawns(),
                    saved.getConsumedMonsterSpawners(), saved.getNativeFloor()).withCampaignName(saved.getCampaignName());
            store.campaignSaves().save(terminal);
            CampaignLibrary library = new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Default", "humanoid-4"));
            CampaignLibrary.Entry entry = library.list().get(0);
            assertTrue(entry.isArchived());
            assertFalse(entry.needsRecovery());
            assertEquals("Friday friends", entry.getCampaignName());
            assertEquals(saved.getFloorId(), entry.getFloorId());
            assertEquals(2, entry.getClaimedSlots());
            assertTrue(entry.getLastSavedTime() > 0L);
            assertTrue(library.describeArchive(roster.getCampaignId(), compatibility()).contains(outcome.name()));
            DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
            try {
                flow.prepareCampaign(library, roster.getCampaignId(), freePort());
                fail("Terminal Archive cannot offer ordinary Resume setup.");
            }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("read-only")); }
            assertNull(flow.getPeer());
            // Terminal save itself is authoritative even before archive/latch write completes.
            java.nio.file.Path folder = new File(root, roster.getCampaignId()).toPath();
            java.nio.file.Path primary = folder.resolve("campaign.save");
            java.nio.file.Files.move(folder.resolve("campaign.archive"), primary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.delete(folder.resolve("terminal.outcome"));
            byte[] terminalBytes = java.nio.file.Files.readAllBytes(primary);
            CampaignLibrary.Entry unlatched = library.list().get(0);
            assertTrue("Saved terminal outcome must classify Archive without marker", unlatched.isArchived());
            assertFalse(unlatched.needsRecovery());
            try {
                flow.prepareCampaign(library, roster.getCampaignId(), freePort());
                fail("Saved terminal outcome cannot prepare ordinary Resume.");
            }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("read-only")); }
            assertNull(flow.getPeer());
            assertArrayEquals(terminalBytes, java.nio.file.Files.readAllBytes(primary));
            assertFalse(java.nio.file.Files.exists(folder.resolve("campaign.archive")));
            assertFalse(java.nio.file.Files.exists(folder.resolve("terminal.outcome")));
        }
    }

    @Test public void corruptPrimaryRemainsVisibleForRecoveryWithoutHidingOtherCampaigns() throws Exception {
        File root = temporary.newFolder("recover-summary");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster original = saveCampaign(store, "Friday friends", 4, 5);
        CampaignSave saved = store.campaignSaves().load(original.getCampaignId(), compatibility());
        store.campaignSaves().beginSession(original.getCampaignId());
        store.campaignSaves().snapshot(saved);
        store.campaignSaves().endSession(original.getCampaignId(), false);
        File saveFile = new File(new File(root, original.getCampaignId()), "campaign.save");
        java.nio.file.Files.write(saveFile.toPath(), new byte[] { 0 });
        CampaignLibrary library = new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Default", "humanoid-4"));
        library.create("other", 2);
        assertEquals(2, library.list().size());
        CampaignLibrary.Entry broken = library.list().stream().filter(entry -> entry.getCampaignId().equals(original.getCampaignId())).findFirst().get();
        assertEquals("Friday friends", broken.getCampaignName());
        assertTrue(broken.needsRecovery());
        assertFalse(broken.isArchived());
        assertNull(broken.getFloorId());
        assertEquals(0L, broken.getLastSavedTime());
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.prepareCampaign(library, original.getCampaignId(), freePort());
            fail("Unclean Campaign cannot bypass recovery through ordinary Resume.");
        }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("Unclean")); }
        assertNull(flow.getPeer());
        CampaignRoster recovered = library.recover(original.getCampaignId(), compatibility());
        assertEquals(original.getSlot(2).getReconnectToken(), recovered.getSlot(2).getReconnectToken());
        DirectConnectSessionFlow.HostSetup setup = flow.prepareCampaign(library, original.getCampaignId(), freePort());
        assertEquals("Friday friends", setup.getCampaignName());
        assertEquals(4, setup.getCapacity());
        assertEquals(5, setup.getStartingLives());
        CampaignLibrary.Entry restored = library.list().stream().filter(entry -> entry.getCampaignId().equals(original.getCampaignId())).findFirst().get();
        assertFalse(restored.needsRecovery());
        assertEquals(saved.getFloorId(), restored.getFloorId());
        assertTrue(restored.getLastSavedTime() > 0L);
        assertNull(flow.getPeer());
    }

    @Test public void savedSetupRetainsRulesOnFailedBindAndAcceptsOnlyExplicitAvailableHostEdits() throws Exception {
        File root = temporary.newFolder("saved-bind");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster original = saveCampaign(store, "Friday friends", 4, 5);
        SlotPresentation defaults = new SlotPresentation("Different default", "humanoid-4");
        LauncherPresentationStore.save(defaults);
        CampaignLibrary library = new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(), identity('1'), defaults);
        java.util.concurrent.atomic.AtomicInteger releases = new java.util.concurrent.atomic.AtomicInteger();
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow(releases::incrementAndGet);
        try(java.net.DatagramSocket occupied = SocketTestPorts.occupyUdpWithAvailableTcp()) {
            DirectConnectSessionFlow.HostSetup setup = flow.prepareCampaign(library, original.getCampaignId(), occupied.getLocalPort());
            DirectConnectSessionFlow.HostSetup edited = setup.withHostOptions("Scout", "humanoid-4", setup.getPort());
            try {
                flow.openSavedCampaign(edited, library, store,
                        (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
                fail("Occupied UDP must keep saved setup open.");
            }
            catch(RuntimeException expected) { assertTrue(expected.getMessage().contains("UDP listener")); }
            assertNull(flow.getPeer());
            assertEquals(0, releases.get());
            assertEquals(defaults, LauncherPresentationStore.load());
            assertEquals(original.getSlot(1).getPresentation(), store.load(original.getCampaignId(), AvatarCatalog.ownedV108Humanoids()).getSlot(1).getPresentation());
            assertEquals(1, store.listCampaigns().size());
            // Offline reservations still constrain explicit Host choices.
            int retryPort = freePort();
            assertInvalid(() -> flow.openSavedCampaign(setup.withHostOptions("Friend", "humanoid-4", retryPort), library, store,
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store)));
            assertInvalid(() -> flow.openSavedCampaign(setup.withHostOptions("Scout", "humanoid-2", retryPort), library, store,
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store)));
            flow.openSavedCampaign(edited.withHostOptions("Scout", "humanoid-4", freePort()), library, store,
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            assertEquals(DirectConnectPhase.LISTENING, host.getStatus().getPhase());
            assertEquals("Friday friends", host.getRoster().getCampaignName());
            assertEquals(4, host.getRoster().getCapacity());
            assertEquals(5, host.getStartingLives());
            assertEquals(new SlotPresentation("Scout", "humanoid-4"), host.getRoster().getSlot(1).getPresentation());
            assertEquals(original.getSlot(2).getReconnectToken(), host.getRoster().getSlot(2).getReconnectToken());
            assertEquals(original.getSlot(2).getPresentation(), host.getRoster().getSlot(2).getPresentation());
            assertEquals(host.getRoster().getSlot(1).getPresentation(), LauncherPresentationStore.load());
            startReadySession(host);
            flow.leave();
            assertEquals(new SlotPresentation("Scout", "humanoid-4"), library.resume(original.getCampaignId()).getSlot(1).getPresentation());
        }
        finally { flow.leave(); }
    }

    @Test public void savedSetupLocksRulesAndHostOnlyResumePreservesAbsentFriendForLiveReturn() throws Exception {
        File root = temporary.newFolder("subset-setup");
        CampaignRosterStore firstStore = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster original = saveCampaign(firstStore, "Friday friends", 4, 5, true);
        CampaignSave before = firstStore.campaignSaves().load(original.getCampaignId(), compatibility());
        assertEquals(4, before.getParticipant(2).getParty().getRemainingLives());
        assertEquals(3, before.getParticipant(2).getParty().getHealth());
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        LauncherPresentationStore.save(new SlotPresentation("Different default", "humanoid-4"));
        CampaignLibrary library = new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), LauncherPresentationStore.load());
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectClient returning = null;
        try {
            DirectConnectSessionFlow.HostSetup setup = flow.prepareCampaign(library, original.getCampaignId(), freePort());
            assertNull(flow.getPeer());
            assertTrue(setup.isResume());
            assertEquals(original.getCampaignId(), setup.getCampaignId());
            assertEquals("Friday friends", setup.getCampaignName());
            assertEquals(4, setup.getCapacity());
            assertEquals(5, setup.getStartingLives());
            assertEquals(new SlotPresentation("Explorer", "humanoid-3"), setup.getPresentation());
            flow.openSavedCampaign(setup, library, store,
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            assertEquals(DirectConnectPhase.LISTENING, host.getStatus().getPhase());
            assertTrue(host.isResumedCampaign());
            assertTrue(host.isStartingLivesLocked());
            assertEquals(5, host.getStartingLives());
            assertEquals(2, host.getRoster().getSlots().size());
            assertEquals(original.getSlot(2).getReconnectToken(), host.getRoster().getSlot(2).getReconnectToken());
            startReadySession(host);
            assertEquals(DirectConnectPhase.READY, host.getStatus().getPhase());
            CampaignSave subset = host.persistCampaign();
            assertEquals(before.getParticipant(2).getParty().getHealth(), subset.getParticipant(2).getParty().getHealth());
            assertEquals(before.getParticipant(2).getParty().getRemainingLives(), subset.getParticipant(2).getParty().getRemainingLives());
            assertEquals(original.getSlot(2).getReconnectToken(), new ProfileReconnectTokenStore().load(original.getCampaignId()));
            ProfileReconnectTokenStore tokens = new ProfileReconnectTokenStore();
            tokens.save(original.getCampaignId(), new String(new char[64]).replace('\0', 'f'));
            DirectConnectClient unauthorized = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                    new SlotPresentation("Provisional", "humanoid-4"), 2, tokens, compatibility());
            try { awaitPhase(unauthorized, DirectConnectPhase.REJECTED); }
            finally { unauthorized.close(); }
            assertEquals(original.getSlot(2).getReconnectToken(), host.getRoster().getSlot(2).getReconnectToken());
            tokens.save(original.getCampaignId(), original.getSlot(2).getReconnectToken());
            returning = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                    new SlotPresentation("Provisional", "humanoid-4"), 0, tokens, compatibility());
            awaitPhase(returning, DirectConnectPhase.READY);
            assertEquals(original.getSlot(2).getPresentation(), host.getRoster().getSlot(2).getPresentation());
            assertEquals(original.getSlot(2).getLauncherIdentity(), host.getRoster().getSlot(2).getLauncherIdentity());
            assertEquals(original.getSlot(2).getReconnectToken(), host.getRoster().getSlot(2).getReconnectToken());
            CampaignSave joined = host.persistCampaign();
            assertEquals("Friday friends", joined.getCampaignName());
            assertEquals(before.getParticipant(2).getParty().getHealth(), joined.getParticipant(2).getParty().getHealth());
            assertEquals(before.getParticipant(2).getParty().getRemainingLives(), joined.getParticipant(2).getParty().getRemainingLives());
        }
        finally { if(returning != null) returning.close(); flow.leave(); }
    }

    @Test public void librarySummariesUseSavedFloorAndFileTimeWithoutInventingUnsavedData() throws Exception {
        File root = temporary.newFolder("summaries");
        CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
        CampaignRoster saved = saveCampaign(store, "Friday friends", 4, 5);
        File saveFile = new File(new File(root, saved.getCampaignId()), "campaign.save");
        long lastSaved = 1700000000000L;
        java.nio.file.Files.setLastModifiedTime(saveFile.toPath(), java.nio.file.attribute.FileTime.fromMillis(lastSaved));
        byte[] before = java.nio.file.Files.readAllBytes(saveFile.toPath());
        CampaignLibrary library = new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(),
                identity('1'), new SlotPresentation("Different default", "humanoid-4"));
        for(int index = 0; index < 16; index++) library.create(String.format("not-started-%02d", index), 2);

        java.util.List<CampaignLibrary.Entry> entries = library.list();
        assertEquals(17, entries.size());
        CampaignLibrary.Entry summary = entries.stream().filter(entry -> entry.hasSave()).findFirst().get();
        assertEquals("Friday friends", summary.getCampaignName());
        assertEquals("levels/test-level.bin", summary.getFloorId());
        assertEquals(lastSaved, summary.getLastSavedTime());
        assertEquals(4, summary.getCapacity());
        assertEquals(2, summary.getClaimedSlots());
        assertEquals(5, summary.getStartingLives());
        assertFalse(summary.needsRecovery());
        assertFalse(summary.isArchived());
        for(CampaignLibrary.Entry entry : entries) {
            if(entry.hasSave()) continue;
            assertEquals(entry.getCampaignId(), entry.getCampaignName());
            assertNull(entry.getFloorId());
            assertEquals(0L, entry.getLastSavedTime());
            assertEquals(1, entry.getClaimedSlots());
        }
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(saveFile.toPath()));
        assertEquals(lastSaved, saveFile.lastModified());
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
            readyClient(client);
            startReadySession(host);
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

    @Test public void repeatedColdSlotReturnKeepsEntityBaselineAheadOfLivePartyUpdates() throws Exception {
        // Fresh save/store and event loops preserve the original failed cold-return scenario.
        // Repetition covers scheduler assignments without production timing hooks.
        for(int attempt = 0; attempt < 128; attempt++) {
            File root = temporary.newFolder("ordered-return-" + attempt);
            CampaignRosterStore firstStore = new CampaignRosterStore(root, new SecureRandom());
            CampaignRoster original = saveCampaign(firstStore, "Returning friends", 4, 5, true);
            CampaignRosterStore store = new CampaignRosterStore(root, new SecureRandom());
            CampaignSave before = store.campaignSaves().load(original.getCampaignId(), compatibility());
            assertEquals("Fresh save Party health at return " + attempt, 3, before.getParticipant(2).getParty().getHealth());
            assertEquals("Fresh save combat health at return " + attempt, 3,
                    before.getCombat().getCombatant("participant:campaign-slot-2").getHealth());
            DirectConnectSessionFlow hostFlow = new DirectConnectSessionFlow();
            DirectConnectSessionFlow clientFlow = new DirectConnectSessionFlow();
            try {
                CampaignLibrary library = new CampaignLibrary(store, AvatarCatalog.ownedV108Humanoids(),
                        identity('1'), new SlotPresentation("Explorer", "humanoid-3"));
                DirectConnectSessionFlow.HostSetup setup = hostFlow.prepareCampaign(library, original.getCampaignId(), freePort());
                hostFlow.openSavedCampaign(setup, library, store,
                        (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
                DirectConnectHost host = (DirectConnectHost)hostFlow.getPeer();
                startReadySession(host);
                host.persistCampaign();
                ProfileReconnectTokenStore tokens = new ProfileReconnectTokenStore();
                tokens.save(original.getCampaignId(), identity('f').getValue());
                DirectConnectClient denied = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                        new SlotPresentation("Provisional", "humanoid-4"), 2, tokens, compatibility());
                try { awaitPhase(denied, DirectConnectPhase.REJECTED); }
                finally { denied.close(); }
                tokens.save(original.getCampaignId(), original.getSlot(2).getReconnectToken());
                clientFlow.openClient(new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", host.getBoundPort(),
                        "Provisional", "humanoid-4"), request -> DirectConnectClient.connect(request.getAddress(), request.getPort(),
                        identity('2'), request.getPresentation(), 0, tokens, compatibility()));
                DirectConnectClient client = (DirectConnectClient)clientFlow.getPeer();
                awaitPhase(client, DirectConnectPhase.READY);
                assertNotNull("Return " + attempt + " needs local entity before Party READY", client.getLocalMovementEntityId());
                assertEquals("Friend", client.getPartyStatus().getMember(2).getNickname());
                assertEquals("humanoid-2", client.getPartyStatus().getMember(2).getAvatarId());
                assertEquals("Return " + attempt + " Host health=" + host.getPartyStatus().getMember(2).getHealth()
                        + " Host combat=" + host.getCombatSnapshot().getCombatant("participant:campaign-slot-2").getHealth()
                        + " Host sequence=" + host.getCombatSnapshot().getSequence(),
                        3, client.getPartyStatus().getMember(2).getHealth());
                assertEquals(4, client.getPartyStatus().getMember(2).getRemainingLives());
            }
            finally { clientFlow.leave(); hostFlow.leave(); }
        }
    }

    private static int freePort() throws Exception {
        return SocketTestPorts.availableTcpAndUdp();
    }

    private CampaignRoster saveCampaign(CampaignRosterStore store, String name, int capacity, int lives) throws Exception {
        return saveCampaign(store, name, capacity, lives, false);
    }

    private CampaignRoster saveCampaign(CampaignRosterStore store, String name, int capacity, int lives, boolean wounded) throws Exception {
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        DirectConnectClient client = null;
        try {
            flow.openNewCampaign(new DirectConnectSessionFlow.HostSetup(name, capacity, lives,
                    "Explorer", "humanoid-3", freePort()), store, identity('1'),
                    (request, roster) -> DirectConnectHost.start(request.getPort(), compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity('2'),
                    new SlotPresentation("Friend", "humanoid-2"), 0, new ProfileReconnectTokenStore(), compatibility());
            readyClient(client);
            startReadySession(host);
            awaitPhase(client, DirectConnectPhase.READY);
            if(wounded) {
                com.interrupt.dungeoneer.multiplayer.participant.ParticipantId friend =
                        new com.interrupt.dungeoneer.multiplayer.participant.ParticipantId("campaign-slot-2");
                com.interrupt.dungeoneer.multiplayer.participant.ParticipantId owner =
                        new com.interrupt.dungeoneer.multiplayer.participant.ParticipantId("campaign-slot-1");
                // Real simultaneous Downing consumes one Life and respawns at half health.
                host.applyNativeParticipantDamage("test-trap", friend, 8, 0f, 0f, 0f);
                host.applyNativeParticipantDamage("test-trap", owner, 8, 0f, 0f, 0f);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
                while(System.nanoTime() < deadline) {
                    com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus member = host.getPartyStatus().getMembers().get(1);
                    if(member.getRemainingLives() == lives - 1 && member.getHealth() == 4) break;
                    Thread.sleep(10);
                }
                assertEquals(lives - 1, host.getPartyStatus().getMembers().get(1).getRemainingLives());
                assertEquals(4, host.getPartyStatus().getMembers().get(1).getHealth());
                host.applyNativeParticipantDamage("test-trap", friend, 1, 0f, 0f, 0f);

            }
            CampaignRoster roster = host.getRoster();
            flow.leave();
            return roster;
        }
        finally { if(client != null) client.close(); flow.leave(); }
    }

    private static void awaitPhase(DirectConnectPeer peer, DirectConnectPhase phase) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            if(peer.getStatus().getPhase() == phase) return;
            Thread.sleep(10);
        }
        assertEquals(peer.getStatus().getMessage(), phase, peer.getStatus().getPhase());
    }

    private static LauncherIdentity identity(char digit) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', digit));
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("setup-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
