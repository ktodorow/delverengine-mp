package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.items.Gold;
import com.interrupt.dungeoneer.entities.items.Decoration;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.movement.*;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import com.interrupt.managers.StringManager;
import com.interrupt.dungeoneer.game.LocalizedString;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class NativeItemCatalogueTest {
    private HashMap<String, LocalizedString> previousStrings;
    private com.interrupt.managers.EntityManager previousEntityManager;
    @Before public void strings() {
        previousStrings = StringManager.localizedStrings;
        previousEntityManager = com.interrupt.managers.EntityManager.instance;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        StringManager.localizedStrings.put("items.Gold.defaultNameText", new LocalizedString("Gold", ""));
        StringManager.localizedStrings.put("items.QuestItem.defaultNameText",
                new LocalizedString("Orb", ""));
    }
    @After public void restoreStrings() {
        StringManager.localizedStrings = previousStrings;
        com.interrupt.managers.EntityManager.setSingleton(previousEntityManager);
    }

    @Test
    public void entityManagerPrefabItemMaterializesWhenOnlyHostSpawnsIt() {
        Decoration candle = new Decoration();
        candle.name = "CANDLE";
        candle.tex = 76;
        com.interrupt.managers.EntityManager entityManager = new com.interrupt.managers.EntityManager();
        entityManager.entities = new HashMap<String,
                com.badlogic.gdx.utils.OrderedMap<String, com.interrupt.dungeoneer.entities.Entity>>();
        com.badlogic.gdx.utils.OrderedMap<String, com.interrupt.dungeoneer.entities.Entity> decorations =
                new com.badlogic.gdx.utils.OrderedMap<String, com.interrupt.dungeoneer.entities.Entity>();
        decorations.put("Candle_1", candle);
        entityManager.entities.put("Decorations", decorations);
        com.interrupt.managers.EntityManager.setSingleton(entityManager);

        Game game = catalogueClient(new com.interrupt.managers.ItemManager(),
                new PhysicalItemState(1L, 1L,
                        "9da080251185efc920280c2cd4fb8b4f8bad2a6585abc139e236e9aa881dc987",
                        null, 1, 1, 0));

        assertEquals(1, game.level.entities.size);
        assertTrue(game.level.entities.first() instanceof Decoration);
        assertEquals("CANDLE", ((Decoration)game.level.entities.first()).name);
    }
    /** Owned items.dat keeps Arrow only inside stacked Arrows; firing makes it a standalone item. */
    @Test
    public void stackedNativeAmmoMaterializesOnClientAfterItIsFired() {
        StringManager.localizedStrings.put("items.ItemStack.defaultNameText",
                new LocalizedString("Arrows", ""));
        com.interrupt.dungeoneer.entities.projectiles.Missile arrow =
                new com.interrupt.dungeoneer.entities.projectiles.Missile();
        arrow.name = "Arrow";
        arrow.tex = 73;
        com.interrupt.dungeoneer.entities.items.ItemStack stack =
                new com.interrupt.dungeoneer.entities.items.ItemStack(arrow, 12, "ARROW");
        stack.name = "Arrows";
        com.interrupt.managers.ItemManager itemManager = new com.interrupt.managers.ItemManager();
        itemManager.junk = new com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Item>();
        itemManager.junk.add(stack);

        Game game = catalogueClient(itemManager, new PhysicalItemState(1L, 1L,
                "2c7ece0ed022921b83bcc73bffa9e5639bba53d317b82a96af3bc9d25e200b27", null, 1, 1, 0));

        assertEquals(1, game.level.entities.size);
        assertTrue(game.level.entities.first()
                instanceof com.interrupt.dungeoneer.entities.projectiles.Missile);
        assertEquals("Arrow", ((com.interrupt.dungeoneer.entities.Item)game.level.entities.first()).name);
    }

    @Test
    public void predefinedMonsterLootMaterializesBeforeOnlyHostDropsIt() {
        Decoration relic = new Decoration();
        relic.name = "Monster Relic";
        relic.tex = 91;
        com.interrupt.dungeoneer.entities.Monster monster =
                new com.interrupt.dungeoneer.entities.Monster();
        monster.loot = new com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Item>();
        monster.loot.add(relic);
        com.interrupt.managers.MonsterManager monsters = new com.interrupt.managers.MonsterManager();
        monsters.monsters = new HashMap<String,
                com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Monster>>();
        com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Monster> cave =
                new com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Monster>();
        cave.add(monster);
        monsters.monsters.put("CAVE", cave);
        String templateId = new DirectConnectItemController(null).describe(relic).templateId;

        Game game = catalogueClient(new com.interrupt.managers.ItemManager(), monsters,
                new PhysicalItemState(1L, 1L, templateId, null, 1, 1, 0));

        assertEquals(1, game.level.entities.size);
        assertEquals("Monster Relic",
                ((com.interrupt.dungeoneer.entities.Item)game.level.entities.first()).name);
    }

    @Test
    public void arrowRecoveredFromMonsterMaterializesFromNativeAmmoCatalogue() {
        assertEquals("fb80dc5bb5436d3ffb436c5a9434ab52f13875ee5007506dc2da32cffb887b73",
                assertRecoveredAmmoMaterializes("Arrow", 73, "ARROW", "item"));
    }

    @Test
    public void recoveredAmmoUsesNativeVariantNameTextureAtlasAndStackType() {
        assertRecoveredAmmoMaterializes("Test Bolt", 92, "BOLT", "test-ammo");
    }

    private String assertRecoveredAmmoMaterializes(String name, int texture, String stackType,
            String atlas) {
        StringManager.localizedStrings.put("items.ItemStack.defaultNameText",
                new LocalizedString("Arrows", ""));
        com.interrupt.dungeoneer.entities.projectiles.Missile arrow =
                new com.interrupt.dungeoneer.entities.projectiles.Missile();
        arrow.name = name;
        arrow.tex = texture;
        arrow.stackType = stackType;
        arrow.spriteAtlas = atlas;
        com.interrupt.dungeoneer.entities.items.ItemStack ammunition =
                new com.interrupt.dungeoneer.entities.items.ItemStack(arrow, 12, stackType);
        com.interrupt.managers.ItemManager manager = new com.interrupt.managers.ItemManager();
        manager.junk = new com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Item>();
        manager.junk.add(ammunition);

        com.interrupt.dungeoneer.entities.Monster worm = new com.interrupt.dungeoneer.entities.Monster();
        arrow.addArrowLootToMonster(worm);
        arrow.addArrowLootToMonster(worm);
        assertEquals(1, worm.loot.size);
        DirectConnectItemController.ItemDescription dropped =
                new DirectConnectItemController(null).describe(worm.loot.first());

        Game client = catalogueClient(manager, new PhysicalItemState(7L, 1L,
                dropped.templateId, null, 1, 1, 0, dropped.properties));
        assertEquals("Recovered arrow stack missing from Client world", 1, client.level.entities.size);
        com.interrupt.dungeoneer.entities.items.ItemStack recovered =
                (com.interrupt.dungeoneer.entities.items.ItemStack)client.level.entities.first();
        assertEquals(name, recovered.name);
        assertEquals(texture, recovered.tex);
        assertEquals(atlas, recovered.spriteAtlas);
        assertEquals(2, recovered.count);
        assertEquals(stackType, recovered.stackType);
        assertTrue(recovered.item instanceof com.interrupt.dungeoneer.entities.projectiles.Missile);
        assertEquals(name, recovered.item.name);
        assertEquals(texture, recovered.item.tex);
        return dropped.templateId;
    }

    @Test
    public void generatedQuestItemMaterializesWithoutExistingOnInitialFloor() {
        com.interrupt.dungeoneer.entities.items.QuestItem orb =
                new com.interrupt.dungeoneer.entities.items.QuestItem();
        String templateId = new DirectConnectItemController(null).describe(orb).templateId;

        Game game = catalogueClient(new com.interrupt.managers.ItemManager(),
                new PhysicalItemState(1L, 1L, templateId, null, 1, 1, 0));

        assertEquals(1, game.level.entities.size);
        assertTrue(game.level.entities.first()
                instanceof com.interrupt.dungeoneer.entities.items.QuestItem);
    }

    private Game catalogueClient(com.interrupt.managers.ItemManager itemManager,
            final PhysicalItemState state) {
        return catalogueClient(itemManager, null, state);
    }

    private Game catalogueClient(com.interrupt.managers.ItemManager itemManager,
            com.interrupt.managers.MonsterManager monsterManager,
            final PhysicalItemState state) {
        DirectConnectPeer peer = (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class}, (proxy, method, args) -> {
            if(method.getName().equals("getNextItemRequestId")) return 1L;
            if(method.getName().equals("getNativeWorldGeneration")) return 1L;
            if(method.getName().equals("getLocalMovementEntityId")) return new NetworkEntityId(2L);
            if(method.getName().equals("getMovementEntities")) return Collections.singletonList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(2L),
                            new ParticipantId("campaign-slot-2"), 2, "Client", "humanoid-2"));
            if(method.getName().equals("getPhysicalItems")) return Collections.singletonList(state);
            if(method.getName().equals("failNativePresentation")) throw new AssertionError(args[0]);
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == int.class) return 0;
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.itemManager = itemManager;
        game.monsterManager = monsterManager;
        game.level = new Level(4, 4) {
            @Override public void SpawnEntity(com.interrupt.dungeoneer.entities.Entity entity) { entities.add(entity); }
        };
        new DirectConnectItemController(peer).prepare(game);
        return game;
    }

    @Test
    public void runtimeGoldAbsentFromInitialFloorStillMaterializesOnClient() {
        PhysicalItemState gold = new PhysicalItemState(1L, 1L,
                "3d289dce0c7823af164db61776edda11a44c78ab08d4b5a524e90094f89b6f72",
                null, 1, 1, 0);
        DirectConnectPeer peer = (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class}, (proxy, method, args) -> {
            if(method.getName().equals("getNextItemRequestId")) return 1L;
            if(method.getName().equals("getNativeWorldGeneration")) return 1L;
            if(method.getName().equals("getLocalMovementEntityId")) return new NetworkEntityId(2L);
            if(method.getName().equals("getMovementEntities")) return Collections.singletonList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(2L),
                            new ParticipantId("campaign-slot-2"), 2, "Client", "humanoid-2"));
            if(method.getName().equals("getPhysicalItems")) return Collections.singletonList(gold);
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == int.class) return 0;
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.level = new Level(4, 4) {
            @Override public void SpawnEntity(com.interrupt.dungeoneer.entities.Entity entity) { entities.add(entity); }
        };
        new DirectConnectItemController(peer).prepare(game);
        assertEquals(1, game.level.entities.size);
        assertTrue(game.level.entities.first() instanceof Gold);
    }

    @Test
    public void hostOnlySpawnChanceItemMaterializesFromRawSharedFloorTemplate() {
        Gold candlestick = new Gold();
        candlestick.name = "Gold Candlestick";
        candlestick.tex = 90;
        com.interrupt.dungeoneer.entities.Group furnishing =
                new com.interrupt.dungeoneer.entities.Group();
        furnishing.entities.add(candlestick);
        Level rawFloor = new Level(4, 4);
        rawFloor.entities.add(furnishing);
        PhysicalItemState hostItem = new PhysicalItemState(1L, 1L,
                "e062604e2d8bebc8bde318d50bdc2a6f98284c2256951248720000391ecefc08",
                null, 1, 1, 0);

        Game game = catalogueClient(new com.interrupt.managers.ItemManager(), hostItem, rawFloor);

        assertEquals(1, game.level.entities.size);
        assertEquals("Gold Candlestick",
                ((com.interrupt.dungeoneer.entities.Item)game.level.entities.first()).name);
    }

    private Game catalogueClient(com.interrupt.managers.ItemManager itemManager,
            final PhysicalItemState state, Level rawFloor) {
        DirectConnectPeer peer = (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class}, (proxy, method, args) -> {
            if(method.getName().equals("getNextItemRequestId")) return 1L;
            if(method.getName().equals("getNativeWorldGeneration")) return 1L;
            if(method.getName().equals("getLocalMovementEntityId")) return new NetworkEntityId(2L);
            if(method.getName().equals("getMovementEntities")) return Collections.singletonList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(2L),
                            new ParticipantId("campaign-slot-2"), 2, "Client", "humanoid-2"));
            if(method.getName().equals("getPhysicalItems")) return Collections.singletonList(state);
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == int.class) return 0;
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.itemManager = itemManager;
        game.level = new Level(4, 4) {
            @Override public void SpawnEntity(com.interrupt.dungeoneer.entities.Entity entity) { entities.add(entity); }
        };
        DirectConnectItemController controller = new DirectConnectItemController(peer);
        controller.rememberLevelTemplates(rawFloor);
        controller.prepare(game);
        return game;
    }
}
