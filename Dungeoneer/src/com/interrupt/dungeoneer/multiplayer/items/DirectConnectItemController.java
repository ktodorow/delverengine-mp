package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.items.Key;
import com.interrupt.dungeoneer.entities.items.Armor;
import com.interrupt.dungeoneer.entities.items.FusedBomb;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.entities.items.Food;
import com.interrupt.dungeoneer.entities.items.Scroll;
import com.interrupt.dungeoneer.entities.items.Gold;
import com.interrupt.dungeoneer.entities.items.QuestItem;
import com.interrupt.dungeoneer.entities.projectiles.Missile;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Group;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Mover;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.entities.triggers.BasicTrigger;
import com.interrupt.dungeoneer.entities.triggers.ButtonModel;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.ItemSpawner;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.multiplayer.floor.SharedFloorIdentity;
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
import com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar;
import com.interrupt.dungeoneer.multiplayer.participant.LocalPlayerCompatibilityAdapter;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.managers.ItemManager;
import com.interrupt.managers.EntityManager;
import com.badlogic.gdx.utils.OrderedMap;
import com.interrupt.managers.MonsterManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Native inventory/world bridge. Only this render-thread boundary mutates engine Items. */
public final class DirectConnectItemController implements Player.ItemAuthorityListener, CombatWeaponResolver {
    private final DirectConnectPeer peer;
    private final DirectConnectHost host;
    private final com.interrupt.dungeoneer.multiplayer.knowledge.NativeKnowledgeController knowledgeController;
    private final Map<String, Item> templates = new LinkedHashMap<String, Item>();
    private final Map<String, ItemModification> modifications = new LinkedHashMap<String, ItemModification>();
    private final Map<Long, Item> nativeItems = new LinkedHashMap<Long, Item>();
    private final Map<Item, Long> itemIds = new IdentityHashMap<Item, Long>();
    private final Map<Entity, Long> transientItemIds = new WeakHashMap<Entity, Long>();
    private final Map<Long, Long> applied = new LinkedHashMap<Long, Long>();
    private final Map<Long, Long> lastPickupAttempts = new LinkedHashMap<Long, Long>();
    private final Map<Long, Entity> objects = new LinkedHashMap<Long, Entity>();
    private final Map<Entity, Long> objectIds = new IdentityHashMap<Entity, Long>();
    private final Map<Long, DoorSnapshot> publishedDoors = new LinkedHashMap<Long, DoorSnapshot>();
    private final Map<Long, DoorSnapshot> appliedDoors = new LinkedHashMap<Long, DoorSnapshot>();
    private final Map<Long, MoverSnapshot> publishedMovers = new LinkedHashMap<Long, MoverSnapshot>();
    private final Map<Long, MoverSnapshot> appliedMovers = new LinkedHashMap<Long, MoverSnapshot>();
    private long moverRevision;
    private final Map<Long, BreakableSnapshot> publishedBreakables =
            new LinkedHashMap<Long, BreakableSnapshot>();
    private final Map<Long, BreakableSnapshot> appliedBreakables =
            new LinkedHashMap<Long, BreakableSnapshot>();
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
    private final Map<Long, Long> pendingConsumption = new LinkedHashMap<Long, Long>();
    private final Map<Long, Vector3> pendingConsumptionAim = new LinkedHashMap<Long, Vector3>();
    private final Set<Long> restoredNativeMetadata = new HashSet<Long>();
    public interface ConsumableConsumer {
        boolean consume(ParticipantId participant, Item item, Vector3 direction);
    }
    private ConsumableConsumer consumableConsumer;

    public void setConsumableConsumer(ConsumableConsumer consumer) {
        consumableConsumer = consumer;
    }

    /** Native automap output seam; only remote markers authorized for this viewer. */
    public List<MovementEntityState> getMapMarkers() {
        return game == null ? java.util.Collections.emptyList() : knowledgeController.mapMarkers(game.level);
    }

    public boolean isLocalMapMarkerVisible() {
        if(peer.getPartyStatus() == null) return true;
        PartyMemberStatus local = peer.getPartyStatus().getMember(peer.getLocalCampaignSlot());
        return local == null || local.getState() != PartyMemberState.SPECTATING;
    }

    /** Campaign Slot economy decisions that share this bridge's request identities. */
    public interface EconomyBoundary {
        boolean acquireGold(ParticipantContext participant, int amount);
        boolean economyAction(ItemRequest request, ParticipantContext participant);
        void onItemActionResult(ItemActionResult result);
    }
    private EconomyBoundary economyBoundary;
    private final Map<Long, Long> pendingGoldPickups = new LinkedHashMap<Long, Long>();

    public void setEconomyBoundary(EconomyBoundary boundary) {
        economyBoundary = boundary;
    }

    @Override public boolean consume(Item item) {
        return consume(item, null);
    }

    @Override public boolean consume(Item item, Vector3 direction) {
        if(!(item instanceof Potion) && !(item instanceof Food) && !(item instanceof Scroll)) return false;
        if(item instanceof Scroll && direction == null) return true;
        Long id = itemIds.get(item);
        if(id == null || nextRequest == Long.MAX_VALUE || peer.isSessionPaused()) return true;
        PhysicalItemState state = null;
        for(PhysicalItemState candidate : peer.getPhysicalItems()) if(candidate.entityId == id) { state = candidate; break; }
        if(state == null || state.consumed || !localId.equals(state.owner)) return true;
        // A pickup accepted mid-frame is owned on Host before it is in the local inventory;
        // a touch-consume (potion, food) must not turn that pickup into a drink.
        if(!isPresented(state) || !game.player.ownsPhysicalItem(item)) return true;
        if(!pendingConsumption.containsKey(id)) {
            long request = nextRequest++;
            pendingConsumption.put(id, request);
            if(direction != null) pendingConsumptionAim.put(id, direction.cpy());
            peer.submitItemAction(request, ItemAction.CONSUME,
                    id, state.properties.condition, state.properties.quantity,
                    direction != null, direction == null ? 0f : direction.x,
                    direction == null ? 0f : direction.y,
                    direction == null ? 0f : direction.z);
        }
        return true;
    }

    @Override public boolean controlsAttackAmmo() { return true; }

    @Override public Missile takeAttackAmmo() { return takeOwnedMissile(localId); }

    private long doorRevision;
    private long breakableRevision;
    private Game game;
    private Level objectLevel;
    private com.interrupt.dungeoneer.multiplayer.movement.NativeMovementObstacles movementObstacles =
            new com.interrupt.dungeoneer.multiplayer.movement.NativeMovementObstacles();
    private long objectGeneration = -1L;
    private ParticipantId localId;
    private long nextRequest;
    private long frame;

    public DirectConnectItemController(DirectConnectPeer peer) {
        this.peer = peer;
        host = peer instanceof DirectConnectHost ? (DirectConnectHost)peer : null;
        knowledgeController = new com.interrupt.dungeoneer.multiplayer.knowledge.NativeKnowledgeController(peer);
    }

