package com.interrupt.dungeoneer.multiplayer.host;

import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.game.Level;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Small authoritative model used only to exercise session mechanics over repository test data. */
final class OpenSourceTestHostSimulation implements AuthoritativeHostSimulation {
    private final int floorWidth;
    private final int floorHeight;
    private final int startX;
    private final int startY;
    private final Map<String, MutableParticipant> participants =
            new TreeMap<String, MutableParticipant>();
    private String floorId = GameApplication.OPEN_SOURCE_TEST_LEVEL;
    private long simulationTicks = 0L;
    private float elapsedSeconds = 0f;
    private float lastFixedDeltaSeconds = 0f;

    OpenSourceTestHostSimulation(Level level) {
        if(level == null) throw new IllegalArgumentException("Open-source test floor cannot be null.");
        if(level.width <= 0 || level.height <= 0) {
            throw new IllegalArgumentException("Open-source test floor dimensions must be positive.");
        }
        floorWidth = level.width;
        floorHeight = level.height;
        startX = clamp(level.playerStartX == null ? floorWidth / 2 : level.playerStartX,
                0, floorWidth - 1);
        startY = clamp(level.playerStartY == null ? floorHeight / 2 : level.playerStartY,
                0, floorHeight - 1);
    }

    @Override
    public void applyCommand(long hostTick, HostSessionCommand command, HostSessionOutput output) {
        if(!(command instanceof SyntheticCommand)) {
            throw new IllegalArgumentException("Unsupported synthetic Host command: " + command);
        }

        SyntheticCommand synthetic = (SyntheticCommand)command;
        switch(synthetic.kind) {
            case JOIN:
                if(participants.containsKey(synthetic.participantId)) {
                    throw new IllegalArgumentException("Participant already joined: "
                            + synthetic.participantId);
                }
                participants.put(synthetic.participantId,
                        new MutableParticipant(startX, startY));
                output.event(new TestEvent(synthetic.participantId, "joined"));
                break;
            case MOVE:
                MutableParticipant moving = requireParticipant(synthetic.participantId);
                moving.velocityX = synthetic.x;
                moving.velocityY = synthetic.y;
                break;
            case EVENT:
                requireParticipant(synthetic.participantId);
                output.event(new TestEvent(synthetic.participantId, synthetic.value));
                break;
            case DISCONNECT:
                requireParticipant(synthetic.participantId);
                participants.remove(synthetic.participantId);
                output.disconnect(new TestDisconnectOutcome(synthetic.participantId,
                        synthetic.value));
                break;
            case TRANSITION:
                requireParticipant(synthetic.participantId);
                String previousFloor = floorId;
                floorId = synthetic.value;
                output.transition(new TestTransitionOutcome(synthetic.participantId,
                        previousFloor, floorId));
                break;
            case PERSIST:
                requireParticipant(synthetic.participantId);
                output.persist(new TestPersistedState(canonicalState(hostTick)));
                break;
            default:
                throw new IllegalStateException("Unhandled synthetic Host command: "
                        + synthetic.kind);
        }
    }

    @Override
    public void tick(long hostTick, float fixedDeltaSeconds, HostSessionOutput output) {
        simulationTicks++;
        elapsedSeconds += fixedDeltaSeconds;
        lastFixedDeltaSeconds = fixedDeltaSeconds;
        for(MutableParticipant participant : participants.values()) {
            participant.x = clamp(participant.x + participant.velocityX, 0, floorWidth - 1);
            participant.y = clamp(participant.y + participant.velocityY, 0, floorHeight - 1);
        }
    }

    @Override
    public HostSessionSnapshot snapshot(long hostTick) {
        Map<String, TestParticipantState> participantStates =
                new LinkedHashMap<String, TestParticipantState>();
        for(Map.Entry<String, MutableParticipant> entry : participants.entrySet()) {
            MutableParticipant participant = entry.getValue();
            participantStates.put(entry.getKey(),
                    new TestParticipantState(participant.x, participant.y));
        }
        return new TestSnapshot(hostTick, floorId, floorWidth, floorHeight, participantStates);
    }

    long getSimulationTicks() {
        return simulationTicks;
    }

    float getElapsedSeconds() {
        return elapsedSeconds;
    }

    float getLastFixedDeltaSeconds() {
        return lastFixedDeltaSeconds;
    }

    private MutableParticipant requireParticipant(String participantId) {
        MutableParticipant participant = participants.get(participantId);
        if(participant == null) {
            throw new IllegalArgumentException("Unknown synthetic Participant: " + participantId);
        }
        return participant;
    }

