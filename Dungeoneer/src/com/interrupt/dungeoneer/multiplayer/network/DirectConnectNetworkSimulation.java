package com.interrupt.dungeoneer.multiplayer.network;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Opt-in client-side gate conditions: 110 ms added RTT, 2% UDP loss each way.
 * TCP remains ordered and reliable. No firewall, router or OS changes.
 */
final class DirectConnectNetworkSimulation extends ChannelDuplexHandler {
    static final String PROPERTY = "delver.networkSimulation";
    static final int DELAY_MILLIS = 55;
    private static final int MAX_PENDING = 2048;
    private final boolean datagrams;
    private final ArrayDeque<Delivery> pending = new ArrayDeque<>();
    private int incoming;
    private int outgoing;

    DirectConnectNetworkSimulation(boolean datagrams) { this.datagrams = datagrams; }

    static boolean enabled() { return "gate".equals(System.getProperty(PROPERTY)); }

    @Override public void channelRead(ChannelHandlerContext context, Object message) {
        if(datagrams && ++incoming % 50 == 0) { ReferenceCountUtil.release(message); return; }
        enqueue(context, message, null);
    }

    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if(datagrams && ++outgoing % 50 == 0) {
            ReferenceCountUtil.release(message);
            promise.trySuccess();
            return;
        }
        enqueue(context, message, promise);
    }

    private void enqueue(final ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if(pending.size() >= MAX_PENDING) {
            ReferenceCountUtil.release(message);
            IllegalStateException error = new IllegalStateException("Network simulation queue exceeded bound.");
            if(promise != null) promise.tryFailure(error);
            context.fireExceptionCaught(error);
            context.close();
            return;
        }
        final Delivery delivery = new Delivery(message, promise);
        pending.addLast(delivery);
        delivery.task = context.executor().schedule(new Runnable() {
            @Override public void run() {
                if(!pending.remove(delivery)) return;
                if(delivery.promise != null && !context.channel().isActive()) {
                    delivery.discard();
                    return;
                }
                if(delivery.promise == null) {
                    context.fireChannelRead(delivery.message);
                    context.fireChannelReadComplete();
                }
                else context.writeAndFlush(delivery.message, delivery.promise);
            }
        }, DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
        // TCP EOF follows bytes already received, including Host's rejection/disconnect reason.
        // Drain accepted inbound messages before EOF; unsent writes and UDP remain discardable.
        boolean read = false;
        Delivery delivery;
        while((delivery = pending.pollFirst()) != null) {
            delivery.task.cancel(false);
            if(!datagrams && delivery.promise == null) {
                context.fireChannelRead(delivery.message);
                read = true;
            }
            else delivery.discard();
        }
        if(read) context.fireChannelReadComplete();
        super.channelInactive(context);
    }

    @Override public void handlerRemoved(ChannelHandlerContext context) {
        discardPending();
    }

    private void discardPending() {
        Delivery delivery;
        while((delivery = pending.pollFirst()) != null) {
            delivery.task.cancel(false);
            delivery.discard();
        }
    }

    private static final class Delivery {
        final Object message;
        final ChannelPromise promise;
        ScheduledFuture<?> task;
        Delivery(Object message, ChannelPromise promise) { this.message = message; this.promise = promise; }
        void discard() {
            ReferenceCountUtil.release(message);
            if(promise != null) promise.tryFailure(new ClosedChannelException());
        }
    }
}
