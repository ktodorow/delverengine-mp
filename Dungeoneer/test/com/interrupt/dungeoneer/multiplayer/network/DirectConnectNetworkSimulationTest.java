package com.interrupt.dungeoneer.multiplayer.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import static org.junit.Assert.*;

public class DirectConnectNetworkSimulationTest {
    @Test public void tcpDeliversBufferedRejectionBeforeConnectionClosed() {
        final java.util.List<String> observed = new java.util.ArrayList<String>();
        EmbeddedChannel channel = new EmbeddedChannel(new DirectConnectNetworkSimulation(false),
                new io.netty.channel.ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(io.netty.channel.ChannelHandlerContext context,
                            Object message) {
                        observed.add((String)message);
                    }
                    @Override public void channelInactive(io.netty.channel.ChannelHandlerContext context) {
                        observed.add("closed");
                    }
                });
        channel.freezeTime();
        try {
            channel.writeInbound("Build mismatch");
            channel.close();
            assertEquals(java.util.Arrays.asList("Build mismatch", "closed"), observed);
        }
        finally { channel.finishAndReleaseAll(); }
    }

    @Test public void tcpIsDelayedInBothDirectionsWithoutLossOrReordering() throws Exception {
        assertDelayedTraffic(false, 100);
    }

    @Test public void udpIsDelayedAndLosesTwoPercentInBothDirections() throws Exception {
        assertDelayedTraffic(true, 98);
    }

    private void assertDelayedTraffic(boolean datagrams, int expected) throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new DirectConnectNetworkSimulation(datagrams));
        channel.freezeTime();
        try {
            channel.writeInbound(0);
            channel.writeOutbound(0);
            assertNull(channel.readInbound());
            assertNull(channel.readOutbound());
            for(int i = 1; i < 100; i++) {
                channel.writeInbound(i);
                channel.writeOutbound(i);
            }
            channel.advanceTimeBy(DirectConnectNetworkSimulation.DELAY_MILLIS - 1,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertNull(channel.readInbound());
            assertNull(channel.readOutbound());
            channel.advanceTimeBy(1, java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            int count = 0;
            for(int i = 0; i < 100; i++) {
                if(datagrams && (i + 1) % 50 == 0) continue;
                assertEquals(Integer.valueOf(i), channel.readInbound());
                assertEquals(Integer.valueOf(i), channel.readOutbound());
                count++;
            }
            assertEquals(expected, count);
            assertNull(channel.readInbound());
            assertNull(channel.readOutbound());
        }
        finally { channel.finishAndReleaseAll(); }
    }

    @Test public void disconnectReleasesDelayedDatagrams() {
        EmbeddedChannel channel = new EmbeddedChannel(new DirectConnectNetworkSimulation(true));
        channel.freezeTime();
        ByteBuf incoming = Unpooled.buffer().writeByte(1);
        ByteBuf outgoing = Unpooled.buffer().writeByte(2);
        channel.writeInbound(incoming);
        io.netty.channel.ChannelFuture write = channel.writeAndFlush(outgoing);
        channel.finishAndReleaseAll();
        assertTrue(write.cause() instanceof java.nio.channels.ClosedChannelException);
        assertEquals(0, incoming.refCnt());
        assertEquals(0, outgoing.refCnt());
    }

    @Test public void tcpCloseTransfersReceivedBuffersAndReleasesUnsentWrites() {
        EmbeddedChannel channel = new EmbeddedChannel(new DirectConnectNetworkSimulation(false));
        channel.freezeTime();
        ByteBuf incoming = Unpooled.buffer().writeByte(1);
        ByteBuf outgoing = Unpooled.buffer().writeByte(2);
        try {
            channel.writeInbound(incoming);
            io.netty.channel.ChannelFuture write = channel.writeAndFlush(outgoing);
            channel.close();
            assertTrue(write.cause() instanceof java.nio.channels.ClosedChannelException);
            assertEquals(0, outgoing.refCnt());
            assertSame(incoming, channel.readInbound());
            assertEquals(1, incoming.refCnt());
            incoming.release();
            assertNull(channel.readInbound());
        }
        finally { channel.finishAndReleaseAll(); }
    }
}
