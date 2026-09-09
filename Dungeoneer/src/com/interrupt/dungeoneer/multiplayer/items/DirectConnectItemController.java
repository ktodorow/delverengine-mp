package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.items.Key;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.entities.items.Gold;
import com.interrupt.dungeoneer.entities.projectiles.Missile;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.entities.triggers.BasicTrigger;
import com.interrupt.dungeoneer.entities.triggers.ButtonModel;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.ItemModification;
import com.interrupt.dungeoneer.entities.items.ItemStack;
import com.interrupt.dungeoneer.entities.items.Wand;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.multiplayer.combat.CombatWeaponResolver;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementObstacle;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.managers.ItemManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native inventory/world bridge. Only this render-thread boundary mutates engine Items. */
public final class DirectConnectItemController implements Player.ItemAuthorityListener, CombatWeaponResolver {
    private final DirectConnectPeer peer;
    private final DirectConnectHost host;
    private final Map<String, Item> templates = new LinkedHashMap<String, Item>();
    private final Map<String, ItemModification> modifications = new LinkedHashMap<String, ItemModification>();
    private final Map<Long, Item> nativeItems = new LinkedHashMap<Long, Item>();
    private final Map<Item, Long> itemIds = new IdentityHashMap<Item, Long>();
    private final Map<Long, Long> applied = new LinkedHashMap<Long, Long>();
    private final Map<Long, Long> lastPickupAttempts = new LinkedHashMap<Long, Long>();
    private final Map<Long, Entity> objects = new LinkedHashMap<Long, Entity>();
    private final Map<Entity, Long> objectIds = new IdentityHashMap<Entity, Long>();
    private final Map<Long, DoorSnapshot> publishedDoors = new LinkedHashMap<Long, DoorSnapshot>();
    private final Map<Long, String> reportedSpending = new LinkedHashMap<Long, String>();
    private final Map<Long, String> reportedEquipment = new LinkedHashMap<Long, String>();
    private final Map<Long, Placement> pendingPlacements = new LinkedHashMap<Long, Placement>();
    private static final class Placement {
        final Integer inventorySlot;
        final String equipmentSlot;
        Placement(Integer inventorySlot, String equipmentSlot) {
            this.inventorySlot = inventorySlot; this.equipmentSlot = equipmentSlot;
        }
    }
    private long doorRevision;
    private Game game;
    private ParticipantId localId;
    private long nextRequest;
    private long frame;

    public DirectConnectItemController(DirectConnectPeer peer) {
        this.peer = peer;
        host = peer instanceof DirectConnectHost ? (DirectConnectHost)peer : null;
    }

    public void prepare(Game current) {
        if(current == null || current.player == null || current.level == null) return;
        if(game == null) attach(current);
        frame++;
        if(host != null) {
            discoverWorldItems();
            for(ItemRequest request : host.drainItemRequests()) {
                ParticipantContext participant = participant(request.getParticipantId());
                if(participant == null) continue;
                AuthoritativeItemWorld.Outcome result = host.getItemWorld().apply(request,
                        participant, boundary);
                if(result == AuthoritativeItemWorld.Outcome.ACCEPTED
                        && request.action == ItemAction.PICKUP) {
                    Item item = nativeItems.get(request.entityId);
                    if(item != null && item.triggersOnPickup != null) {
                        game.level.trigger(item, item.triggersOnPickup, item.name, participant);
                    }
                }
            }
        }
        applyStates();
        game.player.keys = peer.getPartyKeys();
        if(host == null) for(DoorSnapshot door : peer.getDoorSnapshots()) {
            Entity entity = objects.get(door.entityId);
            if(entity instanceof Door) ((Door)entity).applyNetworkSnapshot(door);
        }
    }

