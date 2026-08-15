package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.DiscoveryAnnouncement;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.DiscoveryProbe;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ProtocolException;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Finds Direct Connect Hosts by bounded Netty NIO UDP broadcast. */
public final class PrivateSessionDiscovery {
    public static final int DEFAULT_TIMEOUT_MILLIS = 1500;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PrivateSessionDiscovery() { }

    public static List<LanPrivateSession> discover(int port,
            DirectConnectCompatibility compatibility) throws IOException {
        return discover(port, compatibility, DEFAULT_TIMEOUT_MILLIS);
    }

    public static List<LanPrivateSession> discover(int port,
            DirectConnectCompatibility compatibility, int timeoutMillis) throws IOException {
        requireInputs(port, compatibility, timeoutMillis);
        long nonce = nextNonce();
        DiscoveryProbe probe = new DiscoveryProbe(nonce,
                DirectConnectProtocol.VERSION, compatibility.getBuildId(),
                compatibility.getContentFormat(), compatibility.getContentSha256());
        Map<String, LanPrivateSession> sessions =
                new LinkedHashMap<String, LanPrivateSession>();
        DiscoveryHandler handler = new DiscoveryHandler(nonce, port,
                compatibility, sessions);
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = null;
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(handler);
            ChannelFuture bind = bootstrap.bind(new InetSocketAddress(0));
            bind.awaitUninterruptibly();
            if(!bind.isSuccess()) {
                throw new IOException("Could not bind LAN discovery listener.",
                        bind.cause());
            }
            channel = bind.channel();
            sendProbe(channel, probe, port);
            channel.closeFuture().awaitUninterruptibly(timeoutMillis);
            if(handler.failure != null) {
                throw new IOException("LAN Private Session discovery failed.",
                        handler.failure);
            }
        }
        finally {
            if(channel != null) channel.close().syncUninterruptibly();
            group.shutdownGracefully(0L, 0L, TimeUnit.MILLISECONDS)
                    .syncUninterruptibly();
        }

        List<LanPrivateSession> result;
        synchronized(sessions) {
            result = new ArrayList<LanPrivateSession>(sessions.values());
        }
        Collections.sort(result, new Comparator<LanPrivateSession>() {
            @Override
            public int compare(LanPrivateSession left, LanPrivateSession right) {
                int campaign = left.getCampaignId().compareTo(right.getCampaignId());
                return campaign != 0 ? campaign
                        : left.getEndpoint().compareTo(right.getEndpoint());
            }
        });
        return Collections.unmodifiableList(result);
    }

    private static void sendProbe(Channel channel, DiscoveryProbe probe, int port)
            throws IOException {
        Throwable lastFailure = null;
        int sent = 0;
        for(InetAddress target : broadcastTargets()) {
            ByteBuf request;
            try {
                request = DirectConnectWire.encodeDatagram(channel.alloc(), probe);
            }
            catch(ProtocolException ex) {
                throw new IOException(
                        "Could not encode LAN Private Session discovery probe.", ex);
            }
            ChannelFuture send = channel.writeAndFlush(new DatagramPacket(request,
                    new InetSocketAddress(target, port)));
            send.awaitUninterruptibly();
            if(send.isSuccess()) sent++;
            else if(send.cause() != null) lastFailure = send.cause();
        }
        if(sent == 0) {
            throw new IOException("Could not send LAN Private Session discovery probe.",
                    lastFailure);
        }
    }

    private static Set<InetAddress> broadcastTargets() throws IOException {
        Set<InetAddress> targets = new LinkedHashSet<InetAddress>();
        targets.add(InetAddress.getByName("127.0.0.1"));
        targets.add(InetAddress.getByName("255.255.255.255"));
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if(interfaces == null) return targets;
            while(interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if(!network.isUp() || network.isLoopback()) continue;
                for(InterfaceAddress address : network.getInterfaceAddresses()) {
                    if(address.getBroadcast() != null) targets.add(address.getBroadcast());
                }
            }
        }
        catch(SocketException ignored) {
            // Limited enumeration still leaves loopback and global broadcast targets.
        }
        return targets;
    }

    static boolean isCompatible(DiscoveryAnnouncement announcement,
            DirectConnectCompatibility compatibility) {
        if(announcement.protocolVersion != DirectConnectProtocol.VERSION) return false;
        try {
            DirectConnectCompatibility remote = new DirectConnectCompatibility(
                    announcement.buildId, announcement.contentFormat,
                    announcement.contentSha256);
            return compatibility.getBuildId().equals(remote.getBuildId())
                    && compatibility.getContentIdentity().equals(remote.getContentIdentity());
        }
        catch(IllegalArgumentException ex) {
            return false;
        }
    }

    private static void requireInputs(int port, DirectConnectCompatibility compatibility,
            int timeoutMillis) {
        if(port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Discovery port must be between 1 and 65535.");
        }
        if(compatibility == null) {
            throw new IllegalArgumentException(
                    "Discovery compatibility cannot be null.");
        }
        if(timeoutMillis < 1 || timeoutMillis > 30000) {
            throw new IllegalArgumentException(
                    "Discovery timeout must be between 1 and 30000 milliseconds.");
        }
    }

    static long nextNonce() {
        long nonce;
        do {
            nonce = RANDOM.nextLong();
        }
        while(nonce == 0L);
        return nonce;
    }

    private static final class DiscoveryHandler
            extends SimpleChannelInboundHandler<DatagramPacket> {
        private final long nonce;
        private final int port;
        private final DirectConnectCompatibility compatibility;
        private final Map<String, LanPrivateSession> sessions;
        private volatile Throwable failure;

        DiscoveryHandler(long nonce, int port, DirectConnectCompatibility compatibility,
                Map<String, LanPrivateSession> sessions) {
            this.nonce = nonce;
            this.port = port;
            this.compatibility = compatibility;
            this.sessions = sessions;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
            Message decoded;
            try {
                decoded = DirectConnectWire.decodeDatagram(packet.content());
            }
            catch(ProtocolException ignored) {
                return;
            }
            if(!(decoded instanceof DiscoveryAnnouncement)) return;
            DiscoveryAnnouncement announcement = (DiscoveryAnnouncement)decoded;
            if(announcement.nonce != nonce || announcement.port != port
                    || packet.sender().getPort() != announcement.port
                    || !isCompatible(announcement, compatibility)) return;

            LanPrivateSession session;
            try {
                session = new LanPrivateSession(packet.sender().getAddress(),
                        announcement.port, announcement.sessionId,
                        announcement.campaignId, announcement.capacity,
                        announcement.claimedSlots, announcement.lobbyOpen);
            }
            catch(IllegalArgumentException ignored) {
                return;
            }
            synchronized(sessions) {
                LanPrivateSession existing = sessions.get(session.getSessionId());
                if(existing == null || existing.getAddress().isLoopbackAddress()
                        && !session.getAddress().isLoopbackAddress()) {
                    sessions.put(session.getSessionId(), session);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            failure = cause;
            context.close();
        }
    }
}
