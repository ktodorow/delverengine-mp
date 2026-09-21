package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.editor.EditorMarker;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Model;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.generator.GenInfo;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.tiles.Tile;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Host movement against solid native objects, the way native Player physics treats them. */
public class NativeObstacleMovementTest {
    private static final ParticipantId PARTICIPANT = new ParticipantId("campaign-slot-1");
    private static final float EAST = (float)(Math.PI / 2.0);

    @Test public void walkwayOverPitCarriesParticipantWhereTilesAloneDropThem() {
        LevelMovementCollisionWorld tilesOnly = new LevelMovementCollisionWorld(pitFloor());
        float[] fell = walkEast(tilesOnly, 240);
        assertTrue("Without native walkway the Host drops the Participant into the pit", fell[1] < -1f);

        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(pitFloor());
        // Stone half-block walkway, top level with the floor, spanning both pit tiles.
        world.setWorldObstacles(Collections.singletonList(
                new MovementObstacle(5f, 2.5f, -0.5f, 1f, 0.5f, 0.5f, true, true, false)));
        float[] crossed = walkEast(world, 240);

        assertTrue("Participant crossed the walkway", crossed[0] > 6.5f);
        assertTrue("Participant never fell: lowest height " + crossed[1], crossed[1] > -0.01f);
    }

    @Test public void tallNativeObjectBlocksWhileLowObjectIsClimbed() {
        LevelMovementCollisionWorld column = new LevelMovementCollisionWorld(openFloor());
        column.setWorldObstacles(Collections.singletonList(
                new MovementObstacle(3.5f, 2.5f, 0f, 0.3f, 0.3f, 2f, true, true, false)));
        assertTrue("Column stops the Participant", walkEast(column, 120)[0] < 3.05f);

        LevelMovementCollisionWorld step = new LevelMovementCollisionWorld(openFloor());
        step.setWorldObstacles(Collections.singletonList(
                new MovementObstacle(4.5f, 2.5f, 0f, 1f, 0.5f, 0.2f, true, true, false)));
        AuthoritativeMovementSimulation simulation = simulation(step);
        float highest = 0f;
        for(int tick = 1; tick <= 60; tick++) {
            move(simulation, tick);
            highest = Math.max(highest, simulation.getState(PARTICIPANT).getZ());
        }
        assertEquals("Participant stands on the low native object", 0.2f, highest, 0.001f);
    }

    @Test public void triggerLikeObjectBlocksButIsNeverAFloor() {
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(openFloor());
        world.setWorldObstacles(Collections.singletonList(
                new MovementObstacle(3.5f, 2.5f, 0f, 0.4f, 0.4f, 0.3f, false, false, false)));

        assertFalse(world.canOccupy(3.5f, 2.5f, 0f));
        assertEquals("Native Player bounces off Triggers", 0f, world.getFloorZ(3.5f, 2.5f, 0.31f), 0.001f);
    }

    @Test public void onlyDoorsAndBreakablesBlockMonsterSight() {
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(openFloor());
        world.setWorldObstacles(Collections.singletonList(
                new MovementObstacle(3.5f, 2.5f, 0f, 0.3f, 0.3f, 2f, true, true, false)));
        assertTrue("Columns keep existing monster sight", world.hasLineOfSight(1.5f, 2.5f, 6.5f, 2.5f));

        world.setWorldObstacles(Collections.singletonList(
                new MovementObstacle(3.5f, 2.5f, 0f, 0.3f, 0.3f, 2f, false, false, true)));
        assertFalse(world.hasLineOfSight(1.5f, 2.5f, 6.5f, 2.5f));
    }

