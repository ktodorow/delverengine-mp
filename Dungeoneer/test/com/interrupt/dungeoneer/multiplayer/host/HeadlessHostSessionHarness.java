package com.interrupt.dungeoneer.multiplayer.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reusable high-level fixture for deterministic Host-session scenarios. */
public final class HeadlessHostSessionHarness {
    private final ControlledClock clock = new ControlledClock();
    private final InMemoryTransport transport = new InMemoryTransport();
    private final InMemoryStorage storage = new InMemoryStorage();
    private final AuthoritativeHostSession session;
    private final FixedStepHostSessionRunner runner;

    public HeadlessHostSessionHarness(AuthoritativeHostSimulation simulation) {
        session = new AuthoritativeHostSession(simulation, transport, storage);
        runner = new FixedStepHostSessionRunner(session, clock);
    }

    public HostSessionCommandGateway getCommandGateway() {
        return session;
    }

    public void advanceTicks(long ticks) {
        clock.advanceTicks(ticks);
        runner.runPendingTicks();
    }

    public long getHostTick() {
        return session.getHostTick();
    }

    public List<ObservedValue<HostSessionSnapshot>> getSnapshots() {
        return transport.snapshots();
    }

    public List<ObservedValue<HostSessionEvent>> getEvents() {
        return transport.events();
    }

    public List<ObservedValue<HostDisconnectOutcome>> getDisconnects() {
        return transport.disconnects();
    }

    public List<ObservedValue<HostTransitionOutcome>> getTransitions() {
        return transport.transitions();
    }

    public List<ObservedValue<HostPersistedState>> getPersistedStates() {
        return storage.states();
    }

    public static final class ObservedValue<T> {
        private final long hostTick;
        private final T value;

        private ObservedValue(long hostTick, T value) {
            this.hostTick = hostTick;
            this.value = value;
        }

        public long getHostTick() {
            return hostTick;
        }

        public T getValue() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof ObservedValue)) return false;
            ObservedValue<?> that = (ObservedValue<?>)other;
            return hostTick == that.hostTick && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return 31 * (int)(hostTick ^ (hostTick >>> 32)) + value.hashCode();
        }

        @Override
        public String toString() {
            return "tick=" + hostTick + ":" + value;
        }
    }

    private static final class ControlledClock implements HostSessionClock {
        private long targetTick = 0L;

        private void advanceTicks(long ticks) {
            if(ticks < 0L) throw new IllegalArgumentException("Host ticks cannot move backwards.");
            if(Long.MAX_VALUE - targetTick < ticks) {
                throw new IllegalArgumentException("Host tick value overflowed.");
            }
            targetTick += ticks;
        }

        @Override
        public long getTargetTick() {
            return targetTick;
        }
    }

    private static final class InMemoryTransport implements HostSessionTransport {
        private final List<ObservedValue<HostSessionSnapshot>> snapshots =
                new ArrayList<ObservedValue<HostSessionSnapshot>>();
        private final List<ObservedValue<HostSessionEvent>> events =
                new ArrayList<ObservedValue<HostSessionEvent>>();
        private final List<ObservedValue<HostDisconnectOutcome>> disconnects =
                new ArrayList<ObservedValue<HostDisconnectOutcome>>();
        private final List<ObservedValue<HostTransitionOutcome>> transitions =
                new ArrayList<ObservedValue<HostTransitionOutcome>>();

        @Override
        public void publishSnapshot(long hostTick, HostSessionSnapshot snapshot) {
            snapshots.add(new ObservedValue<HostSessionSnapshot>(hostTick, snapshot));
        }

        @Override
        public void publishEvent(long hostTick, HostSessionEvent event) {
            events.add(new ObservedValue<HostSessionEvent>(hostTick, event));
        }

        @Override
        public void publishDisconnect(long hostTick, HostDisconnectOutcome outcome) {
            disconnects.add(new ObservedValue<HostDisconnectOutcome>(hostTick, outcome));
        }

        @Override
        public void publishTransition(long hostTick, HostTransitionOutcome outcome) {
            transitions.add(new ObservedValue<HostTransitionOutcome>(hostTick, outcome));
        }

        private List<ObservedValue<HostSessionSnapshot>> snapshots() {
            return immutableCopy(snapshots);
        }

        private List<ObservedValue<HostSessionEvent>> events() {
            return immutableCopy(events);
        }

        private List<ObservedValue<HostDisconnectOutcome>> disconnects() {
            return immutableCopy(disconnects);
        }

        private List<ObservedValue<HostTransitionOutcome>> transitions() {
            return immutableCopy(transitions);
        }
    }

    private static final class InMemoryStorage implements HostSessionStorage {
        private final List<ObservedValue<HostPersistedState>> states =
                new ArrayList<ObservedValue<HostPersistedState>>();

        @Override
        public void persist(long hostTick, HostPersistedState state) {
            states.add(new ObservedValue<HostPersistedState>(hostTick, state));
        }

        private List<ObservedValue<HostPersistedState>> states() {
            return immutableCopy(states);
        }
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }
}
