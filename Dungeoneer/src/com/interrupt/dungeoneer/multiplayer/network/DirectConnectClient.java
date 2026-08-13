package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.CampaignChallenge;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ClientHello;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ProtocolException;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerAccepted;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerDisconnect;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerRejected;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SessionReady;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SlotClaim;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.SlotPending;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegister;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.UdpRegistered;

import io.netty.bootstrap.Bootstrap;
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
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct Connect client whose private Launcher Identity claims one persistent Campaign Slot. */
public final class DirectConnectClient implements DirectConnectPeer {
    private final String host;
    private final int port;
    private final LauncherIdentity launcherIdentity;
    private final SlotPresentation presentation;
    private final int requestedSlot;
    private final ReconnectTokenStore reconnectTokens;
    private final DirectConnectCompatibility compatibility;
    private final EventLoopGroup networkGroup = new NioEventLoopGroup(1);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean resourcesClosing = new AtomicBoolean(false);

    private volatile DirectConnectStatus status;
    private volatile Channel tcpChannel;
    private volatile Channel udpChannel;
    private volatile String sessionId;
    private volatile String campaignId;
    private volatile int campaignCapacity;
    private volatile int campaignSlot;
    private volatile long udpToken;
    private volatile boolean udpRegistered;
    private volatile SessionReady readyMessage;
    private volatile int udpRegistrationAttempts;

    private DirectConnectClient(String host, int port, LauncherIdentity launcherIdentity,
            SlotPresentation presentation, int requestedSlot,
            ReconnectTokenStore reconnectTokens, DirectConnectCompatibility compatibility) {
        if(host == null || host.trim().isEmpty()
                || host.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("Direct Connect address must be 1-255 bytes.");
        }
        if(port < 1 || port > 65535) {
            throw new IllegalArgumentException("Direct Connect port must be between 1 and 65535.");
        }
        if(launcherIdentity == null) throw new IllegalArgumentException("Launcher Identity cannot be null.");
        if(presentation == null) throw new IllegalArgumentException("Slot presentation cannot be null.");
        if(requestedSlot < 0 || requestedSlot > 4) {
            throw new IllegalArgumentException("Requested Campaign Slot must be zero (any) or 1-4.");
        }
        if(reconnectTokens == null) throw new IllegalArgumentException("Reconnect token store cannot be null.");
        if(compatibility == null) throw new IllegalArgumentException("Client compatibility cannot be null.");
        this.host = host.trim();
        this.port = port;
        this.launcherIdentity = launcherIdentity;
        this.presentation = presentation;
        this.requestedSlot = requestedSlot;
        this.reconnectTokens = reconnectTokens;
        this.compatibility = compatibility;
        status = new DirectConnectStatus(DirectConnectPhase.CONNECTING,
                "Connecting TCP to " + this.host + ":" + port + ".",
                null, null, null);
    }

    public static DirectConnectClient connect(String host, int port,
            LauncherIdentity launcherIdentity, SlotPresentation presentation,
            int requestedSlot, ReconnectTokenStore reconnectTokens,
            DirectConnectCompatibility compatibility) {
        DirectConnectClient client = new DirectConnectClient(host, port, launcherIdentity,
                presentation, requestedSlot, reconnectTokens, compatibility);
        client.start();
        return client;
    }

