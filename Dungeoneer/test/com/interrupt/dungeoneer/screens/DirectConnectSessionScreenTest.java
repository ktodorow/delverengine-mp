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
    public void textBoundsStayInsideBottomLeftOriginViewport() {
        assertInsideViewport(320f, 0.9f);
        assertInsideViewport(640f, 0.8f);
        assertInsideViewport(1920f, 0.9f);
    }

    @Test
    public void hostApprovalControlsStayAboveSmallWindowsViewportBottom() {
        assertTrue(DirectConnectSessionScreen.lowestHostTextBaseline(320f) >= 16f);
    }

    @Test
    public void waitsForHostApprovedLocalSpawnBeforeEnteringFloor() {
        NetworkEntityId localEntityId = new NetworkEntityId(2L);

        assertFalse(DirectConnectSessionScreen.isFloorEntryReady(
                DirectConnectPhase.READY, localEntityId,
                Collections.<MovementSnapshot>emptyList()));
        assertTrue(DirectConnectSessionScreen.isFloorEntryReady(
                DirectConnectPhase.READY, localEntityId,
                Arrays.asList(snapshot(localEntityId))));
    }

    private static void assertInsideViewport(float viewportWidth, float textWidthFraction) {
        float left = DirectConnectSessionScreen.textLeft(viewportWidth, textWidthFraction);
        float right = left + viewportWidth * textWidthFraction;

        assertTrue("Text starts outside viewport.", left >= 0f);
        assertTrue("Text ends outside viewport.", right <= viewportWidth);
    }

    private static MovementSnapshot snapshot(NetworkEntityId entityId) {
        MovementEntityState state = new MovementEntityState(entityId, 1L, 0L,
                8f, 9f, 0.5f, 0f, 0f, 0f, 0f, MovementState.IDLE);
        return new MovementSnapshot(1L, 3L, Arrays.asList(state));
    }
}
