package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.*;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.entities.items.Scroll;
import com.interrupt.dungeoneer.entities.spells.Spell;
import com.badlogic.gdx.math.Vector3;
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
    private final List<Object[]> requestArguments = new ArrayList<>();
    private final List<ItemActionResult> results = new ArrayList<>();
    private final List<BreakableSnapshot> breakables = new ArrayList<>();
    private long nativeGeneration = 1L;
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
            if(method.getName().equals("getBreakableSnapshots")) return breakables;
            if(method.getName().equals("getNativeWorldGeneration")) return nativeGeneration;
            if(method.getName().equals("drainItemActionResults")) {
                List<ItemActionResult> copy = new ArrayList<>(results); results.clear(); return copy;
            }
            if(method.getName().equals("submitItemAction")) {
                requests.add((ItemAction)args[1]); requestArguments.add(args); return null;
            }
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == boolean.class) return false;
            if(method.getReturnType() == int.class) return 0;
            return null;
        });
        controller = new DirectConnectItemController(peer);
        set("game", game); set("objectLevel", game.level); set("objectGeneration", nativeGeneration);
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
    @Test public void equippedNativeResistanceMatchesHostAndRemoteAndClearsWhenStowed() throws Exception {
        Armor armor = item(owner);
        armor.baseMods = new com.interrupt.dungeoneer.entities.items.ItemModification() {
            @Override public float getMagicResistMod(Item item) { return 0.25f; }
        };
        states.set(0, new PhysicalItemState(1, 2, "armor", owner, 1, 1, 0,
                ItemProperties.DEFAULT, false, "ARMOR"));
        Player host = new Player(); host.hp = host.maxHp = 8;
        host.equippedItems.put("ARMOR", armor); host.calculatedStats.Recalculate(host);
        Player remoteStats = new Player();
        controller.synchronizeEquipment(owner, remoteStats);
        remoteStats.calculatedStats.Recalculate(remoteStats);
        com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar remote =
                new com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar(
                        new com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor(1,
                                new com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId(2), owner,
                                2, "Friend", "humanoid-2"));
        remote.setNativeCombatStats(remoteStats);
        assertEquals(host.applyNativeDamage(4, com.interrupt.dungeoneer.entities.items.Weapon.DamageType.MAGIC, null),
                remote.applyNativeDamage(4, com.interrupt.dungeoneer.entities.items.Weapon.DamageType.MAGIC, null));
        assertEquals(6, remote.hp);
        states.set(0, new PhysicalItemState(1, 3, "armor", owner, 1, 1, 0));
        controller.synchronizeEquipment(owner, remoteStats); remoteStats.calculatedStats.Recalculate(remoteStats);
        assertEquals(4, remote.applyNativeDamage(4, com.interrupt.dungeoneer.entities.items.Weapon.DamageType.MAGIC, null));
    }

    @Test public void potionWaitsForAcceptanceWithoutLocalHealingOrRepeatedSpending() throws Exception {
        Potion potion = new Potion(); potion.potionType = Potion.PotionType.health;
        states.add(new PhysicalItemState(1, 1, "potion", owner, 1, 1, 0));
        this.<Map<Long, Item>>field("nativeItems").put(1L, potion);
        this.<Map<Item, Long>>field("itemIds").put(potion, 1L);
        game.player.inventory.set(0, potion); game.player.hp = 2;
        potion.Drink(game.player); potion.Drink(game.player);
        assertEquals(2, game.player.hp);
        assertSame(potion, game.player.inventory.get(0));
        assertEquals(Collections.singletonList(ItemAction.CONSUME), requests);
        results.add(new ItemActionResult(1, 1, false));
        controller.prepare(game);
        potion.Drink(game.player);
        assertEquals(Arrays.asList(ItemAction.CONSUME, ItemAction.CONSUME), requests);
        results.add(new ItemActionResult(1, 1, false)); // stale rejection cannot unlock current request
        controller.prepare(game); potion.Drink(game.player);
        assertEquals(2, requests.size());
        potion.applyNativeEffect(game.player);
        assertTrue(game.player.hp >= 6 && game.player.hp <= game.player.getMaxHp());
    }

    @Test public void scrollSendsBoundedAimAndWaitsForHostBeforeLocalPresentation() throws Exception {
        final int[] gameplay = {0}, presentation = {0};
        Scroll scroll = new Scroll();
        scroll.spell = new Spell() {
            @Override public void doCast(Entity source, Vector3 direction, Vector3 position) {
                gameplay[0]++;
            }
            @Override public void playZapPresentation(Actor source, Vector3 position) {
                presentation[0]++;
            }
        };
        states.add(new PhysicalItemState(1, 1, "scroll", owner, 1, 1, 0));
        this.<Map<Long, Item>>field("nativeItems").put(1L, scroll);
        this.<Map<Item, Long>>field("itemIds").put(scroll, 1L);
        this.<Map<Long, Long>>field("applied").put(1L, 1L);
        game.player.inventory.set(0, scroll);

        Vector3 aim = new Vector3(0.25f, -0.5f, 0.75f).nor();
        assertTrue(game.player.requestItemConsume(scroll, aim));
        assertTrue(game.player.requestItemConsume(scroll, aim));
        assertEquals(Collections.singletonList(ItemAction.CONSUME), requests);
        Object[] request = requestArguments.get(0);
        assertEquals(Boolean.TRUE, request[5]);
        assertEquals(aim.x, (Float)request[6], 0f);
        assertEquals(aim.y, (Float)request[7], 0f);
        assertEquals(aim.z, (Float)request[8], 0f);
        assertEquals(0, gameplay[0]); assertEquals(0, presentation[0]);
        assertSame(scroll, game.player.inventory.get(0));

        states.set(0, new PhysicalItemState(1, 2, "scroll", null, 1, 1, 0,
                ItemProperties.DEFAULT, true));
        controller.prepare(game);
        assertEquals("Client cannot execute scroll gameplay", 0, gameplay[0]);
        assertEquals("Consumer receives native cast feedback once", 1, presentation[0]);
        assertFalse(game.player.inventory.contains(scroll, true));
    }

    @Test public void clientBowCopiesExactAmmoWithoutSpeculativeInventorySpend() throws Exception {
        com.interrupt.dungeoneer.entities.projectiles.Missile arrow =
                new com.interrupt.dungeoneer.entities.projectiles.Missile();
        arrow.spriteAtlas = "frost-arrows"; arrow.tex = 19;
        arrow.damageType = com.interrupt.dungeoneer.entities.items.Weapon.DamageType.ICE;
        arrow.color = com.badlogic.gdx.graphics.Color.CYAN.cpy();
        com.interrupt.dungeoneer.entities.items.ItemStack stack =
                new com.interrupt.dungeoneer.entities.items.ItemStack(arrow, 3, "ARROW");
        states.add(new PhysicalItemState(9, 1, "frost-arrow-stack", owner, 1, 1, 0,
                new ItemProperties(2, 1, "", "", 3)));
        this.<Map<Long, Item>>field("nativeItems").put(9L, stack);
        this.<Map<Item, Long>>field("itemIds").put(stack, 9L);
        game.player.inventory.set(0, stack);

        com.interrupt.dungeoneer.entities.projectiles.Missile copy =
                game.player.takeAuthoritativeAttackAmmo();

        assertNotNull(copy); assertNotSame(arrow, copy);
        assertEquals("frost-arrows", copy.spriteAtlas); assertEquals(19, copy.tex);
        assertEquals(com.interrupt.dungeoneer.entities.items.Weapon.DamageType.ICE,
                copy.damageType);
        assertEquals(3, stack.count);
        assertSame(stack, game.player.inventory.get(0));
        assertEquals(3, states.get(0).properties.quantity);
    }

    @Test public void consumedMissileTombstoneDoesNotDeactivateItsInFlightReplica()
            throws Exception {
        com.interrupt.dungeoneer.entities.projectiles.Missile arrow =
                new com.interrupt.dungeoneer.entities.projectiles.Missile();
        arrow.nativePresentationReplica = true; arrow.isActive = true;
        states.add(new PhysicalItemState(10, 2, "single-arrow", null, 1, 1, 0,
                new ItemProperties(2, 1, "", "", 0), true));
        this.<Map<Long, Item>>field("nativeItems").put(10L, arrow);
        this.<Map<Item, Long>>field("itemIds").put(arrow, 10L);
        game.player.inventory.set(0, arrow);

        controller.prepare(game);

        assertFalse(game.player.inventory.contains(arrow, true));
        assertTrue("Dynamic tombstone, not inventory tombstone, owns flight cleanup",
                arrow.isActive);
    }

    @Test public void clientAppliesBreakableStateAndPlaysLiveDestructionOnlyOnce()
            throws Exception {
        final int[] breaks = {0};
        Breakable crate = new Breakable() {
            @Override public void playNetworkBreakPresentation(Level level) { breaks[0]++; }
        };
        this.<Map<Long, Entity>>field("objects").put(1000000L, crate);
        breakables.add(new BreakableSnapshot(1000000L, 1L, 2, true, true,
                1f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f));

        controller.prepare(game);
        assertEquals(2, crate.hp);
        assertTrue(crate.isActive);
        assertTrue(crate.nativePresentationReplica);
        assertEquals(0, breaks[0]);

        breakables.set(0, new BreakableSnapshot(1000000L, 2L, 0, false, false,
                1f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f));
        controller.prepare(game);
        controller.prepare(game);
        assertFalse(crate.isActive);
        assertEquals("Live destruction must not replay on later frames", 1, breaks[0]);
    }

    @Test public void clientRebindsWorldObjectsAndAcceptsLowerRevisionsOnNewFloor()
            throws Exception {
        Breakable oldCrate = new Breakable();
        this.<Map<Long, Entity>>field("objects").put(1000000L, oldCrate);
        breakables.add(new BreakableSnapshot(1000000L, 8L, 1, true, true,
                1f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f));
        controller.prepare(game);
        assertEquals(1, oldCrate.hp);

        Level next = new Level(4, 4);
        Breakable nextCrate = new Breakable();
        next.entities.add(nextCrate);
        game.level = next;
        nativeGeneration++;
        breakables.set(0, new BreakableSnapshot(1000000L, 1L, 6, true, true,
                3f, 1f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f));

        controller.prepare(game);

        assertEquals(6, nextCrate.hp);
        assertEquals(3f, nextCrate.x, 0f);
        assertSame(nextCrate, this.<Map<Long, Entity>>field("objects").get(1000000L));
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

    @Test public void remoteDoorFeedbackDoesNotOverwriteHostsHud() {
        com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext remote =
                new com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext(owner,
                        new com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState(1, 1, 0, 0),
                        new com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression());
        List<DoorFeedback> feedback = new ArrayList<>();
        Door door = new Door();
        door.doorState = Door.DoorState.OPEN; door.getsStuckOpen = true;
        Game.ShowMessage("host-only", 3);
        door.use(remote, () -> false, feedback::add);
        assertEquals("host-only", Game.message.first());
        assertEquals(Collections.singletonList(DoorFeedback.STUCK), feedback);
        door.doorState = Door.DoorState.CLOSED; door.isLocked = true; door.takesKey = true;
        door.use(remote, () -> false, feedback::add);
        assertEquals(DoorFeedback.LOCKED, feedback.get(1));
        door.takesKey = false;
        door.use(remote, () -> false, feedback::add);
        assertEquals(DoorFeedback.OPENS_ELSEWHERE, feedback.get(2));
        door.takesKey = true; door.openSound = "";
        door.use(remote, () -> true, feedback::add);
        assertEquals(DoorFeedback.UNLOCKED, feedback.get(3));
        assertEquals("host-only", Game.message.first());
        door.doorState = Door.DoorState.OPEN;
        door.use((Player)null, 0, 0);
        assertFalse("Single-player feedback remains local", "host-only".equals(Game.message.first()));
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
