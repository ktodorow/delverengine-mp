package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
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
