package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld.Outcome.*;
import static org.junit.Assert.*;

public class EconomyItemWorldTest {
    private final AuthoritativeItemWorld world = new AuthoritativeItemWorld();
    private final ParticipantContext alpha = participant("alpha");
    private final ParticipantContext beta = participant("beta");
    private final List<String> acquired = new ArrayList<String>();
    private final List<ItemAction> economyActions = new ArrayList<ItemAction>();
    private boolean divide = true;
    private boolean active = true;

    private final AuthoritativeItemWorld.InteractionBoundary boundary =
            new AuthoritativeItemWorld.InteractionBoundary() {
                public boolean canAct(ParticipantContext participant) { return active; }
                public boolean canReach(ParticipantContext participant, float x, float y, float z) { return true; }
                public boolean useObject(long id, ParticipantContext participant) { return false; }
                @Override public boolean acquireGold(ParticipantContext participant, int amount) {
                    acquired.add(participant.getParticipantId().getValue() + ":" + amount);
                    return divide;
                }
                @Override public boolean economyAction(ItemRequest request, ParticipantContext participant) {
                    economyActions.add(request.action);
                    return request.quantity == 1;
                }
            };

    @Test
    public void simultaneousGoldPickupDividesOnePileOnceAndNeverFillsBackpack() {
        world.registerParticipant(alpha.getParticipantId(), 1);
        world.registerParticipant(beta.getParticipantId(), 1);
        PhysicalItemState gold = world.spawn("gold", null, 1, 1, 0, new ItemProperties(2, 1, "", "", 25));
        world.registerGold(gold.entityId);

        assertEquals(ACCEPTED, apply(alpha, 1, ItemAction.PICKUP, gold.entityId, 0));
        assertEquals(UNKNOWN_ENTITY, apply(beta, 1, ItemAction.PICKUP, gold.entityId, 0));

        assertEquals(1, acquired.size());
        assertEquals("alpha:25", acquired.get(0));
        assertTrue(world.get(gold.entityId).consumed);
        assertNull(world.get(gold.entityId).owner);
        assertTrue(world.inventory(alpha.getParticipantId()).isEmpty());
        assertTrue(world.hasBackpackSpace(alpha.getParticipantId()));
    }

    @Test
    public void rejectedGoldDivisionLeavesPileInWorld() {
        world.registerParticipant(alpha.getParticipantId(), 4);
        PhysicalItemState gold = world.spawn("gold", null, 1, 1, 0, new ItemProperties(2, 1, "", "", 3));
        world.registerGold(gold.entityId);
        divide = false;

        assertEquals(OBJECT_REJECTED, apply(alpha, 1, ItemAction.PICKUP, gold.entityId, 0));

        assertFalse(world.get(gold.entityId).consumed);
        divide = true;
        assertEquals(ACCEPTED, apply(alpha, 2, ItemAction.PICKUP, gold.entityId, 0));
    }

    @Test
    public void economyActionsKeepDuplicateAndActivityGuards() {
        world.registerParticipant(alpha.getParticipantId(), 4);

        assertEquals(ACCEPTED, apply(alpha, 5, ItemAction.PURCHASE, 1000000L, 1));
        assertEquals(DUPLICATE, apply(alpha, 5, ItemAction.PURCHASE, 1000000L, 1));
        assertEquals(OBJECT_REJECTED, apply(alpha, 6, ItemAction.CHOOSE_STAT, 1L, 0));
        active = false;
        assertEquals(INACTIVE_PARTICIPANT, apply(alpha, 7, ItemAction.PURCHASE, 1000000L, 1));

        assertEquals(2, economyActions.size());
        assertEquals(ItemAction.PURCHASE, economyActions.get(0));
        assertEquals(ItemAction.CHOOSE_STAT, economyActions.get(1));
    }

    @Test
    public void capacityGrowsForSoulboundBagWithoutDiscardingItems() {
        world.registerParticipant(alpha.getParticipantId(), 1);
        world.spawn("dagger", alpha.getParticipantId(), 0, 0, 0);
        assertFalse(world.hasBackpackSpace(alpha.getParticipantId()));

        world.registerParticipant(alpha.getParticipantId(), 2);

        assertEquals(2, world.getCapacity(alpha.getParticipantId()));
        assertTrue(world.hasBackpackSpace(alpha.getParticipantId()));
    }

    private AuthoritativeItemWorld.Outcome apply(ParticipantContext participant, long request,
            ItemAction action, long entity, int quantity) {
        return world.apply(new ItemRequest(participant.getParticipantId(), request, action, entity, 0, quantity),
                participant, boundary);
    }

    private static ParticipantContext participant(String id) {
        return new ParticipantContext(new ParticipantId(id), new ParticipantCharacterState(1, 1, 0, 0),
                new SharedPartyProgression());
    }
}
