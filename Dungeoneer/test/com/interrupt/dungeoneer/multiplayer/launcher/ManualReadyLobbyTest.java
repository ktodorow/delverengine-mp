package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.lobby.*;
import com.interrupt.dungeoneer.multiplayer.network.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Player consent at production launcher action/session boundary with actual TCP/UDP. */
public class ManualReadyLobbyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void synchronizedParticipantsCannotStartWithoutExplicitPlayerConsent() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("campaigns"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("ready", "Friday Delver", 4, 3,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        DirectConnectSessionFlow hostFlow = new DirectConnectSessionFlow();
        DirectConnectSessionFlow clientFlow = new DirectConnectSessionFlow();
        try {
            hostFlow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)hostFlow.getPeer();
            clientFlow.open(() -> friend(host, '2', "Friend", "humanoid-2", memoryTokens()));
            awaitLobby(host, clientFlow.getPeer(), 2);
            assertFalse("Network-ready Participants have not given player consent", host.canStartSession());
            try { host.startSession(); fail("Start must reject missing consent"); }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("Ready")); }
            assertNull(host.getLocalMovementEntityId());
            assertEquals(DirectConnectPhase.LOBBY, clientFlow.getPeer().getStatus().getPhase());
        }
        finally { clientFlow.leave(); hostFlow.leave(); }
    }

    @Test public void ownReadyActionsPublishConsentButOnlySeparateHostStartEntersGameplay() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("consent"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("ready", "Friday Delver", 4, 3,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        DirectConnectSessionFlow hostFlow = new DirectConnectSessionFlow(), clientFlow = new DirectConnectSessionFlow();
        try {
            hostFlow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)hostFlow.getPeer();
            clientFlow.open(() -> friend(host, '2', "Friend", "humanoid-2", memoryTokens()));
            DirectConnectPeer client = clientFlow.getPeer();
            awaitLobby(host, client, 2);
            assertFalse(host.getLobbySnapshot().getSlot(1).isPlayerReady());
            assertFalse(client.getLobbySnapshot().getSlot(2).isPlayerReady());
            assertTrue(host.canSetPlayerReady());
            assertTrue(client.canSetPlayerReady());
            assertTrue(clientFlow.setPlayerReady(client, true));
            awaitConsent(host, client, false, true);
            assertFalse(host.canStartSession());
            assertTrue(hostFlow.setPlayerReady(host, true));
            awaitConsent(host, client, true, true);
            assertTrue(host.canStartSession());
            assertNull("Final Ready cannot automatically start", host.getLocalMovementEntityId());
            assertEquals(DirectConnectPhase.LOBBY, client.getStatus().getPhase());
            assertTrue(clientFlow.setPlayerReady(client, false));
            awaitConsent(host, client, true, false);
            assertFalse(host.canStartSession());
            assertTrue(clientFlow.setPlayerReady(client, true));
            awaitConsent(host, client, true, true);
            assertFalse("Client cannot request Host Start", clientFlow.startCampaign(client));
            assertTrue(hostFlow.startCampaign(host));
            awaitPhase(client, DirectConnectPhase.READY);
            assertEquals(2, host.getMovementEntities().size());
            assertFalse(host.canSetPlayerReady());
            assertFalse(client.canSetPlayerReady());
        }
        finally { clientFlow.leave(); hostFlow.leave(); }
    }

    @Test public void authoritativePresentationEditsClearOnlyEditorsConsentAndRejectedEditsRetainIt() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("edits"), new SecureRandom());
        SlotPresentation original = new SlotPresentation("Host", "humanoid-1");
        CampaignRoster roster = CampaignRoster.createNamed("ready", "Friday Delver", 4, 3,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), original, new SecureRandom());
        store.save(roster);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectClient client = friend(host, '2', "Friend", "humanoid-2", memoryTokens());
        try {
            awaitLobby(host, client, 2);
            host.setPlayerReady(true); client.setPlayerReady(true);
            awaitConsent(host, client, true, true);
            try { roster.updateHostPresentation(identity('1'), new SlotPresentation("Friend", "humanoid-3")); fail("Conflict must reject"); }
            catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Nickname")); }
            assertEquals(original, host.getLobbySnapshot().getSlot(1).getPresentation());
            assertTrue(host.canStartSession());
            SlotPresentation edited = new SlotPresentation("Changed Host", "humanoid-3");
            roster.updateHostPresentation(identity('1'), edited);
            assertFalse("Accepted authoritative edit invalidates editor consent", host.getLobbySnapshot().getSlot(1).isPlayerReady());
            awaitConsent(host, client, false, true);
            assertEquals(edited, client.getLobbySnapshot().getSlot(1).getPresentation());
            assertFalse(host.canStartSession());
            host.setPlayerReady(true); awaitConsent(host, client, true, true);
            roster.updateHostPresentation(identity('1'), edited);
            assertTrue("No presentation change keeps consent", host.canStartSession());
            roster.updateHostPresentation(identity('1'), original);
            roster.updateHostPresentation(identity('1'), edited);
            assertFalse("Restoring previous choice cannot restore old consent", host.canStartSession());
            awaitConsent(host, client, false, true);
        }
        finally { client.close(); host.close(); }
    }

    @Test public void newHostOnlyCannotStartButSavedHostOnlyResumeExemptsOfflineSlotAndClearsRestartConsent() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("resume"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("ready", "Friday Delver", 2, 3,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        ReconnectTokenStore tokens = memoryTokens();
        DirectConnectSessionFlow hostFlow = new DirectConnectSessionFlow(), clientFlow = new DirectConnectSessionFlow();
        try {
            hostFlow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            DirectConnectHost host = (DirectConnectHost)hostFlow.getPeer();
            hostFlow.setPlayerReady(host, true);
            assertFalse("New campaign still needs second connected player", host.canStartSession());
            clientFlow.open(() -> friend(host, '2', "Friend", "humanoid-2", tokens));
            DirectConnectPeer first = clientFlow.getPeer();
            awaitLobby(host, first, 2);
            clientFlow.setPlayerReady(first, true); awaitConsent(host, first, true, true);
            hostFlow.startCampaign(host); awaitPhase(first, DirectConnectPhase.READY);
            int retainedLives = host.getPartyStatus().getMember(2).getRemainingLives();
            hostFlow.leave(); clientFlow.leave();
            hostFlow.open(() -> DirectConnectHost.start(0, compatibility(), store.load("ready", AvatarCatalog.ownedV108Humanoids()), store));
            DirectConnectHost resumed = (DirectConnectHost)hostFlow.getPeer();
            assertTrue(resumed.isResumedCampaign());
            assertFalse(resumed.getLobbySnapshot().getSlot(1).isPlayerReady());
            assertFalse(resumed.getLobbySnapshot().getSlot(2).isPlayerReady());
            assertEquals(1, resumed.getLobbySnapshot().getReservedCount());
            assertFalse(resumed.canStartSession());
            hostFlow.setPlayerReady(resumed, true);
            assertTrue("Offline reserved friend cannot block saved Host-only resume", resumed.canStartSession());
            hostFlow.startCampaign(resumed);
            clientFlow.open(() -> friend(resumed, '2', "Provisional", "humanoid-3", tokens));
            DirectConnectPeer returning = clientFlow.getPeer();
            awaitPhase(returning, DirectConnectPhase.READY);
            assertFalse("Active return never requires pregame consent", returning.canSetPlayerReady());
            assertEquals(retainedLives, returning.getPartyStatus().getMember(2).getRemainingLives());
            assertEquals(2, returning.getLocalCampaignSlot());
        }
        finally { clientFlow.leave(); hostFlow.leave(); }
    }

    @Test public void pregameReconnectClearsOnlyReturningConsentAndStaleFlowCannotTargetReplacement() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("reconnect"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("ready", "Friday Delver", 3, 3,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        ReconnectTokenStore tokens = memoryTokens();
        DirectConnectClient third = null;
        try {
            flow.open(() -> friend(host, '2', "Friend", "humanoid-2", tokens));
            DirectConnectPeer previous = flow.getPeer();
            awaitLobby(host, previous, 2);
            third = friend(host, '3', "Three", "humanoid-3", memoryTokens());
            awaitLobby(host, previous, 3); awaitLobby(host, third, 3);
            host.setPlayerReady(true); flow.setPlayerReady(previous, true); third.setPlayerReady(true);
            awaitConsent(host, previous, true, true);
            awaitReadySlot(host, 3, true);
            assertTrue(host.canStartSession());
            flow.leave(); awaitConnected(host, 2);
            assertTrue(host.getLobbySnapshot().getSlot(1).isPlayerReady());
            assertTrue(host.getLobbySnapshot().getSlot(3).isPlayerReady());
            flow.open(() -> friend(host, '2', "Changed default", "humanoid-4", tokens));
            DirectConnectPeer returning = flow.getPeer();
            awaitLobby(host, returning, 3);
            assertFalse(flow.setPlayerReady(previous, true));
            assertFalse(flow.startCampaign(previous));
            assertFalse(host.getLobbySnapshot().getSlot(2).isPlayerReady());
            assertTrue(host.getLobbySnapshot().getSlot(1).isPlayerReady());
            assertTrue(host.getLobbySnapshot().getSlot(3).isPlayerReady());
            assertFalse(host.canStartSession());
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), returning.getLobbySnapshot().getSlot(2).getPresentation());
            flow.setPlayerReady(returning, true); awaitReadySlot(host, 2, true);
            assertTrue(host.canStartSession());
        }
        finally { flow.leave(); if(third != null) third.close(); host.close(); }
    }

    @Test public void failedPregameHostCannotResumeWithPreviouslyGivenConsent() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("failed-ready"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("ready", "Friday Delver", 2, 3,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        DirectConnectHost first = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectClient client = friend(first, '2', "Friend", "humanoid-2", memoryTokens());
        try {
            awaitLobby(first, client, 2);
            first.setPlayerReady(true); client.setPlayerReady(true);
            awaitConsent(first, client, true, true);
            first.startSession(); awaitPhase(client, DirectConnectPhase.READY);
            first.saveAndQuit();
        }
        finally { client.close(); first.close(); }
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.open(() -> DirectConnectHost.start(0, compatibility(), store.load("ready", AvatarCatalog.ownedV108Humanoids()), store));
            DirectConnectHost host = (DirectConnectHost)flow.getPeer();
            assertTrue(host.isResumedCampaign());
            assertTrue(flow.setPlayerReady(host, true));
            assertTrue(host.canStartSession());
            host.failNativePresentation("Required native asset unavailable.");
            assertEquals(DirectConnectPhase.FAILED, host.getStatus().getPhase());
            assertFalse(host.canSetPlayerReady());
            assertFalse("Failed Host cannot use retained consent to enter gameplay", host.canStartSession());
            try { flow.startCampaign(host); fail("Failed Host must reject Start"); }
            catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("Ready")); }
            assertNull(host.getLocalMovementEntityId());
            assertEquals(DirectConnectPhase.FAILED, host.getStatus().getPhase());
        }
        finally { flow.leave(); }
    }

    private static void awaitConnected(DirectConnectHost host, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(host.getLobbySnapshot().getConnectedCount() != count && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(count, host.getLobbySnapshot().getConnectedCount());
    }

    private static void awaitReadySlot(DirectConnectHost host, int slot, boolean ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(host.getLobbySnapshot().getSlot(slot).isPlayerReady() != ready && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(ready, host.getLobbySnapshot().getSlot(slot).isPlayerReady());
    }

    private static void awaitConsent(DirectConnectHost host, DirectConnectPeer client, boolean hostReady, boolean clientReady) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            LobbySnapshot lobby = host.getLobbySnapshot();
            if(lobby.getSlot(1).isPlayerReady() == hostReady && lobby.getSlot(2).isPlayerReady() == clientReady
                    && lobby.equals(client.getLobbySnapshot())) return;
            Thread.sleep(10L);
        }
        fail("Expected authoritative consent Host=" + hostReady + ", Friend=" + clientReady);
    }

    private static void awaitPhase(DirectConnectPeer peer, DirectConnectPhase phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            if(peer.getStatus().getPhase() == phase) return;
            Thread.sleep(10L);
        }
        fail("Expected " + phase + ", received " + peer.getStatus().getPhase() + ": " + peer.getStatus().getMessage());
    }

    private static LauncherIdentity identity(char value) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', value));
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("ready-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private static void awaitLobby(DirectConnectHost host, DirectConnectPeer client, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            LobbySnapshot lobby = host.getLobbySnapshot();
            boolean synchronizedState = lobby.getConnectedCount() == count;
            for(LobbySnapshot.Slot slot : lobby.getSlots())
                if(slot.isConnected() && !slot.isSynchronized()) synchronizedState = false;
            if(synchronizedState && lobby.equals(client.getLobbySnapshot())
                    && client.getStatus().getPhase() == DirectConnectPhase.LOBBY) return;
            Thread.sleep(10L);
        }
        fail("Expected synchronized shared lobby; Host=" + host.getStatus().getPhase()
                + ", Client=" + client.getStatus().getPhase());
    }
}
