package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.utils.ArrayMap;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.GameData;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.ModManager;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.game.Progression;
import com.interrupt.dungeoneer.gfx.GlRenderer;
import com.interrupt.dungeoneer.gfx.TextureAtlas;
import com.interrupt.dungeoneer.multiplayer.movement.AuthoritativeMovementSimulation;
import com.interrupt.dungeoneer.multiplayer.movement.LevelMovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputCommand;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSpawn;
import com.interrupt.dungeoneer.multiplayer.movement.NativeMovementObstacles;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.owned.KnownV108OwnedGameCopies;
import com.interrupt.dungeoneer.owned.OwnedGameCopyMount;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.managers.EntityManager;
import com.interrupt.managers.ItemManager;
import com.interrupt.managers.MonsterManager;
import com.interrupt.utils.JsonUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Retail v1.08 shop-interstitial (Cave theme, decorated with random Crate/Spider Egg Breakables)
 * built as two peers. Reads the Owned Game Copy named by OWNED_GAME_COPY_TEST read-only.
 */
public class OwnedSharedFloorBuildTest {
    private static HeadlessApplication application;
    private Game previousGame;
    private ModManager previousMods;
    private GameData previousGameData;
    private EntityManager previousEntities;
    private MonsterManager previousMonsters;
    private GlRenderer previousRenderer;
    private ArrayMap<String, TextureAtlas> previousAtlases;
    private ArrayMap<String, TextureAtlas> previousSpriteAtlases;
    private int previousDetail;
    private float previousQuality;
    private ItemManager items;
    private MonsterManager monsters;
    private EntityManager entities;

    @BeforeClass public static void startHeadlessRuntime() {
        application = new HeadlessApplication(new ApplicationAdapter() { });
    }

    @AfterClass public static void stopHeadlessRuntime() {
        OwnedGameCopyMount.unmount();
        if(application != null) application.exit();
    }

    @Before public void mountOwnedCopy() throws Exception {
        String ownedCopyPath = System.getenv("OWNED_GAME_COPY_TEST");
        Assume.assumeTrue("Set OWNED_GAME_COPY_TEST to run retail integration test.",
                ownedCopyPath != null && !ownedCopyPath.trim().isEmpty());
        previousGame = Game.instance;
        previousMods = Game.modManager;
        previousGameData = Game.gameData;
        previousEntities = EntityManager.instance;
        previousMonsters = MonsterManager.instance;
        previousRenderer = GameManager.renderer;
        previousAtlases = TextureAtlas.cachedRepeatingAtlases;
        previousSpriteAtlases = TextureAtlas.cachedAtlases;
        previousDetail = Options.instance.graphicsDetailLevel;
        previousQuality = Options.instance.gfxQuality;

        OwnedGameCopyMount.mount(KnownV108OwnedGameCopies.validator().validate(new File(ownedCopyPath)));
        ModManager mods = new ModManager();
        mods.modsFound.add(".");
        Game.modManager = mods;
        Game.gameData = JsonUtil.fromJson(GameData.class, OwnedGameCopyMount.resolve("data/game.dat"));
        com.interrupt.managers.StringManager.init();
        items = mods.loadItemManager(Game.gameData.itemDataFiles);
        monsters = mods.loadMonsterManager(Game.gameData.monsterDataFiles);
        entities = mods.loadEntityManager(Game.gameData.entityDataFiles);
        EntityManager.setSingleton(entities);
        MonsterManager.setSingleton(monsters);
        GameManager.renderer = new ObjenesisStd().newInstance(SharedFloorLevelBuildTest.HeadlessRenderer.class);
        TextureAtlas.cachedRepeatingAtlases = SharedFloorLevelBuildTest.headlessAtlases();
        TextureAtlas.cachedAtlases = SharedFloorLevelBuildTest.headlessAtlases();
    }

    @After public void restore() {
        if(previousAtlases == null) return;
        Game.instance = previousGame;
        Game.modManager = previousMods;
        Game.gameData = previousGameData;
        EntityManager.instance = previousEntities;
        MonsterManager.instance = previousMonsters;
        GameManager.renderer = previousRenderer;
        TextureAtlas.cachedRepeatingAtlases = previousAtlases;
        TextureAtlas.cachedAtlases = previousSpriteAtlases;
        Options.instance.graphicsDetailLevel = previousDetail;
        Options.instance.gfxQuality = previousQuality;
    }

