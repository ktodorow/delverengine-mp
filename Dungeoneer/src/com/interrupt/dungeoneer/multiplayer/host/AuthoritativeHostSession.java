package com.interrupt.dungeoneer.multiplayer.host;

import java.util.ArrayDeque;
import java.util.Queue;

/** Orders commands and advances one authoritative simulation at a fixed 60 Hz. */
public final class AuthoritativeHostSession implements HostSessionCommandGateway {
    public static final int TICKS_PER_SECOND = 60;
    public static final float FIXED_DELTA_SECONDS = 1f / TICKS_PER_SECOND;

    private final AuthoritativeHostSimulation simulation;
    private final HostSessionTransport transport;
    private final HostSessionStorage storage;
    private final Queue<HostSessionCommand> pendingCommands =
            new ArrayDeque<HostSessionCommand>();
    private long hostTick = 0L;

    public AuthoritativeHostSession(AuthoritativeHostSimulation simulation,
            HostSessionTransport transport, HostSessionStorage storage) {
        if(simulation == null) throw new IllegalArgumentException("Host simulation cannot be null.");
        if(transport == null) throw new IllegalArgumentException("Host transport cannot be null.");
        if(storage == null) throw new IllegalArgumentException("Host storage cannot be null.");
        this.simulation = simulation;
        this.transport = transport;
        this.storage = storage;
    }

    @Override
    public synchronized void submit(HostSessionCommand command) {
        if(command == null) throw new IllegalArgumentException("Host command cannot be null.");
        pendingCommands.add(command);
    }

    public synchronized void advanceOneTick() {
        hostTick++;
        HostSessionOutput output = new TickOutput(hostTick);
        int commandCount = pendingCommands.size();
        for(int i = 0; i < commandCount; i++) {
            simulation.applyCommand(hostTick, pendingCommands.remove(), output);
        }

        simulation.tick(hostTick, FIXED_DELTA_SECONDS, output);
        HostSessionSnapshot snapshot = simulation.snapshot(hostTick);
        if(snapshot == null) throw new IllegalStateException("Host simulation returned a null snapshot.");
        transport.publishSnapshot(hostTick, snapshot);
    }

    public synchronized long getHostTick() {
        return hostTick;
    }

    private final class TickOutput implements HostSessionOutput {
        private final long tick;

        private TickOutput(long tick) {
            this.tick = tick;
        }

        @Override
        public void event(HostSessionEvent event) {
            if(event == null) throw new IllegalArgumentException("Host event cannot be null.");
            transport.publishEvent(tick, event);
        }

        @Override
        public void disconnect(HostDisconnectOutcome outcome) {
            if(outcome == null) throw new IllegalArgumentException("Disconnect outcome cannot be null.");
            transport.publishDisconnect(tick, outcome);
        }

        @Override
        public void transition(HostTransitionOutcome outcome) {
            if(outcome == null) throw new IllegalArgumentException("Transition outcome cannot be null.");
            transport.publishTransition(tick, outcome);
        }

        @Override
        public void persist(HostPersistedState state) {
            if(state == null) throw new IllegalArgumentException("Persisted Host state cannot be null.");
            storage.persist(tick, state);
        }
    }
}