    private void start() {
        Bootstrap tcp = new Bootstrap();
        tcp.group(networkGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        DirectConnectWire.configureTcp(channel.pipeline());
                        channel.pipeline().addLast("directConnectClient", new ClientTcpHandler());
                    }
                });

        ChannelFuture connect = tcp.connect(host, port);
        tcpChannel = connect.channel();
        connect.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if(!future.isSuccess()) fail(connectFailureReason(future.cause()));
            }
        });
    }

    private String connectFailureReason(Throwable failure) {
        Throwable current = failure;
        while(current != null) {
            String message = current.getMessage();
            if(current instanceof ConnectException && message != null
                    && message.toLowerCase(Locale.ROOT).contains("refused")) {
                return "No Direct Connect Host is listening at " + host + ":" + port
                        + ". Start Host first and check that both ports match.";
            }
            if(current.getCause() == current) break;
            current = current.getCause();
        }
        return "TCP connection failed: " + safeMessage(failure);
    }

    private synchronized void tcpConnected(ChannelHandlerContext context) {
        if(closing.get()) {
            context.close();
            return;
        }
        status = new DirectConnectStatus(DirectConnectPhase.HANDSHAKING,
                "TCP connected. Validating protocol build and normalized content identity.",
                null, null, null);
        context.writeAndFlush(new ClientHello(DirectConnectProtocol.VERSION,
                compatibility.getBuildId(), compatibility.getContentFormat(),
                compatibility.getContentSha256(), launcherIdentity.getValue()));
        context.executor().schedule(new Runnable() {
            @Override
            public void run() {
                DirectConnectPhase phase = status.getPhase();
                if(phase == DirectConnectPhase.HANDSHAKING
                        || phase == DirectConnectPhase.CLAIMING_SLOT
                        || phase == DirectConnectPhase.REGISTERING_UDP) {
                    fail("Direct Connect lobby handshake timed out.");
                }
            }
        }, 10L, TimeUnit.SECONDS);
    }

    private synchronized void campaignChallenge(CampaignChallenge challenge) {
        if(sessionId != null || challenge.sessionId == null || challenge.sessionId.trim().isEmpty()
                || challenge.capacity < 2 || challenge.capacity > 4) {
            fail("Host returned malformed Campaign lobby details.");
            return;
        }
        try {
            CampaignRoster.requireCampaignId(challenge.campaignId);
        }
        catch(IllegalArgumentException ex) {
            fail("Host returned malformed Campaign identity.");
            return;
        }
        sessionId = challenge.sessionId;
        campaignId = challenge.campaignId;
        campaignCapacity = challenge.capacity;

        String reconnectToken;
        try {
            reconnectToken = reconnectTokens.load(campaignId);
        }
        catch(RuntimeException ex) {
            fail("Could not read private Campaign reconnect credential: " + safeMessage(ex));
            return;
        }
        tcpChannel.writeAndFlush(new SlotClaim(sessionId, presentation.getNickname(),
                presentation.getAvatarId(), requestedSlot,
                reconnectToken == null ? "" : reconnectToken));
        status = new DirectConnectStatus(DirectConnectPhase.CLAIMING_SLOT,
                reconnectToken == null
                        ? "Requesting a new Host-approved Campaign Slot."
                        : "Reclaiming persistent Campaign Slot with private reconnect credential.",
                sessionId, "host", null);
    }

    private synchronized void pending(SlotPending pending) {
        if(sessionId == null || !sessionId.equals(pending.sessionId)) {
            fail("Host returned malformed pending approval state.");
            return;
        }
        status = new DirectConnectStatus(DirectConnectPhase.AWAITING_APPROVAL,
                pending.reason, sessionId, "host", null);
    }

    private synchronized void accepted(ServerAccepted accepted) {
        if(sessionId == null || !sessionId.equals(accepted.sessionId)
                || !campaignId.equals(accepted.campaignId) || accepted.udpToken == 0L
                || accepted.slotNumber < 1 || accepted.slotNumber > campaignCapacity) {
            fail("Host returned malformed Campaign Slot acceptance.");
            return;
        }
        try {
            reconnectTokens.save(campaignId, accepted.reconnectToken);
        }
        catch(RuntimeException ex) {
            fail("Could not store private Campaign reconnect credential: " + safeMessage(ex));
            return;
        }
        campaignSlot = accepted.slotNumber;
        udpToken = accepted.udpToken;
        status = new DirectConnectStatus(DirectConnectPhase.REGISTERING_UDP,
                "Campaign Slot " + campaignSlot
                        + " accepted. Registering UDP on Host's same numeric port.",
                sessionId, "host", null);
        bindUdp();
    }

    private void bindUdp() {
        Bootstrap udp = new Bootstrap();
        udp.group(networkGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext context,
                            DatagramPacket packet) {
                        udpMessage(packet);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                        fail("UDP session failed: " + safeMessage(cause));
                    }
                });

        udp.bind(0).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if(!future.isSuccess()) {
                    fail("Could not open client UDP socket: " + safeMessage(future.cause()));
                    return;
                }
                udpChannel = future.channel();
                sendUdpRegistration();
            }
        });
    }

    private synchronized void sendUdpRegistration() {
        if(udpChannel == null || tcpChannel == null || !tcpChannel.isActive()) return;
        if(udpRegistered) return;
        if(udpRegistrationAttempts >= 20) {
            fail("UDP registration timed out while TCP remained connected.");
            return;
        }
        udpRegistrationAttempts++;
        InetSocketAddress remote = (InetSocketAddress)tcpChannel.remoteAddress();
        try {
            ByteBuf registration = DirectConnectWire.encodeDatagram(udpChannel.alloc(),
                    new UdpRegister(sessionId, udpToken));
            udpChannel.writeAndFlush(new DatagramPacket(registration,
                    new InetSocketAddress(remote.getAddress(), port)));
            udpChannel.eventLoop().schedule(new Runnable() {
                @Override
                public void run() {
                    sendUdpRegistration();
                }
            }, 250L, TimeUnit.MILLISECONDS);
        }
        catch(ProtocolException ex) {
            fail("Could not encode UDP registration: " + safeMessage(ex));
        }
    }

    private synchronized void udpMessage(DatagramPacket packet) {
        if(!fromExpectedHost(packet.sender())) return;
        Message message;
        try {
            message = DirectConnectWire.decodeDatagram(packet.content());
        }
        catch(ProtocolException ignored) {
            return;
        }
        if(!(message instanceof UdpRegistered)) return;
        UdpRegistered registered = (UdpRegistered)message;
        if(!sessionId.equals(registered.sessionId) || udpToken != registered.udpToken) return;
        udpRegistered = true;
        status = new DirectConnectStatus(DirectConnectPhase.LOBBY,
                "Campaign Slot " + campaignSlot + " is ready. Waiting for Host to start play.",
                sessionId, "host", null);
        becomeReadyIfComplete();
    }

    private synchronized void sessionReady(SessionReady ready) {
        if(sessionId == null || !sessionId.equals(ready.sessionId)
                || ready.participantCount < 2 || ready.participantCount > campaignCapacity) {
            fail("Host returned malformed session-ready state.");
            return;
        }
        readyMessage = ready;
        becomeReadyIfComplete();
    }

    private void becomeReadyIfComplete() {
        if(!udpRegistered || readyMessage == null) return;
        status = new DirectConnectStatus(DirectConnectPhase.READY,
                "Host started play with " + readyMessage.participantCount
                        + " Participants. Entering shared open-source test floor.",
                sessionId, "host", readyMessage.floorId);
    }

    private synchronized void rejected(ServerRejected rejection) {
        status = new DirectConnectStatus(DirectConnectPhase.REJECTED,
                rejection.code.name() + ": " + rejection.reason,
                sessionId, "host", null);
        shutdownAsync();
    }

    private synchronized void serverDisconnected(ServerDisconnect disconnect) {
        status = new DirectConnectStatus(DirectConnectPhase.DISCONNECTED,
                "Host disconnected cleanly: " + disconnect.reason,
                sessionId, "host", null);
        shutdownAsync();
    }

    private synchronized void channelClosed() {
        DirectConnectPhase phase = status.getPhase();
        if(!closing.get() && phase != DirectConnectPhase.REJECTED
                && phase != DirectConnectPhase.DISCONNECTED
                && phase != DirectConnectPhase.FAILED) {
            status = new DirectConnectStatus(DirectConnectPhase.DISCONNECTED,
                    "Host TCP connection closed.", sessionId, "host", null);
        }
        shutdownAsync();
    }

    private synchronized void unexpected(Message message) {
        fail("Host sent message outside current session state: "
                + message.getClass().getSimpleName() + ".");
    }

    private synchronized void fail(String reason) {
        if(closing.get() || status.getPhase() == DirectConnectPhase.REJECTED) return;
        status = new DirectConnectStatus(DirectConnectPhase.FAILED, boundedReason(reason),
                sessionId, "host", null);
        shutdownAsync();
    }

    private void shutdownAsync() {
        if(!resourcesClosing.compareAndSet(false, true)) return;
        Channel udp = udpChannel;
        Channel tcp = tcpChannel;
        if(udp != null) udp.close();
        if(tcp != null) tcp.close();
        networkGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS);
    }

    private boolean fromExpectedHost(InetSocketAddress sender) {
        if(tcpChannel == null || sender == null) return false;
        InetSocketAddress tcpRemote = (InetSocketAddress)tcpChannel.remoteAddress();
        if(tcpRemote == null || sender.getPort() != port) return false;
        InetAddress tcpAddress = tcpRemote.getAddress();
        InetAddress udpAddress = sender.getAddress();
        return tcpAddress != null && tcpAddress.equals(udpAddress);
    }

    public LauncherIdentity getLauncherIdentity() {
        return launcherIdentity;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public int getCampaignCapacity() {
        return campaignCapacity;
    }

    public int getCampaignSlot() {
        return campaignSlot;
    }

    @Override
    public DirectConnectStatus getStatus() {
        return status;
    }

    @Override
    public String getRole() {
        return "Client";
    }

    @Override
    public String getEndpoint() {
        return host + ":" + port + " (TCP + UDP)";
    }

    @Override
    public void close() {
        if(!closing.compareAndSet(false, true)) return;
        Channel tcp = tcpChannel;
        if(tcp != null && tcp.isActive()) {
            tcp.writeAndFlush(new ClientDisconnect("Client closed session."))
                    .awaitUninterruptibly(1000L);
        }
        if(resourcesClosing.compareAndSet(false, true)) {
            if(udpChannel != null) udpChannel.close().awaitUninterruptibly(2000L);
            if(tcp != null) tcp.close().awaitUninterruptibly(2000L);
            networkGroup.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).syncUninterruptibly();
        }
        else {
            networkGroup.terminationFuture().awaitUninterruptibly(2000L);
        }
        status = new DirectConnectStatus(DirectConnectPhase.CLOSED,
                "Client session closed.", sessionId, "host", null);
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

    private final class ClientTcpHandler extends SimpleChannelInboundHandler<Message> {
        @Override
        public void channelActive(ChannelHandlerContext context) {
            tcpConnected(context);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, Message message) {
            if(message instanceof CampaignChallenge && sessionId == null) {
                campaignChallenge((CampaignChallenge)message);
            }
            else if(message instanceof SlotPending
                    && status.getPhase() == DirectConnectPhase.CLAIMING_SLOT) {
                pending((SlotPending)message);
            }
            else if(message instanceof ServerAccepted && sessionId != null
                    && campaignSlot == 0) {
                accepted((ServerAccepted)message);
            }
            else if(message instanceof ServerRejected) {
                rejected((ServerRejected)message);
            }
            else if(message instanceof SessionReady && sessionId != null
                    && campaignSlot != 0) {
                sessionReady((SessionReady)message);
            }
            else if(message instanceof ServerDisconnect) {
                serverDisconnected((ServerDisconnect)message);
            }
            else {
                unexpected(message);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            channelClosed();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            fail("TCP session failed: " + safeMessage(cause));
        }
    }
}