    private String canonicalState(long hostTick) {
        StringBuilder state = new StringBuilder();
        state.append("tick=").append(hostTick).append(";floor=").append(floorId);
        for(Map.Entry<String, MutableParticipant> entry : participants.entrySet()) {
            MutableParticipant participant = entry.getValue();
            state.append(';').append(entry.getKey()).append('=')
                    .append(participant.x).append(',').append(participant.y);
        }
        return state.toString();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class MutableParticipant {
        private int x;
        private int y;
        private int velocityX = 0;
        private int velocityY = 0;

        private MutableParticipant(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    static final class SyntheticCommand implements HostSessionCommand {
        private final CommandKind kind;
        private final String participantId;
        private final int x;
        private final int y;
        private final String value;

        private SyntheticCommand(CommandKind kind, String participantId, int x, int y,
                String value) {
            if(participantId == null || participantId.trim().isEmpty()) {
                throw new IllegalArgumentException("Synthetic Participant identity is required.");
            }
            this.kind = kind;
            this.participantId = participantId;
            this.x = x;
            this.y = y;
            this.value = value;
        }

        static SyntheticCommand join(String participantId) {
            return new SyntheticCommand(CommandKind.JOIN, participantId, 0, 0, null);
        }

        static SyntheticCommand move(String participantId, int velocityX, int velocityY) {
            return new SyntheticCommand(CommandKind.MOVE, participantId, velocityX, velocityY,
                    null);
        }

        static SyntheticCommand event(String participantId, String event) {
            return new SyntheticCommand(CommandKind.EVENT, participantId, 0, 0,
                    requireValue(event, "Synthetic event is required."));
        }

        static SyntheticCommand disconnect(String participantId, String reason) {
            return new SyntheticCommand(CommandKind.DISCONNECT, participantId, 0, 0,
                    requireValue(reason, "Disconnect reason is required."));
        }

        static SyntheticCommand transition(String participantId, String floorId) {
            return new SyntheticCommand(CommandKind.TRANSITION, participantId, 0, 0,
                    requireValue(floorId, "Transition floor is required."));
        }

        static SyntheticCommand persist(String participantId) {
            return new SyntheticCommand(CommandKind.PERSIST, participantId, 0, 0, null);
        }

        private static String requireValue(String value, String message) {
            if(value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
            return value;
        }
    }

    private enum CommandKind {
        JOIN,
        MOVE,
        EVENT,
        DISCONNECT,
        TRANSITION,
        PERSIST
    }

    static final class TestSnapshot implements HostSessionSnapshot {
        private final long hostTick;
        private final String floorId;
        private final int floorWidth;
        private final int floorHeight;
        private final Map<String, TestParticipantState> participants;

        private TestSnapshot(long hostTick, String floorId, int floorWidth, int floorHeight,
                Map<String, TestParticipantState> participants) {
            this.hostTick = hostTick;
            this.floorId = floorId;
            this.floorWidth = floorWidth;
            this.floorHeight = floorHeight;
            this.participants = Collections.unmodifiableMap(
                    new LinkedHashMap<String, TestParticipantState>(participants));
        }

        long getHostTick() {
            return hostTick;
        }

        String getFloorId() {
            return floorId;
        }

        int getFloorWidth() {
            return floorWidth;
        }

        int getFloorHeight() {
            return floorHeight;
        }

        Map<String, TestParticipantState> getParticipants() {
            return participants;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof TestSnapshot)) return false;
            TestSnapshot that = (TestSnapshot)other;
            return hostTick == that.hostTick && floorWidth == that.floorWidth
                    && floorHeight == that.floorHeight && floorId.equals(that.floorId)
                    && participants.equals(that.participants);
        }

        @Override
        public int hashCode() {
            int result = (int)(hostTick ^ (hostTick >>> 32));
            result = 31 * result + floorId.hashCode();
            result = 31 * result + floorWidth;
            result = 31 * result + floorHeight;
            return 31 * result + participants.hashCode();
        }

        @Override
        public String toString() {
            return floorId + "@" + hostTick + participants;
        }
    }

    static final class TestParticipantState {
        private final int x;
        private final int y;

        private TestParticipantState(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof TestParticipantState)) return false;
            TestParticipantState that = (TestParticipantState)other;
            return x == that.x && y == that.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }

        @Override
        public String toString() {
            return x + "," + y;
        }
    }

    static final class TestEvent implements HostSessionEvent {
        private final String participantId;
        private final String event;

        private TestEvent(String participantId, String event) {
            this.participantId = participantId;
            this.event = event;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof TestEvent)) return false;
            TestEvent that = (TestEvent)other;
            return participantId.equals(that.participantId) && event.equals(that.event);
        }

        @Override
        public int hashCode() {
            return 31 * participantId.hashCode() + event.hashCode();
        }

        @Override
        public String toString() {
            return participantId + ":" + event;
        }
    }

    static final class TestDisconnectOutcome implements HostDisconnectOutcome {
        private final String participantId;
        private final String reason;

        private TestDisconnectOutcome(String participantId, String reason) {
            this.participantId = participantId;
            this.reason = reason;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof TestDisconnectOutcome)) return false;
            TestDisconnectOutcome that = (TestDisconnectOutcome)other;
            return participantId.equals(that.participantId) && reason.equals(that.reason);
        }

        @Override
        public int hashCode() {
            return 31 * participantId.hashCode() + reason.hashCode();
        }

        @Override
        public String toString() {
            return participantId + ":" + reason;
        }
    }

    static final class TestTransitionOutcome implements HostTransitionOutcome {
        private final String participantId;
        private final String fromFloor;
        private final String toFloor;

        private TestTransitionOutcome(String participantId, String fromFloor, String toFloor) {
            this.participantId = participantId;
            this.fromFloor = fromFloor;
            this.toFloor = toFloor;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof TestTransitionOutcome)) return false;
            TestTransitionOutcome that = (TestTransitionOutcome)other;
            return participantId.equals(that.participantId) && fromFloor.equals(that.fromFloor)
                    && toFloor.equals(that.toFloor);
        }

        @Override
        public int hashCode() {
            int result = participantId.hashCode();
            result = 31 * result + fromFloor.hashCode();
            return 31 * result + toFloor.hashCode();
        }

        @Override
        public String toString() {
            return participantId + ":" + fromFloor + "->" + toFloor;
        }
    }

    static final class TestPersistedState implements HostPersistedState {
        private final String canonicalState;

        private TestPersistedState(String canonicalState) {
            this.canonicalState = canonicalState;
        }

        String getCanonicalState() {
            return canonicalState;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof TestPersistedState)) return false;
            TestPersistedState that = (TestPersistedState)other;
            return canonicalState.equals(that.canonicalState);
        }

        @Override
        public int hashCode() {
            return canonicalState.hashCode();
        }

        @Override
        public String toString() {
            return canonicalState;
        }
    }
}
