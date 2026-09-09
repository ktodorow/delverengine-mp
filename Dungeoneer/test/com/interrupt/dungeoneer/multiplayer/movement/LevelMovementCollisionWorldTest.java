package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.tiles.Tile;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LevelMovementCollisionWorldTest {
    private static HeadlessApplication application;

    @BeforeClass
    public static void startHeadlessRuntime() {
        application = new HeadlessApplication(new ApplicationAdapter() { });
    }

    @AfterClass
    public static void stopHeadlessRuntime() {
        if(application != null) application.exit();
    }

    @Test
    public void concurrentCeilingQueryCannotLiftAuthoritativeFloorAboveMap() throws Exception {
        checkConcurrentCeilingQuery(false);
    }

    @Test
    public void concurrentCeilingQueryCannotTeleportParticipantAboveMap() throws Exception {
        checkConcurrentCeilingQuery(true);
    }

    private void checkConcurrentCeilingQuery(boolean simulateParticipant) throws Exception {
        final CountDownLatch floorStarted = new CountDownLatch(1);
        final CountDownLatch ceilingFinished = new CountDownLatch(1);
        final boolean[] armed = { !simulateParticipant };
        Tile floor = new Tile() {
            private boolean interleaved;
            @Override public float getNWFloorHeight() {
                if(armed[0] && !interleaved) {
                    interleaved = true;
                    floorStarted.countDown();
                    try {
                        assertTrue("Ceiling query timed out", ceilingFinished.await(5, TimeUnit.SECONDS));
                    }
                    catch(InterruptedException e) { throw new AssertionError(e); }
                }
                return super.getNWFloorHeight();
            }
        };
        floor.slopeNW = 0.1f;
        Level level = new Level(4, 4);
        java.util.Arrays.fill(level.tiles, floor);
        level.playerStartX = level.playerStartY = 1;
        final LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(level);
        final ParticipantId participant = new ParticipantId("campaign-slot-1");
        AuthoritativeMovementSimulation simulation = null;
        if(simulateParticipant) {
            // Force render work to overlap the real Host landing query, after
            // spawn and horizontal collision checks have completed normally.
            MovementCollisionWorld scheduledWorld = new MovementCollisionWorld() {
                @Override public MovementSpawn getSpawn(int slot) { return world.getSpawn(slot); }
                @Override public boolean canOccupy(float x, float y, float z) {
                    return world.canOccupy(x, y, z);
                }
                @Override public boolean hasLineOfSight(float x, float y, float toX, float toY) {
                    return world.hasLineOfSight(x, y, toX, toY);
                }
                @Override public float getFloorZ(float x, float y, float z) {
                    armed[0] = true;
                    return world.getFloorZ(x, y, z);
                }
            };
            simulation = new AuthoritativeMovementSimulation(scheduledWorld,
                    java.util.Collections.singletonList(new MovementEntityDescriptor(1L,
                            new NetworkEntityId(1L), participant, 1, "Host", "humanoid-1")));
        }
        final Tile ceiling = new Tile();
        ceiling.ceilHeight = 8f;
        ceiling.ceilSlopeNW = 0.2f;
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread renderer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    assertTrue("Floor query timed out", floorStarted.await(5, TimeUnit.SECONDS));
                    ceiling.getCeilHeight(0.2f, 0.3f);
                }
                catch(Throwable e) { failure.set(e); }
                finally { ceilingFinished.countDown(); }
            }
        }, "concurrent-ceiling-query");
        renderer.start();
        try {
            if(simulation == null) {
                float floorZ = world.getFloorZ(1.4f, 1.4f, 0f);
                assertEquals("Concurrent rendering must not replace floor with ceiling height",
                        0.06f, floorZ, 0.00001f);
            }
            else {
                float spawnZ = simulation.getState(participant).getZ();
                simulation.applyCommand(1L, new MovementInputCommand(participant,
                        new MovementInputFrame(1L, 0f, 0f, 0f, false)), null);
                simulation.tick(1L, 1f / 60f, null);
                assertEquals("Idle participant must stay on floor during concurrent rendering",
                        spawnZ, simulation.getState(participant).getZ(), 0.00001f);
            }
        }
        finally { renderer.join(6000); }
        if(failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test
    public void usesRepositoryTestFloorMarkerAndRejectsOutsideBounds() {
        File testFloor = new File("assets", GameApplication.OPEN_SOURCE_TEST_LEVEL);
        Level level = KryoSerializer.loadLevel(new FileHandle(testFloor));
        assertNotNull(level);
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(level);

        MovementSpawn spawn = world.getSpawn(1);
        assertTrue(world.canOccupy(spawn.getX(), spawn.getY(), spawn.getZ()));
        assertFalse(world.canOccupy(-1f, spawn.getY(), spawn.getZ()));
        assertFalse(world.canOccupy(level.width + 1f, spawn.getY(), spawn.getZ()));
    }

    @Test
    public void closedDoorBlocksMovementAndOpeningRestoresPassage() {
        Level level = KryoSerializer.loadLevel(new FileHandle(
                new File("assets", GameApplication.OPEN_SOURCE_TEST_LEVEL)));
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(level);
        MovementSpawn spawn = world.getSpawn(1);
        world.setDoorObstacles(java.util.Collections.singletonList(new MovementObstacle(
                spawn.getX(), spawn.getY(), spawn.getZ(), 0.5f, 0.1f, 1f)));
        assertFalse(world.canOccupy(spawn.getX(), spawn.getY(), spawn.getZ()));
        world.setDoorObstacles(java.util.Collections.<MovementObstacle>emptyList());
        assertTrue(world.canOccupy(spawn.getX(), spawn.getY(), spawn.getZ()));
    }

    @Test
    public void doorBoundsBlockCrossingRaysButAllowRaysBesideDoor() {
        MovementObstacle door = new MovementObstacle(2, 2, 0, 0.5f, 0.1f, 1f);
        assertTrue(door.blocksSegment(2, 1, 2, 3));
        assertTrue(door.blocksSegment(1, 2, 3, 2));
        assertTrue(door.blocksSegment(3, 3, 1, 1));
        assertFalse(door.blocksSegment(1, 1, 1, 3));
        assertFalse(door.blocksSegment(2, 1, 2, 1.5f));
    }

    @Test
    public void givesEachCampaignSlotAValidDistinctSpawnWhenSpaceAllows() {
        File testFloor = new File("assets", GameApplication.OPEN_SOURCE_TEST_LEVEL);
        Level level = KryoSerializer.loadLevel(new FileHandle(testFloor));
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(level);

        MovementSpawn first = world.getSpawn(1);
        MovementSpawn second = world.getSpawn(2);
        assertTrue(world.canOccupy(first.getX(), first.getY(), first.getZ()));
        assertTrue(world.canOccupy(second.getX(), second.getY(), second.getZ()));
        assertNotEquals(first.getX(), second.getX(), 0f);
    }
}
