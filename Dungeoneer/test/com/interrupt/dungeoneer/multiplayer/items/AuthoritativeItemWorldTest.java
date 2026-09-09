package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld.Outcome.*;

public class AuthoritativeItemWorldTest {
    private final AuthoritativeItemWorld world = new AuthoritativeItemWorld();
    private final SharedPartyProgression progression = new SharedPartyProgression();
    private final ParticipantContext alpha = participant("alpha", 2);
    private final ParticipantContext beta = participant("beta", 2);
    private boolean active = true;
    private boolean visible = true;
    private ParticipantContext activatedBy;
    private final AuthoritativeItemWorld.InteractionBoundary boundary =
            new AuthoritativeItemWorld.InteractionBoundary() {
                public boolean canAct(ParticipantContext participant) { return active; }
                public boolean canReach(ParticipantContext participant, float x, float y, float z) {
                    return visible;
                }
                public boolean useObject(long id, ParticipantContext participant) {
                    if(id != 99L) return false;
                    activatedBy = participant;
                    return true;
                }
            };

    @Test
    public void spendingAndConsumingPreserveHostPotionType() {
        PhysicalItemState potion = world.spawn("potion", alpha.getParticipantId(), 1, 1, 0,
                new ItemProperties(2, 1, "", "", 1, 3));
        assertEquals(ACCEPTED, world.apply(new ItemRequest(alpha.getParticipantId(), 1,
                ItemAction.SPEND, potion.entityId, 1, 1), alpha, boundary));
        assertEquals(3, world.get(potion.entityId).properties.potionType);
        assertEquals(ACCEPTED, request(alpha, 2, ItemAction.DROP, potion.entityId));
        assertEquals(ACCEPTED, request(beta, 1, ItemAction.PICKUP, potion.entityId));
        assertEquals(3, world.get(potion.entityId).properties.potionType);
        assertEquals(ACCEPTED, world.apply(new ItemRequest(beta.getParticipantId(), 2,
                ItemAction.CONSUME, potion.entityId, 1, 0), beta, boundary));
        assertEquals(3, world.get(potion.entityId).properties.potionType);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownPotionType() {
        new ItemProperties(2, 1, "", "", 1, 7);
    }

    @Test
    public void equipmentFreesBackpackSpaceAndSwapRetainsBothOwners() {
        world.registerParticipant(alpha.getParticipantId(), 1);
        PhysicalItemState first = world.spawn("armor", alpha.getParticipantId(), 1, 1, 0);
        world.registerEquipment(first.entityId, "ARMOR", false);
        assertEquals(ACCEPTED, request(alpha, 1, ItemAction.EQUIP, first.entityId));
        PhysicalItemState second = world.spawn("armor", null, 1, 1, 0);
        world.registerEquipment(second.entityId, "ARMOR", false);
        assertEquals(ACCEPTED, request(alpha, 2, ItemAction.PICKUP, second.entityId));
        assertEquals(INVENTORY_FULL, request(alpha, 3, ItemAction.STOW, first.entityId));
        assertEquals(ACCEPTED, request(alpha, 4, ItemAction.EQUIP, second.entityId));
        assertEquals("", world.get(first.entityId).equipmentSlot);
        assertEquals("ARMOR", world.get(second.entityId).equipmentSlot);
        assertEquals(2, world.inventory(alpha.getParticipantId()).size());
        assertEquals(ACCEPTED, request(alpha, 5, ItemAction.DROP, second.entityId));
        assertEquals(ACCEPTED, request(beta, 1, ItemAction.PICKUP, second.entityId));
        assertEquals("", world.get(second.entityId).equipmentSlot);
        assertEquals(beta.getParticipantId(), world.get(second.entityId).owner);
    }

    @Test
    public void sharedKeyPickupWorksWithFullBackpackAndCannotCreditTwice() {
        world.registerParticipant(alpha.getParticipantId(), 1);
        world.spawn("starter", alpha.getParticipantId(), 1, 1, 0);
        PhysicalItemState key = world.spawn("key", null, 1, 1, 0);
        world.registerKey(key.entityId);
        assertEquals(ACCEPTED, request(alpha, 1, ItemAction.PICKUP, key.entityId));
        assertTrue(world.get(key.entityId).consumed);
        assertEquals(1, world.getPartyKeys());
        assertEquals(DUPLICATE, request(alpha, 1, ItemAction.PICKUP, key.entityId));
        assertEquals(UNKNOWN_ENTITY, request(beta, 1, ItemAction.PICKUP, key.entityId));
        assertEquals(1, world.getPartyKeys());
        assertTrue(world.spendPartyKey());
        assertFalse(world.spendPartyKey());
        assertEquals(0, world.getPartyKeys());
        assertEquals(1, world.inventory(alpha.getParticipantId()).size());
    }

    @Test
    public void competingPickupHasOneWinnerAndSharingPreservesIdentity() {
        PhysicalItemState item = world.spawn("test-sword", null, 1, 1, 0);
        assertEquals(ACCEPTED, request(alpha, 1, ItemAction.PICKUP, item.entityId));
        assertEquals(ALREADY_OWNED, request(beta, 1, ItemAction.PICKUP, item.entityId));
        assertEquals(1, world.inventory(alpha.getParticipantId()).size());
        assertTrue(world.inventory(beta.getParticipantId()).isEmpty());
        assertEquals(NOT_OWNER, request(beta, 2, ItemAction.DROP, item.entityId));
        assertEquals(ACCEPTED, request(alpha, 2, ItemAction.DROP, item.entityId));
        assertEquals(DUPLICATE, request(beta, 1, ItemAction.PICKUP, item.entityId));
        assertNull(world.get(item.entityId).owner);
        assertEquals(ACCEPTED, request(beta, 3, ItemAction.PICKUP, item.entityId));
        assertEquals(beta.getParticipantId(), world.get(item.entityId).owner);
        assertTrue(world.inventory(alpha.getParticipantId()).isEmpty());
        assertEquals(1, world.snapshot().size());
        assertEquals(item.entityId, world.inventory(beta.getParticipantId()).get(0).entityId);
    }

    @Test
    public void replayCannotDropAnItemAfterItWasRecollected() {
        PhysicalItemState item = world.spawn("test-potion", alpha.getParticipantId(), 0, 0, 0);
        assertEquals(ACCEPTED, request(alpha, 1, ItemAction.DROP, item.entityId));
        assertEquals(ACCEPTED, request(alpha, 2, ItemAction.PICKUP, item.entityId));
        long revision = world.get(item.entityId).revision;
        assertEquals(DUPLICATE, request(alpha, 1, ItemAction.DROP, item.entityId));
        assertEquals(revision, world.get(item.entityId).revision);
        assertEquals(3L, world.nextRequestId(alpha.getParticipantId()));
    }

    @Test
    public void fullInventoryRejectsWithoutRemovingWorldItem() {
        world.registerParticipant(alpha.getParticipantId(), 1);
        world.spawn("starter", alpha.getParticipantId(), 0, 0, 0);
        PhysicalItemState item = world.spawn("test-potion", null, 1, 1, 0);
        assertEquals(INVENTORY_FULL, request(alpha, 1, ItemAction.PICKUP, item.entityId));
        assertSame(item, world.get(item.entityId));
        assertEquals(ACCEPTED, request(beta, 1, ItemAction.PICKUP, item.entityId));
    }

    @Test
    public void hostPositionVisibilityAndActivityControlPickup() {
        PhysicalItemState far = world.spawn("far", null, 10, 10, 0);
        PhysicalItemState near = world.spawn("near", null, 1, 1, 0);
        assertEquals(OUT_OF_REACH, request(alpha, 1, ItemAction.PICKUP, far.entityId));
        visible = false;
        assertEquals(OUT_OF_REACH, request(alpha, 2, ItemAction.PICKUP, near.entityId));
        visible = true;
        active = false;
        assertEquals(INACTIVE_PARTICIPANT, request(alpha, 3, ItemAction.PICKUP, near.entityId));
        active = true;
        assertEquals(DUPLICATE, request(alpha, 3, ItemAction.PICKUP, near.entityId));
        assertEquals(ACCEPTED, request(alpha, 4, ItemAction.PICKUP, near.entityId));
    }

    @Test
    public void objectUseCarriesExactActivatorAndDuplicateDoesNotActivateAgain() {
        assertEquals(ACCEPTED, request(beta, 1, ItemAction.USE_OBJECT, 99));
        assertSame(beta, activatedBy);
        activatedBy = null;
        assertEquals(DUPLICATE, request(beta, 1, ItemAction.USE_OBJECT, 99));
        assertNull(activatedBy);
    }

    @Test
    public void spendingCannotImproveItemsAndSharingKeepsRemainingCharges() {
        PhysicalItemState item = world.spawn("wand", alpha.getParticipantId(), 1, 1, 0,
                new ItemProperties(3, 2, "", "", 8));
        assertEquals(NOT_OWNER, world.apply(new ItemRequest(beta.getParticipantId(), 1,
                ItemAction.SPEND, item.entityId, 2, 3), beta, boundary));
        assertEquals(INVALID_SPEND, world.apply(new ItemRequest(alpha.getParticipantId(), 1,
                ItemAction.SPEND, item.entityId, 4, 9), alpha, boundary));
        assertEquals(ACCEPTED, world.apply(new ItemRequest(alpha.getParticipantId(), 2,
                ItemAction.SPEND, item.entityId, 2, 3), alpha, boundary));
        assertEquals(ACCEPTED, request(alpha, 3, ItemAction.DROP, item.entityId));
        assertEquals(ACCEPTED, request(beta, 2, ItemAction.PICKUP, item.entityId));
        assertEquals(3, world.get(item.entityId).properties.quantity);
        assertEquals(2, world.get(item.entityId).properties.condition);
    }

    @Test
    public void consumedIdentityCannotReturnThroughPickupDropOrReplay() {
        PhysicalItemState item = world.spawn("potion", alpha.getParticipantId(), 1, 1, 0);
        assertEquals(NOT_OWNER, request(beta, 1, ItemAction.CONSUME, item.entityId));
        assertEquals(ACCEPTED, request(alpha, 1, ItemAction.CONSUME, item.entityId));
        assertTrue(world.get(item.entityId).consumed);
        assertNull(world.get(item.entityId).owner);
        assertTrue(world.inventory(alpha.getParticipantId()).isEmpty());
        assertEquals(DUPLICATE, request(alpha, 1, ItemAction.CONSUME, item.entityId));
        assertEquals(UNKNOWN_ENTITY, request(alpha, 2, ItemAction.DROP, item.entityId));
        assertEquals(UNKNOWN_ENTITY, request(beta, 2, ItemAction.PICKUP, item.entityId));
        long revision = world.get(item.entityId).revision;
        world.move(item.entityId, 2, 2, 0);
        assertEquals(revision, world.get(item.entityId).revision);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mismatchedParticipantContextCannotChangeOwnership() {
        world.apply(new ItemRequest(alpha.getParticipantId(), 1, ItemAction.PICKUP, 1),
                beta, boundary);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonFiniteWorldPositionIsRejected() {
        world.spawn("invalid", null, Float.NaN, 0, 0);
    }

    private ParticipantContext participant(String name, int capacity) {
        ParticipantId id = new ParticipantId(name);
        world.registerParticipant(id, capacity);
        return new ParticipantContext(id, new ParticipantCharacterState(1, 1, 0, 0), progression);
    }

    private AuthoritativeItemWorld.Outcome request(ParticipantContext participant,
            long requestId, ItemAction action, long entityId) {
        return world.apply(new ItemRequest(participant.getParticipantId(), requestId, action,
                entityId), participant, boundary);
    }
}
