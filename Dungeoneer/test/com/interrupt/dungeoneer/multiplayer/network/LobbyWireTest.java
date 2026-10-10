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
    @Test public void presentationEditsAndResultsRequireReliableTcpInBothDirections() throws Exception {
        DirectConnectWire.PresentationEdit edit = new DirectConnectWire.PresentationEdit("session", 2, 123L, 9L, "  Scout  ", "humanoid-3");
        DirectConnectWire.PresentationEdited result = new DirectConnectWire.PresentationEdited("session",
                new com.interrupt.dungeoneer.multiplayer.lobby.PresentationEditResult(9L, false,
                        new SlotPresentation("Friend", "humanoid-2"), "Nickname is already used by another Campaign Slot."));
        for(DirectConnectWire.Message message : Arrays.asList(edit, result)) {
            try {
                ByteBuf datagram = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT, message);
                datagram.release(); fail("Presentation authority requires reliable TCP");
            }
            catch(DirectConnectWire.ProtocolException expected) { assertTrue(expected.getMessage().contains("reliable")); }
            ByteBuf bytes = payload(message);
            try {
                assertTrue(bytes.readableBytes() <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
                ByteBuf datagram = bytes.copy();
                try { DirectConnectWire.decodeDatagram(datagram); fail("UDP cannot deliver presentation authority"); }
                catch(DirectConnectWire.ProtocolException expected) { assertTrue(expected.getMessage().contains("reliable")); }
                finally { datagram.release(); }
                DirectConnectWire.Message decoded = decode(bytes.copy());
                if(decoded instanceof DirectConnectWire.PresentationEdit) {
                    DirectConnectWire.PresentationEdit request = (DirectConnectWire.PresentationEdit)decoded;
                    assertEquals("session", request.sessionId); assertEquals(2, request.slot);
                    assertEquals(123L, request.connectionToken); assertEquals(9L, request.requestId);
                    assertEquals("  Scout  ", request.nickname); assertEquals("humanoid-3", request.avatar);
                }
                else {
                    DirectConnectWire.PresentationEdited report = (DirectConnectWire.PresentationEdited)decoded;
                    assertEquals("session", report.sessionId); assertEquals(9L, report.result.getRequestId());
                    assertFalse(report.result.isAccepted());
                    assertEquals(new SlotPresentation("Friend", "humanoid-2"), report.result.getPresentation());
                    assertEquals("Nickname is already used by another Campaign Slot.", report.result.getReason());
                }
            }
            finally { bytes.release(); }
        }
    }

    @Test public void malformedPresentationAuthorityRejectsInvalidIdsFlagsUtf8AndFraming() throws Exception {
        ByteBuf edit = payload(new DirectConnectWire.PresentationEdit("session", 2, 123L, 1L, "Scout", "humanoid-3"));
        ByteBuf result = payload(new DirectConnectWire.PresentationEdited("session",
                new com.interrupt.dungeoneer.multiplayer.lobby.PresentationEditResult(1L, true,
                        new SlotPresentation("Scout", "humanoid-3"), "Presentation updated.")));
        try {
            ByteBuf cursor = edit.duplicate(); cursor.skipBytes(5); skipString(cursor);
            int slot = cursor.readerIndex();
            for(int value : new int[] {0, 5, 255}) {
                ByteBuf bad = edit.copy(); bad.setByte(slot, value); rejects(bad);
            }
            ByteBuf zeroToken = edit.copy(); zeroToken.setLong(slot + 1, 0L); rejects(zeroToken);
            for(long value : new long[] {0L, -1L}) {
                ByteBuf bad = edit.copy(); bad.setLong(slot + 9, value); rejects(bad);
            }
            ByteBuf invalidUtf8 = edit.copy(); invalidUtf8.setByte(slot + 19, 255); rejects(invalidUtf8);
            ByteBuf oversizedNickname = edit.copy(); oversizedNickname.setShort(slot + 17, 257); rejects(oversizedNickname);
            cursor = result.duplicate(); cursor.skipBytes(5); skipString(cursor);
            int id = cursor.readerIndex();
            ByteBuf zeroId = result.copy(); zeroId.setLong(id, 0L); rejects(zeroId);
            for(int value : new int[] {2, 255}) {
                ByteBuf bad = result.copy(); bad.setByte(id + 8, value); rejects(bad);
            }
            ByteBuf invalidNickname = result.copy(); invalidNickname.setByte(id + 12, 10); rejects(invalidNickname);
            for(ByteBuf valid : Arrays.asList(edit, result)) {
                for(int length : new int[] {0, 4, 10, valid.readableBytes() - 1}) rejects(valid.copy(0, length));
                ByteBuf trailing = valid.copy(); trailing.writeByte(0); rejects(trailing);
            }
        }
        finally { edit.release(); result.release(); }
    }

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
