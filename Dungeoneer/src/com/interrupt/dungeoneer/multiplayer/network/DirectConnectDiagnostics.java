package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.DiscoveryAnnouncement;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.DiscoveryProbe;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ProtocolException;

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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only Netty NIO endpoint probes. Never changes firewall, router, or NAT state. */
public final class DirectConnectDiagnostics {
    public static final int DEFAULT_TIMEOUT_MILLIS = 1500;

    private DirectConnectDiagnostics() { }

    public static DirectConnectDiagnosticReport diagnose(String host, int port,
            DirectConnectCompatibility compatibility) {
        return diagnose(host, port, compatibility, DEFAULT_TIMEOUT_MILLIS);
    }

    public static DirectConnectDiagnosticReport diagnose(String host, int port,
            DirectConnectCompatibility compatibility, int timeoutMillis) {
        String target = requireHost(host);
        requireInputs(port, compatibility, timeoutMillis);

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(unbracket(target));
        }
        catch(UnknownHostException ex) {
            String detail = "Address could not be resolved: " + safeMessage(ex);
            return new DirectConnectDiagnosticReport(displayEndpoint(target, port),
                    false, detail, false, detail, false);
        }
        return diagnoseResolved(target, addresses, port, compatibility, timeoutMillis);
    }

    static DirectConnectDiagnosticReport diagnoseResolved(String target,
            InetAddress[] addresses, int port, DirectConnectCompatibility compatibility,
            int timeoutMillis) {
        requireHost(target);
        requireInputs(port, compatibility, timeoutMillis);
        final List<InetAddress> resolved = requireAddresses(addresses);
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Probe tcp = probeTcp(group, resolved, port, timeoutMillis);
            UdpProbe udp = probeUdp(group, resolved, port, compatibility, timeoutMillis);
            InetAddress respondingAddress = udp.address != null ? udp.address
                    : tcp.address != null ? tcp.address : resolved.get(0);
            return new DirectConnectDiagnosticReport(endpoint(respondingAddress, port),
                    tcp.reachable, tcp.detail, udp.reachable, udp.detail, udp.compatible);
        }
        finally {
            group.shutdownGracefully(0L, 0L, TimeUnit.MILLISECONDS)
                    .syncUninterruptibly();
        }
    }

    private static Probe probeTcp(EventLoopGroup group, List<InetAddress> addresses,
            int port, int timeoutMillis) {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger remaining = new AtomicInteger(addresses.size());
        final AtomicReference<InetAddress> respondingAddress =
                new AtomicReference<InetAddress>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        List<ChannelFuture> attempts = new ArrayList<ChannelFuture>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) { }
                });

        for(final InetAddress address : addresses) {
            try {
                ChannelFuture attempt = bootstrap.connect(
                        new InetSocketAddress(address, port));
                attempt.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        if(future.isSuccess()) {
                            respondingAddress.compareAndSet(null, address);
                            completed.countDown();
                        }
                        else if(future.cause() != null) {
                            failure.compareAndSet(null, future.cause());
                        }
                        if(remaining.decrementAndGet() == 0) completed.countDown();
                    }
                });
                attempts.add(attempt);
            }
            catch(RuntimeException ex) {
                failure.compareAndSet(null, ex);
                if(remaining.decrementAndGet() == 0) completed.countDown();
            }
        }

        await(completed, timeoutMillis);
        InetAddress reached = respondingAddress.get();
        Throwable cause = failure.get();
        for(ChannelFuture attempt : attempts) {
            attempt.channel().close();
        }
        if(reached != null) {
            return new Probe(true,
                    "Connection accepted. TCP path reaches a listener on this port.",
                    reached);
        }
        String detail = cause == null
                ? "No resolved address accepted a TCP connection within the timeout."
                : "Connection failed for all resolved addresses: " + safeMessage(cause);
        return new Probe(false, detail, null);
    }

    private static UdpProbe probeUdp(EventLoopGroup group, List<InetAddress> addresses,
            final int port, final DirectConnectCompatibility compatibility,
            int timeoutMillis) {
        final long nonce = PrivateSessionDiscovery.nextNonce();
        final CountDownLatch compatibleReply = new CountDownLatch(1);
        final AtomicReference<UdpProbe> reply = new AtomicReference<UdpProbe>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Set<InetAddress> expectedAddresses =
                new LinkedHashSet<InetAddress>(addresses);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext context,
                            DatagramPacket packet) {
                        InetSocketAddress sender = packet.sender();
                        if(sender == null || sender.getPort() != port
                                || !expectedAddresses.contains(sender.getAddress())) return;
                        Message decoded;
                        try {
                            decoded = DirectConnectWire.decodeDatagram(packet.content());
                        }
                        catch(ProtocolException ignored) {
                            return;
                        }
                        if(!(decoded instanceof DiscoveryAnnouncement)) return;
                        DiscoveryAnnouncement announcement =
                                (DiscoveryAnnouncement)decoded;
                        if(announcement.nonce != nonce || announcement.port != port) return;
                        boolean compatible = PrivateSessionDiscovery.isCompatible(
                                announcement, compatibility);
                        UdpProbe result = new UdpProbe(true, compatible
                                ? "Private Session replied and protocol, build, and content match."
                                : "Private Session replied, but protocol, build, or content does not match.",
                                compatible, sender.getAddress());
                        if(compatible) {
                            reply.set(result);
                            compatibleReply.countDown();
                        }
                        else {
                            reply.compareAndSet(null, result);
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext context,
                            Throwable cause) {
                        failure.compareAndSet(null, cause);
                        compatibleReply.countDown();
                        context.close();
                    }
                });

        Channel channel = null;
        try {
            ChannelFuture bind = bootstrap.bind(new InetSocketAddress(0));
            bind.awaitUninterruptibly();
            if(!bind.isSuccess()) {
                return new UdpProbe(false, "Probe could not bind: "
                        + safeMessage(bind.cause()), false, null);
            }
            channel = bind.channel();
            int sent = 0;
            Throwable sendFailure = null;
            for(InetAddress address : addresses) {
                ByteBuf request;
                try {
                    request = DirectConnectWire.encodeDatagram(channel.alloc(),
                            new DiscoveryProbe(nonce, DirectConnectProtocol.VERSION,
                                    compatibility.getBuildId(),
                                    compatibility.getContentFormat(),
                                    compatibility.getContentSha256()));
                }
                catch(ProtocolException ex) {
                    return new UdpProbe(false, "Probe could not be encoded: "
                            + safeMessage(ex), false, null);
                }
                ChannelFuture send = channel.writeAndFlush(new DatagramPacket(request,
                        new InetSocketAddress(address, port)));
                send.awaitUninterruptibly();
                if(send.isSuccess()) sent++;
                else if(send.cause() != null) sendFailure = send.cause();
            }
            if(sent == 0) {
                return new UdpProbe(false, "Probe could not be sent: "
                        + safeMessage(sendFailure), false, null);
            }

            await(compatibleReply, timeoutMillis);
            UdpProbe response = reply.get();
            if(response != null) return response;
            Throwable receiveFailure = failure.get();
            if(receiveFailure != null) {
                return new UdpProbe(false, "Probe failed: "
                        + safeMessage(receiveFailure), false, null);
            }
            return new UdpProbe(false,
                    "No Private Session reply. UDP may be blocked or forwarded incorrectly, or no current Host is listening.",
                    false, null);
        }
        finally {
            if(channel != null) channel.close().syncUninterruptibly();
        }
    }

    private static void requireInputs(int port, DirectConnectCompatibility compatibility,
            int timeoutMillis) {
        if(port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Diagnostic port must be between 1 and 65535.");
        }
        if(compatibility == null) {
            throw new IllegalArgumentException(
                    "Diagnostic compatibility cannot be null.");
        }
        if(timeoutMillis < 1 || timeoutMillis > 30000) {
            throw new IllegalArgumentException(
                    "Diagnostic timeout must be between 1 and 30000 milliseconds.");
        }
    }

    private static List<InetAddress> requireAddresses(InetAddress[] addresses) {
        if(addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException(
                    "Diagnostic addresses cannot be null or empty.");
        }
        LinkedHashSet<InetAddress> unique = new LinkedHashSet<InetAddress>();
        for(InetAddress address : addresses) {
            if(address == null) {
                throw new IllegalArgumentException(
                        "Diagnostic addresses cannot contain null.");
            }
            unique.add(address);
        }
        return new ArrayList<InetAddress>(unique);
    }

    private static boolean await(CountDownLatch latch, int timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String requireHost(String host) {
        if(host == null || host.trim().isEmpty()
                || host.trim().getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException(
                    "Diagnostic address must be 1-255 bytes.");
        }
        return host.trim();
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private static String displayEndpoint(String host, int port) {
        String unbracketed = unbracket(host);
        return unbracketed.indexOf(':') >= 0
                ? "[" + unbracketed + "]:" + port : unbracketed + ":" + port;
    }

    private static String endpoint(InetAddress address, int port) {
        String host = address.getHostAddress();
        if(address instanceof Inet6Address) host = "[" + host + "]";
        return host + ":" + port;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if(message == null || message.trim().isEmpty()) {
            message = failure == null ? "unknown network failure"
                    : failure.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }

    private static class Probe {
        final boolean reachable;
        final String detail;
        final InetAddress address;

        Probe(boolean reachable, String detail, InetAddress address) {
            this.reachable = reachable;
            this.detail = detail;
            this.address = address;
        }
    }

    private static final class UdpProbe extends Probe {
        final boolean compatible;

        UdpProbe(boolean reachable, String detail, boolean compatible,
                InetAddress address) {
            super(reachable, detail, address);
            this.compatible = compatible;
        }
    }
}