    public void update(Game current) {
        if(game == null) return;
        reportEquipment();
        reportSpending();
        if(host != null) {
            for(Map.Entry<Long, Item> entry : nativeItems.entrySet()) {
                Item item = entry.getValue();
                PhysicalItemState state = host.getItemWorld().get(entry.getKey());
                if(state != null && !state.consumed && state.owner == null && item.isActive) {
                    host.getItemWorld().move(state.entityId, item.x, item.y, item.z);
                }
            }
            host.publishPhysicalItems();
            List<MovementObstacle> obstacles =
                    new ArrayList<MovementObstacle>();
            for(Map.Entry<Long, Entity> entry : objects.entrySet()) {
                if(!(entry.getValue() instanceof Door)) continue;
                Door door = (Door)entry.getValue();
                if(door.isActive && door.isSolid) obstacles.add(
                        new MovementObstacle(
                                door.x, door.y, door.z, door.collision.x, door.collision.y, door.collision.z));
                DoorSnapshot state = ((Door)entry.getValue()).snapshot(entry.getKey(), doorRevision + 1);
                DoorSnapshot previous = publishedDoors.get(entry.getKey());
                if(previous != null && sameDoor(previous, state)) continue;
                doorRevision++;
                publishedDoors.put(entry.getKey(), state);
                host.publishDoor(state);
            }
            host.setDoorObstacles(obstacles);
        }
    }

