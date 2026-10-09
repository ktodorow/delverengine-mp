package com.interrupt.dungeoneer.multiplayer.launcher;

import com.interrupt.dungeoneer.multiplayer.lobby.*;
import com.interrupt.dungeoneer.multiplayer.network.*;
import com.interrupt.dungeoneer.owned.MultiplayerProfile;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Launcher actions exercise real Host authority and TCP/UDP, never a substitute lobby. */
public class SharedNativeLobbyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File previousProfile;

    @Before public void profile() throws Exception {
        previousProfile = MultiplayerProfile.isInitialized() ? MultiplayerProfile.getRoot() : null;
        MultiplayerProfile.initialize(temporary.newFolder("profile"));
    }

    @After public void restoreProfile() {
        if(previousProfile != null) MultiplayerProfile.initialize(previousProfile);
    }

    @Test public void compatibleConnectAutomaticallyClaimsUnusedPregameCapacity() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("campaigns"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("friends", "Friday Delver", 4, 5,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.openClient(new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", host.getBoundPort(), "Friend", "humanoid-2"),
                    setup -> DirectConnectClient.connect(setup.getAddress(), setup.getPort(), identity('2'),
                            setup.getPresentation(), 0, new ProfileReconnectTokenStore(), compatibility()));
            DirectConnectPeer client = flow.getPeer();
            awaitPhase(client, DirectConnectPhase.LOBBY);
            assertEquals(2, host.getConnectedParticipantCount());
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), host.getRoster().getSlot(2).getPresentation());
            assertNotNull(new ProfileReconnectTokenStore().load("friends"));
            assertTrue(host.getPendingClaims().isEmpty());
            assertNull("Lobby admission cannot start gameplay", host.getLocalMovementEntityId());
        }
        finally { flow.leave(); host.close(); }
    }

    @Test public void hostAndClientShareAuthoritativeSettingsCardsAndEmptyCapacity() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("projection"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("friends", "Friday Delver", 4, 5,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            flow.openClient(new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", host.getBoundPort(), "Friend", "humanoid-2"),
                    setup -> DirectConnectClient.connect(setup.getAddress(), setup.getPort(), identity('2'),
                            setup.getPresentation(), 0, new ProfileReconnectTokenStore(), compatibility()));
            awaitPhase(flow.getPeer(), DirectConnectPhase.LOBBY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime() < deadline && (flow.getPeer().getLobbySnapshot() == null
                    || !flow.getPeer().getLobbySnapshot().equals(host.getLobbySnapshot())
                    || !flow.getPeer().getLobbySnapshot().getSlot(2).isSynchronized())) Thread.sleep(10L);
            LobbySnapshot lobby = flow.getPeer().getLobbySnapshot();
            assertNotNull("Admitted Client receives shared lobby", lobby);
            assertEquals(host.getLobbySnapshot(), lobby);
            assertEquals("Friday Delver", lobby.getCampaignName());
            assertEquals(4, lobby.getCapacity());
            assertEquals(5, lobby.getStartingLives());
            assertEquals(1, lobby.getHostSlot());
            assertEquals(2, lobby.getConnectedCount());
            assertEquals(2, lobby.getClaimedCount());
            assertEquals(2, lobby.getEmptyCount());
            assertEquals(0, lobby.getReservedCount());
            assertEquals(new SlotPresentation("Host", "humanoid-1"), lobby.getSlot(1).getPresentation());
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), lobby.getSlot(2).getPresentation());
            assertTrue(lobby.getSlot(2).isConnected());
            assertTrue(lobby.getSlot(2).isAuthenticated());
            assertTrue(lobby.getSlot(2).isSynchronized());
            assertFalse(lobby.getSlot(3).isClaimed());
            assertFalse(lobby.getSlot(3).isConnected());
            assertNull(lobby.getSlot(3).getPresentation());
        }
        finally { flow.leave(); host.close(); }
    }

    private static LauncherIdentity identity(char value) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', value));
    }

    @Test public void fourCardsFollowLeaveKickAndAuthenticatedReservedReturn() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("four"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("friends", "Friday Delver", 4, 5,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        store.save(roster);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        ReconnectTokenStore returningTokens = memoryTokens();
        DirectConnectClient second = null, third = null, fourth = null, returning = null;
        try {
            second = friend(host, '2', "Two", 0, "humanoid-2", returningTokens);
            awaitPhase(second, DirectConnectPhase.LOBBY);
            third = friend(host, '3', "Three", 0, "humanoid-3", memoryTokens());
            awaitPhase(third, DirectConnectPhase.LOBBY);
            fourth = friend(host, '4', "Four", 0, "humanoid-4", memoryTokens());
            awaitPhase(fourth, DirectConnectPhase.LOBBY);
            awaitProjection(host, 4, second, third, fourth);
            assertEquals(4, host.getLobbySnapshot().getClaimedCount());
            assertEquals(0, host.getLobbySnapshot().getEmptyCount());
            String originalToken = returningTokens.load("friends");
            second.close();
            awaitProjection(host, 3, third, fourth);
            assertEquals(1, host.getLobbySnapshot().getReservedCount());
            assertEquals(new SlotPresentation("Two", "humanoid-2"), third.getLobbySnapshot().getSlot(2).getPresentation());
            returning = friend(host, '2', "hOsT", 2, "humanoid-1", returningTokens);
            awaitPhase(returning, DirectConnectPhase.LOBBY);
            awaitProjection(host, 4, returning, third, fourth);
            assertEquals(originalToken, returningTokens.load("friends"));
            assertEquals(new SlotPresentation("Two", "humanoid-2"), returning.getLobbySnapshot().getSlot(2).getPresentation());
            assertTrue(host.kick(identity('2').getValue()));
            awaitProjection(host, 3, third, fourth);
            assertEquals(identity('2'), host.getRoster().getSlot(2).getLauncherIdentity());
            assertEquals(originalToken, host.getRoster().getSlot(2).getReconnectToken());
            assertFalse(host.getLobbySnapshot().getSlot(2).isConnected());
            assertFalse(host.getLobbySnapshot().getSlot(2).isAuthenticated());
            assertFalse(host.getLobbySnapshot().getSlot(2).isSynchronized());
        }
        finally {
            if(second != null) second.close(); if(third != null) third.close();
            if(fourth != null) fourth.close(); if(returning != null) returning.close(); host.close();
        }
    }

    @Test public void automaticAdmissionCannotReassignReservedSlotOrBypassPrivateToken() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("protection"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("friends", "Friday Delver", 4, 5,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        CampaignSlot reserved = roster.approve(new SlotClaimRequest(identity('2'), new SlotPresentation("Two", "humanoid-2"), 2, null), new SecureRandom()).getSlot();
        store.save(roster);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectClient impostor = null, outsider = null, accepted = null;
        try {
            impostor = friend(host, '2', "Two", 2, "humanoid-2", memoryTokens());
            awaitPhase(impostor, DirectConnectPhase.REJECTED);
            assertTrue(impostor.getStatus().getMessage().contains("RECONNECT_DENIED"));
            outsider = friend(host, '3', "Three", 2, "humanoid-3", memoryTokens());
            awaitPhase(outsider, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(outsider.getStatus().getMessage().contains("trusted Host relink"));
            assertEquals(identity('2'), host.getRoster().getSlot(2).getLauncherIdentity());
            assertEquals(reserved.getReconnectToken(), host.getRoster().getSlot(2).getReconnectToken());
            assertTrue(host.decline(identity('3').getValue()));
            awaitPhase(outsider, DirectConnectPhase.REJECTED);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while(!host.getPendingClaims().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10L);
            assertTrue(host.getPendingClaims().isEmpty());
            accepted = friend(host, '3', "Three", 0, "humanoid-3", memoryTokens());
            awaitPhase(accepted, DirectConnectPhase.LOBBY);
            awaitProjection(host, 2, accepted);
            assertEquals(3, accepted.getLocalCampaignSlot());
            assertEquals(1, accepted.getLobbySnapshot().getReservedCount());
            assertEquals(1, accepted.getLobbySnapshot().getEmptyCount());
        }
        finally {
            if(impostor != null) impostor.close(); if(outsider != null) outsider.close();
            if(accepted != null) accepted.close(); host.close();
        }
    }

    @Test public void lostIdentityRequiresExplicitTrustedRelinkAndRotatesOnlyReservedOwner() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("relink"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("friends", "Friday Delver", 3, 5,
                AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        CampaignSlot reserved = roster.approve(new SlotClaimRequest(identity('2'), new SlotPresentation("Two", "humanoid-2"), 2, null), new SecureRandom()).getSlot();
        store.save(roster);
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectClient replacement = friend(host, '3', "Recovered", 2, "humanoid-3", memoryTokens());
        try {
            awaitPhase(replacement, DirectConnectPhase.AWAITING_APPROVAL);
            assertEquals(identity('2'), host.getRoster().getSlot(2).getLauncherIdentity());
            assertEquals(1, host.getLobbySnapshot().getReservedCount());
            assertTrue(host.relinkTrustedParticipant(identity('3').getValue()));
            awaitPhase(replacement, DirectConnectPhase.LOBBY);
            awaitProjection(host, 2, replacement);
            assertEquals(2, replacement.getLocalCampaignSlot());
            assertEquals(identity('3'), host.getRoster().getSlot(2).getLauncherIdentity());
            assertNotEquals(reserved.getReconnectToken(), host.getRoster().getSlot(2).getReconnectToken());
            assertEquals(identity('1'), host.getRoster().getSlot(1).getLauncherIdentity());
            assertEquals(1, host.getLobbySnapshot().getEmptyCount());
        }
        finally { replacement.close(); host.close(); }
    }

    private static ReconnectTokenStore memoryTokens() {
        return new ReconnectTokenStore() {
            private volatile String token;
            public String load(String campaign) { return token; }
            public void save(String campaign, String value) { token = value; }
        };
    }

    private static DirectConnectClient friend(DirectConnectHost host, char identity, String nickname, int slot, String avatar, ReconnectTokenStore tokens) {
        return DirectConnectClient.connect("127.0.0.1", host.getBoundPort(), identity(identity), new SlotPresentation(nickname, avatar), slot, tokens, compatibility());
    }

    private static void awaitProjection(DirectConnectHost host, int connected, DirectConnectPeer... clients) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) {
            LobbySnapshot lobby = host.getLobbySnapshot();
            boolean matches = lobby.getConnectedCount() == connected;
            for(LobbySnapshot.Slot slot : lobby.getSlots()) if(slot.isConnected() && !slot.isSynchronized()) matches = false;
            for(DirectConnectPeer client : clients) if(!lobby.equals(client.getLobbySnapshot())) matches = false;
            if(matches) return;
            Thread.sleep(10L);
        }
        assertEquals(connected, host.getLobbySnapshot().getConnectedCount());
        for(DirectConnectPeer client : clients) assertEquals(host.getLobbySnapshot(), client.getLobbySnapshot());
        for(LobbySnapshot.Slot slot : host.getLobbySnapshot().getSlots()) if(slot.isConnected()) assertTrue(slot.isSynchronized());
    }

    @Test public void validPregameReturnRestoresReservedPresentationBeforeProvisionalConflictValidation() throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder("reserved"), new SecureRandom());
        CampaignRoster roster = CampaignRoster.createNamed("friends", "Friday Delver", 4, 5,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
        CampaignSlot friend = roster.approve(new SlotClaimRequest(identity('2'),
                new SlotPresentation("Friend", "humanoid-2"), 2, null), new SecureRandom()).getSlot();
        store.save(roster);
        ReconnectTokenStore tokens = new ReconnectTokenStore() {
            private String token = friend.getReconnectToken();
            public String load(String campaign) { return token; }
            public void save(String campaign, String value) { token = value; }
        };
        DirectConnectHost host = DirectConnectHost.start(0, compatibility(), roster, store);
        DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        try {
            assertEquals(1, host.getLobbySnapshot().getReservedCount());
            assertEquals(2, host.getLobbySnapshot().getEmptyCount());
            flow.openClient(new DirectConnectSessionFlow.ConnectSetup("127.0.0.1", host.getBoundPort(), "hOsT", "humanoid-1"),
                    setup -> DirectConnectClient.connect(setup.getAddress(), setup.getPort(), identity('2'),
                            setup.getPresentation(), 0, tokens, compatibility()));
            awaitPhase(flow.getPeer(), DirectConnectPhase.LOBBY);
            assertEquals(2, flow.getPeer().getLocalCampaignSlot());
            assertEquals(new SlotPresentation("Friend", "humanoid-2"), host.getRoster().getSlot(2).getPresentation());
            assertEquals(friend.getReconnectToken(), host.getRoster().getSlot(2).getReconnectToken());
        }
        finally { flow.leave(); host.close(); }
    }

    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("lobby-floor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