    @Test public void vanillaShopFloorDiffersBetweenPeersWithDifferentSettings() {
        SharedFloorFingerprint host = build(null, 4, 1.5f, 11L);
        SharedFloorFingerprint client = build(null, 1, 0.25f, 29L);

        // Reported bug: client lacks or misplaces objects Host decorated, so hits name unknown targets.
        assertNotEquals(host, client);
        System.out.println("Vanilla shop floor Host/client: " + host.describeDifferences(client));
    }

    @Test public void sharedSeedBuildsIdenticalShopFloorAcrossPeerSettings() {
        SharedFloorFingerprint host = build(0x5EEDL, 4, 1.5f, 11L);
        SharedFloorFingerprint client = build(0x5EEDL, 1, 0.25f, 29L);

        assertEquals(host.describeDifferences(client), host, client);
        assertTrue("Shop floor must contain shared world objects",
                host.getCount(SharedFloorFingerprint.Category.WORLD_OBJECTS) > 0);
    }

    @Test public void participantsCrossShopWalkwaysFromArrivalStairsWithoutFalling() {
        Level live = buildLevel(0x5EEDL, 3, 1f, 5L);
        // Host movement keeps its own tile copy, as GameApplication loads it at Host start.
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(
                KryoSerializer.loadLevel(OwnedGameCopyMount.resolve("levels/shop-interstitial.bin")));
        world.setWorldObstacles(new NativeMovementObstacles().changed(live));
        ParticipantId participant = new ParticipantId("campaign-slot-1");
        AuthoritativeMovementSimulation simulation = new AuthoritativeMovementSimulation(world,
                Collections.singletonList(new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                        participant, 1, "Host", "humanoid-1")));
        MovementSpawn spawn = world.getSpawn(1);
        assertEquals("Arrival on the up stairs", 15.5f, spawn.getX(), 0.01f);
        assertEquals(25.55f, spawn.getY(), 0.01f);

        // South walkway between the entrance pillars, around the island well, north walkway to the door.
        float[][] route = { { 15.5f, 17.2f }, { 14.6f, 16.4f }, { 14.6f, 14.6f },
                { 15.5f, 13.9f }, { 15.5f, 8.3f } };
        int waypoint = 0;
        float lowest = Float.MAX_VALUE;
        for(int tick = 1; tick <= 1200 && waypoint < route.length; tick++) {
            MovementEntityState state = simulation.getState(participant);
            float dx = route[waypoint][0] - state.getX(), dy = route[waypoint][1] - state.getY();
            if(dx * dx + dy * dy < 0.15f * 0.15f) { waypoint++; continue; }
            // Host movement: velocity x = forward * sin(rotation), y = forward * cos(rotation).
            simulation.applyCommand(tick, new MovementInputCommand(participant,
                    new MovementInputFrame(tick, 1f, 0f, (float)Math.atan2(dx, dy), false)), null);
            simulation.tick(tick, 1f / 60f, null);
            lowest = Math.min(lowest, simulation.getState(participant).getZ());
        }

        MovementEntityState state = simulation.getState(participant);
        assertTrue("Participant fell into the shop pit; lowest height " + lowest, lowest > -0.5f);
        assertTrue("Participant stopped at (" + state.getX() + ", " + state.getY()
                + ") before reaching route point " + waypoint, waypoint >= route.length - 1);
        assertTrue("Participant must reach the north walkway by the door; reached y "
                + state.getY(), state.getY() < 9f);
    }

    private SharedFloorFingerprint build(Long seed, int detail, float quality, long history) {
        Level level = buildLevel(seed, detail, quality, history);
        return lastBuild == null ? SharedFloorFingerprint.capture(level) : lastBuild.getFingerprint();
    }

    private SharedFloorBuild lastBuild;

    private Level buildLevel(Long seed, int detail, float quality, long history) {
        Options.instance.graphicsDetailLevel = detail;
        Options.instance.gfxQuality = quality;
        Game.rand.setSeed(history);
        Level level = KryoSerializer.loadLevel(OwnedGameCopyMount.resolve("levels/shop-interstitial.bin"));
        level.theme = "CAVE";
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.level = level;
        game.itemManager = items;
        game.monsterManager = monsters;
        game.entityManager = entities;
        game.player = JsonUtil.fromJson(Player.class,
                OwnedGameCopyMount.resolve("data/" + Game.gameData.playerDataFile));
        game.progression = new Progression();
        Game.instance = game;
        lastBuild = seed == null ? null : new SharedFloorBuild(seed);
        level.sharedFloorBuild = lastBuild;
        level.loadFromEditor();
        return level;
    }
}
