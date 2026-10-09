package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.lobby.LobbySnapshot;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** External reliable wire boundary; assertions follow independent launcher contract. */
public class LobbyWireTest {
    @Test public void playerConsentRequiresReliableTcpInBothDirections() throws Exception {
        DirectConnectWire.PlayerReady request = new DirectConnectWire.PlayerReady("session", 2, 123L, 7L, true);
        try {
            ByteBuf datagram = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT, request);
            datagram.release();
            fail("Player consent must use reliable TCP");
        }
        catch(DirectConnectWire.ProtocolException expected) { assertTrue(expected.getMessage().contains("reliable")); }
        ByteBuf payload = payload(request);
        try {
            DirectConnectWire.PlayerReady decoded = (DirectConnectWire.PlayerReady)decode(payload.copy());
            assertEquals("session", decoded.sessionId);
            assertEquals(2, decoded.slot);
            assertEquals(123L, decoded.connectionToken);
            assertEquals(7L, decoded.sequence);
            assertTrue(decoded.ready);
            ByteBuf datagram = payload.copy();
            try { DirectConnectWire.decodeDatagram(datagram); fail("UDP cannot deliver consent"); }
            catch(DirectConnectWire.ProtocolException expected) { assertTrue(expected.getMessage().contains("reliable")); }
            finally { datagram.release(); }
        }
        finally { payload.release(); }
    }

    @Test public void malformedConsentSlotTokenSequenceFlagAndFramingAreRejected() throws Exception {
        ByteBuf valid = payload(new DirectConnectWire.PlayerReady("session", 2, 123L, 7L, true));
        try {
            ByteBuf cursor = valid.duplicate(); cursor.skipBytes(5); skipString(cursor);
            int slot = cursor.readerIndex();
            for(int[] mutation : new int[][] {{slot, 0}, {slot, 5}, {slot + 17, 2}, {slot + 17, 255}}) {
                ByteBuf malformed = valid.copy(); malformed.setByte(mutation[0], mutation[1]); rejects(malformed);
            }
            ByteBuf zeroToken = valid.copy(); zeroToken.setLong(slot + 1, 0L); rejects(zeroToken);
            ByteBuf zeroSequence = valid.copy(); zeroSequence.setLong(slot + 9, 0L); rejects(zeroSequence);
            ByteBuf negativeSequence = valid.copy(); negativeSequence.setLong(slot + 9, -1L); rejects(negativeSequence);
            for(int length : new int[] {0, 4, slot, valid.readableBytes() - 1}) rejects(valid.copy(0, length));
            ByteBuf trailing = valid.copy(); trailing.writeByte(0); rejects(trailing);
            ByteBuf withdraw = payload(new DirectConnectWire.PlayerReady("session", 4, -123L, Long.MAX_VALUE, false));
            try { assertFalse(((DirectConnectWire.PlayerReady)decode(withdraw.copy())).ready); }
            finally { withdraw.release(); }
        }
        finally { valid.release(); }
    }

    @Test public void authoritativeProjectionRoundTripsReadyDistinctFromNetworkSynchronization() throws Exception {
        LobbySnapshot expected = new LobbySnapshot(5L, "Friday Delver", 2, 3, 1, Arrays.asList(
                new LobbySnapshot.Slot(1, new SlotPresentation("Host", "humanoid-1"), true, true, true, true),
                new LobbySnapshot.Slot(2, new SlotPresentation("Friend", "humanoid-2"), true, true, true, false)));
        ByteBuf payload = payload(new DirectConnectWire.LobbySnapshotMessage("session", expected));
        try { assertEquals(expected, ((DirectConnectWire.LobbySnapshotMessage)decode(payload.copy())).snapshot); }
        finally { payload.release(); }
        try { new LobbySnapshot.Slot(2, new SlotPresentation("Friend", "humanoid-2"), true, true, false, true); fail("Unsynchronized consent is invalid"); }
        catch(IllegalArgumentException expectedFailure) { assertTrue(expectedFailure.getMessage().contains("state")); }
    }

    @Test public void lobbyReportsAndReceiptsCannotUseUnreliableDatagrams() throws Exception {
        for(DirectConnectWire.Message message : Arrays.asList(
                new DirectConnectWire.LobbySnapshotMessage("session", lobby()),
                new DirectConnectWire.LobbyReceived("session", 1L))) {
            try {
                ByteBuf datagram = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT, message);
                datagram.release();
                fail("Lobby authority requires ordered reliable TCP.");
            }
            catch(DirectConnectWire.ProtocolException expected) { assertTrue(expected.getMessage().contains("reliable")); }
        }
    }

    @Test public void reliableReportsRoundTripWithinSmallFrameBound() throws Exception {
        DirectConnectWire.LobbySnapshotMessage report = new DirectConnectWire.LobbySnapshotMessage("session", lobby());
        ByteBuf payload = payload(report);
        try {
            assertTrue("Four cards fit existing bounded frame", payload.readableBytes() <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
            DirectConnectWire.LobbySnapshotMessage decoded = (DirectConnectWire.LobbySnapshotMessage)decode(payload.copy());
            assertEquals("session", decoded.sessionId);
            assertEquals(lobby(), decoded.snapshot);
            ByteBuf datagram = payload.copy();
            try {
                DirectConnectWire.decodeDatagram(datagram);
                fail("UDP decoder must reject reliable lobby authority");
            }
            catch(DirectConnectWire.ProtocolException expected) { assertTrue(expected.getMessage().contains("reliable")); }
            finally { datagram.release(); }
        }
        finally { payload.release(); }
        ByteBuf receipt = payload(new DirectConnectWire.LobbyReceived("session", 7L));
        try {
            DirectConnectWire.LobbyReceived decoded = (DirectConnectWire.LobbyReceived)decode(receipt.copy());
            assertEquals("session", decoded.sessionId);
            assertEquals(7L, decoded.sequence);
        }
        finally { receipt.release(); }
    }

    @Test public void malformedRosterBoundsFlagsAndTruncationFailAtReliableBoundary() throws Exception {
        ByteBuf valid = payload(new DirectConnectWire.LobbySnapshotMessage("session", lobby()));
        try {
            ByteBuf cursor = valid.duplicate();
            cursor.skipBytes(5); // magic and message type
            skipString(cursor); cursor.skipBytes(8); skipString(cursor);
            int settings = cursor.readerIndex();
            for(int[] mutation : new int[][] {
                    {settings, 1}, {settings, 255}, {settings + 1, 0}, {settings + 1, 6},
                    {settings + 2, 2}, {settings + 3, 255},
                    {settings + 4, 0}, {settings + 5, 16}, {settings + 5, 3},
                    {settings + 23, '9'}, {settings + 24, 1}}) {
                ByteBuf malformed = valid.copy();
                malformed.setByte(mutation[0], mutation[1]);
                rejects(malformed);
            }
            for(int length : new int[] { 0, 4, settings + 2, valid.readableBytes() - 1 })
                rejects(valid.copy(0, length));
            ByteBuf trailing = valid.copy();
            trailing.writeByte(0);
            rejects(trailing);
        }
        finally { valid.release(); }
    }

    @Test public void maximumUnicodeCampaignAndFourNicknamesStillFitReliableFrame() throws Exception {
        StringBuilder name = new StringBuilder();
        for(int index = 0; index < 64; index++) name.append("😀");
        java.util.List<LobbySnapshot.Slot> cards = new java.util.ArrayList<>();
        for(int slot = 1; slot <= 4; slot++) {
            StringBuilder nickname = new StringBuilder();
            for(int index = 0; index < 16; index++) nickname.appendCodePoint(0x1f600 + slot);
            cards.add(new LobbySnapshot.Slot(slot, new SlotPresentation(nickname.toString(), "humanoid-" + slot), true, true, true));
        }
        LobbySnapshot lobby = new LobbySnapshot(Long.MAX_VALUE, name.toString(), 4, 5, 1, cards);
        ByteBuf payload = payload(new DirectConnectWire.LobbySnapshotMessage("01234567890123456789012345678901", lobby));
        try {
            assertTrue(payload.readableBytes() <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
            assertEquals(lobby, ((DirectConnectWire.LobbySnapshotMessage)decode(payload.copy())).snapshot);
        }
        finally { payload.release(); }
    }

    private static void skipString(ByteBuf input) { input.skipBytes(input.readUnsignedShort()); }

    private static void rejects(ByteBuf payload) {
        try { decode(payload); fail("Malformed lobby must fail before publishing cards"); }
        catch(DecoderException expected) {
            assertTrue("Protocol rejection, not allocation or incidental exception", expected.getCause() instanceof DirectConnectWire.ProtocolException);
        }
    }

    private static ByteBuf payload(DirectConnectWire.Message message) {
        EmbeddedChannel encoder = new EmbeddedChannel();
        DirectConnectWire.configureTcp(encoder.pipeline());
        ByteBuf frame = Unpooled.buffer();
        try {
            assertTrue(encoder.writeOutbound(message));
            ByteBuf part;
            while((part = encoder.readOutbound()) != null) {
                try { frame.writeBytes(part); }
                finally { part.release(); }
            }
            int length = frame.readInt();
            assertEquals(length, frame.readableBytes());
            return frame.copy();
        }
        finally { frame.release(); encoder.finishAndReleaseAll(); }
    }

    private static DirectConnectWire.Message decode(ByteBuf payload) {
        EmbeddedChannel decoder = new EmbeddedChannel();
        DirectConnectWire.configureTcp(decoder.pipeline());
        ByteBuf frame = Unpooled.buffer(4 + payload.readableBytes());
        frame.writeInt(payload.readableBytes()).writeBytes(payload);
        payload.release();
        try { decoder.writeInbound(frame); return decoder.readInbound(); }
        finally { decoder.finishAndReleaseAll(); }
    }

    private static LobbySnapshot lobby() {
        return new LobbySnapshot(1L, "Friday Delver", 4, 5, 1, Arrays.asList(
                new LobbySnapshot.Slot(1, new SlotPresentation("Host", "humanoid-1"), true, true, true),
                new LobbySnapshot.Slot(2, new SlotPresentation("Friend", "humanoid-2"), false, false, false),
                new LobbySnapshot.Slot(3, null, false, false, false),
                new LobbySnapshot.Slot(4, null, false, false, false)));
    }
}
