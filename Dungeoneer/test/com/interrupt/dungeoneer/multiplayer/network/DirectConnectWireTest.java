package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorFingerprint;
import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;

import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DirectConnectWireTest {
    @Test public void malformedCombatFragmentsAreRejectedBeforePublishingState() {
        assertBadFragments(fragment(16385, 0, 1011)); // Allocation bound.
        assertBadFragments(fragment(2048, 1, 1011)); // No first part.
        assertBadFragments(fragment(2048, 0, 0)); // Empty part.
        assertBadFragments(fragment(2048, 0, 1011), fragment(2048, 0, 1011)); // Duplicate.
        assertBadFragments(fragment(2048, 0, 1011), fragment(2048, 1012, 1011)); // Gap.
        assertBadFragments(fragment(2048, 0, 1011), fragment(2049, 1011, 1011)); // Changed total.
        ByteBuf unrelated = fragment(2048, 0, 1011);
        unrelated.setByte(21, 1); // ClientHello inside fragment instead of combat state.
        assertBadFragments(unrelated);
        ByteBuf interrupted = Unpooled.buffer();
        interrupted.writeInt(5).writeInt(DirectConnectProtocol.MAGIC).writeByte(1);
        assertBadFragments(fragment(2048, 0, 1011), interrupted);
    }

    private ByteBuf fragment(int total, int offset, int size) {
        ByteBuf bytes = Unpooled.buffer();
        bytes.writeInt(13 + size).writeInt(DirectConnectProtocol.MAGIC)
                .writeByte(DirectConnectWire.COMBAT_STATE_PART).writeInt(total).writeInt(offset);
        if(size >= 5) bytes.writeInt(DirectConnectProtocol.MAGIC).writeByte(27).writeZero(size - 5);
        else bytes.writeZero(size);
        return bytes;
    }

    private void assertBadFragments(ByteBuf... frames) {
        EmbeddedChannel channel = new EmbeddedChannel();
        DirectConnectWire.configureTcp(channel.pipeline());
        try {
            for(ByteBuf frame : frames) channel.writeInbound(frame);
            fail("Malformed fragment accepted");
        }
        catch(io.netty.handler.codec.DecoderException expected) {
            assertNull(channel.readInbound());
        }
        finally {
            for(ByteBuf frame : frames) if(frame.refCnt() > 0) frame.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void fullFloorCombatStateFitsBoundedTcpFrames() {
        assertFullFloorRoundTrip(false);
        assertFullFloorRoundTrip(true);
    }

    private void assertFullFloorRoundTrip(boolean maximumIds) {
        List<MonsterSnapshot> monsters = new ArrayList<>();
        List<CombatantSnapshot> actors = new ArrayList<>();
        String target = maximumIds ? repeat('t') : "participant:campaign-slot-2";
        for(int slot = 1; slot <= 4; slot++) actors.add(new CombatantSnapshot(
                "participant:campaign-slot-" + slot, CombatantKind.PARTICIPANT, 8, 8));
        for(int i = 0; i < CombatSnapshot.MAX_MONSTERS; i++) {
            String id = maximumIds ? String.format("%064d", i + 1) : "monster:" + (i + 1);
            monsters.add(new MonsterSnapshot(id, target, i, 2f, 0.5f, i == 0));
            actors.add(new CombatantSnapshot(id, CombatantKind.MONSTER, i == 0 ? 0 : 12, 24));
        }
        EmbeddedChannel outbound = new EmbeddedChannel();
        EmbeddedChannel inbound = new EmbeddedChannel();
        DirectConnectWire.configureTcp(outbound.pipeline());
        DirectConnectWire.configureTcp(inbound.pipeline());
        try {
            assertTrue(outbound.writeOutbound(new DirectConnectWire.CombatStateMessage(
                    "session", new CombatSnapshot(4L, 61L, monsters, actors))));
            ByteBuf framed = Unpooled.buffer();
            ByteBuf bytes;
            while((bytes = outbound.readOutbound()) != null) {
                framed.writeBytes(bytes);
                bytes.release();
            }
            try {
                while(framed.isReadable()) {
                    int length = framed.getInt(framed.readerIndex());
                    assertTrue(length > 0 && length <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
                    inbound.writeInbound(framed.readRetainedSlice(length + 4));
                    if(framed.isReadable()) assertNull("Partial snapshot escaped", inbound.readInbound());
                }
            }
            finally { framed.release(); }
            DirectConnectWire.CombatStateMessage state = inbound.readInbound();
            assertNotNull(state);
            assertEquals(64, state.snapshot.getMonsters().size());
            assertEquals(68, state.snapshot.getCombatants().size());
            String first = maximumIds ? String.format("%064d", 1) : "monster:1";
            String last = maximumIds ? String.format("%064d", 64) : "monster:64";
            assertEquals(target, state.snapshot.getMonster(last).getTargetId());
            assertTrue(state.snapshot.getMonster(first).isGibbed());
            assertEquals(12, state.snapshot.getCombatant(last).getHealth());
            assertNull(inbound.readInbound());
        }
        finally {
            outbound.finishAndReleaseAll();
            inbound.finishAndReleaseAll();
        }
    }

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
                new DirectConnectWire.SessionReady("session", 3, 42L, "floor", -7L));
        assertEquals(3, ready.participantCount);
        assertEquals(42L, ready.nextCombatRequestId);
        assertEquals("floor", ready.floorId);
        assertEquals(-7L, ready.floorSeed);
    }

    @Test
    public void sessionReadyWithoutSharedFloorSeedIsRejected() throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT,
                new DirectConnectWire.SessionReady("session", 2, 1L, "floor", 9L));
        encoded.setLong(encoded.writerIndex() - 8, 0L);
        assertProtocolFailure(encoded, "Shared floor seed is missing");
    }

    @Test(expected = IllegalArgumentException.class)
    public void sessionReadyRequiresSharedFloorSeed() {
        new DirectConnectWire.SessionReady("session", 2, 1L, "floor", 0L);
    }

    @Test
    public void sharedFloorFingerprintRoundTripsEveryCategory() throws Exception {
        SharedFloorFingerprint fingerprint = new SharedFloorFingerprint(
                new int[] { 57, 3, 0, 120, 812 },
                new long[] { Long.MIN_VALUE, -1L, 0L, 42L, Long.MAX_VALUE });

        DirectConnectWire.SharedFloorFingerprintMessage decoded =
                (DirectConnectWire.SharedFloorFingerprintMessage)roundTrip(
                        new DirectConnectWire.SharedFloorFingerprintMessage("session", fingerprint));

        assertEquals("session", decoded.sessionId);
        assertEquals(fingerprint, decoded.fingerprint);
    }

    @Test
    public void sharedFloorFingerprintRejectsCountOutsideBounds() throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT,
                new DirectConnectWire.SharedFloorFingerprintMessage("session",
                        new SharedFloorFingerprint(new int[5], new long[5])));
        // magic, type, then two-byte length and seven-byte session identity
        encoded.setInt(4 + 1 + 2 + 7, -1);
        assertProtocolFailure(encoded, "Invalid shared floor fingerprint");
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
        DirectConnectWire.ItemRequestMessage aimed = (DirectConnectWire.ItemRequestMessage)
                roundTrip(new DirectConnectWire.ItemRequestMessage("session", 9L,
                        ItemAction.CONSUME, 18L, 2, 1, true, 0.25f, -0.5f, 0.75f));
        assertTrue(aimed.hasAim); assertEquals(0.25f, aimed.aimX, 0f);
        assertEquals(-0.5f, aimed.aimY, 0f); assertEquals(0.75f, aimed.aimZ, 0f);
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

    @Test
    public void nativeMonsterSpawnRoundTripsThemeNamePositionAndHealth() throws Exception {
        com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn spawn =
                new com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn("monster:7",
                        "dungeon", "Spider", 3.5f, -2.25f, 0.125f, 4, 6);
        DirectConnectWire.NativeMonsterSpawnMessage decoded =
                (DirectConnectWire.NativeMonsterSpawnMessage)roundTrip(
                        new DirectConnectWire.NativeMonsterSpawnMessage("session", 9L, spawn, 3L));
        assertEquals("session", decoded.sessionId);
        assertEquals(9L, decoded.sequence);
        assertEquals(3L, decoded.generation);
        assertEquals("monster:7", decoded.spawn.monsterId);
        assertEquals("dungeon", decoded.spawn.theme);
        assertEquals("Spider", decoded.spawn.name);
        assertEquals(3.5f, decoded.spawn.x, 0f);
        assertEquals(-2.25f, decoded.spawn.y, 0f);
        assertEquals(0.125f, decoded.spawn.z, 0f);
        assertEquals(4, decoded.spawn.health);
        assertEquals(6, decoded.spawn.maximumHealth);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nativeMonsterSpawnRejectsHealthAboveMaximum() {
        new com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn("monster:1", "", "Rat",
                0f, 0f, 0f, 7, 6);
    }

    @Test(expected = IllegalArgumentException.class)
    public void exhaustedItemRequestIdCannotBeEncoded() {
        new DirectConnectWire.ItemRequestMessage("session", Long.MAX_VALUE,
                ItemAction.PICKUP, 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void absentAimCannotSmuggleUnusedCoordinatesIntoItemRequest() {
        new ItemRequest(new ParticipantId("campaign-slot-1"), 1L,
                ItemAction.CONSUME, 1L, 0, 0, false, 1f, 0f, 0f);
    }

    @Test public void maximumNativeStatusSetFitsFrameAndPreservesCurrentState() throws Exception {
        java.util.List<NativeStatusEffectState> effects = new java.util.ArrayList<>();
        for(NativeStatusEffectState.Kind kind : NativeStatusEffectState.Kind.values()) {
            effects.add(new NativeStatusEffectState(kind.ordinal() + 1, kind, 123.5f,
                    0.25f, "01234567890123456789012345678901", true, 40f, 1.075f,
                    kind.ordinal() + 10));
        }
        ActorEffectsSnapshot state = new ActorEffectsSnapshot(repeat('m'), 99, true, effects, 4.25f, 0.75f, 0.4f, true, 0.4f,
                new com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationState(
                        com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationState.Kind.DODGE,
                        77, 25f, true, false, 40));
        DirectConnectWire.MonsterEffectsMessage decoded = (DirectConnectWire.MonsterEffectsMessage)
                roundTrip(new DirectConnectWire.MonsterEffectsMessage("session", state));
        assertTrue(state.sameState(decoded.state));
        assertEquals(99, decoded.state.sequence);
        assertTrue(decoded.live);
        decoded = (DirectConnectWire.MonsterEffectsMessage)roundTrip(
                new DirectConnectWire.MonsterEffectsMessage("session", state, false));
        assertFalse(decoded.live);
        assertTrue(state.sameState(decoded.state));
    }

    @Test public void doorFeedbackRoundTripsAllNativeOutcomes() throws Exception {
        for(com.interrupt.dungeoneer.multiplayer.items.DoorFeedback feedback :
                com.interrupt.dungeoneer.multiplayer.items.DoorFeedback.values()) {
            DirectConnectWire.DoorFeedbackMessage decoded = (DirectConnectWire.DoorFeedbackMessage)
                    roundTrip(new DirectConnectWire.DoorFeedbackMessage("session", feedback));
            assertEquals("session", decoded.sessionId); assertEquals(feedback, decoded.feedback);
        }
    }

    @Test public void nativeDynamicStateRoundTripsExactProjectileAndCleanup() throws Exception {
        com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile projectile =
                new com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile();
        projectile.x = 1.25f; projectile.y = 2.5f; projectile.z = 0.75f;
        projectile.xa = 0.2f; projectile.ya = -0.1f; projectile.za = 0.05f;
        projectile.tex = 9; projectile.spriteAtlas = "magic-blue";
        projectile.color.set(0.1f, 0.4f, 1f, 1f); projectile.scale = 1.5f;
        com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState state =
                com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(
                        41L, 0L, projectile);
        DirectConnectWire.NativeDynamicStateMessage decoded =
                (DirectConnectWire.NativeDynamicStateMessage)roundTrip(
                        new DirectConnectWire.NativeDynamicStateMessage("session", 7L, state, 3L));

        assertEquals(7L, decoded.sequence); assertEquals(3L, decoded.generation);
        com.interrupt.dungeoneer.entities.Entity replica = decoded.state.apply(null);
        assertTrue(replica instanceof com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile);
        assertTrue(replica.nativePresentationReplica); assertEquals(1.25f, replica.x, 0f);
        assertEquals("magic-blue", replica.spriteAtlas); assertEquals(0.4f, replica.color.g, 0f);

        com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState cleanup =
                com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(
                        41L, 0L, projectile, false);
        decoded = (DirectConnectWire.NativeDynamicStateMessage)roundTrip(
                new DirectConnectWire.NativeDynamicStateMessage("session", 8L, cleanup, 3L));
        assertFalse(decoded.state.active);
    }

    @Test public void nativeBeamStateAndImpactCuePreserveConfiguredPresentation() throws Exception {
        com.interrupt.dungeoneer.entities.projectiles.BeamProjectile beam =
                new com.interrupt.dungeoneer.entities.projectiles.BeamProjectile();
        beam.x = 2f; beam.y = 3f; beam.z = 0.8f;
        beam.artType = com.interrupt.dungeoneer.entities.Entity.ArtType.sprite;
        beam.xa = 0.4f; beam.ya = 0.2f; beam.za = -0.1f;
        beam.startPos.set(1f, 2f, 0.7f); beam.length = 7.5f;
        beam.startTex = 21; beam.endTex = 24; beam.animateTime = 4f;
        beam.spriteAtlas = "blue-beam"; beam.color.set(0.1f, 0.5f, 1f, 1f);
        beam.damageType = com.interrupt.dungeoneer.entities.items.Weapon.DamageType.ICE;
        ((com.interrupt.dungeoneer.entities.projectiles.Projectile)beam).damageType = beam.damageType;
        beam.explosion = new com.interrupt.dungeoneer.entities.Explosion();

        com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState state =
                com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(52L, 0L, beam);
        DirectConnectWire.NativeDynamicStateMessage dynamic =
                (DirectConnectWire.NativeDynamicStateMessage)roundTrip(
                        new DirectConnectWire.NativeDynamicStateMessage("session", 10L, state, 4L));
        com.interrupt.dungeoneer.entities.projectiles.BeamProjectile replica =
                (com.interrupt.dungeoneer.entities.projectiles.BeamProjectile)dynamic.state.apply(null);
        assertEquals("blue-beam", replica.spriteAtlas); assertEquals(7.5f, replica.length, 0f);
        assertEquals(21, replica.startTex); assertEquals(24, replica.endTex);
        assertEquals(com.interrupt.dungeoneer.entities.items.Weapon.DamageType.ICE,
                replica.damageType); assertNotNull(replica.drawable); assertNotNull(replica.explosion);

        com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue cue =
                com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue.captureImpact(
                        52L, 0L, beam, true, 3.5f, 4.5f, 1.1f);
        DirectConnectWire.NativeDynamicCueMessage message =
                (DirectConnectWire.NativeDynamicCueMessage)roundTrip(
                        new DirectConnectWire.NativeDynamicCueMessage("session", 11L, cue, 4L));
        assertEquals(11L, message.sequence); assertEquals(4L, message.generation);
        assertEquals(com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue.Kind.PROJECTILE_IMPACT,
                message.cue.kind); assertTrue(message.cue.entityHit);
        assertTrue(message.cue.secondaryExplosion); assertEquals(3.5f, message.cue.x, 0f);
    }

    @Test public void nativeDynamicCaptureRejectsMalformedPresentationData() {
        com.interrupt.dungeoneer.entities.projectiles.Projectile projectile =
                new com.interrupt.dungeoneer.entities.projectiles.Projectile();
        projectile.artType = null;
        try {
            com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(1L, 0L, projectile);
            fail("Missing art type must fail cleanly");
        }
        catch(IllegalArgumentException expected) { }

        projectile.artType = com.interrupt.dungeoneer.entities.Entity.ArtType.sprite;
        projectile.x = Float.NaN;
        try {
            com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(1L, 0L, projectile);
            fail("Nonfinite position must fail cleanly");
        }
        catch(IllegalArgumentException expected) { }

        projectile.x = 0f;
        projectile.spriteAtlas = new String(new char[129]).replace('\0', 'x');
        try {
            com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(1L, 0L, projectile);
            fail("Unbounded atlas must fail cleanly");
        }
        catch(IllegalArgumentException expected) { }

        try {
            com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(1L, 0L,
                    new com.interrupt.dungeoneer.entities.projectiles.Projectile() { });
            fail("Unknown projectile subclass cannot fall back to generic presentation");
        }
        catch(IllegalArgumentException expected) { }
    }

    @Test public void nativeSpellPresentationRoundTripsAcceptedItemAndExactAudio() throws Exception {
        com.interrupt.dungeoneer.entities.spells.Beam spell =
                new com.interrupt.dungeoneer.entities.spells.Beam();
        com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation presentation =
                com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation.capture(
                        "participant:campaign-slot-2", 71L, spell,
                        new com.badlogic.gdx.math.Vector3(1.25f, 2.5f, 0.75f), true);
        DirectConnectWire.NativeSpellPresentationMessage decoded =
                (DirectConnectWire.NativeSpellPresentationMessage)roundTrip(
                        new DirectConnectWire.NativeSpellPresentationMessage(
                                "session", 9L, presentation, 4L));

        assertEquals(9L, decoded.sequence); assertEquals(4L, decoded.generation);
        assertEquals("participant:campaign-slot-2", decoded.presentation.sourceId);
        assertEquals(71L, decoded.presentation.itemId); assertTrue(decoded.presentation.zap);
        assertEquals(spell.getCastSoundAsset(), decoded.presentation.sound);
        assertEquals(0.75f, decoded.presentation.volume, 0f);
        assertEquals(13f, decoded.presentation.range, 0f);
        assertEquals(2.5f, decoded.presentation.y, 0f);
    }

    @Test public void nativeMeleePresentationRoundTripsItemImpactAndDirection() throws Exception {
        com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation presentation =
                com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation.capture(
                        "participant:campaign-slot-2", 72L,
                        com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation.Kind.WORLD_HIT,
                        new com.badlogic.gdx.math.Vector3(1f, 2f, 0.5f),
                        new com.badlogic.gdx.math.Vector3(0.5f, 0.5f, 0f).nor(),
                        1000007L);
        DirectConnectWire.NativeMeleePresentationMessage decoded =
                (DirectConnectWire.NativeMeleePresentationMessage)roundTrip(
                        new DirectConnectWire.NativeMeleePresentationMessage(
                                "session", 10L, presentation, 5L));

        assertEquals(10L, decoded.sequence); assertEquals(5L, decoded.generation);
        assertEquals(72L, decoded.presentation.itemId);
        assertEquals(1000007L, decoded.presentation.targetObjectId);
        assertEquals(com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation.Kind.WORLD_HIT,
                decoded.presentation.kind);
        assertEquals(2f, decoded.presentation.y, 0f);
        assertEquals(0.70710677f, decoded.presentation.directionX, 0.00001f);
    }

    @Test public void breakableStateRoundTripsDamageMotionAndDestruction() throws Exception {
        com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot state =
                new com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot(
                        1000007L, 9L, 1, false, false,
                        1f, 2f, 0.5f, 0.1f, -0.2f, 0.3f, 4f, 5f, 6f);
        DirectConnectWire.BreakableStateMessage decoded =
                (DirectConnectWire.BreakableStateMessage)roundTrip(
                        new DirectConnectWire.BreakableStateMessage("session", state));

        assertEquals(1000007L, decoded.state.entityId);
        assertEquals(9L, decoded.state.revision);
        assertEquals(1, decoded.state.hp);
        assertFalse(decoded.state.active);
        assertEquals(-0.2f, decoded.state.velocityY, 0f);
        assertEquals(6f, decoded.state.rotationZ, 0f);
    }

    @Test public void nativeRangedPresentationRoundTripsAcceptedBowRelease() throws Exception {
        com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation presentation =
                com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation.capture(
                        "participant:campaign-slot-2", 73L,
                        new com.badlogic.gdx.math.Vector3(1.5f, 2.75f, 0.5f));
        DirectConnectWire.NativeRangedPresentationMessage decoded =
                (DirectConnectWire.NativeRangedPresentationMessage)roundTrip(
                        new DirectConnectWire.NativeRangedPresentationMessage(
                                "session", 11L, presentation, 6L));

        assertEquals(11L, decoded.sequence); assertEquals(6L, decoded.generation);
        assertEquals("participant:campaign-slot-2", decoded.presentation.sourceId);
        assertEquals(73L, decoded.presentation.itemId);
        assertEquals(2.75f, decoded.presentation.y, 0f);
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
