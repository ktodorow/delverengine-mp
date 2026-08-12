package com.interrupt.dungeoneer.multiplayer.host;

/**
 * Outbound session boundary. Tests use an in-memory implementation; network transports will
 * encode the same typed outcomes into bounded wire messages.
 */
public interface HostSessionTransport {
    void publishSnapshot(long hostTick, HostSessionSnapshot snapshot);

    void publishEvent(long hostTick, HostSessionEvent event);

    void publishDisconnect(long hostTick, HostDisconnectOutcome outcome);

    void publishTransition(long hostTick, HostTransitionOutcome outcome);
}
