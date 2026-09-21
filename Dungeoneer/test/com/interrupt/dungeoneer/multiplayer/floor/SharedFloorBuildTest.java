package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.math.MathUtils;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.managers.EntityManager;
import com.interrupt.managers.ItemManager;
import com.interrupt.managers.MonsterManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SharedFloorBuildTest {
    private Game previousGame;
    private EntityManager previousEntities;
    private MonsterManager previousMonsters;
    private int previousDetail;
    private float previousQuality;

    @Before public void installManagers() {
        previousGame = Game.instance;
        previousEntities = EntityManager.instance;
        previousMonsters = MonsterManager.instance;
        previousDetail = Options.instance.graphicsDetailLevel;
        previousQuality = Options.instance.gfxQuality;
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.itemManager = new ItemManager();
        game.monsterManager = new MonsterManager();
        game.entityManager = new EntityManager();
        Game.instance = game;
        EntityManager.instance = game.entityManager;
        MonsterManager.instance = game.monsterManager;
    }

    @After public void restore() {
        Game.instance = previousGame;
        EntityManager.instance = previousEntities;
        MonsterManager.instance = previousMonsters;
        Options.instance.graphicsDetailLevel = previousDetail;
        Options.instance.gfxQuality = previousQuality;
    }

    @Test public void sameSeedRepeatsEveryBuildGeneratorDespiteLocalHistoryAndSettings()
            throws Exception {
        List<Long> host = buildDraws(0x5EEDL, 4, 1.5f, 3);
        List<Long> client = buildDraws(0x5EEDL, 1, 0.25f, 17);

        assertEquals(host, client);
    }

    @Test public void differentSeedsBuildDifferentFloors() throws Exception {
        assertNotEquals(buildDraws(1L, 3, 1f, 0), buildDraws(2L, 3, 1f, 0));
    }

    @Test public void spawnAffectingOptionsUseDesktopDefaultsOnlyWhileBuilding() {
        Options.instance.graphicsDetailLevel = 1;
        Options.instance.gfxQuality = 0.25f;
        SharedFloorBuild build = new SharedFloorBuild(7L);

        build.begin();
        assertEquals(SharedFloorBuild.CANONICAL_GRAPHICS_DETAIL_LEVEL, Options.instance.graphicsDetailLevel);
        assertEquals(SharedFloorBuild.CANONICAL_GFX_QUALITY, Options.instance.gfxQuality, 0f);
        build.end();

        assertEquals(1, Options.instance.graphicsDetailLevel);
        assertEquals(0.25f, Options.instance.gfxQuality, 0f);
        assertFalse(build.isActive());
    }

    @Test public void oneDivergentMarkerRollCannotShiftLaterMarkers() throws Exception {
        SharedFloorBuild host = new SharedFloorBuild(99L);
        host.begin();
        host.enterEntity(SharedFloorBuild.Phase.MARKERS, 0, 0);
        host.enterEntity(SharedFloorBuild.Phase.MARKERS, 0, 1);
        List<Long> hostNext = draws();
        host.end();

        SharedFloorBuild client = new SharedFloorBuild(99L);
        client.begin();
        client.enterEntity(SharedFloorBuild.Phase.MARKERS, 0, 0);
        Game.rand.nextFloat();
        MathUtils.random.nextInt();
        managerRandom(Game.instance.itemManager).nextDouble();
        client.enterEntity(SharedFloorBuild.Phase.MARKERS, 0, 1);
        List<Long> clientNext = draws();
        client.end();

        assertEquals(hostNext, clientNext);
    }

    @Test public void finishedBuildReturnsUnpredictableRandomness() {
        SharedFloorBuild first = new SharedFloorBuild(5L);
        first.begin();
        first.end();
        long afterFirst = Game.rand.nextLong();

        SharedFloorBuild second = new SharedFloorBuild(5L);
        second.begin();
        second.end();
        long afterSecond = Game.rand.nextLong();

        assertNotEquals(afterFirst, afterSecond);
        second.enter(SharedFloorBuild.Phase.DECORATION);
        assertNotEquals("Finished build must not reseed", afterSecond, Game.rand.nextLong());
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingSeedIsRejected() {
        new SharedFloorBuild(0L);
    }

    @Test public void nestedBuildIsRejectedAndLeavesOptionsRestorable() {
        Options.instance.graphicsDetailLevel = 2;
        SharedFloorBuild build = new SharedFloorBuild(3L);
        build.begin();
        try {
            build.begin();
        }
        catch(IllegalStateException expected) {
            assertTrue(build.isActive());
        }
        finally {
            build.end();
        }
        assertEquals(2, Options.instance.graphicsDetailLevel);
    }

    /** One peer's draws across every phase after arbitrary local history and settings. */
    private List<Long> buildDraws(long seed, int detail, float quality, int history)
            throws Exception {
        Options.instance.graphicsDetailLevel = detail;
        Options.instance.gfxQuality = quality;
        Random local = new Random(history);
        for(int index = 0; index < history; index++) {
            Game.rand.nextInt();
            MathUtils.random.nextFloat();
            managerRandom(Game.instance.itemManager).nextInt(local.nextInt(50) + 1);
            managerRandom(Game.instance.monsterManager).nextBoolean();
            managerRandom(EntityManager.instance).nextLong();
        }
        List<Long> result = new ArrayList<Long>();
        SharedFloorBuild build = new SharedFloorBuild(seed);
        build.begin();
        result.addAll(draws());
        for(SharedFloorBuild.Phase phase : SharedFloorBuild.Phase.values()) {
            build.enter(phase);
            result.addAll(draws());
            build.enterEntity(phase, 2, 11);
            result.addAll(draws());
        }
        build.end();
        return result;
    }

    private List<Long> draws() throws Exception {
        List<Long> result = new ArrayList<Long>();
        result.add(Game.rand.nextLong());
        result.add(MathUtils.random.nextLong());
        result.add(managerRandom(Game.instance.itemManager).nextLong());
        result.add(managerRandom(Game.instance.monsterManager).nextLong());
        result.add(managerRandom(EntityManager.instance).nextLong());
        return result;
    }

    private static Random managerRandom(Object manager) throws Exception {
        Field field = manager.getClass().getDeclaredField("random");
        field.setAccessible(true);
        return (Random)field.get(manager);
    }
}
