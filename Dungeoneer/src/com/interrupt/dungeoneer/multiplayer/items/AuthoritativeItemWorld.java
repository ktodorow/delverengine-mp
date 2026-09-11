package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serial Host ownership boundary. Engine objects never enter snapshots or commands. */
public final class AuthoritativeItemWorld {
    public static final int MAX_ITEMS = 4096;
    public static final int MAX_INVENTORY_ITEMS = 64;
    public static final float INTERACTION_REACH = 1.1f;

    public enum Outcome {
        ACCEPTED, DUPLICATE, INACTIVE_PARTICIPANT, UNKNOWN_ENTITY,
        ALREADY_OWNED, NOT_OWNER, OUT_OF_REACH, INVENTORY_FULL, OBJECT_REJECTED, INVALID_SPEND
    }

    /** Called only on Host simulation thread with authoritative Participant state. */
    public interface InteractionBoundary {
        boolean canAct(ParticipantContext participant);
        boolean canReach(ParticipantContext participant, float x, float y, float z);
        boolean useObject(long entityId, ParticipantContext participant);
        default boolean consumeItem(long entityId, ParticipantContext participant) { return true; }
        default boolean consumeItem(ItemRequest request, ParticipantContext participant) {
            return consumeItem(request.entityId, participant);
        }
    }

    private final Map<Long, PhysicalItemState> items =
            new LinkedHashMap<Long, PhysicalItemState>();
    private final Map<ParticipantId, Long> lastRequests =
            new LinkedHashMap<ParticipantId, Long>();
    private final Map<ParticipantId, Integer> capacities =
            new LinkedHashMap<ParticipantId, Integer>();
    private final Map<Long, String> equipmentSlots = new LinkedHashMap<Long, String>();
    private final java.util.Set<Long> keys = new java.util.HashSet<Long>();
    private int partyKeys;
    private long keyRevision;
    private long nextEntityId = 1L;
    private long revision;

    public synchronized void registerParticipant(ParticipantId participantId, int capacity) {
        if(participantId == null || capacity < 1 || capacity > MAX_INVENTORY_ITEMS) {
            throw new IllegalArgumentException("Invalid Participant inventory capacity.");
        }
        if(!capacities.containsKey(participantId) && capacities.size() >= 4) {
            throw new IllegalStateException("Physical item world supports four Participants.");
        }
        if(backpackCount(participantId) > capacity) {
            throw new IllegalArgumentException("Capacity cannot discard owned items.");
        }
        capacities.put(participantId, capacity);
    }

    public synchronized PhysicalItemState spawn(String templateId, ParticipantId owner,
            float x, float y, float z) {
        return spawn(templateId, owner, x, y, z, ItemProperties.DEFAULT);
    }

    public synchronized PhysicalItemState spawn(String templateId, ParticipantId owner,
            float x, float y, float z, ItemProperties properties) {
        if(items.size() >= MAX_ITEMS) throw new IllegalStateException("Physical item limit reached.");
        if(owner != null && (!capacities.containsKey(owner)
                || backpackCount(owner) >= capacities.get(owner))) {
            throw new IllegalArgumentException("Initial item owner has no inventory space.");
        }
        PhysicalItemState item = new PhysicalItemState(nextEntityId, revision + 1L,
                templateId, owner, x, y, z, properties);
        nextEntityId++;
        revision++;
        items.put(item.entityId, item);
        return item;
    }

