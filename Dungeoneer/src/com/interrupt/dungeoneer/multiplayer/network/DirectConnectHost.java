package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientHello;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ProtocolException;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.RejectCode;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerAccepted;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerRejected;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SessionReady;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegister;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegistered;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Participant-hosted authority for one initial remote Participant. */
public final class DirectConnectHost implements DirectConnectPeer {
    private final DirectConnectCompatibility compatibility;
    private final EventLoopGroup acceptGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup networkGroup = new NioEventLoopGroup(2);
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final String sessionId;

    private volatile DirectConnectStatus status;
    private volatile Channel tcpListener;
    private volatile Channel udpListener;
    private volatile Channel remoteTcp;
    private volatile ParticipantId remoteParticipant;
    private volatile InetSocketAddress remoteTcpAddress;
    private volatile InetSocketAddress remoteUdpAddress;
    private volatile long udpToken;
    private volatile int boundPort;

    private DirectConnectHost(DirectConnectCompatibility compatibility) {
        if(compatibility == null) {
            throw new IllegalArgumentException("Host compatibility cannot be null.");
        }
        this.compatibility = compatibility;
        sessionId = newSessionId(random);
        status = status(DirectConnectPhase.STARTING,
                "Opening authoritative TCP and UDP listeners.", null, null);
    }

    public static DirectConnectHost start(int port, DirectConnectCompatibility compatibility) {
        if(port < 0 || port > 65535) {
            throw new IllegalArgumentException("Host port must be between 0 and 65535.");
        }

        DirectConnectHost host = new DirectConnectHost(compatibility);
        try {
            host.bind(port);
            return host;
        }
        catch(RuntimeException ex) {
            host.closeResources();
            throw ex;
        }
    }

