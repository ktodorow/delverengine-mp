package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.host.HostDisconnectOutcome;
import com.interrupt.dungeoneer.multiplayer.host.HostPersistedState;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.host.HostTransitionOutcome;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AuthoritativeMovementSimulationTest {
    private static final float FIXED_DELTA = 1f / 60f;
    private static final HostSessionOutput NO_OUTPUT = new HostSessionOutput() {
        @Override public void event(HostSessionEvent event) { }
        @Override public void disconnect(HostDisconnectOutcome outcome) { }
        @Override public void transition(HostTransitionOutcome outcome) { }
        @Override public void persist(HostPersistedState state) { }
    };

    @Test
    public void duplicatedAndSkippedInputTicksDoNotDuplicateAuthoritativeMovement() {
        AuthoritativeMovementSimulation once = simulation(1);
        AuthoritativeMovementSimulation duplicated = simulation(1);
        MovementInputCommand input = command(1, 1L, 1f, 0f, 0f, false);

        once.applyCommand(1L, input, NO_OUTPUT);
        duplicated.applyCommand(1L, input, NO_OUTPUT);
        duplicated.applyCommand(1L, input, NO_OUTPUT);
        for(int tick = 1; tick <= 60; tick++) {
            once.tick(tick, FIXED_DELTA, NO_OUTPUT);
            duplicated.tick(tick, FIXED_DELTA, NO_OUTPUT);
        }

        MovementEntityState expected = once.getState(participant(1));
        MovementEntityState actual = duplicated.getState(participant(1));
        assertEquals(expected.getX(), actual.getX(), 0f);
        assertEquals(expected.getY(), actual.getY(), 0f);
        assertEquals(1L, actual.getLastProcessedInputTick());

        duplicated.applyCommand(61L,
                command(1, 4L, 0f, 0f, 0f, false), NO_OUTPUT);
        duplicated.applyCommand(61L,
                command(1, 3L, -1f, 0f, 0f, false), NO_OUTPUT);
        duplicated.applyCommand(61L,
                command(1, 2L, 1f, 0f, 0f, false), NO_OUTPUT);
        duplicated.tick(61L, FIXED_DELTA, NO_OUTPUT);
        assertEquals(4L, duplicated.getState(participant(1)).getLastProcessedInputTick());
    }

    @Test
    public void hostAcknowledgesEachBundledInputOnlyAfterItsSimulationStep() {
        AuthoritativeMovementSimulation simulation = simulation(1);
        for(long inputTick = 1L; inputTick <= 8L; inputTick++) {
            simulation.applyCommand(1L,
                    command(1, inputTick, 1f, 0f, 0f, false), NO_OUTPUT);
        }

        assertEquals(0L, simulation.getState(participant(1)).getLastProcessedInputTick());
        simulation.tick(1L, FIXED_DELTA, NO_OUTPUT);
        assertEquals(1L, simulation.getState(participant(1)).getLastProcessedInputTick());
        simulation.tick(2L, FIXED_DELTA, NO_OUTPUT);
        assertEquals(2L, simulation.getState(participant(1)).getLastProcessedInputTick());
    }

    @Test
    public void reorderedInputWaitsForMissingFrameWithinGraceWindow() {
        AuthoritativeMovementSimulation simulation = simulation(1);
        simulation.applyCommand(1L, command(1, 1L, 1f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(1L, FIXED_DELTA, NO_OUTPUT);
        simulation.applyCommand(2L, command(1, 3L, 1f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(2L, FIXED_DELTA, NO_OUTPUT);

        assertEquals(1L, simulation.getState(participant(1)).getLastProcessedInputTick());
        simulation.applyCommand(3L, command(1, 2L, 1f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(3L, FIXED_DELTA, NO_OUTPUT);
        assertEquals(3L, simulation.getState(participant(1)).getLastProcessedInputTick());
    }

    @Test
    public void permanentlyMissingInputCannotBlockNewerMovementForever() {
        AuthoritativeMovementSimulation simulation = simulation(1);
        simulation.applyCommand(1L, command(1, 1L, 1f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(1L, FIXED_DELTA, NO_OUTPUT);
        simulation.applyCommand(2L, command(1, 4L, 1f, 0f, 0f, false), NO_OUTPUT);
        for(long hostTick = 2L; hostTick <= 5L; hostTick++) {
            simulation.tick(hostTick, FIXED_DELTA, NO_OUTPUT);
        }

        assertEquals(4L, simulation.getState(participant(1)).getLastProcessedInputTick());
    }

    @Test
    public void hostOwnsBoundsVelocityCollisionAndMovementState() {
        AuthoritativeMovementSimulation simulation = new AuthoritativeMovementSimulation(
                new RectangularMovementCollisionWorld(2f, 2f, 1f, 1f, 0.5f),
                descriptors(1));
        for(int tick = 1; tick <= 300; tick++) {
            simulation.applyCommand(tick,
                    command(1, tick, 1f, 0f, 0f, false), NO_OUTPUT);
            simulation.tick(tick, FIXED_DELTA, NO_OUTPUT);
        }

        MovementEntityState state = simulation.getState(participant(1));
        assertTrue(state.getY() <= 1.8f);
        assertEquals(0f, state.getVelocityY(), 0f);
        assertEquals(MovementState.BLOCKED, state.getMovementState());
    }

    @Test
    public void participantsPassThroughEachOtherWithoutCollisionOrPush() {
        AuthoritativeMovementSimulation simulation = new AuthoritativeMovementSimulation(
                new RectangularMovementCollisionWorld(10f, 10f, 5f, 5f, 0.5f),
                descriptors(2));
        for(int tick = 1; tick <= 30; tick++) {
            simulation.applyCommand(tick,
                    command(1, tick, 0f, 1f, 0f, false), NO_OUTPUT);
            simulation.applyCommand(tick,
                    command(2, tick, 0f, -1f, 0f, false), NO_OUTPUT);
            simulation.tick(tick, FIXED_DELTA, NO_OUTPUT);
        }

        assertTrue(simulation.getState(participant(1)).getX()
                > simulation.getState(participant(2)).getX());
    }

    @Test
    public void jumpStateAndVerticalVelocityRemainHostOwned() {
        AuthoritativeMovementSimulation simulation = simulation(1);
        simulation.applyCommand(1L, command(1, 1L, 0f, 0f, 0f, true), NO_OUTPUT);
        simulation.tick(1L, FIXED_DELTA, NO_OUTPUT);

        MovementEntityState airborne = simulation.getState(participant(1));
        assertEquals(MovementState.AIRBORNE, airborne.getMovementState());
        assertTrue(airborne.getVelocityZ() > 0f);
        assertTrue(airborne.getZ() > 0.5f);
    }

    @Test
    public void jumpTapSurvivesBundledInputFramesBeforeHostTick() {
        AuthoritativeMovementSimulation simulation = simulation(1);
        simulation.applyCommand(1L, command(1, 1L, 0f, 0f, 0f, true), NO_OUTPUT);
        simulation.applyCommand(1L, command(1, 2L, 0f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(1L, FIXED_DELTA, NO_OUTPUT);

        MovementEntityState airborne = simulation.getState(participant(1));
        assertEquals(1L, airborne.getLastProcessedInputTick());
        assertEquals(MovementState.AIRBORNE, airborne.getMovementState());
        assertTrue(airborne.getVelocityZ() > 0f);
        simulation.tick(2L, FIXED_DELTA, NO_OUTPUT);
        assertEquals(2L, simulation.getState(participant(1)).getLastProcessedInputTick());
    }

    @Test
    public void frozenParticipantRejectsInputsAndStaysAtItsAuthoritativePosition() {
        AuthoritativeMovementSimulation simulation = simulation(1);
        simulation.applyCommand(1L, command(1, 1L, 1f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(1L, FIXED_DELTA, NO_OUTPUT);
        MovementEntityState beforeFreeze = simulation.getState(participant(1));

        simulation.freezeParticipant(participant(1));
        simulation.applyCommand(2L, command(1, 2L, 1f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(2L, FIXED_DELTA, NO_OUTPUT);
        MovementEntityState frozen = simulation.getState(participant(1));

        assertEquals(beforeFreeze.getX(), frozen.getX(), 0f);
        assertEquals(beforeFreeze.getY(), frozen.getY(), 0f);
        assertEquals(1L, frozen.getLastProcessedInputTick());
        assertEquals(MovementState.IDLE, frozen.getMovementState());

        simulation.resumeParticipant(participant(1));
        simulation.applyCommand(3L, command(1, 2L, 1f, 0f, 0f, false), NO_OUTPUT);
        simulation.tick(3L, FIXED_DELTA, NO_OUTPUT);
        assertEquals(2L, simulation.getState(participant(1)).getLastProcessedInputTick());
    }

    private AuthoritativeMovementSimulation simulation(int participants) {
        return new AuthoritativeMovementSimulation(
                new RectangularMovementCollisionWorld(20f, 20f, 10f, 10f, 0.5f),
                descriptors(participants));
    }

    private List<MovementEntityDescriptor> descriptors(int count) {
        List<MovementEntityDescriptor> descriptors =
                new ArrayList<MovementEntityDescriptor>();
        for(int slot = 1; slot <= count; slot++) {
            descriptors.add(new MovementEntityDescriptor(slot,
                    new NetworkEntityId(slot), participant(slot), slot,
                    "Participant " + slot, "humanoid-" + slot));
        }
        return descriptors;
    }

    private MovementInputCommand command(int slot, long tick, float forward,
            float strafe, float rotation, boolean jump) {
        return new MovementInputCommand(participant(slot),
                new MovementInputFrame(tick, forward, strafe, rotation, jump));
    }

    private ParticipantId participant(int slot) {
        return new ParticipantId("campaign-slot-" + slot);
    }
}