    public synchronized Outcome apply(ItemRequest request, ParticipantContext participant,
            InteractionBoundary boundary) {
        if(request == null || participant == null || boundary == null
                || !request.getParticipantId().equals(participant.getParticipantId())) {
            throw new IllegalArgumentException("Request must retain authenticated Participant context.");
        }
        ParticipantId actor = request.getParticipantId();
        if(!capacities.containsKey(actor)) return Outcome.INACTIVE_PARTICIPANT;
        Long previous = lastRequests.get(actor);
        if(previous != null && request.requestId <= previous) return Outcome.DUPLICATE;
        // Rejected attempts also consume their identity. Replaying after a drop cannot win.
        lastRequests.put(actor, request.requestId);
        if(!boundary.canAct(participant)) return Outcome.INACTIVE_PARTICIPANT;
        if(request.action == ItemAction.USE_OBJECT) {
            return boundary.useObject(request.entityId, participant)
                    ? Outcome.ACCEPTED : Outcome.OBJECT_REJECTED;
        }
        PhysicalItemState item = items.get(request.entityId);
        if(item == null || item.consumed) return Outcome.UNKNOWN_ENTITY;
        if(request.action == ItemAction.PICKUP) {
            if(item.owner != null) return Outcome.ALREADY_OWNED;
            float dx = participant.getCharacter().getX() - item.x;
            float dy = participant.getCharacter().getY() - item.y;
            float dz = participant.getCharacter().getZ() - item.z;
            if(dx * dx + dy * dy > INTERACTION_REACH * INTERACTION_REACH
                    || Math.abs(dz) > 1f
                    || !boundary.canReach(participant, item.x, item.y, item.z)) {
                return Outcome.OUT_OF_REACH;
            }
            if(keys.contains(item.entityId)) {
                if(partyKeys >= 1000000) return Outcome.INVENTORY_FULL;
                items.put(item.entityId, new PhysicalItemState(item.entityId, ++revision,
                        item.templateId, null, item.x, item.y, item.z, item.properties, true));
                partyKeys++; keyRevision++;
                return Outcome.ACCEPTED;
            }
            if(backpackCount(actor) >= capacities.get(actor)) return Outcome.INVENTORY_FULL;
            replace(item, actor, item.x, item.y, item.z);
        }
        else if(request.action == ItemAction.EQUIP || request.action == ItemAction.STOW) {
            if(!actor.equals(item.owner)) return Outcome.NOT_OWNER;
            String slot = request.action == ItemAction.EQUIP ? equipmentSlots.get(item.entityId) : "";
            if(slot == null || (request.action == ItemAction.EQUIP && slot.isEmpty())) return Outcome.OBJECT_REJECTED;
            if(slot.equals(item.equipmentSlot)) return Outcome.ACCEPTED;
            if(slot.isEmpty() && backpackCount(actor) >= capacities.get(actor)) return Outcome.INVENTORY_FULL;
            if(!slot.isEmpty()) for(PhysicalItemState other : inventory(actor)) {
                if(slot.equals(other.equipmentSlot)) setEquipment(other, "");
            }
            setEquipment(item, slot);
        }
        else if(request.action == ItemAction.SPEND || request.action == ItemAction.CONSUME) {
            if(!actor.equals(item.owner)) return Outcome.NOT_OWNER;
            if(request.condition > item.properties.condition || request.quantity > item.properties.quantity) {
                return Outcome.INVALID_SPEND;
            }
            boolean consumed = request.action == ItemAction.CONSUME;
            if(consumed && !boundary.consumeItem(request, participant)) return Outcome.OBJECT_REJECTED;
            ItemProperties properties = new ItemProperties(request.condition, item.properties.level,
                    item.properties.suffix, item.properties.prefix, request.quantity, item.properties.potionType);
            items.put(item.entityId, new PhysicalItemState(item.entityId, ++revision,
                    item.templateId, consumed ? null : actor, item.x, item.y, item.z,
                    properties, consumed, consumed ? "" : item.equipmentSlot));
        }
        else {
            if(!actor.equals(item.owner)) return Outcome.NOT_OWNER;
            replace(item, null, participant.getCharacter().getX(),
                    participant.getCharacter().getY(), participant.getCharacter().getZ());
        }
        return Outcome.ACCEPTED;
    }

    public synchronized void registerEquipment(long id, String slot, boolean equipped) {
        if(slot == null || slot.isEmpty()) return;
        if(slot.length() > 32 || !items.containsKey(id)) throw new IllegalArgumentException("Invalid equipment.");
        equipmentSlots.put(id, slot);
        if(equipped) setEquipment(items.get(id), slot);
    }

