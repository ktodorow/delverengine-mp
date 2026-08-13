package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.movement.MovementPredictionBuffer.PredictedPosition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MovementPredictionBufferTest {
    @Test
    public void replaysOnlyInputsNewerThanHostAcknowledgement() {
        MovementPredictionBuffer predictions = new MovementPredictionBuffer();
        predictions.add(1L, 1f, 0f, 0f);
        predictions.add(2L, 2f, 0f, 0f);
        predictions.add(3L, 3f, 0f, 0f);

        PredictedPosition replayed = predictions.replay(new MovementEntityState(
                new NetworkEntityId(1L), 1L, 2L, 10f, 5f, 0.5f,
                0f, 0f, 0f, 0f, MovementState.MOVING));

        assertEquals(13f, replayed.getX(), 0f);
        assertEquals(5f, replayed.getY(), 0f);
        assertEquals(1, predictions.size());
    }
}
