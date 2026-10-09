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
import static org.junit.Assert.assertFalse;
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
    private com.interrupt.managers.TileManager previousTiles;
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
        // TileManager loads once, when Tile is first touched; an earlier test in this JVM may
        // have done that before the owned copy was mounted, leaving no owned water or pits.
        previousTiles = com.interrupt.managers.TileManager.instance;
        com.interrupt.managers.TileManager tiles = mods.loadTileManager();
        java.lang.reflect.Method parse = com.interrupt.managers.TileManager.class.getDeclaredMethod("init");
        parse.setAccessible(true);
        parse.invoke(tiles);
        com.interrupt.managers.TileManager.instance = tiles;
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
        com.interrupt.managers.TileManager.instance = previousTiles;
        GameManager.renderer = previousRenderer;
        TextureAtlas.cachedRepeatingAtlases = previousAtlases;
        TextureAtlas.cachedAtlases = previousSpriteAtlases;
        Options.instance.graphicsDetailLevel = previousDetail;
        Options.instance.gfxQuality = previousQuality;
    }

    @org.junit.Rule public org.junit.rules.TemporaryFolder travelStorage = new org.junit.rules.TemporaryFolder();

    @Test public void partyTravelInstallsNativeDestinationAndRestoresSourceOnAscent() throws Exception {
        buildFloor("levels/tutorial.bin", null, 77L, 3, 1f, 1L);
        GameManager.renderer = new ObjenesisStd().newInstance(GenerationRenderer.class);
        Game game = Game.instance;
        game.level = NativeFloorRecipe.campaign(1, 1).build(0x2727L);
        Level source = game.level;
        int changedTile = 0; while(source.tiles[changedTile] == null || source.tiles[changedTile].renderSolid) changedTile++;
        source.tiles[changedTile].floorHeight = 0.27f;
        com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore store = new com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore(
                travelStorage.newFolder("native-travel"), new java.security.SecureRandom());
        StringBuilder identity = new StringBuilder(); while(identity.length() < 64) identity.append('1');
        com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster roster = store.loadOrCreate("native-travel", 2,
                com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog.ownedV108Humanoids(),
                new com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity(identity.toString()),
                new com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation("Host", "humanoid-1"));
        com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost host = com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost.start(0,
                com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility.forOpenSourceTestFloor(new byte[] { 27 }),
                roster, store, new LevelMovementCollisionWorld(game.level));
        com.interrupt.dungeoneer.multiplayer.network.DirectConnectClient friend = com.interrupt.dungeoneer.multiplayer.network.DirectConnectClient.connect(
                "127.0.0.1", host.getBoundPort(), new com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity(identity.toString().replace('1', '2')),
                new com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation("Friend", "humanoid-2"), 0,
                new com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore() {
                    public String load(String campaign) { return null; } public void save(String campaign, String token) { }
                }, com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility.forOpenSourceTestFloor(new byte[] { 27 }));
        try {
            awaitNativePhase(friend, com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase.LOBBY);
            host.startSession();
            awaitNativePhase(friend, com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase.READY);
            host.setNativeFloorCapture(() -> NativeFloorSave.capture(game.level));
            host.setSessionPaused(true);
            host.activateCampaignFloor("campaign:1", "campaign:1", 0x2727L, source.multiplayerBuiltFingerprint,
                    () -> source, level -> game.level = level);
            host.setSessionPaused(false);
            NativePartyTravel travel = new NativePartyTravel(host, slot -> { throw new AssertionError("Living Host stays same character"); },
                    game::installPartyFloor);
            travel.update(game); host.completeNativeWorld(host.getNativeWorldGeneration());
            com.interrupt.dungeoneer.entities.Stairs down = source.down;
            host.setNativeParticipantPosition(new ParticipantId("campaign-slot-1"), down.x, down.y + 0.05f, source.getTile((int)down.x, (int)down.y).floorHeight + 0.5f);
            host.setNativeParticipantPosition(new ParticipantId("campaign-slot-2"), down.x, down.y + 0.05f, source.getTile((int)down.x, (int)down.y).floorHeight + 0.5f);
            host.requestPartyTransition("stairs:down"); awaitLoading(host); travel.update(game);
            assertEquals(2, NativeFloorRecipe.decode(game.level.multiplayerNativeRecipe).campaignIndex);
            assertTrue(game.level != source);
            assertTrue("Native player ignores arrival stairs until stepping away", game.player.ignoreStairs);
            host.acknowledgePartyDestination(host.getNativeWorldGeneration());
            friend.acknowledgePartyDestination(host.getNativeWorldGeneration());
            long release = System.currentTimeMillis() + 8000L;
            while(host.isSessionPaused() && System.currentTimeMillis() < release) Thread.sleep(10L);
            assertFalse(host.isSessionPaused()); travel.update(game);
            com.interrupt.dungeoneer.entities.Stairs up = game.level.up;
            if(up == null) {
                up = new com.interrupt.dungeoneer.entities.Stairs(); up.direction = com.interrupt.dungeoneer.entities.Stairs.StairDirection.up;
                up.x = game.level.down.x; up.y = game.level.down.y; up.z = game.level.down.z; game.level.entities.add(up);
            }
            travel.update(game);
            host.setNativeParticipantPosition(new ParticipantId("campaign-slot-1"), up.x, up.y + 0.05f, game.level.getTile((int)up.x, (int)up.y).floorHeight + 0.5f);
            host.setNativeParticipantPosition(new ParticipantId("campaign-slot-2"), up.x, up.y + 0.05f, game.level.getTile((int)up.x, (int)up.y).floorHeight + 0.5f);
            host.requestPartyTransition("stairs:up"); awaitLoading(host); travel.update(game);
            assertEquals(1, NativeFloorRecipe.decode(game.level.multiplayerNativeRecipe).campaignIndex);
            assertEquals("Visited floor restores changed native terrain", 0.27f, game.level.tiles[changedTile].floorHeight, 0f);
            com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState arrival = host.persistCampaign().getParticipant(1).getMovement();
            assertEquals("Ascent arrives at down stairs", game.level.down.x, arrival.getX(), 0.01f);
            assertEquals(game.level.down.y + 0.05f, arrival.getY(), 0.01f);
            host.acknowledgePartyDestination(host.getNativeWorldGeneration());
            friend.acknowledgePartyDestination(host.getNativeWorldGeneration());
            release = System.currentTimeMillis() + 8000L;
            while(host.isSessionPaused() && System.currentTimeMillis() < release) Thread.sleep(10L);
            com.interrupt.dungeoneer.entities.triggers.TriggeredWarp warp = new com.interrupt.dungeoneer.entities.triggers.TriggeredWarp();
            warp.generated = false; warp.levelToLoad = "levels/shop-interstitial.bin"; warp.levelTheme = "DUNGEON";
            warp.x = game.level.down.x; warp.y = game.level.down.y;
            warp.z = game.level.getTile((int)warp.x, (int)warp.y).floorHeight + 0.5f;
            game.level.non_collidable_entities.add(warp); travel.update(game);
            for(int slot = 1; slot <= 2; slot++) host.setNativeParticipantPosition(new ParticipantId("campaign-slot-" + slot), warp.x, warp.y, warp.z);
            host.requestPartyTransition(PartyPortals.key(warp)); awaitLoading(host); travel.update(game);
            assertEquals("campaign:1", game.level.multiplayerParentArea);
            assertEquals("levels/shop-interstitial.bin", game.level.levelFileName);
            assertFalse("Distinct parent areas never share branch despite Java hash collisions",
                    PartyPortals.branchArea("FB", warp).equals(PartyPortals.branchArea("Ea", warp)));
            host.acknowledgePartyDestination(host.getNativeWorldGeneration()); friend.acknowledgePartyDestination(host.getNativeWorldGeneration());
            release = System.currentTimeMillis() + 8000L;
            while(host.isSessionPaused() && System.currentTimeMillis() < release) Thread.sleep(10L);
            com.interrupt.dungeoneer.entities.triggers.TriggeredWarp exit = new com.interrupt.dungeoneer.entities.triggers.TriggeredWarp();
            exit.isExit = true;
            com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState branchSpawn = host.persistCampaign().getParticipant(1).getMovement();
            exit.x = branchSpawn.getX(); exit.y = branchSpawn.getY(); exit.z = branchSpawn.getZ();
            game.level.non_collidable_entities.add(exit); travel.update(game);
            for(int slot = 1; slot <= 2; slot++) host.setNativeParticipantPosition(new ParticipantId("campaign-slot-" + slot), exit.x, exit.y, exit.z);
            host.requestPartyTransition(PartyPortals.key(exit)); awaitLoading(host); travel.update(game);
            assertEquals("campaign:1", host.getActiveAreaKey());
            assertEquals(0.27f, game.level.tiles[changedTile].floorHeight, 0f);
            com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState branchReturn = host.persistCampaign().getParticipant(1).getMovement();
            assertEquals(warp.x, branchReturn.getX(), 0.01f); assertEquals(warp.y, branchReturn.getY(), 0.01f);
        }
        finally { friend.close(); host.close(); }
    }

    private static void awaitNativePhase(com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer peer,
            com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase phase) throws Exception {
        long deadline = System.currentTimeMillis() + 8000L;
        while(peer.getStatus().getPhase() != phase && System.currentTimeMillis() < deadline) Thread.sleep(10L);
        assertEquals(phase, peer.getStatus().getPhase());
    }

    private static void awaitLoading(com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost host) throws Exception {
        long deadline = System.currentTimeMillis() + 8000L;
        while(System.currentTimeMillis() < deadline) {
            if(host.getPartyTransition().phase == PartyTransition.Phase.LOADING) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Native travel never reached loading: " + host.getPartyTransition().phase);
    }

    @Test public void startingTutorialCarriesRecipeForBranchReturnAndColdObserver() throws Exception {
        buildFloor("levels/tutorial.bin", null, 77L, 3, 1f, 1L);
        GameManager.renderer = new ObjenesisStd().newInstance(GenerationRenderer.class);
        com.interrupt.dungeoneer.GameApplication app = new ObjenesisStd().newInstance(com.interrupt.dungeoneer.GameApplication.class);
        java.lang.reflect.Method loader = com.interrupt.dungeoneer.GameApplication.class.getDeclaredMethod("loadDirectConnectLevel", String.class);
        loader.setAccessible(true);
        Level legacy = buildFloor("levels/tutorial.bin", null, 0x2799L, 3, 1f, 1L);
        SharedFloorFingerprint legacyFingerprint = legacy.multiplayerBuiltFingerprint;
        Game.instance.progression = new Progression();
        Level initial = (Level)loader.invoke(app, com.interrupt.dungeoneer.GameApplication.OWNED_TUTORIAL_FLOOR);
        assertTrue("Starting tutorial must retain native recipe for branch returns/cold observers", initial.multiplayerNativeRecipe != null);
        initial.sharedFloorBuild = new SharedFloorBuild(0x2799L); initial.loadForCampaign();
        assertEquals("Starting floor keeps predecessor construction for existing Campaign resume", legacyFingerprint, initial.multiplayerBuiltFingerprint);
        Game.instance.player.level = 9;
        Level observer = NativeFloorRecipe.decode(initial.multiplayerNativeRecipe).definition();
        observer.multiplayerNativeRecipe = initial.multiplayerNativeRecipe;
        observer.sharedFloorBuild = new SharedFloorBuild(0x2799L); observer.loadForCampaign();
        assertEquals(initial.multiplayerBuiltFingerprint, observer.multiplayerBuiltFingerprint);
        assertEquals("Temporary construction level cannot alter observer character", 9, Game.instance.player.level);
    }

    @Test public void destinationBuildKeepsSourceProgressionUntouchedUntilSceneCommit() {
        buildFloor("levels/tutorial.bin", null, 77L, 3, 1f, 1L);
        GameManager.renderer = new ObjenesisStd().newInstance(GenerationRenderer.class);
        com.interrupt.dungeoneer.game.Progression original = Game.instance.progression;
        original.dungeonAreasSeen.clear(); original.uniqueTilesSeen.clear(); original.uniqueItemsSpawned.clear();
        com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot before =
                com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(original, 1L);
        Level candidate = NativeFloorRecipe.campaign(1, 1).build(0x27ABL);
        assertTrue("Refused save must not leave destination construction history on source",
                before.sameFacts(com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.capture(Game.instance.progression, 1L)));
        assertTrue(original == Game.instance.progression);
        Game.instance.installPartyFloor(candidate);
        assertTrue("Successful scene commit retains destination construction facts",
                original.dungeonAreasSeen.contains(candidate.theme, false));
    }

    @Test public void nativeRecipeRebuildIgnoresLaterCampaignConstructionHistory() {
        buildFloor("levels/tutorial.bin", null, 77L, 3, 1f, 1L);
        GameManager.renderer = new ObjenesisStd().newInstance(GenerationRenderer.class);
        NativeFloorRecipe recipe = NativeFloorRecipe.campaign(1, 1);
        String encoded = recipe.encode();
        Level first = recipe.build(0x2788L);
        Game.instance.progression.markDungeonAreaAsSeen(first.theme);
        Game.instance.progression.uniqueTilesSeen.add("newer-floor-tile");
        Game.instance.progression.uniqueItemsSpawned.add("newer-floor-item");
        Level replay = NativeFloorRecipe.decode(encoded).build(0x2788L);
        assertEquals("Cold observer reconstructs original floor, not a later-history variant",
                first.multiplayerBuiltFingerprint, replay.multiplayerBuiltFingerprint);
    }

    @Test public void nativeTravelRecipeBuildsStandardLayoutAndKeepsWarpAppearance() {
        buildFloor("levels/tutorial.bin", null, 77L, 3, 1f, 1L);
        GameManager.renderer = new ObjenesisStd().newInstance(GenerationRenderer.class);
        NativeFloorRecipe campaign = NativeFloorRecipe.campaign(1, Game.instance.player.level);
        Level dungeon = NativeFloorRecipe.decode(campaign.encode()).build(0x2727L);
        assertTrue(dungeon.generated);
        assertTrue(dungeon.down != null);
        assertTrue(dungeon.multiplayerBuiltFingerprint != null);
        com.interrupt.dungeoneer.entities.triggers.TriggeredWarp warp = new com.interrupt.dungeoneer.entities.triggers.TriggeredWarp();
        warp.generated = false; warp.levelToLoad = "levels/shop-interstitial.bin";
        warp.levelTheme = "CAVE"; warp.fogStart = 1.5f; warp.fogEnd = 9f; warp.spawnMonsters = false;
        warp.fogColor = new com.badlogic.gdx.graphics.Color(0.4f, 0.1f, 0.2f, 1f);
        NativeFloorRecipe recipe = NativeFloorRecipe.decode(NativeFloorRecipe.warp(warp, 3).encode());
        Level shop = recipe.build(0x2728L);
        assertEquals("CAVE", shop.theme);
        assertEquals(1.5f, shop.fogStart, 0f); assertEquals(9f, shop.fogEnd, 0f);
        assertEquals(warp.fogColor, shop.fogColor);
        assertFalse(shop.spawnMonsters);
        assertTrue(shop.multiplayerBuiltFingerprint != null);
    }

    @Test public void nativeGeneratedCampaignFloorUsesSharedSeedAcrossPeerSettings() {
        buildFloor("levels/tutorial.bin", null, 77L, 3, 1f, 1L);
        GameManager.renderer = new ObjenesisStd().newInstance(GenerationRenderer.class);
        Level host = firstGeneratedCampaignFloor();
        SharedFloorBuild hostBuild = new SharedFloorBuild(0xCAFE27L);
        host.sharedFloorBuild = hostBuild; host.load();
        assertTrue("Native generated build must finish its Shared Floor fingerprint", hostBuild.getFingerprint() != null);
        Options.instance.graphicsDetailLevel = 1; Options.instance.gfxQuality = 0.25f;
        Game.rand.setSeed(999L);
        Game.instance.progression = new Progression();
        Level client = firstGeneratedCampaignFloor();
        SharedFloorBuild clientBuild = new SharedFloorBuild(0xCAFE27L);
        client.sharedFloorBuild = clientBuild; client.load();
        assertEquals(hostBuild.getFingerprint(), clientBuild.getFingerprint());
        assertTrue("Native generated floor retains down stairs", host.down != null);
        assertEquals(host.up != null, client.up != null);
    }

    static class GenerationRenderer extends SharedFloorLevelBuildTest.HeadlessRenderer {
        @Override public void makeMapTextureForLevel(Level level) { }
    }

    private Level firstGeneratedCampaignFloor() {
        for(Level definition : Game.buildLevelLayout()) if(definition.generated) return definition;
        throw new AssertionError("Native campaign must contain generated dungeon floors");
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

    @Test public void builtTutorialCheckpointReloadsLikeASinglePlayerSave() {
        Level live = buildFloor("levels/tutorial.bin", null, 0x5EEDL, 3, 1f, 5L);
        SharedFloorFingerprint built = lastBuild.getFingerprint();
        com.interrupt.dungeoneer.entities.Door door = first(live, com.interrupt.dungeoneer.entities.Door.class);
        door.doorState = com.interrupt.dungeoneer.entities.Door.DoorState.OPEN;
        door.multiplayerIdentity = "object:4242";

        byte[] checkpoint = NativeFloorSave.capture(live);
        System.out.println("Tutorial floor checkpoint: " + checkpoint.length + " bytes");
        Level restored = NativeFloorSave.restore(checkpoint);
        Game.instance.level = restored;
        restored.loadForCampaign();

        SharedFloorFingerprint reloaded = SharedFloorFingerprint.capture(restored);
        for(SharedFloorFingerprint.Category category : new SharedFloorFingerprint.Category[] {
                SharedFloorFingerprint.Category.WORLD_OBJECTS, SharedFloorFingerprint.Category.TRAPS }) {
            assertEquals(category.getLabel(), built.getCount(category), reloaded.getCount(category));
            assertEquals(category.getLabel(), built.getDigest(category), reloaded.getDigest(category));
        }
        assertEquals(count(live, com.interrupt.dungeoneer.entities.MonsterSpawner.class),
                count(restored, com.interrupt.dungeoneer.entities.MonsterSpawner.class));
        com.interrupt.dungeoneer.entities.Door saved =
                first(restored, com.interrupt.dungeoneer.entities.Door.class);
        assertEquals(com.interrupt.dungeoneer.entities.Door.DoorState.OPEN, saved.doorState);
        assertEquals("object:4242", saved.multiplayerIdentity);
        assertTrue(restored.isLoaded);
    }

    @Test public void hostMovementFollowsNativePlayerAcrossTheTutorial() throws Exception {
        // Every peer predicts its own Participant with native Player physics and the Host
        // corrects it to its movement simulation; wherever the two disagree a Participant is
        // dragged up walls, bounced in water or pulled off ladders. Walk (and jump) from every
        // open tile in eight directions with both and compare.
        Level live = buildFloor("levels/tutorial.bin", null, 0x5EEDL, 3, 1f, 5L);
        int water = 0;
        for(com.interrupt.dungeoneer.tiles.Tile tile : live.tiles) if(tile != null && tile.data.isWater) water++;
        assertTrue("Owned tile rules give the tutorial its pools and waterfall: " + water, water >= 40);
        LevelMovementCollisionWorld world = hostTutorialWorld(live);
        Player player = nativeWalker(live);
        java.util.List<String> divergent = new java.util.ArrayList<String>();
        int compared = 0;
        for(boolean jump : new boolean[] { false, true }) {
            for(int tileY = 0; tileY < live.height; tileY++) for(int tileX = 0; tileX < live.width; tileX++) {
                float x = tileX + 0.5f, y = tileY + 0.5f;
                com.interrupt.dungeoneer.tiles.Tile tile = live.getTile(tileX, tileY);
                float z = world.getFloorZ(x, y, -100f);
                if(tile.blockMotion || tile.renderSolid || !world.canOccupy(x, y, z)) continue;
                assertEquals("Host and native floor under " + x + ", " + y,
                        live.maxFloorHeight(x, y, 0f, 0.2f) + 0.5f, z, 0.05f);
                for(int direction = 0; direction < 8; direction++) {
                    float rotation = (float)(direction * Math.PI / 4.0);
                    float[] expected = nativeWalk(live, player, x, y, z, rotation, jump, null);
                    float[] actual = hostWalk(world, x, y, z, rotation, jump, null);
                    compared++;
                    if(Math.abs(actual[3] - expected[3]) > 0.3f || Math.abs(actual[2] - expected[2]) > 0.3f
                            || Math.hypot(actual[0] - expected[0], actual[1] - expected[1]) > 0.75f) {
                        divergent.add(String.format("jump=%s from %.1f,%.1f direction %d: native %.2f,%.2f,%.2f"
                                + " (highest %.2f), Host %.2f,%.2f,%.2f (highest %.2f)", jump, x, y, direction,
                                expected[0], expected[1], expected[2], expected[3],
                                actual[0], actual[1], actual[2], actual[3]));
                    }
                }
            }
        }
        assertTrue("Tutorial has open floor to walk: " + compared, compared > 4000);
        assertTrue(divergent.size() + " walks diverge, first: "
                + divergent.subList(0, Math.min(5, divergent.size())), divergent.isEmpty());
    }

    @Test public void hostClimbsTheTutorialPitLadderAndStaysOffTheWaterfallLikeNativePlayer()
            throws Exception {
        Level live = buildFloor("levels/tutorial.bin", null, 0x5EEDL, 3, 1f, 5L);
        LevelMovementCollisionWorld world = hostTutorialWorld(live);
        Player player = nativeWalker(live);

        // Water pit under the tutorial's only Ladder (48.6, 15.0); its rim floor stands at -0.94.
        float pitZ = world.getFloorZ(48.5f, 15.5f, -100f);
        assertEquals("Participant sinks to the water floor", -2.34f, pitZ, 0.01f);
        float north = (float)Math.PI;
        float[] nativeClimb = nativeWalk(live, player, 48.5f, 15.5f, pitZ, north, false, null);
        float[] hostClimb = hostWalk(world, 48.5f, 15.5f, pitZ, north, false, null);
        assertEquals("Native Player climbs out", -0.94f, nativeClimb[2], 0.02f);
        assertEquals("Host climbs with it", nativeClimb[2], hostClimb[2], 0.02f);
        assertEquals(nativeClimb[1], hostClimb[1], 0.1f);

        // Waterfall: water tiles stacked from the pool (floor -1.4) up to 7.66 beside it.
        float poolZ = world.getFloorZ(29.5f, 22.5f, -100f);
        float west = (float)(Math.PI * 1.5);
        float[] nativeWall = nativeWalk(live, player, 29.5f, 22.5f, poolZ, west, true, null);
        float[] hostWall = hostWalk(world, 29.5f, 22.5f, poolZ, west, true, null);
        assertTrue("Native Player stays below the waterfall: " + nativeWall[3], nativeWall[3] < 1f);
        assertEquals("Host never climbs the waterfall", nativeWall[3], hostWall[3], 0.05f);
    }

    @Test public void nativePlayerNudgedIntoTheSpawnBlockIsLiftedOntoItWholesale() throws Exception {
        // Why a movement correction must never move a Player's box into a wall: native physics
        // stands a Player on the highest floor its box overlaps, however far above its feet.
        Level live = buildFloor("levels/tutorial.bin", null, 0x5EEDL, 3, 1f, 5L);
        Player player = nativeWalker(live);
        // Block (33,29) rises about 1.4 over the spawn clearing; its face is at y = 30.
        float[] heights = new float[2];
        float[] pushes = { 0f, 0.001f };
        for(int i = 0; i < pushes.length; i++) {
            player.x = 33.5f;
            player.y = 30.2f - pushes[i];
            player.z = live.maxFloorHeight(33.5f, 30.21f, 0f, 0.2f) + 0.5f;
            player.xa = player.ya = player.za = 0f;
            player.isOnFloor = true;
            live.updateSpatialHash(player);
            player.tick(live, 1f);
            heights[i] = player.z;
        }
        assertEquals("Touching the face leaves the Player below", 0.17f, heights[0], 0.01f);
        assertEquals("A thousandth inside puts it on top", 1.59f, heights[1], 0.01f);
    }

    /** Host movement as a resumed or new Host builds it: file tiles, live tile rules and objects. */
    private static LevelMovementCollisionWorld hostTutorialWorld(Level live) {
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(
                KryoSerializer.loadLevel(OwnedGameCopyMount.resolve("levels/tutorial.bin")));
        world.adoptTileRules(live);
        world.setWorldObstacles(new NativeMovementObstacles().changed(live));
        return world;
    }

    private static Player nativeWalker(Level live) {
        Player player = Game.instance.player;
        player.isSolid = true;
        // The first native tick refreshes the menu for changed stats; headless has none.
        try { player.tick(live, 1f); } catch(RuntimeException noMenu) { }
        return player;
    }

    private static final int WALK_TICKS = 120;
    private static final float LOOK_PITCH = 0.3f;

    /**
     * Native Player frames: Level.tick (collision hash, Ladders) then the Player's walking force,
     * jump and Player.tick(level, delta), with forward held and jump pressed every half second.
     */
    private static float[] nativeWalk(Level level, Player player, float x, float y, float z,
            float rotation, boolean jump, java.util.List<float[]> path) throws Exception {
        java.lang.reflect.Field walkVector = Player.class.getDeclaredField("walkVelVector");
        walkVector.setAccessible(true);
        java.lang.reflect.Field stepHeight = Player.class.getDeclaredField("stepHeight");
        stepHeight.setAccessible(true);
        stepHeight.setFloat(player, 0.35f);
        player.friction = 1f;
        player.x = x; player.y = y; player.z = z;
        player.xa = player.ya = player.za = 0f;
        player.rot = rotation;
        player.yrot = LOOK_PITCH;
        player.isOnFloor = true;
        player.isOnEntity = false;
        player.isOnLadder = false;
        float highest = z;
        for(int tick = 0; tick < WALK_TICKS; tick++) {
            level.updateSpatialHash(player);
            for(com.interrupt.dungeoneer.entities.Entity entity : level.entities) {
                if(entity instanceof com.interrupt.dungeoneer.entities.Ladder) entity.tick(level, 1f);
            }
            ((com.badlogic.gdx.math.Vector2)walkVector.get(player)).set(0f, 1f);
            float force = 0.05f * player.getWalkSpeed();
            float xMod = (float)Math.sin(player.rot) * force, yMod = (float)Math.cos(player.rot) * force;
            if(player.isOnLadder) {
                xMod *= 0.5f;
                yMod *= 0.5f;
            }
            if(jump && tick % 30 == 0 && (player.isOnFloor || player.isOnEntity) && !player.isOnLadder) {
                player.za += player.jumpHeight;
            }
            player.xa += xMod * Math.min(player.friction * 1.4f, 1f);
            player.ya += yMod * Math.min(player.friction * 1.4f, 1f);
            player.tick(level, 1f);
            if(path != null) path.add(new float[] { player.x, player.y, player.z });
            highest = Math.max(highest, player.z);
        }
        return new float[] { player.x, player.y, player.z, highest };
    }

    /** The same walk as Host movement input: one 60 Hz frame per tick, looking up the same pitch. */
    private static float[] hostWalk(LevelMovementCollisionWorld world, float x, float y, float z,
            float rotation, boolean jump, java.util.List<float[]> path) {
        ParticipantId participant = new ParticipantId("campaign-slot-1");
        AuthoritativeMovementSimulation simulation = new AuthoritativeMovementSimulation(world,
                Collections.singletonList(new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                        participant, 1, "Host", "humanoid-1")));
        simulation.setNativePosition(participant, x, y, z);
        float highest = z;
        MovementEntityState state = null;
        for(int tick = 1; tick <= WALK_TICKS; tick++) {
            simulation.applyCommand(tick, new MovementInputCommand(participant,
                    new MovementInputFrame(tick, 1f, 0f, rotation, jump && (tick - 1) % 30 == 0,
                            (float)Math.sin(LOOK_PITCH))), null);
            simulation.tick(tick, 1f / 60f, null);
            state = simulation.getState(participant);
            if(path != null) path.add(new float[] { state.getX(), state.getY(), state.getZ() });
            highest = Math.max(highest, state.getZ());
        }
        return new float[] { state.getX(), state.getY(), state.getZ(), highest };
    }

    private static <T> T first(Level level, Class<T> type) {
        for(com.interrupt.dungeoneer.entities.Entity entity : level.entities) {
            if(type.isInstance(entity)) return type.cast(entity);
        }
        throw new AssertionError("Floor has no " + type.getSimpleName());
    }

    private static int count(Level level, Class<?> type) {
        int found = 0;
        for(com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Entity> list
                : java.util.Arrays.asList(level.entities, level.non_collidable_entities,
                        level.static_entities)) {
            for(com.interrupt.dungeoneer.entities.Entity entity : list) {
                if(type.isInstance(entity)) found++;
            }
        }
        return found;
    }

    private SharedFloorFingerprint build(Long seed, int detail, float quality, long history) {
        Level level = buildLevel(seed, detail, quality, history);
        return lastBuild == null ? SharedFloorFingerprint.capture(level) : lastBuild.getFingerprint();
    }

    private SharedFloorBuild lastBuild;

    private Level buildLevel(Long seed, int detail, float quality, long history) {
        return buildFloor("levels/shop-interstitial.bin", "CAVE", seed, detail, quality, history);
    }

    private Level buildFloor(String file, String theme, Long seed, int detail, float quality,
            long history) {
        Options.instance.graphicsDetailLevel = detail;
        Options.instance.gfxQuality = quality;
        Game.rand.setSeed(history);
        Level level = KryoSerializer.loadLevel(OwnedGameCopyMount.resolve(file));
        if(theme != null) level.theme = theme;
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