    private void setEquipment(PhysicalItemState item, String slot) {
        items.put(item.entityId, new PhysicalItemState(item.entityId, ++revision, item.templateId,
                item.owner, item.x, item.y, item.z, item.properties, false, slot));
    }

    private int backpackCount(ParticipantId owner) {
        int count = 0;
        for(PhysicalItemState item : items.values()) {
            if(owner.equals(item.owner) && item.equipmentSlot.isEmpty()) count++;
        }
        return count;
    }

    /** Native Host catalogue determines key/equipment type, never a client claim. */
    public synchronized void registerKey(long id) {
        if(!items.containsKey(id)) throw new IllegalArgumentException("Unknown key.");
        keys.add(id);
    }

    public synchronized void initializePartyKeys(int count) {
        if(count < 0 || count > 1000000) throw new IllegalArgumentException("Invalid Party Keys.");
        if(keyRevision != 0) return;
        partyKeys = count; keyRevision = 1;
    }

    public synchronized int getPartyKeys() { return partyKeys; }
    public synchronized long getKeyRevision() { return keyRevision; }
    public synchronized boolean spendPartyKey() {
        if(partyKeys == 0) return false;
        partyKeys--; keyRevision++;
        return true;
    }

    /** Host-native attacks spend one owned ammunition unit after combat-request deduplication. */
    public synchronized boolean spendNativeUnit(ParticipantId participant, long entityId) {
        PhysicalItemState item = items.get(entityId);
        if(participant == null || item == null || item.consumed
                || !participant.equals(item.owner) || item.properties.quantity < 1) return false;
        int quantity = item.properties.quantity - 1;
        boolean consumed = quantity == 0;
        ItemProperties properties = new ItemProperties(item.properties.condition,
                item.properties.level, item.properties.suffix, item.properties.prefix,
                quantity, item.properties.potionType);
        items.put(entityId, new PhysicalItemState(item.entityId, ++revision,
                item.templateId, consumed ? null : participant, item.x, item.y, item.z,
                properties, consumed, consumed ? "" : item.equipmentSlot));
        return true;
    }

    /** Native Host destruction (explosion, shattering) leaves a durable item tombstone. */
    public synchronized void destroyWorldItem(long entityId) {
        PhysicalItemState item = items.get(entityId);
        if(item == null || item.consumed || item.owner != null) return;
        items.put(entityId, new PhysicalItemState(item.entityId, ++revision, item.templateId,
                null, item.x, item.y, item.z, item.properties, true));
    }

    /** Native Host physics can move world items, but cannot transfer ownership. */
    public synchronized void move(long entityId, float x, float y, float z) {
        PhysicalItemState item = items.get(entityId);
        if(item == null || item.consumed || item.owner != null) return;
        if(item.x == x && item.y == y && item.z == z) return;
        replace(item, null, x, y, z);
    }

    private void replace(PhysicalItemState item, ParticipantId owner, float x, float y, float z) {
        PhysicalItemState replacement = new PhysicalItemState(item.entityId, revision + 1L,
                item.templateId, owner, x, y, z, item.properties, false,
                owner != null && owner.equals(item.owner) ? item.equipmentSlot : "");
        items.put(item.entityId, replacement);
        revision++;
    }

    public synchronized long nextRequestId(ParticipantId participant) {
        Long last = lastRequests.get(participant);
        return last == null ? 1L : last + 1L;
    }

    public synchronized PhysicalItemState get(long entityId) { return items.get(entityId); }

    public synchronized List<PhysicalItemState> snapshot() {
        return Collections.unmodifiableList(new ArrayList<PhysicalItemState>(items.values()));
    }

    public synchronized List<PhysicalItemState> inventory(ParticipantId owner) {
        if(owner == null) throw new IllegalArgumentException("Inventory requires an owner.");
        List<PhysicalItemState> inventory = new ArrayList<PhysicalItemState>();
        for(PhysicalItemState item : items.values()) if(owner.equals(item.owner)) inventory.add(item);
        return Collections.unmodifiableList(inventory);
    }
}
