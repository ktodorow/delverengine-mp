package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.launcher.DirectConnectSessionFlow;
import com.interrupt.dungeoneer.multiplayer.lobby.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.DataInputStream;
import java.net.*;
import java.security.SecureRandom;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real reliable/unreliable boundaries; raw participants expose adversarial external commands. */
public class ManualReadyAuthorityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void consentRequiresAuthenticationSynchronizationAndCurrentOwnSlotAuthority() throws Exception {
        try(Fixture fixture = new Fixture(); RawParticipant participant = new RawParticipant(fixture.host, '2', 2, "")) {
            DirectConnectHost host = fixture.host;
            host.setPlayerReady(true);
            participant.sendReady(2, participant.accepted.udpToken, 1L, true, participant.session);
            participant.fence();
            assertFalse(host.getLobbySnapshot().getSlot(2).isPlayerReady());
            participant.authenticateUdp();
            participant.sendReady(2, participant.accepted.udpToken, participant.lobby.getSequence(), true, participant.session);
            participant.fence();
            assertFalse("Authenticated but unsynchronized connection cannot consent", host.getLobbySnapshot().getSlot(2).isPlayerReady());
            participant.synchronizeLobby();
            long sequence = participant.lobby.getSequence();
            participant.sendReady(1, participant.accepted.udpToken, sequence, false, participant.session);
            participant.sendReady(3, participant.accepted.udpToken, sequence, true, participant.session);
            participant.sendReady(2, participant.accepted.udpToken + 1L, sequence, true, participant.session);
            participant.sendReady(2, participant.accepted.udpToken, sequence, true, "old-session");
            participant.sendReady(2, participant.accepted.udpToken, Long.MAX_VALUE, true, participant.session);
            participant.sendReady(2, participant.accepted.udpToken, 1L, true, participant.session);
            participant.fence();
            LobbySnapshot state = host.getLobbySnapshot();
            assertTrue(state.getSlot(1).isPlayerReady());
            assertFalse(state.getSlot(2).isPlayerReady());
            assertFalse(state.getSlot(3).isPlayerReady());
            assertFalse(host.canStartSession());
            participant.ready(true); participant.fence();
            assertTrue(host.canStartSession());
            participant.ready(false); participant.fence();
            assertFalse(host.canStartSession());
            assertNull(host.getLocalMovementEntityId());
        }
    }

    @Test public void previousConnectionConsentCannotBeReplayedByAuthenticatedReturningConnection() throws Exception {
        try(Fixture fixture = new Fixture()) {
            DirectConnectHost host = fixture.host;
            host.setPlayerReady(true);
            DirectConnectWire.PlayerReady stale;
            String reconnect;
            long oldToken;
            try(RawParticipant previous = new RawParticipant(host, '2', 2, "")) {
                previous.authenticateUdp(); previous.synchronizeLobby(); previous.ready(true); previous.fence();
                assertTrue(host.canStartSession());
                oldToken = previous.accepted.udpToken;
                reconnect = previous.accepted.reconnectToken;
                stale = new DirectConnectWire.PlayerReady(previous.session, 2, oldToken, previous.lobby.getSequence(), true);
            }
            awaitConnected(host, 1);
            try(RawParticipant returning = new RawParticipant(host, '2', 2, reconnect)) {
                returning.authenticateUdp(); returning.synchronizeLobby();
                assertNotEquals(oldToken, returning.accepted.udpToken);
                write(returning.tcp, stale); returning.fence();
                assertTrue(host.getLobbySnapshot().getSlot(1).isPlayerReady());
                assertFalse(host.getLobbySnapshot().getSlot(2).isPlayerReady());
                assertFalse(host.canStartSession());
                returning.ready(true); returning.fence();
                assertTrue(host.canStartSession());
                assertEquals(reconnect, returning.accepted.reconnectToken);
            }
        }
    }

    @Test public void queuedStartRechecksNewUnreadyAdmissionInsteadOfLaunchingPreviouslyReadySubset() throws Exception {
        try(Fixture fixture = new Fixture(); RawParticipant second = new RawParticipant(fixture.host, '2', 2, "")) {
            DirectConnectHost host = fixture.host;
            second.authenticateUdp(); second.synchronizeLobby(); host.setPlayerReady(true); second.ready(true); second.fence();
            assertTrue("Displayed gate permits Start before new admission", host.canStartSession());
            CountDownLatch startQueued = new CountDownLatch(1), executeStart = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Boolean> start = executor.submit(() -> {
                startQueued.countDown();
                assertTrue(executeStart.await(8, TimeUnit.SECONDS));
                try { fixture.flow.startCampaign(host); return true; }
                catch(IllegalStateException rejected) { assertTrue(rejected.getMessage().contains("Ready")); return false; }
            });
            try {
                assertTrue(startQueued.await(8, TimeUnit.SECONDS));
                try(RawParticipant third = new RawParticipant(host, '3', 3, "")) {
                    assertEquals(3, host.getLobbySnapshot().getConnectedCount());
                    assertFalse(host.getLobbySnapshot().getSlot(3).isAuthenticated());
                    executeStart.countDown();
                    assertFalse("Queued Start must use current all-connected gate", start.get(8, TimeUnit.SECONDS));
                    assertNull(host.getLocalMovementEntityId());
                    assertTrue(host.getLobbySnapshot().getSlot(2).isPlayerReady());
                    third.authenticateUdp(); third.synchronizeLobby();
                    assertFalse("Network readiness alone cannot permit subset launch", host.canStartSession());
                    third.ready(true); third.fence();
                    assertTrue(host.canStartSession());
                    fixture.flow.startCampaign(host);
                    assertEquals(3, host.getMovementEntities().size());
                }
            }
            finally { executeStart.countDown(); executor.shutdownNow(); }
        }
    }

    private final class Fixture implements AutoCloseable {
        final DirectConnectSessionFlow flow = new DirectConnectSessionFlow();
        final DirectConnectHost host;
        Fixture() throws Exception {
            CampaignRosterStore store = new CampaignRosterStore(temporary.newFolder(), new SecureRandom());
            CampaignRoster roster = CampaignRoster.createNamed("authority", "Friday Delver", 4, 3,
                    AvatarCatalog.ownedV108Humanoids(), identity('1'), new SlotPresentation("Host", "humanoid-1"), new SecureRandom());
            store.save(roster);
            flow.open(() -> DirectConnectHost.start(0, compatibility(), roster, store));
            host = (DirectConnectHost)flow.getPeer();
        }
        public void close() { flow.leave(); }
    }

    private static final class RawParticipant implements AutoCloseable {
        final Socket tcp;
        final DatagramSocket udp;
        final DirectConnectWire.SlotClaim claim;
        final DirectConnectWire.ServerAccepted accepted;
        final String session;
        final int slot, port;
        LobbySnapshot lobby;
        RawParticipant(DirectConnectHost host, char owner, int slot, String reconnect) throws Exception {
            this.slot = slot; port = host.getBoundPort();
            tcp = new Socket("127.0.0.1", port); tcp.setSoTimeout(8000); udp = new DatagramSocket();
            DirectConnectCompatibility compatibility = compatibility();
            write(tcp, new DirectConnectWire.ClientHello(DirectConnectProtocol.VERSION, compatibility.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256(), identity(owner).getValue()));
            session = next(DirectConnectWire.CampaignChallenge.class).sessionId;
            claim = new DirectConnectWire.SlotClaim(session, "Friend" + slot, "humanoid-" + slot, slot, reconnect);
            write(tcp, claim); accepted = next(DirectConnectWire.ServerAccepted.class);
            readLobby(false, false);
        }
        void authenticateUdp() throws Exception {
            ByteBuf packet = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT,
                    new DirectConnectWire.UdpRegister(session, accepted.udpToken));
            try {
                byte[] bytes = new byte[packet.readableBytes()]; packet.readBytes(bytes);
                udp.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
            }
            finally { packet.release(); }
            readLobby(true, false);
        }
        void synchronizeLobby() throws Exception {
            write(tcp, new DirectConnectWire.LobbyReceived(session, lobby.getSequence())); readLobby(true, true);
        }
        void ready(boolean ready) throws Exception { sendReady(slot, accepted.udpToken, lobby.getSequence(), ready, session); }
        void sendReady(int target, long token, long sequence, boolean ready, String session) throws Exception {
            write(tcp, new DirectConnectWire.PlayerReady(session, target, token, sequence, ready));
        }
        /** Idempotent claim response fences all earlier reliable commands without changing consent. */
        void fence() throws Exception { write(tcp, claim); next(DirectConnectWire.ServerAccepted.class); }
        void readLobby(boolean authenticated, boolean synchronizedState) throws Exception {
            do { lobby = next(DirectConnectWire.LobbySnapshotMessage.class).snapshot; }
            while(lobby.getSlot(slot).isAuthenticated() != authenticated || lobby.getSlot(slot).isSynchronized() != synchronizedState);
        }
        <T extends DirectConnectWire.Message> T next(Class<T> type) throws Exception {
            while(true) {
                DirectConnectWire.Message message = read(tcp);
                if(message instanceof DirectConnectWire.LobbySnapshotMessage) lobby = ((DirectConnectWire.LobbySnapshotMessage)message).snapshot;
                if(type.isInstance(message)) return type.cast(message);
                if(message instanceof DirectConnectWire.ServerRejected) fail("Unexpected rejection: " + ((DirectConnectWire.ServerRejected)message).reason);
            }
        }
        public void close() throws Exception { tcp.close(); udp.close(); }
    }

    private static void write(Socket socket, DirectConnectWire.Message message) throws Exception {
        EmbeddedChannel encoder = new EmbeddedChannel(); DirectConnectWire.configureTcp(encoder.pipeline());
        try {
            encoder.writeOutbound(message); ByteBuf buffer;
            while((buffer = encoder.readOutbound()) != null) {
                try { byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); socket.getOutputStream().write(bytes); }
                finally { buffer.release(); }
            }
            socket.getOutputStream().flush();
        }
        finally { encoder.finishAndReleaseAll(); }
    }

    private static DirectConnectWire.Message read(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream()); int length = input.readInt();
        assertTrue(length > 0 && length <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
        byte[] bytes = new byte[length]; input.readFully(bytes);
        EmbeddedChannel decoder = new EmbeddedChannel(); DirectConnectWire.configureTcp(decoder.pipeline());
        try { decoder.writeInbound(Unpooled.buffer(4 + length).writeInt(length).writeBytes(bytes)); return decoder.readInbound(); }
        finally { decoder.finishAndReleaseAll(); }
    }

    private static void awaitConnected(DirectConnectHost host, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(host.getLobbySnapshot().getConnectedCount() != count && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(count, host.getLobbySnapshot().getConnectedCount());
    }
    private static LauncherIdentity identity(char value) {
        return new LauncherIdentity(new String(new char[LauncherIdentity.ENCODED_LENGTH]).replace('\0', value));
    }
    private static DirectConnectCompatibility compatibility() {
        return DirectConnectCompatibility.forOpenSourceTestFloor("ready-authority".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