    private void bind(int requestedPort) {
        ServerBootstrap tcp = new ServerBootstrap();
        tcp.group(acceptGroup, networkGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        DirectConnectWire.configureTcp(channel.pipeline());
                        channel.pipeline().addLast("directConnectHost", new HostTcpHandler());
                    }
                });

        ChannelFuture tcpBind = tcp.bind(new InetSocketAddress(requestedPort)).syncUninterruptibly();
        requireSuccess(tcpBind, "Could not bind Direct Connect TCP listener");
        tcpListener = tcpBind.channel();
        boundPort = ((InetSocketAddress)tcpListener.localAddress()).getPort();

        Bootstrap udp = new Bootstrap();
        udp.group(networkGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext context,
                            DatagramPacket packet) {
                        handleUdp(packet);
                    }
                });

        ChannelFuture udpBind = udp.bind(new InetSocketAddress(boundPort)).syncUninterruptibly();
        requireSuccess(udpBind, "Could not bind Direct Connect UDP listener");
        udpListener = udpBind.channel();
        status = status(DirectConnectPhase.LISTENING,
                "Waiting for one Participant on TCP and UDP port " + boundPort + ".",
                null, null);
    }

    private static void requireSuccess(ChannelFuture future, String message) {
        if(future.isSuccess()) return;
        Throwable cause = future.cause();
        throw new IllegalStateException(message + (cause == null ? "." : ": "
                + safeMessage(cause)), cause);
    }

    private synchronized boolean acceptHello(ChannelHandlerContext context, ClientHello hello) {
        if(remoteTcp != null && remoteTcp.isActive() && remoteTcp != context.channel()) {
            reject(context, RejectCode.SESSION_FULL,
                    "Session already has its one remote Participant.");
            return false;
        }

        if(hello.protocolVersion != DirectConnectProtocol.VERSION) {
            reject(context, RejectCode.PROTOCOL_MISMATCH,
                    "Protocol mismatch: Host requires " + DirectConnectProtocol.VERSION
                            + " but client sent " + hello.protocolVersion + ".");
            return false;
        }

        DirectConnectCompatibility clientCompatibility;
        ParticipantId participantId;
        try {
            clientCompatibility = new DirectConnectCompatibility(hello.buildId,
                    hello.contentFormat, hello.contentSha256);
            participantId = new ParticipantId(hello.participantId);
        }
        catch(IllegalArgumentException ex) {
            reject(context, RejectCode.MALFORMED_HANDSHAKE,
                    "Malformed handshake: " + safeMessage(ex));
            return false;
        }

        if(!compatibility.getBuildId().equals(clientCompatibility.getBuildId())) {
            reject(context, RejectCode.BUILD_MISMATCH,
                    "Build mismatch: Host requires " + compatibility.getBuildId()
                            + " but client sent " + clientCompatibility.getBuildId() + ".");
            return false;
        }
        if(!compatibility.getContentIdentity().equals(clientCompatibility.getContentIdentity())) {
            reject(context, RejectCode.CONTENT_MISMATCH,
                    "Content mismatch: normalized content identity differs from Host.");
            return false;
        }

        remoteTcp = context.channel();
        remoteTcpAddress = (InetSocketAddress)context.channel().remoteAddress();
        remoteParticipant = participantId;
        remoteUdpAddress = null;
        udpToken = nextNonZeroLong(random);
        context.writeAndFlush(new ServerAccepted(sessionId, udpToken));
        status = status(DirectConnectPhase.REGISTERING_UDP,
                "TCP handshake accepted. Waiting for authenticated UDP registration.",
                participantId.getValue(), null);
        return true;
    }

    private synchronized void handleUdp(DatagramPacket packet) {
        if(closing.get() || remoteTcp == null || !remoteTcp.isActive()) return;

        Message decoded;
        try {
            decoded = DirectConnectWire.decodeDatagram(packet.content());
        }
        catch(ProtocolException ignored) {
            return;
        }
        if(!(decoded instanceof UdpRegister)) return;

        UdpRegister register = (UdpRegister)decoded;
        if(!sessionId.equals(register.sessionId) || udpToken != register.udpToken) return;
        if(!sameAddress(remoteTcpAddress, packet.sender())) return;

        remoteUdpAddress = packet.sender();
        try {
            ByteBuf response = DirectConnectWire.encodeDatagram(udpListener.alloc(),
                    new UdpRegistered(sessionId, udpToken));
            udpListener.writeAndFlush(new DatagramPacket(response, remoteUdpAddress));
            remoteTcp.writeAndFlush(new SessionReady(sessionId, 2,
                    GameApplication.OPEN_SOURCE_TEST_LEVEL));
            status = status(DirectConnectPhase.READY,
                    "TCP and UDP are ready. Entering shared open-source test floor.",
                    remoteParticipant.getValue(), GameApplication.OPEN_SOURCE_TEST_LEVEL);
        }
        catch(ProtocolException ex) {
            failActiveConnection("Could not encode UDP registration response: "
                    + safeMessage(ex));
        }
    }

    private synchronized void clientDisconnected(ChannelHandlerContext context, String reason) {
        if(context.channel() != remoteTcp) {
            context.close();
            return;
        }
        status = status(DirectConnectPhase.DISCONNECTED,
                "Participant disconnected cleanly: " + boundedReason(reason),
                remoteParticipant == null ? null : remoteParticipant.getValue(), null);
        context.close();
    }

    private synchronized void channelClosed(Channel channel) {
        if(channel != remoteTcp) return;
        if(!closing.get() && status.getPhase() != DirectConnectPhase.DISCONNECTED) {
            status = status(DirectConnectPhase.DISCONNECTED,
                    "Participant TCP connection closed.",
                    remoteParticipant == null ? null : remoteParticipant.getValue(), null);
        }
        remoteTcp = null;
        remoteTcpAddress = null;
        remoteUdpAddress = null;
        udpToken = 0L;
    }

    private void reject(ChannelHandlerContext context, RejectCode code, String reason) {
        context.writeAndFlush(new ServerRejected(code, boundedReason(reason)))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private synchronized void malformed(ChannelHandlerContext context, Throwable failure) {
        if(closing.get()) {
            context.close();
            return;
        }
        reject(context, RejectCode.MALFORMED_HANDSHAKE,
                "Malformed handshake: " + safeMessage(failure));
        if(context.channel() == remoteTcp) {
            status = status(DirectConnectPhase.DISCONNECTED,
                    "Participant sent malformed session input and was disconnected.",
                    remoteParticipant == null ? null : remoteParticipant.getValue(), null);
        }
    }

    private synchronized void failActiveConnection(String reason) {
        status = status(DirectConnectPhase.FAILED, boundedReason(reason),
                remoteParticipant == null ? null : remoteParticipant.getValue(), null);
        if(remoteTcp != null) remoteTcp.close();
    }

    @Override
    public DirectConnectStatus getStatus() {
        return status;
    }

    public int getBoundPort() {
        return boundPort;
    }

    @Override
    public String getRole() {
        return "Host";
    }

    @Override
    public String getEndpoint() {
        return "0.0.0.0:" + boundPort + " (TCP + UDP)";
    }

    @Override
    public void close() {
        if(!closing.compareAndSet(false, true)) return;
        Channel participant = remoteTcp;
        if(participant != null && participant.isActive()) {
            participant.writeAndFlush(new ServerDisconnect("Host closed session."))
                    .awaitUninterruptibly(1000L);
        }
        closeResources();
        status = status(DirectConnectPhase.CLOSED, "Host session closed.",
                remoteParticipant == null ? null : remoteParticipant.getValue(), null);
    }

    private void closeResources() {
        closeChannel(remoteTcp);
        closeChannel(udpListener);
        closeChannel(tcpListener);
        networkGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly();
        acceptGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private static void closeChannel(Channel channel) {
        if(channel != null) channel.close().awaitUninterruptibly(2000L);
    }

    private DirectConnectStatus status(DirectConnectPhase phase, String message,
            String participantId, String floorId) {
        return new DirectConnectStatus(phase, message, sessionId, participantId, floorId);
    }

    private static boolean sameAddress(InetSocketAddress tcpAddress,
            InetSocketAddress udpAddress) {
        if(tcpAddress == null || udpAddress == null) return false;
        InetAddress tcp = tcpAddress.getAddress();
        InetAddress udp = udpAddress.getAddress();
        return tcp != null && tcp.equals(udp);
    }

    private static long nextNonZeroLong(SecureRandom random) {
        long value;
        do {
            value = random.nextLong();
        }
        while(value == 0L);
        return value;
    }

    private static String newSessionId(SecureRandom random) {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for(int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static String safeMessage(Throwable failure) {
        if(failure == null) return "unknown network failure";
        Throwable current = failure;
        while(current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if(message == null || message.trim().isEmpty()) message = current.getClass().getSimpleName();
        return boundedReason(message);
    }

    private static String boundedReason(String reason) {
        if(reason == null || reason.trim().isEmpty()) return "No reason supplied.";
        String trimmed = reason.trim();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }

    private final class HostTcpHandler extends SimpleChannelInboundHandler<Message> {
        private boolean helloAccepted = false;

        @Override
        public void channelActive(final ChannelHandlerContext context) {
            context.executor().schedule(new Runnable() {
                @Override
                public void run() {
                    if(!helloAccepted && context.channel().isActive()) {
                        reject(context, RejectCode.MALFORMED_HANDSHAKE,
                                "Malformed handshake: Client hello timed out.");
                    }
                }
            }, 10L, TimeUnit.SECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, Message message) {
            if(message instanceof ClientHello && !helloAccepted) {
                helloAccepted = acceptHello(context, (ClientHello)message);
            }
            else if(message instanceof ClientDisconnect && helloAccepted) {
                clientDisconnected(context, ((ClientDisconnect)message).reason);
            }
            else {
                reject(context, RejectCode.MALFORMED_HANDSHAKE,
                        "Malformed handshake: message is not valid in current session state.");
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            channelClosed(context.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            malformed(context, cause);
        }
    }
}
