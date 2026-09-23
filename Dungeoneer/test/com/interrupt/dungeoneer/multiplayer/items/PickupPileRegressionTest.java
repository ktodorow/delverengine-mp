package com.interrupt.dungeoneer.multiplayer.items;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Bow;
import com.interrupt.dungeoneer.entities.items.Food;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.LocalizedString;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.managers.HUDManager;
import com.interrupt.dungeoneer.ui.Hotbar;
import com.interrupt.dungeoneer.ui.Hud;
import com.interrupt.managers.StringManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A pile of Host items picked up in one sweep must all land and stay in the local inventory. */
public class PickupPileRegressionTest {
    private final ParticipantId owner = new ParticipantId("campaign-slot-2");
    private final Map<Long, PhysicalItemState> states = new HashMap<Long, PhysicalItemState>();
    private final List<Object[]> requests = new ArrayList<Object[]>();
    private final List<ItemActionResult> results = new ArrayList<ItemActionResult>();
    private com.badlogic.gdx.Application previousApp;
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
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player(); game.level = new Level(4, 4);
        game.player.inventory.clear();
        for(int i = 0; i < game.player.inventorySize; i++) game.player.inventory.add(null);
        Game.instance = game;
        Game.hudManager = new HUDManager();
        Game.hudManager.quickSlots = new Hotbar() { @Override public void refresh() {} };
        Game.hudManager.backpack = new Hotbar() { @Override public void refresh() {} };
        Game.hud = new Hud() {
            @Override public void refresh() {}
            @Override public void refreshEquipLocations() {}
        };
        DirectConnectPeer peer = (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class}, (proxy, method, args) -> {
            if(method.getName().equals("getPhysicalItems")) return new ArrayList<PhysicalItemState>(states.values());
            if(method.getName().equals("getNativeWorldGeneration")) return 1L;
            if(method.getName().equals("drainItemActionResults")) {
                List<ItemActionResult> copy = new ArrayList<ItemActionResult>(results); results.clear(); return copy;
            }
            if(method.getName().equals("submitItemAction")) { requests.add(args); return null; }
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == boolean.class) return false;
            if(method.getReturnType() == int.class) return 0;
            return null;
        });
        controller = new DirectConnectItemController(peer);
        set("game", game); set("objectLevel", game.level); set("objectGeneration", 1L);
        set("localId", owner); set("nextRequest", 1L);
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

    private Item worldItem(long id, Item item) throws Exception {
        item.isActive = true;
        item.x = 1f; item.y = 1f;
        states.put(id, new PhysicalItemState(id, 1L, "template-" + id, null, 1f, 1f, 0f));
        this.<Map<Long, Item>>field("nativeItems").put(id, item);
        this.<Map<Item, Long>>field("itemIds").put(item, id);
        game.level.entities.add(item);
        return item;
    }

    private void frame() {
        controller.prepare(game);
        controller.update(game);
    }

    private List<ItemAction> actions() {
        List<ItemAction> actions = new ArrayList<ItemAction>();
        for(Object[] request : requests) actions.add((ItemAction)request[1]);
        return actions;
    }

    @Test public void everyItemOfASweptPileLandsAndStaysInInventoryWhateverTheAcceptanceOrder() throws Exception {
        // Already holding an owned sword, so a picked-up weapon is not auto-equipped
        // (native equip needs a renderer here); the held sword is what WIELD reports.
        Item held = new Sword();
        states.put(6L, new PhysicalItemState(6L, 1L, "template-6", owner, 1f, 1f, 0f));
        this.<Map<Long, Item>>field("nativeItems").put(6L, held);
        this.<Map<Item, Long>>field("itemIds").put(held, 6L);
        this.<Map<Long, Long>>field("applied").put(6L, 1L);
        game.player.inventory.set(0, held);
        game.player.heldItem = 0;
        List<Item> pile = Arrays.asList(worldItem(1L, new Potion()), worldItem(2L, new Potion()),
                worldItem(3L, new Food()), worldItem(4L, new Bow()), worldItem(5L, new Sword()));
        frame();
        for(Item item : pile) item.doPickup(game.player);
        assertEquals(5, Collections.frequency(actions(), ItemAction.PICKUP));
        for(Item item : pile) assertFalse("nothing enters inventory before Host accepts", game.player.inventory.contains(item, true));

        Random random = new Random(3L);
        List<Long> order = new ArrayList<Long>(Arrays.asList(1L, 2L, 3L, 4L, 5L));
        Collections.shuffle(order, random);
        long revision = 2L;
        for(int index = 0; index < order.size(); index++) {
            long id = order.get(index);
            states.put(id, new PhysicalItemState(id, revision++, "template-" + id, owner, 1f, 1f, 0f));
            results.add(new ItemActionResult(index + 1L, id, true));
            // Acceptance can land after prepare already ran this frame: update and native
            // touches then see the owned state before it has been presented.
            if(random.nextBoolean()) controller.update(game);
            for(Item item : pile) item.doPickup(game.player);
            // Sometimes two acceptances share a frame, sometimes frames pass with nothing.
            if(random.nextBoolean()) frame();
            if(random.nextInt(3) == 0) frame();
            for(Item item : pile) item.doPickup(game.player);
        }
        for(int i = 0; i < 5; i++) frame();

        for(Item item : pile) assertTrue(item.name + " must be in inventory", game.player.inventory.contains(item, true));
        List<ItemAction> actions = actions();
        assertFalse("nothing may be dropped: " + actions, actions.contains(ItemAction.DROP));
        assertFalse("nothing may be consumed: " + actions, actions.contains(ItemAction.CONSUME));
        assertFalse("nothing may be spent: " + actions, actions.contains(ItemAction.SPEND));
        assertTrue("the held weapon is reported once", actions.contains(ItemAction.WIELD));
        assertEquals(1, Collections.frequency(actions, ItemAction.WIELD));
        for(Item item : pile) assertFalse(item.isActive);
    }
}
