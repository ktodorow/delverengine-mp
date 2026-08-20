package com.interrupt.dungeoneer.multiplayer.network;

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
