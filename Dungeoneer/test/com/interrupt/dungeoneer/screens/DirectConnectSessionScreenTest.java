package com.interrupt.dungeoneer.screens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementState;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class DirectConnectSessionScreenTest {
    @Test
    public void waitsForAuthoritativeLocalSpawnBeforeEnteringFloor() {
        NetworkEntityId localEntityId = new NetworkEntityId(2L);

        assertFalse(DirectConnectSessionScreen.isFloorEntryReady(
                DirectConnectPhase.READY, localEntityId,
                Collections.<MovementSnapshot>emptyList()));
        assertTrue(DirectConnectSessionScreen.isFloorEntryReady(
                DirectConnectPhase.READY, localEntityId,
                Arrays.asList(snapshot(localEntityId))));
    }

    private static MovementSnapshot snapshot(NetworkEntityId entityId) {
        MovementEntityState state = new MovementEntityState(entityId, 1L, 0L,
                8f, 9f, 0.5f, 0f, 0f, 0f, 0f, MovementState.IDLE);
        return new MovementSnapshot(1L, 3L, Arrays.asList(state));
    }
}
