package com.interrupt.dungeoneer.owned;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.files.FileHandle;
import com.interrupt.dungeoneer.game.GameData;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.ModManager;
import com.interrupt.dungeoneer.game.Progression;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.ItemSpawner;
import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.serializers.KryoSerializer;
import com.interrupt.utils.JsonUtil;
import com.interrupt.managers.EntityManager;
import com.interrupt.managers.ItemManager;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.objenesis.ObjenesisStd;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OwnedTutorialSmokeTest {
    private static HeadlessApplication application;

    @BeforeClass
    public static void startHeadlessRuntime() {
        application = new HeadlessApplication(new ApplicationAdapter() { });
    }

    @AfterClass
    public static void stopHeadlessRuntime() {
        OwnedGameCopyMount.unmount();
        if(application != null) application.exit();
    }

    @Test
    public void materializesTutorialFromValidatedOwnedCopy() throws Exception {
        String ownedCopyPath = System.getenv("OWNED_GAME_COPY_TEST");
        Assume.assumeTrue("Set OWNED_GAME_COPY_TEST to run retail integration test.",
                ownedCopyPath != null && !ownedCopyPath.trim().isEmpty());

        OwnedGameCopy ownedGameCopy = KnownV108OwnedGameCopies.validator().validate(new File(ownedCopyPath));
        OwnedGameCopyMount.mount(ownedGameCopy);

        FileHandle gameDataFile = OwnedGameCopyMount.resolve("data/game.dat");
        assertNotNull("Validated copy did not mount data/game.dat", gameDataFile);
        assertNotNull("Validated copy did not resolve retail UI audio path",
                OwnedGameCopyMount.resolve("audio//ui/ui_equip_item.mp3"));
        GameData gameData = JsonUtil.fromJson(GameData.class, gameDataFile);
        assertNotNull("Validated copy did not deserialize game data", gameData);
        assertNotNull("Validated copy does not define tutorial", gameData.tutorialLevel);

        Level tutorial = gameData.tutorialLevel;
        assertNotNull("Tutorial does not reference a level file", tutorial.levelFileName);
        FileHandle tutorialFile = OwnedGameCopyMount.resolve(tutorial.levelFileName);
        assertNotNull("Tutorial level file was blocked or missing", tutorialFile);
        tutorial = KryoSerializer.loadLevel(tutorialFile);

        assertNotNull("Tutorial level file could not be deserialized", tutorial);
        assertTrue("Tutorial width must be positive", tutorial.width > 0);
        assertTrue("Tutorial height must be positive", tutorial.height > 0);
        assertNotNull("Tutorial tiles were not materialized", tutorial.tiles);
    }

    @Test
    public void clientCatalogueMaterializesHostShopFloorItemsAcrossIndependentRolls()
            throws Exception {
        String ownedCopyPath = System.getenv("OWNED_GAME_COPY_TEST");
        Assume.assumeTrue("Set OWNED_GAME_COPY_TEST to run retail integration test.",
                ownedCopyPath != null && !ownedCopyPath.trim().isEmpty());
        OwnedGameCopyMount.mount(KnownV108OwnedGameCopies.validator().validate(new File(ownedCopyPath)));

        ModManager mods = new ModManager();
        mods.modsFound.add(".");
        Game.modManager = mods;
        Game.gameData = JsonUtil.fromJson(GameData.class, OwnedGameCopyMount.resolve("data/game.dat"));
        com.interrupt.managers.StringManager.init();
        ItemManager itemManager = mods.loadItemManager(Game.gameData.itemDataFiles);
        EntityManager entityManager = mods.loadEntityManager(Game.gameData.entityDataFiles);
        EntityManager.setSingleton(entityManager);

        Level rawFloor = shopFloor();
        Game.rand.setSeed(17L);
        Game hostGame = game(shopFloor(), itemManager, entityManager);
        List<Item> hostItems = items(hostGame.level);
        for(com.badlogic.gdx.utils.OrderedMap<String, Entity> category
                : entityManager.entities.values()) {
            for(Entity entity : category.values()) collectItems(entity, hostItems);
        }
        Player hostPlayer = JsonUtil.fromJson(Player.class,
                OwnedGameCopyMount.resolve("data/" + Game.gameData.playerDataFile));
        hostGame.player = hostPlayer;
        for(Entity starter : hostPlayer.startingInventory) {
            if(starter instanceof ItemSpawner) {
                Entity generated = ((ItemSpawner)starter).getItem();
                if(generated instanceof Item) hostItems.add((Item)generated);
            }
        }

        Game.rand.setSeed(71L);
        Game clientGame = game(shopFloor(), itemManager, entityManager);
        DirectConnectItemController client = new DirectConnectItemController(peer());
        client.rememberLevelTemplates(rawFloor);
        client.prepare(clientGame);
        DirectConnectItemController host = new DirectConnectItemController(peer());

        for(Item hostItem : hostItems) {
            DirectConnectItemController.ItemDescription description = host.describe(hostItem);
            assertNotNull("Client catalogue missed Host item " + hostItem.getClass().getName()
                    + " / " + hostItem.name + " / texture " + hostItem.tex,
                    client.materialize(description.templateId, description.properties));
        }
    }

    private static Level shopFloor() {
        Level level = KryoSerializer.loadLevel(
                OwnedGameCopyMount.resolve("levels/shop-interstitial.bin"));
        level.theme = "CAVE";
        return level;
    }

    private static Game game(Level level, ItemManager items, EntityManager entities) {
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.level = level;
        game.itemManager = items;
        game.entityManager = entities;
        game.player = new Player();
        game.progression = new Progression();
        Game.instance = game;
        return game;
    }

    private static List<Item> items(Level level) {
        List<Item> result = new ArrayList<Item>();
        for(Entity entity : level.entities) if(entity instanceof Item) result.add((Item)entity);
        for(Entity entity : level.static_entities) if(entity instanceof Item) result.add((Item)entity);
        for(Entity entity : level.non_collidable_entities) if(entity instanceof Item) result.add((Item)entity);
        return result;
    }

    private static void collectItems(Entity entity, List<Item> result) {
        if(entity == null) return;
        if(entity instanceof Item) result.add((Item)entity);
        if(entity instanceof com.interrupt.dungeoneer.entities.Group) {
            for(Entity child : ((com.interrupt.dungeoneer.entities.Group)entity).entities) {
                collectItems(child, result);
            }
        }
        if(entity.getAttached() != null) {
            for(Entity child : entity.getAttached()) collectItems(child, result);
        }
    }

    private static DirectConnectPeer peer() {
        return (DirectConnectPeer)Proxy.newProxyInstance(OwnedTutorialSmokeTest.class.getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class}, (proxy, method, args) -> {
            if(method.getName().equals("getNextItemRequestId")) return 1L;
            if(method.getName().equals("getNativeWorldGeneration")) return 1L;
            if(method.getName().equals("getLocalMovementEntityId")) return new NetworkEntityId(2L);
            if(method.getName().equals("getMovementEntities")) return Collections.singletonList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(2L),
                            new ParticipantId("campaign-slot-2"), 2, "Client", "humanoid-2"));
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == int.class) return 0;
            if(method.getReturnType() == long.class) return 0L;
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
    }
}
