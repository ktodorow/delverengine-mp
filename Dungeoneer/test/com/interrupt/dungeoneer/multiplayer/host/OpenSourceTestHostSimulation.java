package com.interrupt.dungeoneer.multiplayer.host;

import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression;

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
    private final Map<ParticipantId, MutableParticipant> participants =
            new TreeMap<ParticipantId, MutableParticipant>();
    private final SharedPartyProgression partyProgression = new SharedPartyProgression();
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
        ParticipantId participantId = synthetic.getParticipantId();
        switch(synthetic.kind) {
            case JOIN:
                if(participants.containsKey(participantId)) {
                    throw new IllegalArgumentException("Participant already joined: "
                            + participantId);
                }
                ParticipantCharacterState character =
                        new ParticipantCharacterState(startX, startY, 0f, 0f);
                participants.put(participantId, new MutableParticipant(
                        new ParticipantContext(participantId, character, partyProgression)));
                output.event(new TestEvent(participantId, "joined"));
                break;
            case MOVE:
                MutableParticipant moving = requireParticipant(participantId);
                moving.velocityX = synthetic.x;
                moving.velocityY = synthetic.y;
                break;
            case SAME_FLOOR_TELEPORT:
                ParticipantContext teleporting = requireParticipant(participantId).context;
                ParticipantCharacter teleportingCharacter = teleporting.getCharacter();
                teleportingCharacter.setPosition(
                        clamp(synthetic.x, 0, floorWidth - 1),
                        clamp(synthetic.y, 0, floorHeight - 1),
                        teleportingCharacter.getZ());
                output.event(new TestEvent(teleporting.getParticipantId(), "teleported"));
                break;
            case PARTY_MUTATION:
                ParticipantContext mutating = requireParticipant(participantId).context;
                mutating.getPartyProgression().putPersistent(synthetic.key, synthetic.value);
                output.event(new TestEvent(mutating.getParticipantId(),
                        "party-mutation:" + synthetic.key));
                break;
            case EVENT:
                requireParticipant(participantId);
                output.event(new TestEvent(participantId, synthetic.value));
                break;
            case DISCONNECT:
                requireParticipant(participantId);
                participants.remove(participantId);
                output.disconnect(new TestDisconnectOutcome(participantId,
                        synthetic.value));
                break;
            case TRANSITION:
                requireParticipant(participantId);
                String previousFloor = floorId;
                floorId = synthetic.value;
                output.transition(new TestTransitionOutcome(participantId,
                        previousFloor, floorId));
                break;
            case PERSIST:
                requireParticipant(participantId);
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
            ParticipantCharacter character = participant.context.getCharacter();
            character.setPosition(
                    clamp(character.getX() + participant.velocityX, 0, floorWidth - 1),
                    clamp(character.getY() + participant.velocityY, 0, floorHeight - 1),
                    character.getZ());
        }
    }

    @Override
    public HostSessionSnapshot snapshot(long hostTick) {
        Map<ParticipantId, TestParticipantState> participantStates =
                new LinkedHashMap<ParticipantId, TestParticipantState>();
        Map<String, String> visiblePartyProgression = partyProgression.snapshotPersistent();
        for(Map.Entry<ParticipantId, MutableParticipant> entry : participants.entrySet()) {
            MutableParticipant participant = entry.getValue();
            ParticipantCharacter character = participant.context.getCharacter();
            participantStates.put(entry.getKey(),
                    new TestParticipantState(character.getX(), character.getY(),
                            visiblePartyProgression, partyProgression.getMutationCount()));
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

    ParticipantContext getParticipant(ParticipantId participantId) {
        return requireParticipant(participantId).context;
    }

    private MutableParticipant requireParticipant(ParticipantId participantId) {
        MutableParticipant participant = participants.get(participantId);
        if(participant == null) {
            throw new IllegalArgumentException("Unknown synthetic Participant: " + participantId);
        }
        return participant;
    }

    private String canonicalState(long hostTick) {
        StringBuilder state = new StringBuilder();
        state.append("tick=").append(hostTick).append(";floor=").append(floorId);
        for(Map.Entry<ParticipantId, MutableParticipant> entry : participants.entrySet()) {
            MutableParticipant participant = entry.getValue();
            ParticipantCharacter character = participant.context.getCharacter();
            state.append(';').append(entry.getKey()).append('=')
                    .append(character.getX()).append(',').append(character.getY());
        }
        state.append(";party=").append(partyProgression.snapshotPersistent());
        return state.toString();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class MutableParticipant {
        private final ParticipantContext context;
        private int velocityX = 0;
        private int velocityY = 0;

        private MutableParticipant(ParticipantContext context) {
            this.context = context;
        }
    }

    static final class SyntheticCommand implements HostSessionCommand {
        private final CommandKind kind;
        private final ParticipantId participantId;
        private final int x;
        private final int y;
        private final String key;
        private final String value;

        private SyntheticCommand(CommandKind kind, String participantId, int x, int y, String key,
                String value) {
            this.kind = kind;
            this.participantId = new ParticipantId(participantId);
            this.x = x;
            this.y = y;
            this.key = key;
            this.value = value;
        }

        static SyntheticCommand join(String participantId) {
            return new SyntheticCommand(CommandKind.JOIN, participantId, 0, 0, null, null);
        }

        static SyntheticCommand move(String participantId, int velocityX, int velocityY) {
            return new SyntheticCommand(CommandKind.MOVE, participantId, velocityX, velocityY,
                    null, null);
        }

        static SyntheticCommand teleportSameFloor(String participantId, int x, int y) {
            return new SyntheticCommand(CommandKind.SAME_FLOOR_TELEPORT, participantId, x, y,
                    null, null);
        }

        static SyntheticCommand mutateParty(String participantId, String key, String value) {
            return new SyntheticCommand(CommandKind.PARTY_MUTATION, participantId, 0, 0,
                    requireValue(key, "Party mutation key is required."),
                    requireValue(value, "Party mutation value is required."));
        }

        static SyntheticCommand event(String participantId, String event) {
            return new SyntheticCommand(CommandKind.EVENT, participantId, 0, 0, null,
                    requireValue(event, "Synthetic event is required."));
        }

        static SyntheticCommand disconnect(String participantId, String reason) {
            return new SyntheticCommand(CommandKind.DISCONNECT, participantId, 0, 0, null,
                    requireValue(reason, "Disconnect reason is required."));
        }

        static SyntheticCommand transition(String participantId, String floorId) {
            return new SyntheticCommand(CommandKind.TRANSITION, participantId, 0, 0, null,
                    requireValue(floorId, "Transition floor is required."));
        }

        static SyntheticCommand persist(String participantId) {
            return new SyntheticCommand(CommandKind.PERSIST, participantId, 0, 0, null, null);
        }

        @Override
        public ParticipantId getParticipantId() {
            return participantId;
        }

        private static String requireValue(String value, String message) {
            if(value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
            return value;
        }
    }

    private enum CommandKind {
        JOIN,
        MOVE,
        SAME_FLOOR_TELEPORT,
        PARTY_MUTATION,
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
        private final Map<ParticipantId, TestParticipantState> participants;

        private TestSnapshot(long hostTick, String floorId, int floorWidth, int floorHeight,
                Map<ParticipantId, TestParticipantState> participants) {
            this.hostTick = hostTick;
            this.floorId = floorId;
            this.floorWidth = floorWidth;
            this.floorHeight = floorHeight;
            this.participants = Collections.unmodifiableMap(
                    new LinkedHashMap<ParticipantId, TestParticipantState>(participants));
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

        Map<ParticipantId, TestParticipantState> getParticipants() {
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
        private final float x;
        private final float y;
        private final Map<String, String> partyProgression;
        private final long partyMutationCount;

        private TestParticipantState(float x, float y, Map<String, String> partyProgression,
                long partyMutationCount) {
            this.x = x;
            this.y = y;
            this.partyProgression = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(partyProgression));
            this.partyMutationCount = partyMutationCount;
        }

        float getX() {
            return x;
        }

        float getY() {
            return y;
        }

        Map<String, String> getPartyProgression() {
            return partyProgression;
        }

        long getPartyMutationCount() {
            return partyMutationCount;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof TestParticipantState)) return false;
            TestParticipantState that = (TestParticipantState)other;
            return Float.compare(x, that.x) == 0 && Float.compare(y, that.y) == 0
                    && partyMutationCount == that.partyMutationCount
                    && partyProgression.equals(that.partyProgression);
        }

        @Override
        public int hashCode() {
            int result = Float.floatToIntBits(x);
            result = 31 * result + Float.floatToIntBits(y);
            result = 31 * result + partyProgression.hashCode();
            return 31 * result + (int)(partyMutationCount ^ (partyMutationCount >>> 32));
        }

        @Override
        public String toString() {
            return x + "," + y + partyProgression;
        }
    }

    static final class TestEvent implements HostSessionEvent {
        private final ParticipantId participantId;
        private final String event;

        private TestEvent(ParticipantId participantId, String event) {
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
        private final ParticipantId participantId;
        private final String reason;

        private TestDisconnectOutcome(ParticipantId participantId, String reason) {
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
        private final ParticipantId participantId;
        private final String fromFloor;
        private final String toFloor;

        private TestTransitionOutcome(ParticipantId participantId, String fromFloor, String toFloor) {
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
