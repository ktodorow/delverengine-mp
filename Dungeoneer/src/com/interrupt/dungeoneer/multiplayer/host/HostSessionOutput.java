package com.interrupt.dungeoneer.multiplayer.host;

/** Effects a simulation may expose while applying commands or advancing one fixed tick. */
public interface HostSessionOutput {
    void event(HostSessionEvent event);

    void disconnect(HostDisconnectOutcome outcome);

    void transition(HostTransitionOutcome outcome);

    void persist(HostPersistedState state);
}
