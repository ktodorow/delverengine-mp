package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.combat.AuthoritativeCombatEncounter;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerRejected;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DirectConnectIntegrationTest {
    private static final long TIMEOUT_MILLIS = 8000L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int campaignStoreCounter;

    @Test
    public void refusedConnectionExplainsHowToStartHost() throws Exception {
        ServerSocket unusedPort = new ServerSocket(0);
        int port = unusedPort.getLocalPort();
        unusedPort.close();

        DirectConnectClient client = client(port, '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(),
                compatibility("unavailable-host"));
        try {
            awaitPhase(client, DirectConnectPhase.FAILED);
            assertEquals("No Direct Connect Host is listening at 127.0.0.1:" + port
                            + ". Start Host first and check that both ports match.",
                    client.getStatus().getMessage());
        }
        finally {
            client.close();
        }
    }

    @Test
    public void unknownIdentityWaitsForHostApprovalBeforeLobbyAndFloorEntry() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("same-floor");
        HostFixture fixture = host(compatibility, 2, "approval");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            awaitPendingCount(fixture.host, 1);
            assertEquals(1, fixture.roster.getSlots().size());

            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            awaitPhase(fixture.host, DirectConnectPhase.LOBBY);
            assertEquals(2, fixture.roster.getSlots().size());
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertTrue(tokens.load("approval") != null);

            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitPhase(fixture.host, DirectConnectPhase.READY);
            assertEquals(GameApplication.OPEN_SOURCE_TEST_LEVEL,
                    client.getStatus().getFloorId());
            assertEquals(2, client.getCampaignSlot());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void ownedCompatibilityReportsOwnedTutorialAsSharedFloor() throws Exception {
        DirectConnectCompatibility compatibility = new DirectConnectCompatibility(
                DirectConnectProtocol.BUILD_ID, "delver-owned-assets-v1",
                "0000000000000000000000000000000000000000000000000000000000000000");
        HostFixture fixture = host(compatibility, 2, "owned-floor");
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            awaitPendingCount(fixture.host, 1);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);

            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            assertEquals("owned-game-copy-tutorial", client.getStatus().getFloorId());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void capacityFourLobbyAdmitsThreeExplicitlyApprovedRemoteIdentities() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("four-party-floor");
        HostFixture fixture = host(compatibility, 4, "four-party");
        DirectConnectClient second = null;
        DirectConnectClient third = null;
        DirectConnectClient fourth = null;
        try {
            second = approveClient(fixture, compatibility, '2', "Two",
                    AvatarCatalog.HUMANOID_2);
            third = approveClient(fixture, compatibility, '3', "Three",
                    AvatarCatalog.HUMANOID_3);
            fourth = approveClient(fixture, compatibility, '4', "Four",
                    AvatarCatalog.HUMANOID_4);

            assertEquals(4, fixture.host.getConnectedParticipantCount());
            assertEquals(4, fixture.host.getUdpReadyParticipantCount());
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            awaitPhase(fourth, DirectConnectPhase.READY);
            assertEquals(4, fixture.roster.getSlots().size());
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertEquals(identity('3'), fixture.roster.getSlot(3).getLauncherIdentity());
            assertEquals(identity('4'), fixture.roster.getSlot(4).getLauncherIdentity());
        }
        finally {
            if(second != null) second.close();
            if(third != null) third.close();
            if(fourth != null) fourth.close();
            fixture.close();
        }
    }

    @Test
    public void activeFloorCarriesTickedInputsAndTwentyHertzAuthoritativeSnapshots()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("movement-floor");
        HostFixture fixture = host(compatibility, 2, "movement");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitMovementSnapshots(client, 3);
            assertEquals(new NetworkEntityId(1L), fixture.host.getLocalMovementEntityId());
            assertEquals(new NetworkEntityId(2L), client.getLocalMovementEntityId());
            assertEquals(2, client.getMovementEntities().size());

            List<MovementSnapshot> snapshotsBefore = client.getMovementSnapshots();
            MovementSnapshot before = snapshotsBefore.get(snapshotsBefore.size() - 1);
            float startingY = before.getEntity(client.getLocalMovementEntityId()).getY();
            client.submitMovementInput(new MovementInputFrame(1L, 1f, 0f, 0f, false));
            client.submitMovementInput(new MovementInputFrame(4L, 1f, 0f, 0f, false));
            fixture.host.submitMovementInput(new MovementInputFrame(
                    1L, 0f, 1f, 0f, false));

            awaitAcknowledgedInput(client, 4L);
            List<MovementSnapshot> snapshotsAfter = client.getMovementSnapshots();
            MovementSnapshot after = snapshotsAfter.get(snapshotsAfter.size() - 1);
            MovementEntityState local = after.getEntity(client.getLocalMovementEntityId());
            assertTrue(local.getY() > startingY);
            assertEquals(4L, local.getLastProcessedInputTick());
            assertTrue(after.getEntity(new NetworkEntityId(1L)).getX() > 16.5f);

            List<MovementSnapshot> snapshots = client.getMovementSnapshots();
            for(int i = 1; i < snapshots.size(); i++) {
                assertEquals(3L, snapshots.get(i).getHostTick()
                        - snapshots.get(i - 1).getHostTick());
            }
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void twoParticipantsObserveOneHostAuthoritativeCombatResult() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("combat-floor");
        HostFixture fixture = host(compatibility, 2, "combat");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitCombatSnapshot(client);
            assertEquals(24, combatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID));

            submitDirectedAtMonster(client, 1L, CombatAction.MELEE);
            awaitCombatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 20);
            awaitCombatPresentations(client, 1);
            assertEquals(20, combatHealth(fixture.host,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(1, fixture.host.getCombatPresentationEvents().size());
            assertEquals(CombatAction.MELEE,
                    client.getCombatPresentationEvents().get(0).getAction());

            submitDirectedAtMonster(fixture.host, 1L, CombatAction.SPELL);
            awaitCombatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 15);
            awaitCombatPresentations(client, 2);
            submitDirectedAtMonster(client, 1L, CombatAction.PROJECTILE);
            Thread.sleep(100L);
            assertEquals(15, combatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(15, combatHealth(fixture.host,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(2, client.getCombatPresentationEvents().size());
            assertEquals(2, fixture.host.getCombatPresentationEvents().size());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void twoClientsConvergeOnFriendlyFireBenefitsHazardsAndDeath() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("combat-policy-floor");
        HostFixture fixture = host(compatibility, 3, "combat-policy");
        DirectConnectClient second = approveClient(fixture, compatibility, '2', "Two",
                AvatarCatalog.HUMANOID_2);
        DirectConnectClient third = approveClient(fixture, compatibility, '3', "Three",
                AvatarCatalog.HUMANOID_3);
        String secondTarget = AuthoritativeCombatEncounter.participantTargetId(
                new ParticipantId("campaign-slot-2"));
        try {
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            awaitCombatSnapshot(second);
            awaitCombatSnapshot(third);
            awaitPartyHealth(second, 2, 8);
            awaitPartyHealth(third, 2, 8);

            second.submitCombatAction(1L, CombatAction.SELF_DAMAGE, secondTarget);
            awaitCombatHealth(third, secondTarget, 6);
            awaitPartyHealth(second, 2, 6);
            awaitPartyHealth(third, 2, 6);
            awaitPartyHealth(fixture.host, 2, 6);
            second.submitCombatAction(2L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 4);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);

            third.submitCombatAction(1L, CombatAction.BENEFICIAL_SPELL, secondTarget);
            awaitCombatHealth(second, secondTarget, 7);
            awaitCombatHealth(third, secondTarget, 7);
            awaitCombatHealth(fixture.host, secondTarget, 7);
            awaitPartyHealth(second, 2, 7);
            awaitPartyHealth(third, 2, 7);
            awaitPartyHealth(fixture.host, 2, 7);

            submitDirectedAtParticipant(third, 2L, CombatAction.MELEE, 2);
            awaitCombatPresentations(second, 4);
            awaitCombatPresentations(third, 4);
            assertEquals(7, combatHealth(second, secondTarget));
            assertEquals(7, combatHealth(third, secondTarget));
            assertEquals(7, combatHealth(fixture.host, secondTarget));

            second.submitCombatAction(3L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 5);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);
            second.submitCombatAction(3L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            second.submitCombatAction(4L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 3);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);
            second.submitCombatAction(5L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 1);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);
            second.submitCombatAction(6L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(second, secondTarget, 0);
            awaitCombatHealth(third, secondTarget, 0);
            awaitCombatHealth(fixture.host, secondTarget, 0);
            awaitPartyHealth(second, 2, 0);
            awaitPartyHealth(third, 2, 0);
            awaitPartyHealth(fixture.host, 2, 0);

            third.submitCombatAction(3L, CombatAction.BENEFICIAL_SPELL, secondTarget);
            submitDirectedAtMonster(third, 4L, CombatAction.MELEE);
            awaitCombatPresentations(second, 9);
            awaitCombatPresentations(third, 9);
            assertEquals(0, combatHealth(second, secondTarget));
            assertEquals(0, combatHealth(third, secondTarget));
            assertEquals(0, combatHealth(fixture.host, secondTarget));
        }
        finally {
            third.close();
            second.close();
            fixture.close();
        }
    }

    @Test
    public void directedClientAttackIsTracedAndReplicatedByHost() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("directed-combat-floor");
        HostFixture fixture = host(compatibility, 2, "directed-combat");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitCombatSnapshot(client);
            awaitMovementSnapshots(client, 1);

            List<MovementSnapshot> movementSnapshots = client.getMovementSnapshots();
            MovementEntityState source = movementSnapshots.get(movementSnapshots.size() - 1)
                    .getEntity(client.getLocalMovementEntityId());
            CombatSnapshot before = client.getCombatSnapshot();
            float aimX = before.getMonsterX() - source.getX();
            float aimY = before.getMonsterY() - source.getY();
            float aimZ = before.getMonsterZ() - (source.getZ() + 0.35f);
            float aimLength = (float)Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);

            client.submitCombatAction(1L, CombatAction.SPELL,
                    aimX / aimLength, aimY / aimLength, aimZ / aimLength);

            awaitCombatPresentations(client, 1);
            CombatPresentationEvent presentation =
                    client.getCombatPresentationEvents().get(0);
            assertEquals(CombatAction.SPELL, presentation.getAction());
            assertEquals("origin=" + presentation.getOriginX() + ","
                            + presentation.getOriginY() + "," + presentation.getOriginZ()
                            + " impact=" + presentation.getImpactX() + ","
                            + presentation.getImpactY() + "," + presentation.getImpactZ(),
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                    presentation.getTargetId());
            assertTrue(presentation.isStateChanged());
            awaitCombatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 19);
            assertEquals(19, combatHealth(fixture.host,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void reliablePartyStatusPreservesFrozenReconnectGrace() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("party-status-floor");
        HostFixture fixture = host(compatibility, 3, "party-status");
        DirectConnectClient second = approveClient(fixture, compatibility, '2', "Two",
                AvatarCatalog.HUMANOID_2);
        DirectConnectClient third = approveClient(fixture, compatibility, '3', "Three",
                AvatarCatalog.HUMANOID_3);
        try {
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            assertEquals(3, third.getPartyStatus().getMembers().size());
            assertEquals(PartyMemberState.CONNECTED,
                    third.getPartyStatus().getMember(2).getState());
            long connectedSequence = third.getPartyStatus().getSequence();

            NetworkEntityId secondEntity = second.getLocalMovementEntityId();
            second.close();
            PartyMemberStatus reconnecting = awaitPartyState(third, 2,
                    PartyMemberState.RECONNECTING);

            assertTrue(third.getPartyStatus().getSequence() > connectedSequence);
            assertEquals("Two", reconnecting.getNickname());
            assertEquals(secondEntity, reconnecting.getEntityId());
            assertEquals(3, reconnecting.getRemainingLives());
            assertTrue(fixture.host.getReconnectGrace(2).isFrozen());
            assertTrue(fixture.host.getReconnectGrace(2).isInvulnerable());
        }
        finally {
            second.close();
            third.close();
            fixture.close();
        }
    }

    @Test
    public void reliablePartyCommunicationUsesHostOwnedChatAndPauseControl()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("communication-floor");
        HostFixture fixture = host(compatibility, 2, "communication");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitMovementSnapshots(client, 2);

            client.submitPartyChat("Watch left.");
            awaitChatCount(fixture.host, 1);
            awaitChatCount(client, 1);
            assertEquals("Friend", fixture.host.getPartyCommunicationState()
                    .getChatHistory().get(0).getNickname());
            assertEquals("Watch left.", client.getPartyCommunicationState()
                    .getChatHistory().get(0).getText());

            fixture.host.submitPartyChat("Moving in.");
            awaitChatCount(client, 2);
            assertEquals("Host", client.getPartyCommunicationState()
                    .getChatHistory().get(1).getNickname());

            client.requestPauseSession();
            awaitPauseRequest(client, 2);
            assertFalse(client.isSessionPaused());
            assertFalse(client.canControlSessionPause());
            try {
                client.setSessionPaused(true);
                fail("Participant changed Host-owned Pause Session state.");
            }
            catch(UnsupportedOperationException expected) { }

            fixture.host.setSessionPaused(true);
            awaitPauseState(client, true);
            Thread.sleep(100L);
            int pausedSnapshotCount = fixture.host.getMovementSnapshots().size();
            Thread.sleep(200L);
            assertEquals(pausedSnapshotCount, fixture.host.getMovementSnapshots().size());

            fixture.host.setSessionPaused(false);
            awaitPauseState(client, false);
            awaitMovementSnapshotCount(fixture.host, pausedSnapshotCount + 1);
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void lostAndDuplicatedMovementDatagramsRecoverWithoutDuplicatingMovement()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("loss-duplication-floor");
        HostFixture fixture = host(compatibility, 2, "loss-duplication");
        final int[] submittedBundles = { 0 };
        DirectConnectClient client = DirectConnectClient.connectForTest("127.0.0.1",
                fixture.host.getBoundPort(), identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 0,
                new MemoryReconnectTokens(), compatibility,
                new DirectConnectClient.MovementDatagramPolicy() {
                    @Override
                    public int copiesForBundle(List<MovementInputFrame> inputs) {
                        return submittedBundles[0]++ == 0 ? 0 : 2;
                    }
                });
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitMovementSnapshots(client, 2);

            client.submitMovementInput(new MovementInputFrame(1L, 0f, 0f, 0f, true));
            client.submitMovementInput(new MovementInputFrame(2L, 0f, 0f, 0f, false));

            awaitAcknowledgedInput(client, 2L);
            MovementEntityState recovered = latestLocalMovement(client);
            assertEquals(2L, recovered.getLastProcessedInputTick());
            assertTrue("Dropped jump input was not recovered from duplicated bundle.",
                    recovered.getZ() > 0.5f);
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void approvedIdentityAutomaticallyReclaimsSameSlotAcrossLobbySessions() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("persistent-floor");
        File storageRoot = temporaryFolder.newFolder("persistent-campaign");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        HostFixture first = host(compatibility, 3, "persistent", storageRoot);
        DirectConnectClient firstClient = client(first.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        try {
            awaitPhase(firstClient, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(first.host.approve(identity('2').getValue()));
            awaitPhase(firstClient, DirectConnectPhase.LOBBY);
            assertEquals(2, firstClient.getCampaignSlot());
        }
        finally {
            firstClient.close();
            first.close();
        }

        HostFixture resumed = host(compatibility, 3, "persistent", storageRoot);
        DirectConnectClient returning = client(resumed.host.getBoundPort(), '2', "Renamed",
                AvatarCatalog.HUMANOID_3, 0, tokens, compatibility);
        try {
            awaitPhase(returning, DirectConnectPhase.LOBBY);
            assertTrue(resumed.host.getPendingClaims().isEmpty());
            assertEquals(2, returning.getCampaignSlot());
            assertEquals("Renamed",
                    resumed.roster.getSlot(2).getPresentation().getNickname());
            assertEquals(identity('2'),
                    resumed.roster.getSlot(2).getLauncherIdentity());
        }
        finally {
            returning.close();
            resumed.close();
        }
    }

    @Test
    public void validReconnectCredentialReclaimsFrozenActiveFloorEntity() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("reconnect-floor");
        HostFixture fixture = host(compatibility, 3, "reconnect");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient second = client(fixture.host.getBoundPort(), '2', "Two",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        DirectConnectClient third = null;
        DirectConnectClient returning = null;
        try {
            awaitPhase(second, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(second, DirectConnectPhase.LOBBY);
            third = approveClient(fixture, compatibility, '3', "Three",
                    AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            NetworkEntityId preservedEntity = second.getLocalMovementEntityId();
            String secondTarget = AuthoritativeCombatEncounter.participantTargetId(
                    new ParticipantId("campaign-slot-2"));
            awaitCombatSnapshot(third);
            submitDirectedAtMonster(second, 1L, CombatAction.MELEE);
            awaitCombatHealth(third, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 20);
            awaitMonsterTarget(fixture.host, secondTarget);

            second.close();
            PartyMemberStatus frozen = awaitPartyState(third, 2,
                    PartyMemberState.RECONNECTING);
            assertEquals(preservedEntity, frozen.getEntityId());
            assertTrue(fixture.host.getReconnectGrace(2).isFrozen());
            assertTrue(fixture.host.getReconnectGrace(2).isInvulnerable());
            awaitMonsterTargetOtherThan(fixture.host, secondTarget);

            returning = client(fixture.host.getBoundPort(), '2', "Different Display",
                    AvatarCatalog.HUMANOID_4, 0, tokens, compatibility);
            awaitPhase(returning, DirectConnectPhase.READY);
            assertEquals(2, returning.getCampaignSlot());
            assertEquals(preservedEntity, returning.getLocalMovementEntityId());
            assertNull(fixture.host.getReconnectGrace(2));
            assertEquals(PartyMemberState.CONNECTED,
                    awaitPartyState(third, 2, PartyMemberState.CONNECTED).getState());
            assertEquals("Two", fixture.roster.getSlot(2).getPresentation().getNickname());
            awaitCombatCadence(fixture.host, CombatAction.PROJECTILE);
            assertEquals(2L, returning.getNextCombatRequestId());
            submitDirectedAtMonster(returning, returning.getNextCombatRequestId(),
                    CombatAction.PROJECTILE);
            awaitCombatHealth(third, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 17);
        }
        finally {
            second.close();
            if(third != null) third.close();
            if(returning != null) returning.close();
            fixture.close();
        }
    }

    @Test
    public void reconnectReadyIncludesCurrentCombatStateAfterEncounterBecomesIdle()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("idle-reconnect-floor");
        HostFixture fixture = host(compatibility, 2, "idle-reconnect");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        DirectConnectClient returning = null;
        String clientTarget = AuthoritativeCombatEncounter.participantTargetId(
                new ParticipantId("campaign-slot-2"));
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitCombatSnapshot(client);

            client.submitCombatAction(1L, CombatAction.SELF_DAMAGE, clientTarget);
            awaitCombatHealth(client, clientTarget, 6);
            for(long requestId = 1L; requestId <= 5L; requestId++) {
                submitDirectedAtMonster(fixture.host, requestId, CombatAction.SPELL);
                awaitCombatHealth(fixture.host,
                        AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                        Math.max(0, 24 - (int)requestId * CombatAction.SPELL.getAmount()));
                if(requestId < 5L) {
                    awaitCombatCadence(fixture.host, CombatAction.SPELL);
                }
            }

            client.close();
            returning = client(fixture.host.getBoundPort(), '2', "Friend",
                    AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
            awaitPhase(returning, DirectConnectPhase.READY);

            assertEquals(2L, returning.getNextCombatRequestId());
            assertNotNull(returning.getCombatSnapshot());
            assertEquals(0, combatHealth(returning,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(6, combatHealth(returning, clientTarget));
        }
        finally {
            client.close();
            if(returning != null) returning.close();
            fixture.close();
        }
    }

    @Test
    public void reconnectGraceExpiresOnlyAfterUnpausedHostTicksAndReturnsSlotSafely()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("reconnect-expiry-floor");
        HostFixture fixture = host(compatibility, 2, "reconnect-expiry",
                temporaryFolder.newFolder("reconnect-expiry-store"), 8L);
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            String reconnectToken = fixture.roster.getSlot(2).getReconnectToken();
            client.close();

            fixture.host.setSessionPaused(true);
            Thread.sleep(250L);
            assertTrue(fixture.host.getReconnectGrace(2) != null);
            fixture.host.setSessionPaused(false);
            awaitPartyState(fixture.host, 2, PartyMemberState.DISCONNECTED);
            assertNull(fixture.host.getReconnectGrace(2));
            assertEquals(1, fixture.host.getMovementEntities().size());
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertEquals(reconnectToken, fixture.roster.getSlot(2).getReconnectToken());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void expiredReconnectHandshakeCannotStartAnotherGraceWindow() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("expired-reconnect-floor");
        HostFixture fixture = host(compatibility, 2, "expired-reconnect",
                temporaryFolder.newFolder("expired-reconnect-store"), 60L);
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        Socket reconnecting = null;
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            client.close();
            assertTrue(fixture.host.getReconnectGrace(2) != null);

            reconnecting = new Socket();
            reconnecting.connect(new InetSocketAddress("127.0.0.1",
                    fixture.host.getBoundPort()));
            reconnecting.setSoTimeout(4000);
            writeTcp(reconnecting, new DirectConnectWire.ClientHello(
                    DirectConnectProtocol.VERSION, compatibility.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256(),
                    identity('2').getValue()));
            Message challengeMessage = readTcp(reconnecting);
            assertTrue(challengeMessage instanceof DirectConnectWire.CampaignChallenge);
            DirectConnectWire.CampaignChallenge challenge =
                    (DirectConnectWire.CampaignChallenge)challengeMessage;
            writeTcp(reconnecting, new DirectConnectWire.SlotClaim(challenge.sessionId,
                    "Friend", AvatarCatalog.HUMANOID_2, 2,
                    tokens.load("expired-reconnect")));
            assertTrue(readTcp(reconnecting) instanceof DirectConnectWire.ServerAccepted);

            Message expired = readTcp(reconnecting);
            assertTrue(expired instanceof DirectConnectWire.ServerDisconnect);
            awaitPartyState(fixture.host, 2, PartyMemberState.DISCONNECTED);
            assertNull(fixture.host.getReconnectGrace(2));
        }
        finally {
            if(reconnecting != null) reconnecting.close();
            client.close();
            fixture.close();
        }
    }

    @Test
    public void hostKickDisconnectsOnlyLiveSessionAndPreservesCampaignOwnership()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("kick-floor");
        HostFixture fixture = host(compatibility, 2, "kick");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            String reconnectToken = fixture.roster.getSlot(2).getReconnectToken();

            assertTrue(fixture.host.kick(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.DISCONNECTED);
            awaitPartyState(fixture.host, 2, PartyMemberState.DISCONNECTED);
            assertNull(fixture.host.getReconnectGrace(2));
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertEquals(reconnectToken, fixture.roster.getSlot(2).getReconnectToken());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void fullRosterAndOccupiedHostSlotRejectUnknownIdentityWithoutReassignment()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("protected-roster");
        HostFixture full = host(compatibility, 2, "full");
        full.roster.approve(new com.interrupt.dungeoneer.multiplayer.lobby.SlotClaimRequest(
                        identity('2'), new SlotPresentation("Owner", AvatarCatalog.HUMANOID_2),
                        2, null), new SecureRandom());
        full.store.save(full.roster);
        DirectConnectClient unknown = client(full.host.getBoundPort(), '3', "Unknown",
                AvatarCatalog.HUMANOID_3, 0, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(unknown, DirectConnectPhase.REJECTED);
            assertTrue(unknown.getStatus().getMessage().startsWith("CAMPAIGN_FULL"));
            assertEquals(identity('2'), full.roster.getSlot(2).getLauncherIdentity());
        }
        finally {
            unknown.close();
            full.close();
        }

        HostFixture occupied = host(compatibility, 3, "occupied");
        DirectConnectClient claimant = client(occupied.host.getBoundPort(), '3', "Claimant",
                AvatarCatalog.HUMANOID_3, 1, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(claimant, DirectConnectPhase.REJECTED);
            assertTrue(claimant.getStatus().getMessage().startsWith("SLOT_OCCUPIED"));
            assertEquals(identity('1'), occupied.roster.getSlot(1).getLauncherIdentity());
        }
        finally {
            claimant.close();
            occupied.close();
        }
    }

    @Test
    public void explicitBuildAndContentMismatchesAreRejectedBeforeSlotClaim() throws Exception {
        DirectConnectCompatibility hostCompatibility = compatibility("host-floor");
        HostFixture fixture = host(hostCompatibility, 2, "compatibility");
        DirectConnectClient client = null;
        try {
            DirectConnectCompatibility wrongBuild = new DirectConnectCompatibility(
                    "different-build", hostCompatibility.getContentFormat(),
                    hostCompatibility.getContentSha256());
            client = client(fixture.host.getBoundPort(), '2', "Wrong Build",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), wrongBuild);
            awaitPhase(client, DirectConnectPhase.REJECTED);
            assertTrue(client.getStatus().getMessage().contains("Build mismatch"));
            client.close();

            DirectConnectCompatibility wrongContent = compatibility("different-floor");
            client = client(fixture.host.getBoundPort(), '2', "Wrong Content",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), wrongContent);
            awaitPhase(client, DirectConnectPhase.REJECTED);
            assertTrue(client.getStatus().getMessage().contains("Content mismatch"));
            assertTrue(fixture.roster.findSlot(identity('2')) == null);
        }
        finally {
            if(client != null) client.close();
            fixture.close();
        }
    }

    @Test
    public void malformedHandshakeRejectsOnlyOffendingConnection() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("safe-floor");
        HostFixture fixture = host(compatibility, 2, "malformed");
        DirectConnectClient validClient = null;
        try {
            Socket malformed = new Socket();
            malformed.connect(new InetSocketAddress("127.0.0.1", fixture.host.getBoundPort()));
            malformed.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(malformed.getOutputStream());
            output.writeInt(5);
            output.writeInt(0x01020304);
            output.writeByte(99);
            output.flush();

            DataInputStream input = new DataInputStream(malformed.getInputStream());
            int responseLength = input.readInt();
            assertTrue(responseLength > 0);
            assertTrue(responseLength <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
            byte[] response = new byte[responseLength];
            input.readFully(response);
            malformed.close();

            ByteBuf responseBuffer = Unpooled.wrappedBuffer(response);
            try {
                Message rejection = DirectConnectWire.decodeDatagram(responseBuffer);
                assertTrue(rejection instanceof ServerRejected);
                assertEquals(DirectConnectWire.RejectCode.MALFORMED_HANDSHAKE,
                        ((ServerRejected)rejection).code);
            }
            finally {
                responseBuffer.release();
            }

            validClient = client(fixture.host.getBoundPort(), '2', "After Malformed",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
            awaitPhase(validClient, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(validClient, DirectConnectPhase.LOBBY);
        }
        finally {
            if(validClient != null) validClient.close();
            fixture.close();
        }
    }

    @Test
    public void protocolMismatchIsExplicitAndDoesNotStopHost() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("protocol-floor");
        HostFixture fixture = host(compatibility, 2, "protocol");
        ByteBuf wrongProtocol = DirectConnectWire.encodeDatagram(
                UnpooledByteBufAllocator.DEFAULT,
                new DirectConnectWire.ClientHello(DirectConnectProtocol.VERSION + 1,
                        compatibility.getBuildId(), compatibility.getContentFormat(),
                        compatibility.getContentSha256(), identity('2').getValue()));
        DirectConnectClient validClient = null;
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", fixture.host.getBoundPort()));
            socket.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(wrongProtocol.readableBytes());
            wrongProtocol.readBytes(output, wrongProtocol.readableBytes());
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int responseLength = input.readInt();
            byte[] response = new byte[responseLength];
            input.readFully(response);
            socket.close();

            ByteBuf responseBuffer = Unpooled.wrappedBuffer(response);
            try {
                Message rejection = DirectConnectWire.decodeDatagram(responseBuffer);
                assertTrue(rejection instanceof ServerRejected);
                assertEquals(DirectConnectWire.RejectCode.PROTOCOL_MISMATCH,
                        ((ServerRejected)rejection).code);
            }
            finally {
                responseBuffer.release();
            }

            validClient = client(fixture.host.getBoundPort(), '2', "Valid",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
            awaitPhase(validClient, DirectConnectPhase.AWAITING_APPROVAL);
        }
        finally {
            wrongProtocol.release();
            if(validClient != null) validClient.close();
            fixture.close();
        }
    }

    @Test
    public void hostCanDisconnectApprovedClientCleanly() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("disconnect-floor");
        HostFixture fixture = host(compatibility, 2, "disconnect");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.close();
            awaitPhase(client, DirectConnectPhase.DISCONNECTED);
            assertTrue(client.getStatus().getMessage().contains("Host disconnected cleanly"));
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    private DirectConnectClient approveClient(HostFixture fixture,
            DirectConnectCompatibility compatibility, char identity, String nickname,
            String avatar) throws Exception {
        DirectConnectClient client = client(fixture.host.getBoundPort(), identity, nickname,
                avatar, 0, new MemoryReconnectTokens(), compatibility);
        awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
        assertTrue(fixture.host.approve(identity(identity).getValue()));
        awaitPhase(client, DirectConnectPhase.LOBBY);
        return client;
    }

    private DirectConnectClient client(int port, char identity, String nickname, String avatar,
            int requestedSlot, ReconnectTokenStore reconnectTokens,
            DirectConnectCompatibility compatibility) {
        return DirectConnectClient.connect("127.0.0.1", port, identity(identity),
                new SlotPresentation(nickname, avatar), requestedSlot, reconnectTokens,
                compatibility);
    }

    private HostFixture host(DirectConnectCompatibility compatibility, int capacity,
            String campaignId) throws Exception {
        return host(compatibility, capacity, campaignId,
                temporaryFolder.newFolder("campaign-store-" + campaignStoreCounter++));
    }

    private HostFixture host(DirectConnectCompatibility compatibility, int capacity,
            String campaignId, File storageRoot) {
        return host(compatibility, capacity, campaignId, storageRoot,
                DirectConnectHost.RECONNECT_GRACE_TICKS);
    }

    private HostFixture host(DirectConnectCompatibility compatibility, int capacity,
            String campaignId, File storageRoot, long reconnectGraceTicks) {
        CampaignRosterStore store = new CampaignRosterStore(storageRoot, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate(campaignId, capacity,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        DirectConnectHost host = DirectConnectHost.startForTest(0, compatibility, roster, store,
                new com.interrupt.dungeoneer.multiplayer.movement.RectangularMovementCollisionWorld(
                        32f, 32f, 16.5f, 16.5f, 0.5f), reconnectGraceTicks);
        return new HostFixture(host, roster, store);
    }

    private DirectConnectCompatibility compatibility(String floor) {
        return DirectConnectCompatibility.forOpenSourceTestFloor(
                floor.getBytes(StandardCharsets.UTF_8));
    }

    private LauncherIdentity identity(char value) {
        StringBuilder result = new StringBuilder(LauncherIdentity.ENCODED_LENGTH);
        while(result.length() < LauncherIdentity.ENCODED_LENGTH) result.append(value);
        return new LauncherIdentity(result.toString());
    }

    private void awaitPendingCount(DirectConnectHost host, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(host.getPendingClaims().size() == count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " pending Campaign Slot claims.");
    }

    private void awaitMovementSnapshots(DirectConnectPeer peer, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getMovementSnapshots().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " authoritative movement snapshots.");
    }

    private void awaitCombatSnapshot(DirectConnectPeer peer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getCombatSnapshot() != null) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for authoritative combat state.");
    }

    private void submitDirectedAtMonster(DirectConnectPeer peer, long requestId,
            CombatAction action) throws InterruptedException {
        awaitMovementSnapshots(peer, 1);
        awaitCombatSnapshot(peer);
        List<MovementSnapshot> movementSnapshots = peer.getMovementSnapshots();
        MovementEntityState source = movementSnapshots.get(movementSnapshots.size() - 1)
                .getEntity(peer.getLocalMovementEntityId());
        CombatSnapshot combat = peer.getCombatSnapshot();
        float aimX = combat.getMonsterX() - source.getX();
        float aimY = combat.getMonsterY() - source.getY();
        float aimLength = (float)Math.sqrt(aimX * aimX + aimY * aimY);
        assertTrue(aimLength > 0.0001f);
        peer.submitCombatAction(requestId, action,
                aimX / aimLength, aimY / aimLength, 0f);
    }

    private void submitDirectedAtParticipant(DirectConnectPeer peer, long requestId,
            CombatAction action, int campaignSlot) throws InterruptedException {
        awaitMovementSnapshots(peer, 1);
        List<MovementSnapshot> movementSnapshots = peer.getMovementSnapshots();
        MovementSnapshot movement = movementSnapshots.get(movementSnapshots.size() - 1);
        MovementEntityState source = movement.getEntity(peer.getLocalMovementEntityId());
        MovementEntityState target = movement.getEntity(new NetworkEntityId(campaignSlot));
        assertNotNull(source);
        assertNotNull(target);
        float aimX = target.getX() - source.getX();
        float aimY = target.getY() - source.getY();
        float aimLength = (float)Math.sqrt(aimX * aimX + aimY * aimY);
        assertTrue(aimLength > 0.0001f);
        peer.submitCombatAction(requestId, action,
                aimX / aimLength, aimY / aimLength, 0f);
    }

    private void awaitCombatHealth(DirectConnectPeer peer, String targetId, int health)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(combatHealth(peer, targetId) == health) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for authoritative combat health=" + health + ".");
    }

    private void awaitCombatCadence(DirectConnectHost host, CombatAction action)
            throws InterruptedException {
        long readyTick = host.getCombatSnapshot().getHostTick()
                + action.getMinimumIntervalTicks();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(host.getCombatSnapshot().getHostTick() >= readyTick) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for combat cadence " + action + ".");
    }

    private void awaitCombatPresentations(DirectConnectPeer peer, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getCombatPresentationEvents().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " combat presentations.");
    }

    private void awaitMonsterTarget(DirectConnectPeer peer, String targetId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            CombatSnapshot snapshot = peer.getCombatSnapshot();
            if(snapshot != null && targetId.equals(snapshot.getMonsterTargetId())) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for monster target " + targetId + ".");
    }

    private void awaitMonsterTargetOtherThan(DirectConnectPeer peer, String targetId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            CombatSnapshot snapshot = peer.getCombatSnapshot();
            if(snapshot != null && !targetId.equals(snapshot.getMonsterTargetId())) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for monster to release target " + targetId + ".");
    }

    private int combatHealth(DirectConnectPeer peer, String targetId) {
        CombatSnapshot snapshot = peer.getCombatSnapshot();
        return snapshot == null || snapshot.getCombatant(targetId) == null
                ? -1 : snapshot.getCombatant(targetId).getHealth();
    }

    private PartyMemberStatus awaitPartyState(DirectConnectPeer peer, int campaignSlot,
            PartyMemberState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getPartyStatus() != null) {
                PartyMemberStatus member = peer.getPartyStatus().getMember(campaignSlot);
                if(member != null && member.getState() == expected) return member;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Campaign Slot " + campaignSlot
                + " Party state " + expected + ".");
        return null;
    }

    private void awaitPartyHealth(DirectConnectPeer peer, int campaignSlot, int health)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            PartyStatusSnapshot snapshot = peer.getPartyStatus();
            PartyMemberStatus member = snapshot == null ? null : snapshot.getMember(campaignSlot);
            if(member != null && member.getHealth() == health) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Campaign Slot " + campaignSlot
                + " Party health=" + health + ".");
    }

    private void awaitChatCount(DirectConnectPeer peer, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getPartyCommunicationState().getChatHistory().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " Party chat deliveries.");
    }

    private void awaitPauseRequest(DirectConnectPeer peer, int campaignSlot)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            PartyCommunicationState state = peer.getPartyCommunicationState();
            if(state.getLatestPauseRequest() != null
                    && state.getLatestPauseRequest().getCampaignSlot() == campaignSlot) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Pause Session request.");
    }

    private void awaitPauseState(DirectConnectPeer peer, boolean paused)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.isSessionPaused() == paused) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Pause Session=" + paused + ".");
    }

    private void awaitMovementSnapshotCount(DirectConnectPeer peer, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getMovementSnapshots().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for resumed authoritative movement.");
    }

    private void awaitAcknowledgedInput(DirectConnectClient client, long inputTick)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            List<MovementSnapshot> snapshots = client.getMovementSnapshots();
            if(!snapshots.isEmpty() && client.getLocalMovementEntityId() != null) {
                MovementEntityState state = snapshots.get(snapshots.size() - 1)
                        .getEntity(client.getLocalMovementEntityId());
                if(state != null && state.getLastProcessedInputTick() >= inputTick) return;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for movement input acknowledgement " + inputTick + ".");
    }

    private MovementEntityState latestLocalMovement(DirectConnectClient client) {
        List<MovementSnapshot> snapshots = client.getMovementSnapshots();
        return snapshots.get(snapshots.size() - 1)
                .getEntity(client.getLocalMovementEntityId());
    }

    private void writeTcp(Socket socket, Message message) throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT,
                message);
        try {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(encoded.readableBytes());
            encoded.readBytes(output, encoded.readableBytes());
            output.flush();
        }
        finally {
            encoded.release();
        }
    }

    private Message readTcp(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        assertTrue(length > 0 && length <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        ByteBuf buffer = Unpooled.wrappedBuffer(encoded);
        try {
            return DirectConnectWire.decodeDatagram(buffer);
        }
        finally {
            buffer.release();
        }
    }

    private void awaitPhase(DirectConnectPeer peer, DirectConnectPhase phase)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            DirectConnectStatus status = peer.getStatus();
            if(status.getPhase() == phase) return;
            if(status.getPhase() == DirectConnectPhase.FAILED
                    && phase != DirectConnectPhase.FAILED) {
                fail("Direct Connect failed while waiting for " + phase + ": "
                        + status.getMessage());
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + phase + "; last state was "
                + peer.getStatus().getPhase() + ": " + peer.getStatus().getMessage());
    }

    private static final class HostFixture implements AutoCloseable {
        private final DirectConnectHost host;
        private final CampaignRoster roster;
        private final CampaignRosterStore store;

        private HostFixture(DirectConnectHost host, CampaignRoster roster,
                CampaignRosterStore store) {
            this.host = host;
            this.roster = roster;
            this.store = store;
        }

        @Override
        public void close() {
            host.close();
        }
    }

    private static final class MemoryReconnectTokens implements ReconnectTokenStore {
        private final Map<String, String> tokens = new HashMap<String, String>();

        @Override
        public synchronized String load(String campaignId) {
            return tokens.get(campaignId);
        }

        @Override
        public synchronized void save(String campaignId, String reconnectToken) {
            tokens.put(campaignId, reconnectToken);
        }
    }
}