    public void prepare(Game current) {
        if(current == null || current.player == null || current.level == null) return;
        if(game == null) attach(current);
        else if(objectLevel != current.level) attachNextFloor(current);
        if(host == null && current.progression != null) peer.getPartyProgression().applyTo(current.progression);
        if(objectLevel != current.level) attachWorldObjects();
        long generation = peer.getNativeWorldGeneration();
        if(objectGeneration != generation) {
            objectGeneration = generation;
            publishedDoors.clear();
            appliedDoors.clear();
            appliedMovers.clear();
            publishedMovers.clear();
            publishedBreakables.clear();
            appliedBreakables.clear();
        }
        frame++;
        if(host != null) {
            discoverWorldItems();
            for(ItemRequest request : host.drainItemRequests()) {
                if(request.worldGeneration != peer.getNativeWorldGeneration()) {
                    diagnose("Host refused stale " + request.action + " for world generation "
                            + request.worldGeneration);
                    host.publishItemActionResult(request.getParticipantId(), new ItemActionResult(
                            request.requestId, request.entityId, false));
                    continue;
                }
                ParticipantContext participant = participant(request.getParticipantId());
                if(participant == null) {
                    diagnose("no authoritative character state for "
                            + request.getParticipantId().getValue() + " " + request.action);
                    host.publishItemActionResult(request.getParticipantId(), new ItemActionResult(
                            request.requestId, request.entityId, false)); continue;
                }
                AuthoritativeItemWorld.Outcome result =
                        host.getItemWorld().apply(request, participant, boundary);
                if(result != AuthoritativeItemWorld.Outcome.ACCEPTED) {
                    diagnose("Host refused " + request.action + " from "
                            + request.getParticipantId().getValue() + " entity " + request.entityId
                            + " request " + request.requestId + ": " + result);
                }
                host.publishItemActionResult(request.getParticipantId(), new ItemActionResult(
                        request.requestId, request.entityId, result == AuthoritativeItemWorld.Outcome.ACCEPTED));
                if(result == AuthoritativeItemWorld.Outcome.ACCEPTED
                        && request.action == ItemAction.PICKUP) {
                    Item item = nativeItems.get(request.entityId);
                    if(item != null && item.triggersOnPickup != null) {
                        game.level.trigger(item, item.triggersOnPickup, item.name, participant);
                    }
                }
                if(result == AuthoritativeItemWorld.Outcome.ACCEPTED
                        && request.action == ItemAction.LIGHT) {
                    Item item = nativeItems.get(request.entityId);
                    if(item instanceof FusedBomb) ((FusedBomb)item).onChargeStart();
                }
            }
        }
        for(ItemActionResult result : peer.drainItemActionResults()) {
            Long pending = pendingConsumption.get(result.entityId);
            if(!result.accepted && pending != null && pending == result.requestId) {
                pendingConsumption.remove(result.entityId);
                pendingConsumptionAim.remove(result.entityId);
            }
            if(!result.accepted) {
                diagnose("Host refused request " + result.requestId + " for entity " + result.entityId);
                // A refused wield report (for example while Downed) is repeated once the owner acts again.
                if(result.requestId == wieldRequest) reportedWield = -1L;
            }
            Long goldId = pendingGoldPickups.remove(result.requestId);
            Item gold = goldId == null ? null : nativeItems.get(goldId);
            // Only the Host-accepted acquirer hears the native pickup; others see the pile vanish.
            if(result.accepted && gold instanceof Gold) ((Gold)gold).presentPickup(game.player);
            if(economyBoundary != null) economyBoundary.onItemActionResult(result);
        }
        for(DoorFeedback feedback : peer.drainDoorFeedback()) {
            Game.ShowMessage(com.interrupt.managers.StringManager.get(feedback.localizationKey), 3, 1f);
        }
        applyStates();
        knowledgeController.prepare(game);
        game.player.keys = peer.getPartyKeys();
        if(host == null) for(DoorSnapshot door : peer.getDoorSnapshots()) {
            Entity entity = objects.get(door.entityId);
            DoorSnapshot previous = appliedDoors.get(door.entityId);
            if(entity instanceof Door && (previous == null || previous.revision < door.revision)) {
                if(previous != null && previous.active && !door.active
                        && (peer.getStatus() == null || peer.getStatus().getPhase()
                                == com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase.READY))
                    ((Door)entity).playNetworkBreakPresentation(game.level);
                ((Door)entity).applyNetworkSnapshot(door);
                appliedDoors.put(door.entityId, door);
            }
        }
        List<MoverSnapshot> movers = host == null ? peer.getMoverSnapshots() : null;
        if(movers != null) for(MoverSnapshot mover : movers) {
            Entity entity = objects.get(mover.entityId);
            MoverSnapshot previous = appliedMovers.get(mover.entityId);
            if(entity instanceof Mover && (previous == null || previous.revision < mover.revision)) {
                ((Mover)entity).applyNetworkState(mover.x, mover.y, mover.z, mover.rotationX,
                        mover.rotationY, mover.rotationZ, mover.moving);
                appliedMovers.put(mover.entityId, mover);
            }
        }
        if(host == null) for(BreakableSnapshot state : peer.getBreakableSnapshots()) {
            Entity entity = objects.get(state.entityId);
            BreakableSnapshot previous = appliedBreakables.get(state.entityId);
            if(entity instanceof Breakable
                    && (previous == null || previous.revision < state.revision)) {
                if(previous != null && previous.active && !state.active
                        && (peer.getStatus() == null || peer.getStatus().getPhase()
                                == com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase.READY))
                    ((Breakable)entity).playNetworkBreakPresentation(game.level);
                ((Breakable)entity).applyNetworkSnapshot(state);
                appliedBreakables.put(state.entityId, state);
            }
        }
    }

    public void update(Game current) {
        if(game == null) return;
        knowledgeController.update(game);
        reportEquipment();
        reportSpending();
        reportWield();
        if(host == null) presentHostTriggerChains();
        if(host != null) {
            for(Map.Entry<Long, Item> entry : nativeItems.entrySet()) {
                Item item = entry.getValue();
                PhysicalItemState state = host.getItemWorld().get(entry.getKey());
                // A Life-loss scatter from the Host tick thread is presented first by applyStates;
                // until then this item is still inactive locally and must not be tombstoned.
                Long presented = applied.get(entry.getKey());
                if(state != null && !state.consumed && state.owner == null
                        && presented != null && presented == state.revision) {
                    if(item.isActive) host.getItemWorld().move(state.entityId, item.x, item.y, item.z);
                    else host.getItemWorld().destroyWorldItem(state.entityId);
                }
            }
            host.publishPhysicalItems();
            if(!peer.isSessionPaused()) fireTouchTriggersForRemoteParticipants();
            for(Map.Entry<Long, Entity> entry : objects.entrySet()) {
                Entity entity = entry.getValue();
                if(entity instanceof Mover) {
                    Mover mover = (Mover)entity;
                    MoverSnapshot state = new MoverSnapshot(entry.getKey(), moverRevision + 1,
                            mover.x, mover.y, mover.z, mover.rotation.x, mover.rotation.y,
                            mover.rotation.z, mover.isMovingForNetwork());
                    if(state.sameTransform(publishedMovers.get(entry.getKey()))) continue;
                    moverRevision++;
                    publishedMovers.put(entry.getKey(), state);
                    host.publishMover(state);
                }
                else if(entity instanceof Door) {
                    DoorSnapshot state = ((Door)entity).snapshot(
                            entry.getKey(), doorRevision + 1);
                    DoorSnapshot previous = publishedDoors.get(entry.getKey());
                    if(previous != null && sameDoor(previous, state)) continue;
                    doorRevision++;
                    publishedDoors.put(entry.getKey(), state);
                    host.publishDoor(state);
                }
                else if(entity instanceof Breakable) {
                    Breakable breakable = (Breakable)entity;
                    // Let native tick run destruction, loot, trigger, and gib once on Host.
                    if(breakable.isActive && breakable.hp <= 0) continue;
                    BreakableSnapshot state = breakable.snapshot(
                            entry.getKey(), breakableRevision + 1);
                    BreakableSnapshot previous = publishedBreakables.get(entry.getKey());
                    if(previous != null && sameBreakable(previous, state)) continue;
                    breakableRevision++;
                    publishedBreakables.put(entry.getKey(), state);
                    host.publishBreakable(state);
                }
            }
            // Walkways, columns, lifts, doors and crates the native Player collides with.
            List<MovementObstacle> obstacles = movementObstacles.changed(game.level);
            if(obstacles != null) host.setWorldObstacles(obstacles);
            host.setActorObstacles(com.interrupt.dungeoneer.multiplayer.movement.NativeMovementObstacles
                    .monsters(game.level));
            if(game.progression != null) host.publishPartyProgression(game.progression);
        }
    }

