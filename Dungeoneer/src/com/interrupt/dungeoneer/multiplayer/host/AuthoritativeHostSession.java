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
    public void submit(HostSessionCommand command) {
        submit(command, () -> true);
    }

    public synchronized void submit(HostSessionCommand command,
            java.util.function.BooleanSupplier maySubmit) {
        if(command == null) throw new IllegalArgumentException("Host command cannot be null.");
        if(command.getParticipantId() == null) {
            throw new IllegalArgumentException("Host command Participant identity cannot be null.");
        }
        if(maySubmit == null) throw new IllegalArgumentException("Command gate cannot be null.");
        if(maySubmit.getAsBoolean()) pendingCommands.add(command);
    }

    public void advanceOneTick() {
        advanceOneTick(() -> true);
    }

    /** Recheck pause while holding session monitor, before touching any simulation state. */
    public boolean advanceOneTick(java.util.function.BooleanSupplier mayAdvance) {
        if(mayAdvance == null) throw new IllegalArgumentException("Tick gate cannot be null.");
        final long tick;
        final HostSessionSnapshot snapshot;
        final TickOutput output;
        synchronized(this) {
            if(!mayAdvance.getAsBoolean()) return false;
            hostTick++;
            tick = hostTick;
            output = new TickOutput(tick);
            int commandCount = pendingCommands.size();
            for(int i = 0; i < commandCount; i++) {
                simulation.applyCommand(tick, pendingCommands.remove(), output);
            }

            simulation.tick(tick, FIXED_DELTA_SECONDS, output);
            snapshot = simulation.snapshot(tick);
            if(snapshot == null) {
                throw new IllegalStateException("Host simulation returned a null snapshot.");
            }
        }
        output.publishPending();
        transport.publishSnapshot(tick, snapshot);
        return true;
    }

    /** Quiesces prior simulation and discards commands accepted for its floor. */
    public synchronized long discardPendingCommands() {
        pendingCommands.clear();
        return hostTick;
    }

    public synchronized long getHostTick() {
        return hostTick;
    }

    private final class TickOutput implements HostSessionOutput {
        private final long tick;
        private final Queue<Runnable> pendingOutputs = new ArrayDeque<Runnable>();

        private TickOutput(long tick) {
            this.tick = tick;
        }

        @Override
        public void event(final HostSessionEvent event) {
            if(event == null) throw new IllegalArgumentException("Host event cannot be null.");
            pendingOutputs.add(new Runnable() {
                @Override public void run() { transport.publishEvent(tick, event); }
            });
        }

        @Override
        public void disconnect(final HostDisconnectOutcome outcome) {
            if(outcome == null) throw new IllegalArgumentException("Disconnect outcome cannot be null.");
            pendingOutputs.add(new Runnable() {
                @Override public void run() { transport.publishDisconnect(tick, outcome); }
            });
        }

        @Override
        public void transition(final HostTransitionOutcome outcome) {
            if(outcome == null) throw new IllegalArgumentException("Transition outcome cannot be null.");
            pendingOutputs.add(new Runnable() {
                @Override public void run() { transport.publishTransition(tick, outcome); }
            });
        }

        @Override
        public void persist(final HostPersistedState state) {
            if(state == null) throw new IllegalArgumentException("Persisted Host state cannot be null.");
            pendingOutputs.add(new Runnable() {
                @Override public void run() { storage.persist(tick, state); }
            });
        }

        private void publishPending() {
            while(!pendingOutputs.isEmpty()) pendingOutputs.remove().run();
        }
    }
}
