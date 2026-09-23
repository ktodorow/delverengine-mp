package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.tiles.Tile;
import com.interrupt.dungeoneer.tiles.TileData;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Host movement follows native water rules so local prediction never fights authority. */
public class WaterMovementTest {
    private static final ParticipantId PARTICIPANT = new ParticipantId("campaign-slot-1");
    private static final float EAST = (float)(Math.PI / 2.0);

    @Test public void waterSlowsWalkingToNativeShareOfLandSpeed() {
        LevelMovementCollisionWorld land = new LevelMovementCollisionWorld(floor(false));
        LevelMovementCollisionWorld water = new LevelMovementCollisionWorld(floor(true));
        float landSpeed = steadySpeed(land);
        float waterSpeed = steadySpeed(water);

        assertEquals(AuthoritativeMovementSimulation.MAX_SPEED, landSpeed, 0.02f);
        assertEquals(AuthoritativeMovementSimulation.MAX_SPEED
                * AuthoritativeMovementSimulation.WATER_SPEED, waterSpeed, 0.02f);
    }

    @Test public void waterRampsUpSlowlyLikeNativeFriction() {
        AuthoritativeMovementSimulation simulation = simulation(
                new LevelMovementCollisionWorld(floor(true)));
        move(simulation, 1);
        move(simulation, 2);
        move(simulation, 3);
        float early = horizontalSpeed(simulation);
        // Native water needs ~25 ticks to reach 63 percent of top speed; land needs ~5.
        assertTrue("Water ramp is gradual, got " + early, early < 0.25f);
    }

    @Test public void participantSinksInPoolAndClimbsBankNativeStepAllows() {
        // Native step: a bank at most 0.35 above the water tile floor is climbable from water.
        Level level = pool(-0.8f);
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(level);
        AuthoritativeMovementSimulation simulation = simulation(world);
        float lowest = Float.MAX_VALUE;
        for(int tick = 1; tick <= 400; tick++) {
            move(simulation, tick);
            lowest = Math.min(lowest, simulation.getState(PARTICIPANT).getZ());
        }
        MovementEntityState state = simulation.getState(PARTICIPANT);
        // Native water floor sits 0.4 below the tile floor, so feet rest at -0.8 - 0.4 + 0.5.
        assertEquals(-0.7f, lowest, 0.02f);
        assertTrue("Participant climbed out of the pool, reached x " + state.getX(),
                state.getX() > 6.5f);
        assertEquals(0f, state.getZ(), 0.02f);
        assertFalse(Float.isNaN(world.getWaterSurfaceZ(4.5f, 2.5f, -0.7f)));
        assertTrue(Float.isNaN(world.getWaterSurfaceZ(1.5f, 2.5f, 0f)));
    }

    @Test public void bankHigherThanNativeStepStillBlocksFromWater() {
        AuthoritativeMovementSimulation simulation = simulation(
                new LevelMovementCollisionWorld(pool(-1.1f)));
        for(int tick = 1; tick <= 400; tick++) move(simulation, tick);
        MovementEntityState state = simulation.getState(PARTICIPANT);
        assertTrue("Native Player cannot climb 0.6 out of water; reached x " + state.getX(),
                state.getX() < 6f);
        assertEquals(-1.0f, state.getZ(), 0.02f);
    }

    /** Columns 3-5 are water with the given tile floor; land keeps the default -0.5 floor. */
    private static Level pool(float waterFloorHeight) {
        Level level = floor(false);
        for(int y = 0; y < level.height; y++) {
            for(int x = 3; x <= 5; x++) {
                Tile tile = level.tiles[x + y * level.width];
                tile.floorHeight = waterFloorHeight;
                tile.data = water();
            }
        }
        return level;
    }

    private static float steadySpeed(LevelMovementCollisionWorld world) {
        AuthoritativeMovementSimulation simulation = simulation(world);
        for(int tick = 1; tick <= 240; tick++) move(simulation, tick);
        return horizontalSpeed(simulation);
    }

    private static float horizontalSpeed(AuthoritativeMovementSimulation simulation) {
        MovementEntityState state = simulation.getState(PARTICIPANT);
        return (float)Math.sqrt(state.getVelocityX() * state.getVelocityX()
                + state.getVelocityY() * state.getVelocityY());
    }

    private static AuthoritativeMovementSimulation simulation(LevelMovementCollisionWorld world) {
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(1L), PARTICIPANT, 1, "Host", "humanoid-1");
        return new AuthoritativeMovementSimulation(world, Collections.singletonList(descriptor));
    }

    private static void move(AuthoritativeMovementSimulation simulation, int tick) {
        simulation.applyCommand(tick, new MovementInputCommand(PARTICIPANT,
                new MovementInputFrame(tick, 1f, 0f, EAST, false)), null);
        simulation.tick(tick, 1f / 60f, null);
    }

    private static TileData water() {
        TileData data = new TileData();
        data.isWater = true;
        return data;
    }

    /** 40x6 floor with feet at height 0 (default Tile floor -0.5); Participants start at (1.5, 2.5). */
    private static Level floor(boolean isWater) {
        Level level = new Level(40, 6);
        for(int index = 0; index < level.tiles.length; index++) {
            Tile tile = new Tile();
            if(isWater) tile.data = water();
            level.tiles[index] = tile;
        }
        level.playerStartX = 1;
        level.playerStartY = 2;
        return level;
    }
}
