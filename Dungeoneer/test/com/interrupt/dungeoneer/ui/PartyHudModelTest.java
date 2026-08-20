package com.interrupt.dungeoneer.ui;

import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PartyHudModelTest {
    @Test
    public void listsHealthLivesDistanceDirectionAndConnectionState() {
        NetworkEntityId localId = new NetworkEntityId(1L);
        NetworkEntityId remoteId = new NetworkEntityId(2L);
        PartyStatusSnapshot party = new PartyStatusSnapshot(1L, Arrays.asList(
                member(1, localId, "Host", PartyMemberState.CONNECTED),
                member(2, remoteId, "Friend", PartyMemberState.CONNECTED),
                member(3, null, "Away", PartyMemberState.DISCONNECTED)));
        MovementSnapshot movement = new MovementSnapshot(1L, 1L, Arrays.asList(
                movement(localId, 10f, 10f), movement(remoteId, 15f, 10f)));

        List<PartyHudModel.Row> rows = PartyHudModel.build(party, movement,
                localId, 10f, 10f, 0f);

        assertEquals("HERE", rows.get(0).getDirection());
        assertEquals(5f, rows.get(1).getDistance(), 0f);
        assertEquals("RIGHT", rows.get(1).getDirection());
        assertTrue(rows.get(1).getDisplayText().contains(
                "Friend  8/8 HP  3 Lives  5.0m RIGHT  CONNECTED"));
        assertNull(rows.get(2).getDistance());
        assertTrue(rows.get(2).getDisplayText().endsWith("--  DISCONNECTED"));
    }

    @Test
    public void mapsRelativeBearingAroundLocalFirstPersonRotation() {
        assertEquals("AHEAD", PartyHudModel.relativeDirection(0f, 1f, 0f));
        assertEquals("RIGHT", PartyHudModel.relativeDirection(1f, 0f, 0f));
        assertEquals("LEFT", PartyHudModel.relativeDirection(-1f, 0f, 0f));
        assertEquals("BEHIND", PartyHudModel.relativeDirection(0f, -1f, 0f));
        assertEquals("AHEAD", PartyHudModel.relativeDirection(1f, 0f,
                (float)(Math.PI * 0.5)));
    }

    private PartyMemberStatus member(int slot, NetworkEntityId entityId,
            String nickname, PartyMemberState state) {
        return new PartyMemberStatus(slot, entityId, nickname, "humanoid-" + slot,
                8, 8, 3, state);
    }

    private MovementEntityState movement(NetworkEntityId entityId, float x, float y) {
        return new MovementEntityState(entityId, 1L, 0L, x, y, 0.5f,
                0f, 0f, 0f, 0f, MovementState.IDLE);
    }
}