    private void attach(Game current) {
        game = current;
        if(peer.getLocalCampaignSlot() > 0) {
            localId = new ParticipantId("campaign-slot-" + peer.getLocalCampaignSlot());
        }
        if(game.itemManager != null) game.itemManager.setCampaignPeer(peer);
        if(host != null && game.progression != null) {
            if(host.isResumedCampaign()) game.initializePartyProgression(host.getPartyProgression());
            // Same native tutorial-entry rule also applies at headless/session bridge boundary.
            if(com.interrupt.dungeoneer.GameApplication.OWNED_TUTORIAL_FLOOR.equals(
                    peer.getStatus().getFloorId())) game.progression.sawTutorial = true;
        }
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
        remember(new QuestItem());
        catalogue(game.itemManager);
        catalogue(EntityManager.instance);
        catalogue(game.monsterManager);
        attachWorldObjects();
        objectGeneration = peer.getNativeWorldGeneration();
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
            for(Item starter : starters) freshReturnFallback.add(ItemManager.Copy(starter.getClass(), starter));
            host.getItemWorld().initializePartyKeys(game.player.keys);
            for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
                ParticipantId participant = descriptor.getParticipantId();
                host.getItemWorld().registerParticipant(participant, game.player.inventorySize);
            }
            if(host.isResumedCampaign()) {
                // Rebuilt native floor is only a template source. Durable identities replace it.
                for(Item starter : starters) game.player.removeAuthoritativeItem(starter);
                for(Entity entity : entities()) {
                    if(entity instanceof Item) entity.isActive = false;
                }
                bindRestoredFloorItems();
            }
            else {
                // Register worn starters first so they do not occupy backpack capacity.
                java.util.Collections.sort(starters, (a, b) -> Boolean.compare(
                        game.player.equippedItems.containsValue(b), game.player.equippedItems.containsValue(a)));
                for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
                    ParticipantId participant = descriptor.getParticipantId();
                    if(participant.equals(localId)) {
                        for(Item starter : starters) {
                            registerStarter(starter, participant,
                                    game.player.equippedItems.containsValue(starter));
                        }
                    }
                    else grantStarterKit(participant, starters);
                }
                discoverWorldItems();
            }
        }
        else {
            for(Item item : starters) game.player.removeAuthoritativeItem(item);
            for(Entity entity : entities()) if(entity instanceof Item) entity.isActive = false;
        }
        game.player.setItemAuthorityListener(this);
    }

    private void attachWorldObjects() {
        objectLevel = game.level;
        game.level.nativeTriggerReplica = host == null;
        objects.clear();
        objectIds.clear();
        Map<String, Integer> occurrences = new HashMap<String, Integer>();
        for(Entity entity : entities()) {
            if(!(entity instanceof Door || entity instanceof Breakable || entity instanceof Trigger
                    || entity instanceof BasicTrigger || entity instanceof ButtonModel
                    || entity instanceof Mover)) continue;
            // A restored Host floor keeps the id every client derives from its fresh build, even
            // for objects that moved (open doors, pushed crates) since it was built.
            Long saved = savedWorldObjectId(entity);
            long objectId;
            if(saved != null) objectId = saved;
            else {
                String placement = SharedFloorIdentity.placementKey(entity);
                Integer occurrence = occurrences.get(placement);
                occurrences.put(placement, occurrence == null ? 1 : occurrence + 1);
                objectId = worldObjectId(placement, occurrence == null ? 0 : occurrence);
            }
            if(objects.containsKey(objectId)) {
                diagnose("World object identity hash collision for "
                        + entity.getClass().getSimpleName() + " at " + entity.x + "," + entity.y);
                continue;
            }
            objects.put(objectId, entity);
            objectIds.put(entity, objectId);
            entity.multiplayerIdentity = WORLD_OBJECT_IDENTITY + objectId;
            // A client's Movers only show the Host's: touching or triggering them moves nothing.
            if(host == null && entity instanceof Mover) ((Mover)entity).setNetworkReplica(true);
        }
        if(host != null) {
            restoreWorldObjects();
            game.level.nativeTriggerSoundListener = source -> {
                String sound = source instanceof Trigger ? ((Trigger)source).triggerSound
                        : source instanceof BasicTrigger ? ((BasicTrigger)source).triggerSound
                        : source instanceof ButtonModel ? ((ButtonModel)source).triggerSound : null;
                Long objectId = objectIds.get(source);
                if(objectId != null && sound != null && !sound.isEmpty()) host.publishTriggerSound(objectId);
            };
            game.level.nativeSecretDiscoveryListener = source -> {
                if(game.progression != null) game.progression.partySecretsFound++;
            };
            game.level.nativeTriggerPresentationListener = (trigger, activator, value) -> {
                Long objectId = objectIds.get(trigger);
                if(objectId != null) host.deliverTriggerPresentation(activator, objectId, value);
            };
        }
    }

    /** Drops belong to their native floor; only carried bindings follow Campaign Slots. */
    private void attachNextFloor(Game current) {
        if(objectLevel != null) {
            objectLevel.nativeTriggerSoundListener = null;
            objectLevel.nativeSecretDiscoveryListener = null;
            objectLevel.nativeTriggerPresentationListener = null;
        }
        Map<Long, PhysicalItemState> active = new HashMap<>();
        for(PhysicalItemState state : peer.getPhysicalItems()) active.put(state.entityId, state);
        for(java.util.Iterator<Map.Entry<Long, Item>> iterator = nativeItems.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Long, Item> binding = iterator.next();
            PhysicalItemState state = active.get(binding.getKey());
            if(state == null || state.owner == null || state.consumed) {
                if(current.player.inventory != null && current.player.inventory.contains(binding.getValue(), true)
                        || current.player.equippedItems != null && current.player.equippedItems.containsValue(binding.getValue()))
                    current.player.removeAuthoritativeItem(binding.getValue());
                itemIds.remove(binding.getValue());
                iterator.remove();
            }
        }
        applied.clear(); lastPickupAttempts.clear(); transientItemIds.clear();
        pendingPlacements.clear(); pendingConsumption.clear(); pendingConsumptionAim.clear();
        pendingGoldPickups.clear(); restoredNativeMetadata.clear();
        publishedDoors.clear(); appliedDoors.clear(); publishedMovers.clear(); appliedMovers.clear();
        publishedBreakables.clear(); appliedBreakables.clear();
        game = current;
        game.player.setItemAuthorityListener(this);
        movementObstacles = new com.interrupt.dungeoneer.multiplayer.movement.NativeMovementObstacles();
        for(Entity entity : entities()) if(entity instanceof Item) remember((Item)entity);
        bindRestoredFloorItems();
        attachWorldObjects();
    }

    private void bindRestoredFloorItems() {
        if(host == null || !game.level.restoredCampaignFloor) return;
        for(Entity entity : entities()) {
            if(!(entity instanceof Item)) continue;
            Item item = (Item)entity;
            String identity = item.multiplayerIdentity;
            Long id = null;
            if(identity != null && identity.startsWith("item:")) {
                try { id = Long.valueOf(identity.substring(5)); }
                catch(NumberFormatException invalid) { /* Older checkpoints rebuild from DTOs. */ }
            }
            PhysicalItemState state = id == null ? null : host.getItemWorld().get(id);
            if(state == null || state.owner != null || state.consumed) {
                item.isActive = false;
                continue;
            }
            item.isActive = true;
            nativeItems.put(id, item); itemIds.put(item, id);
            // The native checkpoint is current; retain velocity, fuse and private timers.
            applied.put(id, state.revision);
            restoreNativeMetadata(state, item);
            restoredNativeMetadata.add(id);
        }
    }

    private final List<Item> freshReturnFallback = new ArrayList<Item>();

    /** Native Party travel (#27) calls once destination is stable; revisits never return a character. */
    public int freshReturnLateParticipants(long generation, boolean firstArrival) {
        if(!firstArrival || game == null || !(peer instanceof com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost)) return 0;
        com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost session =
                (com.interrupt.dungeoneer.multiplayer.network.DirectConnectHost)peer;
        int returned = 0;
        for(com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus member : peer.getPartyStatus().getMembers()) {
            if(member.getEntityId() != null || member.getState()
                    != com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState.SPECTATING) continue;
            ParticipantId participant = new ParticipantId("campaign-slot-" + member.getCampaignSlot());
            Player template = startingKitTemplate();
            Player character = template == null ? new Player() : template;
            StartingKit kit = template == null ? new StartingKit() : rollStartingKit(template);
            if(kit.worn.isEmpty() && kit.carried.isEmpty()) for(Item item : freshReturnFallback) {
                Item copy = ItemManager.Copy(item.getClass(), item);
                if(copy instanceof Armor) kit.worn.add(copy); else kit.carried.add(copy);
            }
            com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress fresh =
                    new com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress(participant, 0L,
                            0, 0, 1, character.stats.ATK, character.stats.DEF, character.stats.DEX,
                            character.stats.SPD, character.stats.MAG, character.stats.END, 0,
                            character.maxHp, character.inventorySize, character.hotbarSize);
            if(session.freshReturnLateParticipant(member.getCampaignSlot(), generation, firstArrival, fresh, () -> {
                for(Item item : kit.worn) registerStarter(item, participant, true);
                for(Item item : kit.carried) registerStarter(item, participant, false);
            })) returned++;
        }
        return returned;
    }

    /** Detached native roll; Campaign save decides when it becomes live. */
    public com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter rollFreshCharacter(int slot) {
        Player template = startingKitTemplate();
        if(template == null && com.interrupt.dungeoneer.owned.OwnedGameCopyMount.isMounted())
            throw new IllegalStateException("Owned native starting character template is unavailable.");
        Player character = template == null ? new Player() : template;
        StartingKit kit = template == null ? new StartingKit() : rollStartingKit(template);
        java.util.List<com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter.Starter> entries = new ArrayList<>();
        for(Item item : kit.worn) {
            ItemDescription description = describe(item);
            entries.add(new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter.Starter(
                    description.templateId, description.properties, item.GetEquipLoc()));
        }
        for(Item item : kit.carried) {
            ItemDescription description = describe(item);
            entries.add(new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter.Starter(
                    description.templateId, description.properties, ""));
        }
        return new com.interrupt.dungeoneer.multiplayer.floor.FreshCharacter(
                new com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress(new ParticipantId("campaign-slot-" + slot),
                        0L, 0, 0, 1, character.stats.ATK, character.stats.DEF, character.stats.DEX, character.stats.SPD,
                        character.stats.MAG, character.stats.END, 0, character.maxHp, character.inventorySize, character.hotbarSize), entries);
    }

    /**
     * Host: a Participant's own roll of the native starting kit. Fixed entries match everyone's,
     * while random ones (potion, wand, food) differ per character, as for each new single-player
     * character. Without the player template, falls back to copies of Host's kit.
     */
    public void grantStarterKit(ParticipantId participant, List<Item> hostKit) {
        Player template = startingKitTemplate();
        StartingKit kit = null;
        try {
            if(template != null) kit = rollStartingKit(template);
        }
        catch(RuntimeException unrollable) {
            kit = null;
        }
        if(kit == null || kit.worn.isEmpty() && kit.carried.isEmpty()) {
            for(Item starter : hostKit) {
                registerStarter(ItemManager.Copy(starter.getClass(), starter), participant,
                        game.player.equippedItems.containsValue(starter));
            }
            return;
        }
        // Worn first so they do not occupy backpack capacity.
        for(Item item : kit.worn) registerStarter(item, participant, true);
        for(Item item : kit.carried) registerStarter(item, participant, false);
    }

    /** One character's kit by Player.init rules: armor is worn, everything else is carried. */
    static StartingKit rollStartingKit(Player template) {
        StartingKit kit = new StartingKit();
        for(Entity entry : template.startingInventory) {
            Entity rolled = entry instanceof ItemSpawner ? ((ItemSpawner)entry).getItem() : entry;
            if(!(rolled instanceof Item)) continue;
            if(rolled instanceof Armor) kit.worn.add((Item)rolled);
            else kit.carried.add((Item)rolled);
        }
        return kit;
    }

    static final class StartingKit {
        final List<Item> worn = new ArrayList<Item>();
        final List<Item> carried = new ArrayList<Item>();
    }

    private void registerStarter(Item item, ParticipantId participant, boolean worn) {
        register(item, participant);
        if(worn) host.getItemWorld().registerEquipment(itemIds.get(item), item.GetEquipLoc(), true);
    }

    /** Fresh player template: its starting kit has not been rolled yet. */
    private static Player startingKitTemplate() {
        if(Game.gameData == null || Game.gameData.playerDataFile == null) return null;
        try {
            Player template = com.interrupt.utils.JsonUtil.fromJson(Player.class,
                    Game.findInternalFileInMods("data/" + Game.gameData.playerDataFile));
            return template == null || template.startingInventory == null
                    || template.startingInventory.size == 0 ? null : template;
        }
        catch(RuntimeException unreadable) {
            return null;
        }
    }

    /** Client: a trigger chain this Participant started on Host shows on this screen only. */
    private void presentHostTriggerChains() {
        for(TriggerPresentation presentation : peer.drainTriggerPresentations()) {
            Entity entity = objects.get(presentation.objectId);
            if(presentation.sharedSound && entity != null) {
                String sound = entity instanceof Trigger ? ((Trigger)entity).triggerSound
                        : entity instanceof BasicTrigger ? ((BasicTrigger)entity).triggerSound
                        : entity instanceof ButtonModel ? ((ButtonModel)entity).triggerSound : null;
                com.interrupt.dungeoneer.Audio.playPositionedSound(sound,
                        new com.badlogic.gdx.math.Vector3(entity.x, entity.y, entity.z), 0.8f, 11f);
            }
            else if(entity instanceof Trigger) {
                // Host already ran the chain; closing a message here must not run it again.
                ((Trigger)entity).presentToActivator(presentation.value, false);
            }
            else if(entity instanceof BasicTrigger) ((BasicTrigger)entity).presentToActivator();
            else if(entity instanceof ButtonModel) ((ButtonModel)entity).presentToActivator();
            else diagnose("No shared trigger " + presentation.objectId + " to present");
        }
    }

    /**
     * Native touch triggers only see solid entities, so a client's avatar never set one off (the
     * tutorial spider trap fired only for Host's Player). Host fires them for each remote
     * Participant it touches, with that Participant as activator, as native does for its Player.
     */
    private void fireTouchTriggersForRemoteParticipants() {
        List<RemoteAvatar> avatars = remoteAvatars();
        if(avatars.isEmpty()) return;
        pressMoversForRemoteParticipants(avatars);
        for(Entity entity : objects.values()) {
            if(!(entity instanceof Trigger) || !entity.isActive) continue;
            Trigger trigger = (Trigger)entity;
            if(trigger.triggerType == Trigger.TriggerType.USE
                    || trigger.onlyTriggeredById != null && !trigger.onlyTriggeredById.isEmpty()) {
                continue;
            }
            for(RemoteAvatar avatar : avatars) {
                if(!touches(trigger, avatar)) continue;
                // Client never executes its copy. Host targets screen-only effects to activator.
                trigger.fire(new ParticipantContext(avatar.getDescriptor().getParticipantId(),
                        new ParticipantCharacterState(avatar.x, avatar.y, avatar.z, 0f),
                        LocalPlayerCompatibilityAdapter.fromGame().getPartyProgression(), false),
                        null);
            }
        }
    }

    /**
     * Native Player presses a Mover it stands on or walks into (lifts, pressure plates); remote
     * Participants have no native Player on Host, so Host presses it for them.
     */
    private void pressMoversForRemoteParticipants(List<RemoteAvatar> avatars) {
        for(Entity entity : objects.values()) {
            if(!(entity instanceof Mover) || !entity.isActive) continue;
            Mover mover = (Mover)entity;
            if(mover.moverMode != Mover.MoverStartMode.ON_PLAYER_TOUCH
                    && mover.moverMode != Mover.MoverStartMode.ON_ANY_TOUCH) continue;
            for(RemoteAvatar avatar : avatars) {
                if(!presses(mover, avatar)) continue;
                mover.encroached((com.interrupt.dungeoneer.entities.Player)null);
                break;
            }
        }
    }

    /** Standing on the Mover (native probe 0.02 below the feet) or pressed against its side. */
    private static boolean presses(Entity mover, Entity toucher) {
        float reach = 0.02f;
        return toucher.x + toucher.collision.x + reach > mover.x - mover.collision.x
                && toucher.x - toucher.collision.x - reach < mover.x + mover.collision.x
                && toucher.y + toucher.collision.y + reach > mover.y - mover.collision.y
                && toucher.y - toucher.collision.y - reach < mover.y + mover.collision.y
                && toucher.z - reach < mover.z + mover.collision.z
                && toucher.z + toucher.collision.z > mover.z;
    }

    private List<RemoteAvatar> remoteAvatars() {
        List<RemoteAvatar> avatars = new ArrayList<RemoteAvatar>();
        for(Entity entity : entities()) {
            // A Downed or Life-exhausted body does not step on anything.
            if(entity instanceof RemoteAvatar && entity.isActive
                    && !((RemoteAvatar)entity).isIncapacitated()) avatars.add((RemoteAvatar)entity);
        }
        return avatars;
    }

    /** Level.getEntitiesColliding bounds, with the avatar in place of a solid toucher. */
    private static boolean touches(Trigger trigger, Entity toucher) {
        return trigger.x > toucher.x - toucher.collision.x - trigger.collision.x
                && trigger.x < toucher.x + toucher.collision.x + trigger.collision.x
                && trigger.y > toucher.y - toucher.collision.y - trigger.collision.y
                && trigger.y < toucher.y + toucher.collision.y + trigger.collision.y
                && trigger.z > toucher.z - trigger.collision.z
                && trigger.z < toucher.z + toucher.collision.z;
    }

    private static final String WORLD_OBJECT_IDENTITY = "object:";

    private static Long savedWorldObjectId(Entity entity) {
        String identity = entity.multiplayerIdentity;
        if(identity == null || !identity.startsWith(WORLD_OBJECT_IDENTITY)) return null;
        try { return Long.parseLong(identity.substring(WORLD_OBJECT_IDENTITY.length())); }
        catch(NumberFormatException invalid) { return null; }
    }

    /**
     * Cold resume applies current durable outcome without replaying open/break presentations.
     * Host stays the native authority: the client replica apply would freeze its doors and
     * breakables. A floor restored from its checkpoint already holds that outcome natively.
     */
    private void restoreWorldObjects() {
        boolean rebuilt = !game.level.restoredCampaignFloor;
        for(DoorSnapshot snapshot : host.getDoorSnapshots()) {
            Entity entity = objects.get(snapshot.entityId);
            if(!(entity instanceof Door)) continue;
            if(rebuilt) ((Door)entity).restoreAuthoritativeSnapshot(snapshot);
            publishedDoors.put(snapshot.entityId, snapshot);
            doorRevision = Math.max(doorRevision, snapshot.revision);
        }
        for(BreakableSnapshot snapshot : host.getBreakableSnapshots()) {
            Entity entity = objects.get(snapshot.entityId);
            if(!(entity instanceof Breakable)) continue;
            if(rebuilt) ((Breakable)entity).restoreAuthoritativeSnapshot(snapshot);
            publishedBreakables.put(snapshot.entityId, snapshot);
            breakableRevision = Math.max(breakableRevision, snapshot.revision);
        }
    }

    /**
     * Identity depends only on shared floor content. Peers build that floor identically from the
     * Host seed, so objects stacked on one spot keep the same encounter order on both sides.
     */
    private static long worldObjectId(String placement, int occurrence) {
        String key = placement + "#" + occurrence;
        long hash = 1125899906842597L;
        for(int index = 0; index < key.length(); index++) hash = hash * 31L + key.charAt(index);
        return 1000000L + Math.abs(hash % 1000000000000L);
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
        PhysicalItemState state = host.getItemWorld().spawn(key, owner, item.x, item.y, item.z, properties(item));
        item.multiplayerIdentity = "item:" + state.entityId;
        nativeItems.put(state.entityId, item);
        itemIds.put(item, state.entityId);
        if(item instanceof Key) host.getItemWorld().registerKey(state.entityId);
        if(item instanceof Gold) host.getItemWorld().registerGold(state.entityId);
        host.getItemWorld().registerKind(state.entityId, kindOf(item));
        host.getItemWorld().registerEquipment(state.entityId, item.GetEquipLoc(), false);
        if(item instanceof ItemStack) {
            host.getItemWorld().registerStack(state.entityId, ((ItemStack)item).stackType, key);
        }
        else if(item instanceof Missile) {
            Missile missile = (Missile)item;
            host.getItemWorld().registerStack(state.entityId, missile.stackType,
                    remember(missile.createRecoveredStack()));
        }
    }

    static ItemKind kindOf(Item item) {
        if(item instanceof Weapon) return ItemKind.WEAPON;
        if(item instanceof Armor) return ItemKind.ARMOR;
        if(item instanceof Potion) return ItemKind.POTION;
        if(item instanceof Scroll) return ItemKind.SCROLL;
        if(item instanceof Food) return ItemKind.FOOD;
        return ItemKind.OTHER;
    }

    private static ItemProperties properties(Item item) {
        return new ItemProperties(item.itemCondition.ordinal(), item.itemLevel,
                item.enchantment == null || item.enchantment.name == null ? "" : item.enchantment.name,
                item.prefixEnchantment == null || item.prefixEnchantment.name == null
                        ? "" : item.prefixEnchantment.name,
                item instanceof ItemStack ? ((ItemStack)item).count
                        : item instanceof Wand ? ((Wand)item).charges
                        // Native autoPickup coins add one gold regardless of goldAmount.
                        : item instanceof Gold ? (((Gold)item).autoPickup ? 1 : ((Gold)item).goldAmount) : 1,
                item instanceof Potion ? ((Potion)item).potionType.ordinal() : -1);
    }

    /** Local template identity plus bounded rolls for an Item that is not yet physical. */
    public static final class ItemDescription {
        public final String templateId;
        public final ItemProperties properties;
        ItemDescription(String templateId, ItemProperties properties) {
            this.templateId = templateId; this.properties = properties;
        }
    }

    public ItemDescription describe(Item item) {
        return new ItemDescription(remember(item), properties(item));
    }

    /** Copies of every catalogued template of one class; only these spawn safely on the shared floor. */
    public List<Item> catalogueTemplates(Class<? extends Item> type) {
        List<Item> result = new ArrayList<Item>();
        for(Item template : templates.values()) {
            if(type.isInstance(template)) result.add(ItemManager.Copy(template.getClass(), template));
        }
        return result;
    }

    /** Registers native content constructed outside items.dat so every peer can materialize it. */
    public String rememberTemplate(Item item) {
        return remember(item);
    }

    /** Catalogues raw shared-floor items before native spawn-chance filtering diverges per peer. */
    public void rememberLevelTemplates(Level level) {
        if(level == null) return;
        IdentityHashMap<Entity, Boolean> visited = new IdentityHashMap<Entity, Boolean>();
        rememberLevelTemplates(level.entities, visited);
        rememberLevelTemplates(level.static_entities, visited);
        rememberLevelTemplates(level.non_collidable_entities, visited);
    }

    private void rememberLevelTemplates(Iterable<? extends Entity> entities,
            IdentityHashMap<Entity, Boolean> visited) {
        if(entities == null) return;
        for(Entity entity : entities) rememberEntityTemplate(entity, visited);
    }

    /** Presentation copy of a Host template; null when local content lacks it. */
    public Item materialize(String templateId, ItemProperties properties) {
        Item template = templates.get(templateId);
        if(template == null) return null;
        Item item = ItemManager.Copy(template.getClass(), template);
        return applyProperties(item, properties.condition, properties.quantity, properties)
                ? item : null;
    }

    private boolean applyProperties(Item item, int condition, int quantity,
            ItemProperties properties) {
        if(!properties.suffix.isEmpty() && !modifications.containsKey(properties.suffix)) {
            peer.failNativePresentation("Unknown Host item modification: " + properties.suffix);
            return false;
        }
        if(!properties.prefix.isEmpty() && !modifications.containsKey(properties.prefix)) {
            peer.failNativePresentation("Unknown Host item modification: " + properties.prefix);
            return false;
        }
        item.itemCondition = Item.ItemCondition.values()[condition];
        item.itemLevel = properties.level;
        if(item instanceof Potion && properties.potionType >= 0) {
            ((Potion)item).potionType = Potion.PotionType.values()[properties.potionType];
        }
        item.enchantment = modification(properties.suffix);
        item.prefixEnchantment = modification(properties.prefix);
        if(item instanceof ItemStack) ((ItemStack)item).count = quantity;
        if(item instanceof Wand) ((Wand)item).charges = quantity;
        if(item instanceof Gold) ((Gold)item).goldAmount = quantity;
        return true;
    }

    /** Host delivers an accepted purchase to the buyer's backpack, or beside them when it is full. */
    public void deliverPurchasedItem(Item item, ParticipantContext buyer) {
        if(host == null || item == null || buyer == null) return;
        ParticipantId owner = buyer.getParticipantId();
        if(host.getItemWorld().hasBackpackSpace(owner)) {
            item.isActive = false;
            register(item, owner);
            return;
        }
        item.isActive = true;
        item.isDynamic = true;
        item.ignorePlayerCollision = true;
        item.x = buyer.getCharacter().getX();
        item.y = buyer.getCharacter().getY();
        item.z = buyer.getCharacter().getZ() + 0.4f;
        game.level.SpawnEntity(item);
        register(item, null);
    }

    /** Shares the per-Participant reliable request sequence used by Host duplicate rejection. */
    public long submitEconomyAction(ItemAction action, long entityId, int quantity) {
        if(nextRequest == Long.MAX_VALUE || peer.isSessionPaused()) return 0L;
        long request = nextRequest++;
        peer.submitItemAction(request, action, entityId, 0, quantity);
        return request;
    }

    public boolean isAttached() { return game != null; }

    public ParticipantId getLocalParticipantId() { return localId; }

    public ParticipantContext participantContext(ParticipantId participant) {
        return participant(participant);
    }

    public java.util.Collection<Entity> worldObjects() {
        return java.util.Collections.unmodifiableCollection(objects.values());
    }

    private void applyStates() {
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            Long revision = applied.get(state.entityId);
            if(revision != null && revision >= state.revision
                    && (host != null || state.owner != null)) continue;
            Item item = nativeItems.get(state.entityId);
            // Host converts a picked-up loose missile into its native inventory bundle.
            if(item instanceof Missile && templates.get(state.templateId) instanceof ItemStack) {
                item.isActive = false;
                if(game.player.ownsPhysicalItem(item)) game.player.removeAuthoritativeItem(item);
                itemIds.remove(item);
                item = null;
            }
            if(item == null) {
                Item template = templates.get(state.templateId);
                if(template == null) {
                    peer.failNativePresentation(
                            "Host item template is unavailable in local content: " + state.templateId);
                    return;
                }
                item = ItemManager.Copy(template.getClass(), template);
                nativeItems.put(state.entityId, item);
                itemIds.put(item, state.entityId);
                item.multiplayerIdentity = "item:" + state.entityId;
            }
            if(host != null && host.isResumedCampaign()
                    && restoredNativeMetadata.add(state.entityId)) {
                restoreNativeMetadata(state, item);
            }
            if(state.owner != null) item.multiplayerDamageSource = state.owner.getValue();
            boolean ownedLocally = localId.equals(state.owner);
            boolean alreadyOwnedLocally = ownedLocally && game.player.ownsPhysicalItem(item);
            int condition = alreadyOwnedLocally ? Math.min(item.itemCondition.ordinal(),
                    state.properties.condition) : state.properties.condition;
            int quantity = state.properties.quantity;
            // Ammo spending and merging are Host-owned; accept both decreases and pickup increases.
            if(alreadyOwnedLocally && item instanceof Wand) quantity = Math.min(quantity, ((Wand)item).charges);
            if(!applyProperties(item, condition, quantity, state.properties)) return;
            if(!ownedLocally && game.player.ownsPhysicalItem(item)) {
                game.player.removeAuthoritativeItem(item);
            }
            if(state.consumed) {
                // A fired standalone Missile keeps its physical identity while dynamic
                // authority owns its in-flight lifetime. Inventory tombstone still removes it.
                if(!item.nativePresentationReplica) item.isActive = false;
                if(pendingConsumption.remove(state.entityId) != null) {
                    if(item instanceof Potion) {
                        Potion potion = (Potion)item;
                        com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge knowledge = peer.getPersonalKnowledge();
                        boolean learned = knowledge != null
                                && (knowledge.potionMask & (1 << potion.potionType.ordinal())) != 0
                                && !game.player.discoveredPotions.contains(potion.potionType, true);
                        potion.presentDrink(game.player, learned);
                    }
                    else if(item instanceof Food) ((Food)item).presentEat(game.player);
                    else if(item instanceof Scroll) ((Scroll)item).presentRead(game.player, host == null);
                    pendingConsumptionAim.remove(state.entityId);
                }
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

    /** Rebuilds Host-only item classifications omitted from detached physical snapshots. */
    private void restoreNativeMetadata(PhysicalItemState state, Item item) {
        if(item instanceof Key) host.getItemWorld().registerKey(state.entityId);
        if(item instanceof Gold) host.getItemWorld().registerGold(state.entityId);
        host.getItemWorld().registerKind(state.entityId, kindOf(item));
        host.getItemWorld().registerEquipment(state.entityId, item.GetEquipLoc(), false);
        if(item instanceof ItemStack) {
            host.getItemWorld().registerStack(state.entityId,
                    ((ItemStack)item).stackType, state.templateId);
        }
        else if(item instanceof Missile) {
            Missile missile = (Missile)item;
            host.getItemWorld().registerStack(state.entityId, missile.stackType,
                    remember(missile.createRecoveredStack()));
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

    /** Held item stays in hand through a Life loss, so Host must know which owned item that is. */
    private void reportWield() {
        if(peer.isSessionPaused() || nextRequest == Long.MAX_VALUE) return;
        Item held = game.player.GetHeldItem();
        Long id = held == null ? null : itemIds.get(held);
        long wielded = 0L, previousStillOwned = 0L;
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            if(!localId.equals(state.owner) || state.consumed) continue;
            if(id != null && state.entityId == id) wielded = id;
            if(state.entityId == reportedWield) previousStillOwned = reportedWield;
        }
        if(wielded == reportedWield) return;
        if(wielded != 0L) {
            wieldRequest = nextRequest;
            peer.submitItemAction(nextRequest++, ItemAction.WIELD, wielded, 0, 1);
        }
        // Host forgets a wield on its own once the item leaves the owner; only an owned release is sent.
        else if(previousStillOwned != 0L) {
            wieldRequest = nextRequest;
            peer.submitItemAction(nextRequest++, ItemAction.WIELD, previousStillOwned, 0, 0);
        }
        reportedWield = wielded;
    }

    private long reportedWield;
    private long wieldRequest;

    /** True once applyStates has presented this exact revision locally. */
    private boolean isPresented(PhysicalItemState state) {
        Long revision = applied.get(state.entityId);
        return revision != null && revision >= state.revision;
    }

    /** Owner may spend existing inventory; this path cannot mint, improve, or transfer an item. */
    private void reportSpending() {
        if(peer.isSessionPaused()) return;
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            // An ownership change that arrived after prepare is not in the inventory yet;
            // judging it now would report a phantom consumption of the item just picked up.
            if(!localId.equals(state.owner) || !isPresented(state)) continue;
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
        if(id == null) {
            diagnose("No shared identity for picked up " + item.getClass().getSimpleName()
                    + " " + item.name);
        }
        if(id != null) {
            Long previous = lastPickupAttempts.get(id);
            // Auto-pickups can touch each render frame while reliable acknowledgement is in flight.
            if(previous == null || frame - previous >= 30L) {
                lastPickupAttempts.put(id, frame);
                long request = submit(ItemAction.PICKUP, id);
                if(request > 0L && item instanceof Gold) {
                    if(pendingGoldPickups.size() >= 64) pendingGoldPickups.clear();
                    pendingGoldPickups.put(request, id);
                }
            }
        }
        return true;
    }

    @Override
    public boolean drop(Item item) {
        reportEquipment();
        reportSpending();
        Long id = itemIds.get(item);
        // A thrown bomb was lit while charging; Host's bomb must light before it lands.
        if(id != null && item instanceof FusedBomb && ((FusedBomb)item).isLit) submit(ItemAction.LIGHT, id);
        if(id != null) submit(ItemAction.DROP, id);
        return true;
    }

    @Override
    public boolean use(Entity entity, float x, float y) {
        if(entity instanceof Item) return pickup((Item)entity);
        Long id = objectIds.get(entity);
        if(id == null) {
            diagnose("No shared identity for used " + entity.getClass().getSimpleName()
                    + "; native local use continues");
            return false;
        }
        submit(ItemAction.USE_OBJECT, id);
        return true;
    }

    /** Bounded troubleshooting for interactions that never reach or pass Host authority. */
    private void diagnose(String message) {
        if(com.badlogic.gdx.Gdx.app != null) com.badlogic.gdx.Gdx.app.log("DelverMultiplayer", message);
    }

    private long submit(ItemAction action, long id) {
        if(nextRequest == Long.MAX_VALUE || peer.isSessionPaused()) {
            diagnose("Dropped " + action + " for entity " + id
                    + " (paused=" + peer.isSessionPaused() + ")");
            return 0L;
        }
        long request = nextRequest++;
        peer.submitItemAction(request, action, id);
        return request;
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
        public boolean consumeItem(ItemRequest request, ParticipantContext participant) {
            Item item = nativeItems.get(request.entityId);
            boolean handled = item instanceof Potion || item instanceof Food || item instanceof Scroll;
            Vector3 direction = request.hasAim
                    ? new Vector3(request.aimX, request.aimY, request.aimZ) : null;
            return !handled || consumableConsumer != null
                    && consumableConsumer.consume(participant.getParticipantId(), item, direction);
        }
        public boolean canReach(ParticipantContext participant, float x, float y, float z) {
            return reachable(participant, x, y, null);
        }
        public boolean acquireGold(ParticipantContext participant, int amount) {
            return economyBoundary != null && economyBoundary.acquireGold(participant, amount);
        }
        public boolean economyAction(ItemRequest request, ParticipantContext participant) {
            return economyBoundary != null && economyBoundary.economyAction(request, participant);
        }
        public boolean useObject(long entityId, ParticipantContext participant) {
            Entity entity = objects.get(entityId);
            if(entity == null || !entity.isActive) return false;
            float dx = participant.getCharacter().getX() - entity.x;
            float dy = participant.getCharacter().getY() - entity.y;
            if(dx * dx + dy * dy > 1.21f
                    || Math.abs(participant.getCharacter().getZ() - entity.z) > 1f
                    || !reachable(participant, entity.x, entity.y, entity)) return false;
            if(entity instanceof Door) ((Door)entity).use(participant, () -> host.getItemWorld().spendPartyKey(),
                    feedback -> host.publishDoorFeedback(participant.getParticipantId(), feedback));
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

    private void catalogue(EntityManager manager) {
        if(manager == null) return;
        IdentityHashMap<Entity, Boolean> visited = new IdentityHashMap<Entity, Boolean>();
        if(manager.entities != null) for(OrderedMap<String, Entity> category : manager.entities.values()) {
            if(category != null) for(Entity entity : category.values()) rememberEntityTemplate(entity, visited);
        }
        if(manager.surprises != null) {
            for(Entity entity : manager.surprises) rememberEntityTemplate(entity, visited);
        }
    }

    private void catalogue(MonsterManager manager) {
        if(manager == null || manager.monsters == null) return;
        IdentityHashMap<Entity, Boolean> visited = new IdentityHashMap<Entity, Boolean>();
        for(Array<Monster> monsters : manager.monsters.values()) {
            if(monsters == null) continue;
            for(Monster monster : monsters) rememberEntityTemplate(monster, visited);
        }
    }

    private void rememberEntityTemplate(Entity entity, IdentityHashMap<Entity, Boolean> visited) {
        if(entity == null || visited.put(entity, Boolean.TRUE) != null) return;
        if(entity instanceof Item) remember((Item)entity);
        if(entity instanceof Monster) {
            Monster monster = (Monster)entity;
            remember(monster.loot);
            rememberEntityTemplate(monster.projectile, visited);
        }
        if(entity instanceof Group) {
            for(Entity child : ((Group)entity).entities) rememberEntityTemplate(child, visited);
        }
        if(entity.getAttached() != null) {
            for(Entity child : entity.getAttached()) rememberEntityTemplate(child, visited);
        }
    }

    private void remember(Array<? extends Item> items) {
        if(items != null) for(Item item : items) remember(item);
    }

    private String remember(Item item) {
        String key = templateKey(item);
        boolean newTemplate = !templates.containsKey(key);
        if(newTemplate) templates.put(key, ItemManager.Copy(item.getClass(), item));
        if(item.enchantment != null) modifications.put(item.enchantment.name, item.enchantment);
        if(item.prefixEnchantment != null) modifications.put(item.prefixEnchantment.name, item.prefixEnchantment);
        // Monster hits create a bundle named after the missile, unlike the original ammo stack.
        // Register once: recovered stacks refer back to this missile through their nested item.
        if(newTemplate && item instanceof Missile) remember(((Missile)item).createRecoveredStack());
        // Stacked native ammo becomes a standalone physical item once it is fired, dropped or bought.
        if(item instanceof ItemStack) {
            Item stacked = ((ItemStack)item).item;
            if(stacked != null && stacked != item) remember(stacked);
        }
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

    public static String templateKey(Item item) {
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

    private static boolean sameBreakable(BreakableSnapshot a, BreakableSnapshot b) {
        return a.hp == b.hp && a.active == b.active && a.solid == b.solid
                && a.x == b.x && a.y == b.y && a.z == b.z
                && a.velocityX == b.velocityX && a.velocityY == b.velocityY
                && a.velocityZ == b.velocityZ && a.rotationX == b.rotationX
                && a.rotationY == b.rotationY && a.rotationZ == b.rotationZ;
    }

    @Override public void synchronizeInventory(ParticipantId participant, Player player) {
        player.inventory.clear();
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            if(state.consumed || !participant.equals(state.owner) || !state.equipmentSlot.isEmpty()) continue;
            Item item = nativeItems.get(state.entityId);
            if(item != null) player.inventory.add(item);
        }
    }

    @Override public void synchronizeEquipment(ParticipantId participant, Player player) {
        player.equippedItems.clear();
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            if(state.consumed || !participant.equals(state.owner) || state.equipmentSlot.isEmpty()) continue;
            Item item = nativeItems.get(state.entityId);
            if(item != null) player.equippedItems.put(state.equipmentSlot, item);
        }
    }

    @Override
    public long identity(Weapon weapon) {
        Long id = itemIds.get(weapon);
        return id == null ? 0L : id;
    }

    @Override
    public long physicalIdentity(Entity entity) {
        if(!(entity instanceof Item)) return 0L;
        Long id = itemIds.get((Item)entity);
        if(id == null) id = transientItemIds.get(entity);
        return id == null ? 0L : id;
    }

    @Override
    public Entity physicalEntity(long entityId) {
        return nativeItems.get(entityId);
    }

    @Override
    public Entity wieldedItem(ParticipantId participant) {
        if(host == null || participant == null) return null;
        long wielded = host.getItemWorld().getWielded(participant);
        return wielded == 0L ? null : nativeItems.get(wielded);
    }

    @Override
    public long worldObjectIdentity(Entity entity) {
        Long id = objectIds.get(entity);
        return id == null ? 0L : id;
    }

    @Override
    public Entity worldObject(long entityId) {
        return objects.get(entityId);
    }

    @Override
    public Missile takeOwnedMissile(ParticipantId participant) {
        if(participant == null) return null;
        for(PhysicalItemState state : peer.getPhysicalItems()) {
            if(state.consumed || !participant.equals(state.owner)
                    || !state.equipmentSlot.isEmpty() || state.properties.quantity < 1) continue;
            Item item = nativeItems.get(state.entityId);
            Missile missile = null;
            boolean standalone = item instanceof Missile;
            if(standalone) missile = (Missile)ItemManager.Copy(Missile.class, (Missile)item);
            else if(item instanceof ItemStack && ((ItemStack)item).item instanceof Missile) {
                missile = (Missile)ItemManager.Copy(Missile.class, ((ItemStack)item).item);
            }
            if(missile == null) continue;
            if(host != null && !host.getItemWorld().spendNativeUnit(participant, state.entityId)) {
                continue;
            }
            missile.multiplayerDamageSource = participant.getValue();
            if(host != null && standalone) transientItemIds.put(missile, state.entityId);
            return missile;
        }
        return null;
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