    private void attach(Game current) {
        game = current;
        for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
            if(descriptor.getEntityId().equals(peer.getLocalMovementEntityId())) {
                localId = descriptor.getParticipantId();
            }
        }
        if(localId == null) throw new IllegalStateException("Item bridge requires local Participant.");
        nextRequest = peer.getNextItemRequestId();
        // Native loot constructors are not entries in items.dat and may first appear after a kill.
        remember(new Gold(1));
        remember(new Gold(6));
        remember(new Key());
        remember(new Missile());
        catalogue(game.itemManager);
        List<Entity> interactable = entities();
        java.util.Collections.sort(interactable, new java.util.Comparator<Entity>() {
            public int compare(Entity a, Entity b) {
                int value = a.getClass().getName().compareTo(b.getClass().getName());
                if(value == 0) value = Float.compare(a.x, b.x);
                if(value == 0) value = Float.compare(a.y, b.y);
                if(value == 0) value = Float.compare(a.z, b.z);
                return value;
            }
        });
        long objectId = 1000000L;
        for(Entity entity : interactable) {
            if(entity instanceof Door || entity instanceof Trigger
                    || entity instanceof BasicTrigger || entity instanceof ButtonModel) {
                objects.put(objectId, entity);
                objectIds.put(entity, objectId++);
            }
        }
        List<Item> starters = new ArrayList<Item>();
        for(Item item : game.player.inventory) if(item != null) {
            remember(item);
            starters.add(item);
        }
        for(Item item : game.player.equippedItems.values()) if(item != null && !starters.contains(item)) {
            remember(item);
            starters.add(item);
        }
        for(Entity entity : entities()) if(entity instanceof Item) remember((Item)entity);
        if(host != null) {
            host.getItemWorld().initializePartyKeys(game.player.keys);
            // Register worn starters first so they do not occupy backpack capacity.
            java.util.Collections.sort(starters, (a, b) -> Boolean.compare(
                    game.player.equippedItems.containsValue(b), game.player.equippedItems.containsValue(a)));
            for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
                ParticipantId participant = descriptor.getParticipantId();
                host.getItemWorld().registerParticipant(participant, game.player.inventorySize);
                for(Item starter : starters) {
                    Item nativeItem = participant.equals(localId) ? starter
                            : ItemManager.Copy(starter.getClass(), starter);
                    register(nativeItem, participant);
                    if(game.player.equippedItems.containsValue(starter)) {
                        host.getItemWorld().registerEquipment(itemIds.get(nativeItem), starter.GetEquipLoc(), true);
                    }
                }
            }
            discoverWorldItems();
        }
        else {
            for(Item item : starters) game.player.removeAuthoritativeItem(item);
            for(Entity entity : entities()) if(entity instanceof Item) entity.isActive = false;
        }
        game.player.setItemAuthorityListener(this);
    }

    private void discoverWorldItems() {
        for(Entity entity : entities()) {
            if(entity instanceof Missile
                    && ((Missile)entity).isInFlight()) continue;
            if(entity instanceof Item && entity.isActive && !itemIds.containsKey((Item)entity)) {
                register((Item)entity, null);
            }
        }
    }

    private List<Entity> entities() {
        List<Entity> result = new ArrayList<Entity>();
        for(Entity entity : game.level.entities) result.add(entity);
        for(Entity entity : game.level.static_entities) result.add(entity);
        for(Entity entity : game.level.non_collidable_entities) result.add(entity);
        return result;
    }

    private void register(Item item, ParticipantId owner) {
        String key = remember(item);
        ItemProperties properties = new ItemProperties(item.itemCondition.ordinal(), item.itemLevel,
                item.enchantment == null || item.enchantment.name == null ? "" : item.enchantment.name,
                item.prefixEnchantment == null || item.prefixEnchantment.name == null
                        ? "" : item.prefixEnchantment.name,
                item instanceof ItemStack ? ((ItemStack)item).count
                        : item instanceof Wand ? ((Wand)item).charges
                        : item instanceof Gold
                                ? ((Gold)item).goldAmount : 1,
                item instanceof Potion ? ((Potion)item).potionType.ordinal() : -1);
        PhysicalItemState state = host.getItemWorld().spawn(key, owner, item.x, item.y, item.z, properties);
        nativeItems.put(state.entityId, item);
        itemIds.put(item, state.entityId);
        if(item instanceof Key) host.getItemWorld().registerKey(state.entityId);
        host.getItemWorld().registerEquipment(state.entityId, item.GetEquipLoc(), false);
    }

    private void applyStates() {
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            Long revision = applied.get(state.entityId);
            if(revision != null && revision >= state.revision
                    && (host != null || state.owner != null)) continue;
            Item item = nativeItems.get(state.entityId);
            if(item == null) {
                Item template = templates.get(state.templateId);
                if(template == null) throw new IllegalStateException(
                        "Host item template is unavailable in local content: " + state.templateId);
                item = ItemManager.Copy(template.getClass(), template);
                nativeItems.put(state.entityId, item);
                itemIds.put(item, state.entityId);
            }
            boolean ownedLocally = localId.equals(state.owner);
            boolean alreadyOwnedLocally = ownedLocally && game.player.ownsPhysicalItem(item);
            int condition = alreadyOwnedLocally ? Math.min(item.itemCondition.ordinal(),
                    state.properties.condition) : state.properties.condition;
            int quantity = state.properties.quantity;
            if(alreadyOwnedLocally && item instanceof ItemStack) quantity = Math.min(quantity, ((ItemStack)item).count);
            if(alreadyOwnedLocally && item instanceof Wand) quantity = Math.min(quantity, ((Wand)item).charges);
            item.itemCondition = Item.ItemCondition.values()[condition];
            item.itemLevel = state.properties.level;
            if(item instanceof Potion && state.properties.potionType >= 0) {
                ((Potion)item).potionType = Potion.PotionType.values()[state.properties.potionType];
            }
            item.enchantment = modification(state.properties.suffix);
            item.prefixEnchantment = modification(state.properties.prefix);
            if(item instanceof ItemStack) ((ItemStack)item).count = quantity;
            if(item instanceof Wand) ((Wand)item).charges = quantity;
            if(item instanceof Gold)
                ((Gold)item).goldAmount = quantity;
            if(!ownedLocally && game.player.ownsPhysicalItem(item)) {
                game.player.removeAuthoritativeItem(item);
            }
            if(state.consumed) {
                item.isActive = false;
            }
            else if(state.owner == null) {
                boolean newlyDropped = !item.isActive || !game.level.entities.contains(item, true);
                item.isActive = true;
                item.isDynamic = true;
                if(host == null) item.xa = item.ya = item.za = 0f;
                item.x = state.x; item.y = state.y; item.z = state.z;
                if(newlyDropped) {
                    item.xa = item.ya = item.za = 0f;
                    item.ignorePlayerCollision = true;
                    if(!game.level.entities.contains(item, true)) game.level.SpawnEntity(item);
                }
            }
            else {
                item.isActive = false;
                String pendingSpend = reportedSpending.get(state.entityId);
                boolean pendingConsumption = pendingSpend != null && pendingSpend.startsWith("true:");
                if(ownedLocally && !pendingConsumption && !game.player.ownsPhysicalItem(item)) {
                    if(!state.equipmentSlot.isEmpty()) {
                        game.player.equippedItems.put(state.equipmentSlot, item);
                        Game.RefreshUI();
                    }
                    else if(!game.player.addAuthoritativeItemToInventory(item)) {
                        throw new IllegalStateException("Local inventory cannot apply Host ownership.");
                    }
                }
            }
            if(ownedLocally) applyPlacement(state.entityId, item);
            else if(state.owner != null || state.consumed) pendingPlacements.remove(state.entityId);
            if(!ownedLocally) {
                reportedSpending.remove(state.entityId);
                reportedEquipment.remove(state.entityId);
            }
            applied.put(state.entityId, state.revision);
        }
    }

    private void reportEquipment() {
        if(peer.isSessionPaused()) return;
        // Equips first: native swaps free the source backpack slot atomically on Host.
        for(boolean equipping : new boolean[]{true, false}) {
            for(PhysicalItemState state : peer.getPhysicalItems()) {
                if(!localId.equals(state.owner)) continue;
                Item item = nativeItems.get(state.entityId);
                if(item == null || !game.player.ownsPhysicalItem(item)) continue;
                String slot = game.player.equippedItems.containsValue(item) ? item.GetEquipLoc() : "";
                if(slot == null || equipping == slot.isEmpty()) continue;
                String pending = reportedEquipment.get(state.entityId);
                if(pending != null && pending.equals(state.equipmentSlot)) {
                    reportedEquipment.remove(state.entityId);
                    pending = null;
                }
                if(slot.equals(pending == null ? state.equipmentSlot : pending)) continue;
                submit(equipping ? ItemAction.EQUIP : ItemAction.STOW, state.entityId);
                reportedEquipment.put(state.entityId, slot);
            }
        }
    }

    /** Owner may spend existing inventory; this path cannot mint, improve, or transfer an item. */
    private void reportSpending() {
        if(peer.isSessionPaused()) return;
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            if(!localId.equals(state.owner) || !applied.containsKey(state.entityId)) continue;
            Item item = nativeItems.get(state.entityId);
            if(item == null) continue;
            boolean consumed = !game.player.ownsPhysicalItem(item);
            int condition = Math.min(item.itemCondition.ordinal(), state.properties.condition);
            int quantity = item instanceof ItemStack ? ((ItemStack)item).count
                    : item instanceof Wand ? ((Wand)item).charges : 1;
            quantity = Math.max(0, Math.min(quantity, state.properties.quantity));
            if(!consumed && condition == state.properties.condition
                    && quantity == state.properties.quantity) continue;
            String signature = consumed + ":" + condition + ":" + quantity;
            if(signature.equals(reportedSpending.get(state.entityId))) continue;
            if(nextRequest == Long.MAX_VALUE) return;
            peer.submitItemAction(nextRequest++, consumed ? ItemAction.CONSUME : ItemAction.SPEND,
                    state.entityId, condition, quantity);
            reportedSpending.put(state.entityId, signature);
        }
    }

    @Override
    public boolean pickupInto(Item item, Integer inventorySlot, String equipmentSlot) {
        Long id = itemIds.get(item);
        if(id == null || peer.isSessionPaused()) return true;
        if(equipmentSlot != null && !equipmentSlot.equals(item.GetEquipLoc())) return true;
        pendingPlacements.clear();
        pendingPlacements.put(id, new Placement(inventorySlot, equipmentSlot));
        return submitPickup(item);
    }

    private void applyPlacement(long id, Item item) {
        Placement placement = pendingPlacements.remove(id);
        if(placement == null) return;
        int source = game.player.inventory.indexOf(item, true);
        if(source < 0) return;
        Item held = game.player.GetHeldItem();
        if(placement.equipmentSlot != null) {
            Item swap = game.player.equippedItems.put(placement.equipmentSlot, item);
            game.player.inventory.set(source, swap);
        }
        else if(placement.inventorySlot != null && placement.inventorySlot >= 0
                && placement.inventorySlot < game.player.inventorySize) {
            Item swap = game.player.inventory.get(placement.inventorySlot);
            game.player.inventory.set(source, swap);
            game.player.inventory.set(placement.inventorySlot, item);
        }
        if(held != null) {
            int heldSlot = game.player.inventory.indexOf(held, true);
            game.player.heldItem = heldSlot < 0 ? null : heldSlot;
        }
        Game.RefreshUI();
    }

    @Override
    public boolean pickup(Item item) {
        Long id = itemIds.get(item);
        if(id != null) pendingPlacements.remove(id);
        return submitPickup(item);
    }

    private boolean submitPickup(Item item) {
        reportEquipment();
        Long id = itemIds.get(item);
        if(id != null) {
            Long previous = lastPickupAttempts.get(id);
            // Auto-pickups can touch each render frame while reliable acknowledgement is in flight.
            if(previous == null || frame - previous >= 30L) {
                lastPickupAttempts.put(id, frame);
                submit(ItemAction.PICKUP, id);
            }
        }
        return true;
    }

    @Override
    public boolean drop(Item item) {
        reportEquipment();
        reportSpending();
        Long id = itemIds.get(item);
        if(id != null) submit(ItemAction.DROP, id);
        return true;
    }

    @Override
    public boolean use(Entity entity, float x, float y) {
        if(entity instanceof Item) return pickup((Item)entity);
        Long id = objectIds.get(entity);
        if(id == null) return false;
        submit(ItemAction.USE_OBJECT, id);
        return true;
    }

    private void submit(ItemAction action, long id) {
        if(nextRequest == Long.MAX_VALUE || peer.isSessionPaused()) return;
        peer.submitItemAction(nextRequest++, action, id);
    }

    private ParticipantContext participant(ParticipantId id) {
        List<MovementSnapshot> snapshots = peer.getMovementSnapshots();
        if(snapshots.isEmpty()) return null;
        MovementSnapshot snapshot = snapshots.get(snapshots.size() - 1);
        for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
            if(!descriptor.getParticipantId().equals(id)) continue;
            MovementEntityState state = snapshot.getEntity(descriptor.getEntityId());
            if(state == null) return null;
            return new ParticipantContext(id, new ParticipantCharacterState(state.getX(), state.getY(),
                    state.getZ(), state.getRotation()),
                    LocalPlayerCompatibilityAdapter.fromGame().getPartyProgression());
        }
        return null;
    }

    private final AuthoritativeItemWorld.InteractionBoundary boundary =
            new AuthoritativeItemWorld.InteractionBoundary() {
        public boolean canAct(ParticipantContext participant) {
            if(peer.isSessionPaused() || peer.getPartyStatus() == null) return false;
            for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
                if(!descriptor.getParticipantId().equals(participant.getParticipantId())) continue;
                for(PartyMemberStatus member : peer.getPartyStatus().getMembers()) {
                    if(member.getCampaignSlot() == descriptor.getCampaignSlot()) {
                        return member.getState() == PartyMemberState.CONNECTED && member.getHealth() > 0;
                    }
                }
            }
            return false;
        }
        public boolean canReach(ParticipantContext participant, float x, float y, float z) {
            return reachable(participant, x, y, null);
        }
        public boolean useObject(long entityId, ParticipantContext participant) {
            Entity entity = objects.get(entityId);
            if(entity == null || !entity.isActive) return false;
            float dx = participant.getCharacter().getX() - entity.x;
            float dy = participant.getCharacter().getY() - entity.y;
            if(dx * dx + dy * dy > 1.21f
                    || Math.abs(participant.getCharacter().getZ() - entity.z) > 1f
                    || !reachable(participant, entity.x, entity.y, entity)) return false;
            if(entity instanceof Door) ((Door)entity).use(participant, () -> host.getItemWorld().spendPartyKey());
            else if(entity instanceof Trigger) {
                Trigger trigger = (Trigger)entity;
                if(trigger.triggerType != Trigger.TriggerType.USE) return false;
                trigger.use(participant);
            }
            else if(entity instanceof ButtonModel) ((ButtonModel)entity).use(participant);
            else if(entity instanceof BasicTrigger) ((BasicTrigger)entity).use(participant);
            else return false;
            return true;
        }
    };

    private boolean reachable(ParticipantContext participant, float x, float y, Entity target) {
        float fromX = participant.getCharacter().getX();
        float fromY = participant.getCharacter().getY();
        if(!game.level.canSee(fromX, fromY, x, y)) return false;
        for(Entity object : objects.values()) {
            if(object == target || !(object instanceof Door) || !object.isActive || !object.isSolid) continue;
            MovementObstacle obstacle = new MovementObstacle(object.x, object.y, object.z,
                    object.collision.x, object.collision.y, object.collision.z);
            if(obstacle.blocksSegment(fromX, fromY, x, y)) return false;
        }
        return true;
    }

    private void catalogue(ItemManager manager) {
        if(manager == null) return;
        if(manager.melee != null) for(Array<? extends Item> values : manager.melee.values()) remember(values);
        if(manager.armor != null) for(Array<? extends Item> values : manager.armor.values()) remember(values);
        if(manager.ranged != null) for(Array<? extends Item> values : manager.ranged.values()) remember(values);
        remember(manager.unique); remember(manager.wands); remember(manager.potions);
        remember(manager.food); remember(manager.scrolls); remember(manager.decorations); remember(manager.junk);
        if(manager.itemsByName != null) for(Item item : manager.itemsByName.values()) remember(item);
        rememberModifications(manager.weaponEnchantments); rememberModifications(manager.weaponPrefixEnchantments);
        rememberModifications(manager.armorEnchantments); rememberModifications(manager.armorPrefixEnchantments);
    }

    private void remember(Array<? extends Item> items) {
        if(items != null) for(Item item : items) remember(item);
    }

    private String remember(Item item) {
        String key = templateKey(item);
        if(!templates.containsKey(key)) templates.put(key, ItemManager.Copy(item.getClass(), item));
        if(item.enchantment != null) modifications.put(item.enchantment.name, item.enchantment);
        if(item.prefixEnchantment != null) modifications.put(item.prefixEnchantment.name, item.prefixEnchantment);
        return key;
    }

    private void rememberModifications(Array<ItemModification> values) {
        if(values != null) for(ItemModification value : values) modifications.put(value.name, value);
    }

    private ItemModification modification(String name) {
        if(name.isEmpty()) return null;
        ItemModification value = modifications.get(name);
        if(value == null) throw new IllegalStateException("Unknown Host item modification: " + name);
        return value;
    }

    private static String templateKey(Item item) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((item.getClass().getName()
                    + "\n" + item.name + "\n" + item.tex).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for(byte value : digest) key.append(String.format("%02x", value & 255));
            return key.toString();
        }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static boolean sameDoor(DoorSnapshot a, DoorSnapshot b) {
        return a.state == b.state && a.locked == b.locked && a.active == b.active
                && a.solid == b.solid && a.x == b.x && a.y == b.y && a.z == b.z
                && a.rotation == b.rotation && a.animation == b.animation;
    }

    @Override
    public long identity(Weapon weapon) {
        Long id = itemIds.get(weapon);
        return id == null ? 0L : id;
    }

    @Override
    public Weapon ownedWeapon(ParticipantId participant, long entityId) {
        if(host == null || participant == null) return null;
        PhysicalItemState state = host.getItemWorld().get(entityId);
        Item item = nativeItems.get(entityId);
        return state != null && !state.consumed && participant.equals(state.owner)
                && item instanceof Weapon ? (Weapon)item : null;
    }

    public void dispose() {
        if(game != null) game.player.setItemAuthorityListener(null);
    }
}
