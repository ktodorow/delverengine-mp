package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.*;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.game.*;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.ui.*;
import com.interrupt.managers.*;
import org.junit.*;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class NativeOwnershipRegressionTest {
    private final ParticipantId owner = new ParticipantId("alpha");
    private final List<PhysicalItemState> states = new ArrayList<>();
    private final List<ItemAction> requests = new ArrayList<>();
    private com.badlogic.gdx.Application previousApp;
    private Integer hoveredSlot = 0;
    private Game previousGame, game;
    private HUDManager previousHudManager;
    private Hud previousHud;
    private HashMap<String, LocalizedString> previousStrings;
    private DirectConnectItemController controller;

    @Before public void setup() throws Exception {
        previousApp = com.badlogic.gdx.Gdx.app;
        com.badlogic.gdx.Gdx.app = (com.badlogic.gdx.Application)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{com.badlogic.gdx.Application.class}, (p, m, a) -> null);
        previousGame = Game.instance; previousHudManager = Game.hudManager; previousHud = Game.hud;
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<>();
        game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player(); game.level = new Level(4, 4);
        game.player.inventory.clear();
        for(int i = 0; i < game.player.inventorySize; i++) game.player.inventory.add(null);
        Game.instance = game;
        Game.hudManager = new HUDManager();
        Game.hudManager.quickSlots = new Hotbar() {
            @Override public void refresh() {}
            @Override public Integer getMouseOverSlot() { return hoveredSlot; }
        };
        Game.hudManager.backpack = new Hotbar() { @Override public void refresh() {} };
        Game.hud = new Hud() {
            @Override public void refresh() {}
            @Override public void refreshEquipLocations() {}
        };
        DirectConnectPeer peer = (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class}, (proxy, method, args) -> {
            if(method.getName().equals("getPhysicalItems")) return states;
            if(method.getName().equals("submitItemAction")) { requests.add((ItemAction)args[1]); return null; }
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == boolean.class) return false;
            if(method.getReturnType() == int.class) return 0;
            return null;
        });
        controller = new DirectConnectItemController(peer);
        set("game", game); set("localId", owner); set("nextRequest", 1L);
        game.player.setItemAuthorityListener(controller);
    }
    @After public void restore() {
        com.badlogic.gdx.Gdx.app = previousApp;
        Game.instance = previousGame; Game.hudManager = previousHudManager; Game.hud = previousHud;
        Game.dragging = null; StringManager.localizedStrings = previousStrings;
    }
    @SuppressWarnings("unchecked") private <T> T field(String name) throws Exception {
        Field field = DirectConnectItemController.class.getDeclaredField(name); field.setAccessible(true);
        return (T)field.get(controller);
    }
    private void set(String name, Object value) throws Exception {
        Field field = DirectConnectItemController.class.getDeclaredField(name); field.setAccessible(true);
        field.set(controller, value);
    }
    private Armor item(ParticipantId participant) throws Exception {
        Armor item = new Armor(); item.equipLoc = "ARMOR";
        states.add(new PhysicalItemState(1, 1, "armor", participant, 1, 1, 0));
        this.<Map<Long, Item>>field("nativeItems").put(1L, item);
        this.<Map<Item, Long>>field("itemIds").put(item, 1L);
        this.<Map<Long, Long>>field("applied").put(1L, 1L);
        if(participant != null) game.player.inventory.set(0, item);
        else { item.isActive = true; game.level.entities.add(item); }
        return item;
    }
    @Test public void nativeArmorEquipDoesNotReportConsumptionOrResurrectInBackpack() throws Exception {
        Armor armor = item(owner);
        game.player.equipArmor(armor);
        controller.update(game);
        assertFalse("Worn armor must remain owned", requests.contains(ItemAction.CONSUME));
        states.set(0, new PhysicalItemState(1, 2, "armor", owner, 1, 1, 0));
        controller.prepare(game);
        assertSame(armor, game.player.equippedItems.get("ARMOR"));
        assertFalse(game.player.inventory.contains(armor, true));
    }
    @Test public void groundDragCannotInsertBeforeHostApproval() throws Exception {
        Armor armor = item(null);
        Item held = new Item();
        game.player.inventory.set(0, held);
        game.player.heldItem = 0;
        Game.DragAndDropInventoryItem(armor, null, null);
        assertFalse("Ground item still belongs to world", game.player.inventory.contains(armor, true));
        assertEquals(Collections.singletonList(ItemAction.PICKUP), requests);
        assertTrue(armor.isActive);
        states.set(0, new PhysicalItemState(1, 2, "armor", owner, 1, 1, 0));
        controller.prepare(game);
        assertSame(armor, game.player.inventory.get(0));
        assertSame(held, game.player.GetHeldItem());
        assertSame(held, game.player.inventory.get(1));
        assertFalse(armor.isActive);
    }

    @Test public void approvedGroundDragCanEquipAndLaterDropSameIdentity() throws Exception {
        Armor armor = item(null);
        assertTrue(game.player.requestGroundItemPlacement(armor, null, "ARMOR"));
        assertTrue(game.player.equippedItems.isEmpty());
        states.set(0, new PhysicalItemState(1, 2, "armor", owner, 1, 1, 0));
        controller.prepare(game);
        assertSame(armor, game.player.equippedItems.get("ARMOR"));
        assertFalse(game.player.inventory.contains(armor, true));
        hoveredSlot = null;
        assertEquals(Hud.DragAndDropResult.ignore,
                Game.DragAndDropInventoryItem(armor, null, "ARMOR"));
        assertSame(armor, game.player.equippedItems.get("ARMOR"));
        assertEquals(ItemAction.DROP, requests.get(requests.size() - 1));
        assertFalse(requests.contains(ItemAction.CONSUME));
        states.set(0, new PhysicalItemState(1, 3, "armor", null, 1, 1, 0));
        controller.prepare(game);
        assertFalse(game.player.ownsPhysicalItem(armor));
        assertTrue(armor.isActive);
        assertEquals(1, game.level.entities.size);
    }

    @Test public void randomizedNativePotionRetainsHostTypeAfterPickupAndSharing() throws Exception {
        Potion cataloguePotion = new Potion();
        cataloguePotion.name = "Test potion";
        cataloguePotion.potionType = Potion.PotionType.poison;
        ItemManager manager = new ItemManager();
        manager.potions = new com.badlogic.gdx.utils.Array<Potion>();
        manager.potions.add(cataloguePotion);
        // One catalogue entry makes native shuffle deterministically assign the first type, health.
        Potion hostPotion = manager.GetRandomPotion();
        assertEquals(Potion.PotionType.health, hostPotion.potionType);
        assertEquals(Potion.PotionType.poison, cataloguePotion.potionType);
        Method remember = DirectConnectItemController.class.getDeclaredMethod("remember", Item.class);
        remember.setAccessible(true);
        String templateId = (String)remember.invoke(controller, cataloguePotion);
        ItemProperties properties = new ItemProperties(2, 1, "", "", 1,
                hostPotion.potionType.ordinal());
        states.add(new PhysicalItemState(5, 1, templateId, owner, 1, 1, 0, properties));
        controller.prepare(game);
        Potion clientPotion = (Potion)game.player.inventory.get(0);
        assertEquals("Pickup must use Host's rolled type, not catalogue default",
                hostPotion.potionType, clientPotion.potionType);
        states.set(0, new PhysicalItemState(5, 2, templateId, null, 1, 1, 0, properties));
        controller.prepare(game);
        assertFalse(game.player.ownsPhysicalItem(clientPotion));
        assertEquals(hostPotion.potionType, clientPotion.potionType);
        states.set(0, new PhysicalItemState(5, 3, templateId, new ParticipantId("beta"),
                1, 1, 0, properties));
        controller.prepare(game);
        states.set(0, new PhysicalItemState(5, 4, templateId, owner, 1, 1, 0, properties));
        controller.prepare(game);
        assertSame(clientPotion, game.player.inventory.get(0));
        assertEquals(hostPotion.potionType, clientPotion.potionType);
    }

    @Test public void clientCanUnlockWithSharedKeyWithoutSpendingHostsPersonalKeys() {
        final com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext remote =
                new com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext(owner,
                        new com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState(1, 1, 0, 0),
                        new com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression());
        final List<com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext> activators = new ArrayList<>();
        game.level = new Level(4, 4) {
            @Override public void trigger(Entity source, String id, String value,
                    com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext participant) {
                activators.add(participant);
            }
        };
        AuthoritativeItemWorld world = new AuthoritativeItemWorld();
        world.initializePartyKeys(1);
        game.player.keys = 9;
        Door door = new Door(); door.isLocked = true; door.takesKey = true;
        door.openSound = ""; door.closingSound = "";
        door.use(remote, world::spendPartyKey);
        assertFalse(door.isLocked);
        assertEquals(Door.DoorState.OPENING, door.doorState);
        assertEquals(0, world.getPartyKeys());
        assertEquals(9, game.player.keys);
        assertSame(remote, activators.get(0));
        door.use(remote, world::spendPartyKey);
        assertEquals(0, world.getPartyKeys());
        Door second = new Door(); second.isLocked = true; second.takesKey = true;
        second.use(remote, world::spendPartyKey);
        assertTrue(second.isLocked);
    }
}
