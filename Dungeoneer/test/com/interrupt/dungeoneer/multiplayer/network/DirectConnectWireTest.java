package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.AuthoritativeCombatEncounter;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantKind;
import com.interrupt.dungeoneer.multiplayer.combat.CombatantSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.MonsterSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.communication.PartyChatMessage;
import com.interrupt.dungeoneer.multiplayer.communication.PauseRequest;
import com.interrupt.dungeoneer.multiplayer.communication.PauseSessionState;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientHello;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ProtocolException;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DirectConnectWireTest {
    @Test
    public void framedHelloRoundTripsThroughExplicitCodec() {
        DirectConnectCompatibility compatibility =
                DirectConnectCompatibility.forOpenSourceTestFloor(
                        "floor".getBytes(StandardCharsets.UTF_8));
        ClientHello hello = new ClientHello(DirectConnectProtocol.VERSION,
                compatibility.getBuildId(), compatibility.getContentFormat(),
                compatibility.getContentSha256(), "participant-2");

        EmbeddedChannel outbound = new EmbeddedChannel();
        DirectConnectWire.configureTcp(outbound.pipeline());
        assertTrue(outbound.writeOutbound(hello));
        ByteBuf framed = Unpooled.buffer();
        ByteBuf encodedChunk;
        while((encodedChunk = outbound.readOutbound()) != null) {
            framed.writeBytes(encodedChunk);
            encodedChunk.release();
        }
        assertTrue(framed.readableBytes() <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES + 4);

        EmbeddedChannel inbound = new EmbeddedChannel();
        DirectConnectWire.configureTcp(inbound.pipeline());
        assertTrue(inbound.writeInbound(framed));
        Message decoded = inbound.readInbound();
        assertTrue(decoded instanceof ClientHello);
        ClientHello decodedHello = (ClientHello)decoded;
        assertEquals(hello.protocolVersion, decodedHello.protocolVersion);
        assertEquals(hello.buildId, decodedHello.buildId);
        assertEquals(hello.contentFormat, decodedHello.contentFormat);
        assertEquals(hello.contentSha256, decodedHello.contentSha256);
        assertEquals(hello.launcherIdentity, decodedHello.launcherIdentity);
        assertFalse(inbound.finishAndReleaseAll());
        assertFalse(outbound.finishAndReleaseAll());
    }

    @Test
    public void boundedCampaignChallengeClaimAndAcceptanceRoundTrip() throws Exception {
        DirectConnectWire.CampaignChallenge challenge =
                (DirectConnectWire.CampaignChallenge)roundTrip(
                        new DirectConnectWire.CampaignChallenge("session", "campaign", 4));
        assertEquals("campaign", challenge.campaignId);
        assertEquals(4, challenge.capacity);

        DirectConnectWire.SlotClaim claim = (DirectConnectWire.SlotClaim)roundTrip(
                new DirectConnectWire.SlotClaim("session", "Friend",
                        "humanoid-2", 3, repeat('a')));
        assertEquals("Friend", claim.nickname);
        assertEquals("humanoid-2", claim.avatarId);
        assertEquals(3, claim.requestedSlot);
        assertEquals(repeat('a'), claim.reconnectToken);

        DirectConnectWire.ServerAccepted accepted =
                (DirectConnectWire.ServerAccepted)roundTrip(
                        new DirectConnectWire.ServerAccepted("session", 42L,
                                "campaign", 3, repeat('b')));
        assertEquals("campaign", accepted.campaignId);
        assertEquals(3, accepted.slotNumber);
        assertEquals(repeat('b'), accepted.reconnectToken);

        DirectConnectWire.SessionReady ready = (DirectConnectWire.SessionReady)roundTrip(
                new DirectConnectWire.SessionReady("session", 3, 42L, "floor"));
        assertEquals(3, ready.participantCount);
        assertEquals(42L, ready.nextCombatRequestId);
        assertEquals("floor", ready.floorId);
    }

    @Test
    public void boundedDiscoveryProbeAndAnnouncementRoundTrip() throws Exception {
        DirectConnectCompatibility compatibility =
                DirectConnectCompatibility.forOpenSourceTestFloor(
                        "floor".getBytes(StandardCharsets.UTF_8));
        DirectConnectWire.DiscoveryProbe probe =
                (DirectConnectWire.DiscoveryProbe)roundTrip(
                        new DirectConnectWire.DiscoveryProbe(42L,
                                DirectConnectProtocol.VERSION,
                                compatibility.getBuildId(),
                                compatibility.getContentFormat(),
                                compatibility.getContentSha256()));
        assertEquals(42L, probe.nonce);
        assertEquals(DirectConnectProtocol.VERSION, probe.protocolVersion);
        assertEquals(compatibility.getContentSha256(), probe.contentSha256);

        DirectConnectWire.DiscoveryAnnouncement announcement =
                (DirectConnectWire.DiscoveryAnnouncement)roundTrip(
                        new DirectConnectWire.DiscoveryAnnouncement(42L,
                                DirectConnectProtocol.VERSION,
                                compatibility.getBuildId(),
                                compatibility.getContentFormat(),
                                compatibility.getContentSha256(), "session", "friends",
                                37777, 4, 2, true));
        assertEquals("friends", announcement.campaignId);
        assertEquals(37777, announcement.port);
        assertEquals(4, announcement.capacity);
        assertEquals(2, announcement.claimedSlots);
        assertTrue(announcement.lobbyOpen);
    }

    @Test
    public void boundedLifecycleInputsAndSnapshotsRoundTrip() throws Exception {
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(2L), new ParticipantId("campaign-slot-2"),
                2, "Friend", "humanoid-2");
        DirectConnectWire.EntitySpawn spawn = (DirectConnectWire.EntitySpawn)roundTrip(
                new DirectConnectWire.EntitySpawn("session", descriptor));
        assertEquals(descriptor.getEntityId(), spawn.descriptor.getEntityId());
        assertEquals(descriptor.getNickname(), spawn.descriptor.getNickname());

        DirectConnectWire.MovementInputs inputs =
                (DirectConnectWire.MovementInputs)roundTrip(
                        new DirectConnectWire.MovementInputs("session", 42L, Arrays.asList(
                                new MovementInputFrame(7L, 1f, 0f, 0.25f, false),
                                new MovementInputFrame(8L, 0f, -1f, 0.5f, true))));
        assertEquals(2, inputs.inputs.size());
        assertEquals(8L, inputs.inputs.get(1).getInputTick());
        assertTrue(inputs.inputs.get(1).isJump());

        MovementEntityState entity = new MovementEntityState(new NetworkEntityId(2L),
                1L, 8L, 3f, 4f, 0.5f, 1f, 2f, 0f, 0.5f,
                MovementState.MOVING);
        DirectConnectWire.MovementSnapshotMessage snapshot =
                (DirectConnectWire.MovementSnapshotMessage)roundTrip(
                        new DirectConnectWire.MovementSnapshotMessage("session",
                                new MovementSnapshot(3L, 9L, Arrays.asList(entity))));
        assertEquals(9L, snapshot.snapshot.getHostTick());
        assertEquals(8L, snapshot.snapshot.getEntity(new NetworkEntityId(2L))
                .getLastProcessedInputTick());
    }

    @Test
    public void fullLatencyRecoveryInputWindowFitsOneDatagram() throws Exception {
        List<MovementInputFrame> frames = new ArrayList<MovementInputFrame>();
        for(int tick = 1; tick <= DirectConnectProtocol.MAX_INPUT_FRAMES; tick++) {
            frames.add(new MovementInputFrame(tick, 1f, 0f, 0f, false));
        }

        DirectConnectWire.MovementInputs decoded =
                (DirectConnectWire.MovementInputs)roundTrip(
                        new DirectConnectWire.MovementInputs("session", 42L, frames));

        assertEquals(16, decoded.inputs.size());
        assertEquals(16L, decoded.inputs.get(15).getInputTick());
    }

    @Test
    public void boundedPartyStatusRoundTripsConnectedAndDisconnectedSlots()
            throws Exception {
        PartyStatusSnapshot party = new PartyStatusSnapshot(3L, Arrays.asList(
                new PartyMemberStatus(1, new NetworkEntityId(1L), "Host",
                        "humanoid-1", 8, 8, 3, PartyMemberState.CONNECTED),
                new PartyMemberStatus(2, null, "Friend", "humanoid-2",
                        4, 8, 2, PartyMemberState.DISCONNECTED)));

        DirectConnectWire.PartyStatusMessage decoded =
                (DirectConnectWire.PartyStatusMessage)roundTrip(
                        new DirectConnectWire.PartyStatusMessage("session", party));

        assertEquals(3L, decoded.snapshot.getSequence());
        assertEquals(2, decoded.snapshot.getMembers().size());
        assertEquals(4, decoded.snapshot.getMember(2).getHealth());
        assertEquals(PartyMemberState.DISCONNECTED,
                decoded.snapshot.getMember(2).getState());
    }

    @Test
    public void boundedPartyCommunicationRoundTripsWithoutEngineObjects() throws Exception {
        DirectConnectWire.PartyChatSubmit submitted =
                (DirectConnectWire.PartyChatSubmit)roundTrip(
                        new DirectConnectWire.PartyChatSubmit("session", "Watch left."));
        assertEquals("Watch left.", submitted.text);

        DirectConnectWire.PartyChatDelivery chat =
                (DirectConnectWire.PartyChatDelivery)roundTrip(
                        new DirectConnectWire.PartyChatDelivery("session",
                                new PartyChatMessage(1L, 2, "Friend", "Watch left.")));
        assertEquals("Friend", chat.message.getNickname());
        assertEquals("Watch left.", chat.message.getText());

        DirectConnectWire.PauseRequestedMessage request =
                (DirectConnectWire.PauseRequestedMessage)roundTrip(
                        new DirectConnectWire.PauseRequestedMessage("session",
                                new PauseRequest(3L, 2, "Friend")));
        assertEquals(2, request.request.getCampaignSlot());

        DirectConnectWire.PauseSessionStateMessage paused =
                (DirectConnectWire.PauseSessionStateMessage)roundTrip(
                        new DirectConnectWire.PauseSessionStateMessage("session",
                                new PauseSessionState(4L, true)));
        assertTrue(paused.state.isPaused());
    }

    @Test
    public void boundedCombatIntentAndAuthoritativeStateRoundTripWithoutEngineObjects()
            throws Exception {
        DirectConnectWire.CombatActionRequestMessage request =
                (DirectConnectWire.CombatActionRequestMessage)roundTrip(
                        new DirectConnectWire.CombatActionRequestMessage("session", 4L,
                                CombatAction.BENEFICIAL_SPELL,
                                "participant:campaign-slot-1"));
        assertEquals(4L, request.requestId);
        assertEquals(CombatAction.BENEFICIAL_SPELL, request.action);

        DirectConnectWire.CombatActionRequestMessage directed =
                (DirectConnectWire.CombatActionRequestMessage)roundTrip(
                        new DirectConnectWire.CombatActionRequestMessage("session", 5L,
                                CombatAction.PROJECTILE, 0.25f, -0.5f, 0.75f));
        assertTrue(directed.directed);
        assertEquals(0.25f, directed.aimX, 0f);
        assertEquals(-0.5f, directed.aimY, 0f);
        assertEquals(0.75f, directed.aimZ, 0f);

        CombatSnapshot snapshot = new CombatSnapshot(3L, 60L, "participant:campaign-slot-2",
                4.5f, 6.5f, 0.5f,
                Arrays.asList(
                        new CombatantSnapshot("participant:campaign-slot-1",
                                CombatantKind.PARTICIPANT, 6, 8),
                        new CombatantSnapshot(AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                                CombatantKind.MONSTER, 12, 24)));
        DirectConnectWire.CombatStateMessage state =
                (DirectConnectWire.CombatStateMessage)roundTrip(
                        new DirectConnectWire.CombatStateMessage("session", snapshot));
        assertEquals(60L, state.snapshot.getHostTick());
        assertEquals(4.5f, state.snapshot.getMonsterX(), 0f);
        assertEquals(6.5f, state.snapshot.getMonsterY(), 0f);
        assertEquals(0.5f, state.snapshot.getMonsterZ(), 0f);
        assertEquals(12, state.snapshot.getCombatant(
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID).getHealth());
    }

    @Test
    public void monsterCorpseStateSurvivesAuthoritativeSnapshotRoundTrip()
            throws Exception {
        MonsterSnapshot monster = new MonsterSnapshot(
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, "", 4.5f, 6.5f,
                0.5f, true);
        CombatSnapshot snapshot = new CombatSnapshot(4L, 61L,
                Arrays.asList(monster), Arrays.asList(
                        new CombatantSnapshot("participant:campaign-slot-1",
                                CombatantKind.PARTICIPANT, 8, 8),
                        new CombatantSnapshot(AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                                CombatantKind.MONSTER, 0, 24)));

        DirectConnectWire.CombatStateMessage state =
                (DirectConnectWire.CombatStateMessage)roundTrip(
                        new DirectConnectWire.CombatStateMessage("session", snapshot));

        assertTrue(state.snapshot.getMonster(
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID).isGibbed());
    }

    @Test(expected = IllegalArgumentException.class)
    public void targetedHarmfulWeaponIntentIsRejectedBeforeEncoding() {
        new DirectConnectWire.CombatActionRequestMessage("session", 4L,
                CombatAction.SPELL, AuthoritativeCombatEncounter.SHARED_MONSTER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void directedNonDirectedIntentIsRejectedBeforeEncoding() {
        new DirectConnectWire.CombatActionRequestMessage("session", 4L,
                CombatAction.ENVIRONMENTAL_HAZARD, 1f, 0f, 0f);
    }

    @Test
    public void boundedCombatPresentationRoundTripsWithoutEngineObjects() throws Exception {
        CombatPresentationEvent event = new CombatPresentationEvent(7L, 120L,
                "participant:campaign-slot-2",
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, CombatAction.SPELL,
                1f, 2f, 0.5f, 4f, 5f, 0.6f, true);

        DirectConnectWire.CombatPresentationMessage message =
                (DirectConnectWire.CombatPresentationMessage)roundTrip(
                        new DirectConnectWire.CombatPresentationMessage("session", event));

        assertEquals(7L, message.event.getSequence());
        assertEquals(CombatAction.SPELL, message.event.getAction());
        assertEquals(4f, message.event.getImpactX(), 0f);
        assertTrue(message.event.isStateChanged());
    }

    @Test
    public void rejectsInvalidUtf8AndUnknownMessageTypes() throws Exception {
        ByteBuf invalidUtf8 = Unpooled.buffer();
        invalidUtf8.writeInt(DirectConnectProtocol.MAGIC);
        invalidUtf8.writeByte(1);
        invalidUtf8.writeInt(DirectConnectProtocol.VERSION);
        invalidUtf8.writeShort(1);
        invalidUtf8.writeByte(0x80);
        assertProtocolFailure(invalidUtf8, "valid UTF-8");

        ByteBuf unknown = Unpooled.buffer();
        unknown.writeInt(DirectConnectProtocol.MAGIC);
        unknown.writeByte(255);
        assertProtocolFailure(unknown, "Unknown Direct Connect message type");
    }

    @Test
    public void rejectsFieldBeyondExplicitBound() throws Exception {
        ByteBuf oversized = Unpooled.buffer();
        oversized.writeInt(DirectConnectProtocol.MAGIC);
        oversized.writeByte(1);
        oversized.writeInt(DirectConnectProtocol.VERSION);
        oversized.writeShort(DirectConnectProtocol.MAX_BUILD_ID_BYTES + 1);
        oversized.writeZero(DirectConnectProtocol.MAX_BUILD_ID_BYTES + 1);
        assertProtocolFailure(oversized, "build identity exceeded");
    }

    @Test
    public void rejectsMalformedPartyCommunicationBeforeSessionCodeCanHandleIt()
            throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(
                UnpooledByteBufAllocator.DEFAULT,
                new DirectConnectWire.PartyChatSubmit("session", "safe"));
        encoded.setByte(encoded.writerIndex() - 2, '\n');
        assertProtocolFailure(encoded, "Party chat cannot contain control characters");
    }

    private void assertProtocolFailure(ByteBuf message, String expected) throws Exception {
        try {
            DirectConnectWire.decodeDatagram(message);
            fail("Malformed message was accepted");
        }
        catch(ProtocolException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains(expected));
        }
        finally {
            message.release();
        }
    }

    @Test
    public void equipmentAndSharedKeysRoundTrip() throws Exception {
        PhysicalItemState equipped = new PhysicalItemState(1, 2, "armor", new ParticipantId("slot-2"),
                1, 1, 0, ItemProperties.DEFAULT, false, "ARMOR");
        DirectConnectWire.ItemStateMessage item = (DirectConnectWire.ItemStateMessage)
                roundTrip(new DirectConnectWire.ItemStateMessage("session", equipped));
        assertEquals("ARMOR", item.state.equipmentSlot);
        DirectConnectWire.PartyKeysMessage keys = (DirectConnectWire.PartyKeysMessage)
                roundTrip(new DirectConnectWire.PartyKeysMessage("session", 7, 2));
        assertEquals(7, keys.revision);
        assertEquals(2, keys.count);
    }

    @Test
    public void physicalItemOwnershipAndPropertiesRoundTrip() throws Exception {
        PhysicalItemState state =
                new PhysicalItemState(17L, 43L,
                        "local-template", new ParticipantId("slot-2"), 2f, 3f, 0.5f,
                        new ItemProperties(
                                4, 7, "magic", "fine", 9, 3));
        DirectConnectWire.ItemStateMessage decoded = (DirectConnectWire.ItemStateMessage)
                roundTrip(new DirectConnectWire.ItemStateMessage("session", state));
        assertEquals(17L, decoded.state.entityId);
        assertEquals(43L, decoded.state.revision);
        assertEquals(state.owner, decoded.state.owner);
        assertEquals(9, decoded.state.properties.quantity);
        assertEquals(3, decoded.state.properties.potionType);
        assertEquals("magic", decoded.state.properties.suffix);
        assertEquals(2f, decoded.state.x, 0f);
        DirectConnectWire.ItemRequestMessage request = (DirectConnectWire.ItemRequestMessage)
                roundTrip(new DirectConnectWire.ItemRequestMessage("session", 4L,
                        ItemAction.DROP, 17L));
        assertEquals(4L, request.requestId);
        assertEquals(17L, request.entityId);
    }

    @Test
    public void spentAndConsumedItemsRoundTrip() throws Exception {
        DirectConnectWire.ItemRequestMessage request = (DirectConnectWire.ItemRequestMessage)
                roundTrip(new DirectConnectWire.ItemRequestMessage("session", 8L,
                        ItemAction.SPEND, 17L, 1, 2));
        assertEquals(1, request.condition);
        assertEquals(2, request.quantity);
        DirectConnectWire.ItemStateMessage state = (DirectConnectWire.ItemStateMessage)
                roundTrip(new DirectConnectWire.ItemStateMessage("session",
                        new PhysicalItemState(
                                17L, 9L, "potion", null, 1, 1, 0,
                                ItemProperties.DEFAULT, true)));
        assertTrue(state.state.consumed);
        assertNull(state.state.owner);
    }

    @Test
    public void unownedItemAndMovingDoorRoundTrip() throws Exception {
        DirectConnectWire.ItemStateMessage item = (DirectConnectWire.ItemStateMessage)
                roundTrip(new DirectConnectWire.ItemStateMessage("session",
                        new PhysicalItemState(
                                1L, 1L, "local-template", null, 1f, 2f, 0.5f)));
        assertNull(item.state.owner);
        DirectConnectWire.DoorStateMessage door = (DirectConnectWire.DoorStateMessage)
                roundTrip(new DirectConnectWire.DoorStateMessage("session",
                        new DoorSnapshot(
                                1000000L, 5L, 1, false, true, false, 1f, 2f, 0.5f, 40f, 0.5f)));
        assertEquals(1000000L, door.state.entityId);
        assertEquals(0.5f, door.state.animation, 0f);
        assertFalse(door.state.solid);
    }

    @Test(expected = IllegalArgumentException.class)
    public void exhaustedItemRequestIdCannotBeEncoded() {
        new DirectConnectWire.ItemRequestMessage("session", Long.MAX_VALUE,
                ItemAction.PICKUP, 1L);
    }

    private DirectConnectWire.Message roundTrip(DirectConnectWire.Message message)
            throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(
                UnpooledByteBufAllocator.DEFAULT, message);
        try {
            return DirectConnectWire.decodeDatagram(encoded);
        }
        finally {
            encoded.release();
        }
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        while(result.length() < 64) result.append(value);
        return result.toString();
    }
}