    @Test public void arrivingParticipantsStartOnUpStairsLikeNativeStairs() {
        Level level = openFloor();
        level.playerStartX = null;
        level.playerStartY = null;
        level.editorMarkers.add(new EditorMarker(GenInfo.Markers.stairUp, 6, 4));

        MovementSpawn spawn = new LevelMovementCollisionWorld(level).getSpawn(1);

        assertEquals(6.5f, spawn.getX(), 0.001f);
        assertEquals(4.55f, spawn.getY(), 0.001f);
        assertEquals("Native arrival faces away from the stairs", (float)Math.PI, spawn.getRotation(), 0.001f);
    }

    @Test public void copiesOnlySolidObjectsTheNativePlayerCollidesWith() {
        java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        com.interrupt.managers.StringManager.localizedStrings =
                new java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
        try {
            copiesSolidObjects();
        }
        finally {
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
        }
    }

    private void copiesSolidObjects() {
        Level level = new Level(8, 8);
        Model walkway = solid(new Model(), 1.5f);
        Breakable crate = solid(new Breakable(), 2.5f);
        Trigger shopkeeper = solid(new Trigger(), 3.5f);
        Model ghost = solid(new Model(), 4.5f);
        ghost.ignorePlayerCollision = true;
        Model staticOnly = solid(new Model(), 5.5f);
        staticOnly.collidesWith = Entity.CollidesWith.staticOnly;
        Model decoration = new Model();
        decoration.isSolid = false;
        Monster monster = solid(new Monster(), 6.5f);
        Armor item = solid(new Armor(), 7.5f);
        level.static_entities.addAll(walkway, crate, shopkeeper, ghost, staticOnly, decoration);
        level.entities.addAll(monster, item);

        NativeMovementObstacles copier = new NativeMovementObstacles();
        List<MovementObstacle> obstacles = copier.changed(level);

        assertNotNull(obstacles);
        assertEquals(Arrays.asList(1.5f, 2.5f, 3.5f), Arrays.asList(
                obstacles.get(0).minX + 0.5f, obstacles.get(1).minX + 0.5f, obstacles.get(2).minX + 0.5f));
        assertTrue(obstacles.get(0).standable && obstacles.get(0).stepable && !obstacles.get(0).blocksSight);
        assertTrue("Crates keep blocking monster sight", obstacles.get(1).blocksSight);
        assertFalse("Triggers are not floors", obstacles.get(2).standable || obstacles.get(2).stepable);
        assertNull("Unchanged floor is not copied again", copier.changed(level));

        crate.isActive = false;
        assertEquals("Broken crate stops blocking", 2, copier.changed(level).size());
    }

    private static <T extends Entity> T solid(T entity, float x) {
        entity.isSolid = true;
        entity.isActive = true;
        entity.x = x;
        entity.y = 2.5f;
        entity.z = -0.5f;
        entity.collision.set(0.5f, 0.5f, 0.5f);
        return entity;
    }

    /** Returns final x and lowest height while holding forward toward +x. */
    private static float[] walkEast(LevelMovementCollisionWorld world, int ticks) {
        AuthoritativeMovementSimulation simulation = simulation(world);
        float lowest = Float.MAX_VALUE;
        for(int tick = 1; tick <= ticks; tick++) {
            move(simulation, tick);
            lowest = Math.min(lowest, simulation.getState(PARTICIPANT).getZ());
        }
        return new float[] { simulation.getState(PARTICIPANT).getX(), lowest };
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

    /** 10x6 open floor with feet at height 0; Participants start at (1.5, 2.5). */
    private static Level openFloor() {
        Level level = new Level(10, 6);
        for(int index = 0; index < level.tiles.length; index++) level.tiles[index] = new Tile();
        level.playerStartX = 1;
        level.playerStartY = 2;
        return level;
    }

    /** Same floor with a 9.5-deep pit across columns 4 and 5. */
    private static Level pitFloor() {
        Level level = openFloor();
        for(int y = 0; y < level.height; y++) {
            level.tiles[4 + y * level.width].floorHeight = -9.5f;
            level.tiles[5 + y * level.width].floorHeight = -9.5f;
        }
        return level;
    }
}
