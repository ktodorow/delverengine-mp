package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerRejected;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DirectConnectIntegrationTest {
    private static final long TIMEOUT_MILLIS = 8000L;

    @Test
    public void refusedConnectionExplainsHowToStartHost() throws Exception {
        ServerSocket unusedPort = new ServerSocket(0);
        int port = unusedPort.getLocalPort();
        unusedPort.close();

        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1", port,
                "participant-2", compatibility("unavailable-host"));
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
    public void oneConfiguredPortCarriesTcpAndUdpBeforeFloorEntry() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("same-floor");
        DirectConnectHost host = DirectConnectHost.start(0, compatibility);
        DirectConnectClient client = null;
        try {
            client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                    "participant-2", compatibility);
            awaitPhase(client, DirectConnectPhase.READY);
            awaitPhase(host, DirectConnectPhase.READY);

            assertTrue(host.getBoundPort() > 0);
            assertEquals(host.getStatus().getSessionId(), client.getStatus().getSessionId());
            assertEquals(GameApplication.OPEN_SOURCE_TEST_LEVEL,
                    client.getStatus().getFloorId());
            assertEquals("participant-2", host.getStatus().getRemoteParticipantId());

            client.close();
            awaitPhase(host, DirectConnectPhase.DISCONNECTED);
            assertTrue(host.getStatus().getMessage().contains("cleanly"));
            client = null;
        }
        finally {
            if(client != null) client.close();
            host.close();
        }
    }

    @Test
    public void explicitBuildAndContentMismatchesAreRejected() throws Exception {
        DirectConnectCompatibility hostCompatibility = compatibility("host-floor");
        DirectConnectHost host = DirectConnectHost.start(0, hostCompatibility);
        DirectConnectClient client = null;
        try {
            DirectConnectCompatibility wrongBuild = new DirectConnectCompatibility(
                    "different-build", hostCompatibility.getContentFormat(),
                    hostCompatibility.getContentSha256());
            client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                    "wrong-build", wrongBuild);
            awaitPhase(client, DirectConnectPhase.REJECTED);
            assertTrue(client.getStatus().getMessage().contains("Build mismatch"));
            client.close();

            DirectConnectCompatibility wrongContent = compatibility("different-floor");
            client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                    "wrong-content", wrongContent);
            awaitPhase(client, DirectConnectPhase.REJECTED);
            assertTrue(client.getStatus().getMessage().contains("Content mismatch"));
            client.close();

            client = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                    "compatible", hostCompatibility);
            awaitPhase(client, DirectConnectPhase.READY);
            awaitPhase(host, DirectConnectPhase.READY);
        }
        finally {
            if(client != null) client.close();
            host.close();
        }
    }

    @Test
    public void malformedHandshakeRejectsOnlyOffendingConnection() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("safe-floor");
        DirectConnectHost host = DirectConnectHost.start(0, compatibility);
        DirectConnectClient validClient = null;
        try {
            Socket malformed = new Socket();
            malformed.connect(new InetSocketAddress("127.0.0.1", host.getBoundPort()));
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

            Socket oversized = new Socket();
            oversized.connect(new InetSocketAddress("127.0.0.1", host.getBoundPort()));
            oversized.setSoTimeout(3000);
            DataOutputStream oversizedOutput = new DataOutputStream(oversized.getOutputStream());
            oversizedOutput.writeInt(DirectConnectProtocol.MAX_TCP_FRAME_BYTES + 1);
            oversizedOutput.flush();
            DataInputStream oversizedInput = new DataInputStream(oversized.getInputStream());
            int oversizedResponseLength = oversizedInput.readInt();
            assertTrue(oversizedResponseLength > 0);
            assertTrue(oversizedResponseLength <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
            oversized.close();

            validClient = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                    "after-malformed", compatibility);
            awaitPhase(validClient, DirectConnectPhase.READY);
            awaitPhase(host, DirectConnectPhase.READY);
        }
        finally {
            if(validClient != null) validClient.close();
            host.close();
        }
    }

    @Test
    public void protocolMismatchIsExplicitAndDoesNotStopHost() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("protocol-floor");
        DirectConnectHost host = DirectConnectHost.start(0, compatibility);
        DirectConnectClient validClient = null;
        ByteBuf wrongProtocol = DirectConnectWire.encodeDatagram(
                UnpooledByteBufAllocator.DEFAULT,
                new DirectConnectWire.ClientHello(DirectConnectProtocol.VERSION + 1,
                        compatibility.getBuildId(), compatibility.getContentFormat(),
                        compatibility.getContentSha256(), "wrong-protocol"));
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", host.getBoundPort()));
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
                assertTrue(((ServerRejected)rejection).reason.contains("Protocol mismatch"));
            }
            finally {
                responseBuffer.release();
            }

            validClient = DirectConnectClient.connect("127.0.0.1", host.getBoundPort(),
                    "after-protocol-mismatch", compatibility);
            awaitPhase(validClient, DirectConnectPhase.READY);
            awaitPhase(host, DirectConnectPhase.READY);
        }
        finally {
            wrongProtocol.release();
            if(validClient != null) validClient.close();
            host.close();
        }
    }

    @Test
    public void hostCanDisconnectClientCleanly() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("disconnect-floor");
        DirectConnectHost host = DirectConnectHost.start(0, compatibility);
        DirectConnectClient client = DirectConnectClient.connect("127.0.0.1",
                host.getBoundPort(), "participant-2", compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.READY);
            host.close();
            awaitPhase(client, DirectConnectPhase.DISCONNECTED);
            assertTrue(client.getStatus().getMessage().contains("Host disconnected cleanly"));
        }
        finally {
            client.close();
            host.close();
        }
    }

    private DirectConnectCompatibility compatibility(String floor) {
        return DirectConnectCompatibility.forOpenSourceTestFloor(
                floor.getBytes(StandardCharsets.UTF_8));
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
}
