package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.entities.Ladder;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.tiles.Tile;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Host movement climbs native Ladders the way the native Player does. */
public class LadderMovementTest {
    private static final ParticipantId PARTICIPANT = new ParticipantId("campaign-slot-1");
    private static final float EAST = (float)(Math.PI / 2.0);

    @Test public void walkingIntoLadderWhileLookingUpClimbsOntoTheLedge() {
        AuthoritativeMovementSimulation simulation = simulation(ledgeWithLadder());
        float highest = 0f;
        for(int tick = 1; tick <= 240; tick++) {
            walk(simulation, tick, (float)Math.sin(0.3f));
            highest = Math.max(highest, simulation.getState(PARTICIPANT).getZ());
        }
        MovementEntityState state = simulation.getState(PARTICIPANT);
        assertTrue("Participant climbed onto the ledge, reached x " + state.getX(), state.getX() > 5.5f);
        assertEquals(1f, state.getZ(), 0.01f);
        // Native keeps the last climbing speed over the top: a small hop, never a launch.
        assertTrue("Ladder climbing never flings past the ledge: " + highest, highest < 1.15f);
    }

    @Test public void lookingLevelAtLadderDoesNotClimb() {
        AuthoritativeMovementSimulation simulation = simulation(ledgeWithLadder());
        for(int tick = 1; tick <= 240; tick++) walk(simulation, tick, 0f);
        MovementEntityState state = simulation.getState(PARTICIPANT);
        assertEquals(0f, state.getZ(), 0.001f);
        assertTrue(state.getX() < 5f);
    }

    @Test public void ledgeWithoutLadderStaysOutOfReach() {
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(ledge());
        AuthoritativeMovementSimulation simulation = simulation(world);
        for(int tick = 1; tick <= 240; tick++) walk(simulation, tick, (float)Math.sin(0.3f));
        assertEquals(0f, simulation.getState(PARTICIPANT).getZ(), 0.001f);
    }

    /** A ladder on the face of a one-unit ledge that starts at x = 5. */
    private static LevelMovementCollisionWorld ledgeWithLadder() {
        Level level = ledge();
        Ladder ladder = new Ladder();
        ladder.x = 4.96f;
        ladder.y = 2.5f;
        ladder.z = 0f;
        ladder.collision.set(0.04f, 0.3f, 1f);
        level.entities.add(ladder);
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(ledge());
        world.setWorldObstacles(new NativeMovementObstacles().changed(level));
        return world;
    }

    /** 12x6 floor with feet at height 0; columns from 5 are a ledge with feet at height 1. */
    private static Level ledge() {
        Level level = new Level(12, 6);
        for(int index = 0; index < level.tiles.length; index++) {
            Tile tile = new Tile();
            tile.ceilHeight = 3f;
            if(index % level.width >= 5) tile.floorHeight = 0.5f;
            level.tiles[index] = tile;
        }
        level.playerStartX = 3;
        level.playerStartY = 2;
        return level;
    }

    private static AuthoritativeMovementSimulation simulation(LevelMovementCollisionWorld world) {
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(1L), PARTICIPANT, 1, "Host", "humanoid-1");
        return new AuthoritativeMovementSimulation(world, Collections.singletonList(descriptor));
    }

    private static void walk(AuthoritativeMovementSimulation simulation, int tick, float lookY) {
        simulation.applyCommand(tick, new MovementInputCommand(PARTICIPANT,
                new MovementInputFrame(tick, 1f, 0f, EAST, false, lookY)), null);
        simulation.tick(tick, 1f / 60f, null);
    }
}
