package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.movement.LocalMovementReconciler.CorrectionStep;
import com.interrupt.dungeoneer.multiplayer.movement.LocalMovementReconciler.Reconciliation;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalMovementReconcilerTest {
    @Test
    public void replaysUnacknowledgedPredictionBeforeSmoothingSmallError() {
        LocalMovementReconciler reconciler = new LocalMovementReconciler();
        reconciler.addPrediction(1L, 0.1f, 0f, 0f);
        reconciler.addPrediction(2L, 0.2f, 0f, 0f);

        Reconciliation result = reconciler.reconcile(state(1L, 1f),
                1.4f, 0f, 0f);

        assertFalse(result.isSnapRequired());
        assertEquals(1.2f, result.getTargetX(), 0.0001f);
        assertEquals(1, reconciler.getPendingInputCount());
        CorrectionStep firstFrame = reconciler.advance(1f / 60f);
        assertEquals(-0.0363f, firstFrame.getX(), 0.0001f);
        assertEquals(0f, firstFrame.getY(), 0f);
    }

    @Test
    public void severeOrImpossibleDivergenceRequiresSafeSnap() {
        LocalMovementReconciler reconciler = new LocalMovementReconciler();

        Reconciliation severe = reconciler.reconcile(state(0L, 0f),
                LocalMovementReconciler.SNAP_DISTANCE + 0.01f, 0f, 0f);
        assertTrue(severe.isSnapRequired());
        assertEquals(0f, severe.getTargetX(), 0f);

        reconciler.addPrediction(1L, 0.25f, 0f, 0f);
        Reconciliation impossible = reconciler.reconcile(state(0L, 1f),
                Float.NaN, 0f, 0f);
        assertTrue(impossible.isSnapRequired());
        assertEquals(1f, impossible.getTargetX(), 0f);
        assertEquals(0, reconciler.getPendingInputCount());
    }

    @Test
    public void smallCorrectionConvergesWithoutOneFrameTeleport() {
        LocalMovementReconciler reconciler = new LocalMovementReconciler();
        Reconciliation result = reconciler.reconcile(state(0L, 0f), 0.5f, 0f, 0f);
        assertFalse(result.isSnapRequired());

        float applied = 0f;
        float largestStep = 0f;
        for(int frame = 0; frame < 90; frame++) {
            CorrectionStep step = reconciler.advance(1f / 60f);
            applied += step.getX();
            largestStep = Math.max(largestStep, Math.abs(step.getX()));
        }

        assertEquals(-0.5f, applied, 0.001f);
        assertTrue(largestStep < 0.11f);
        assertEquals(0f, reconciler.getRemainingCorrectionDistance(), 0.0001f);
    }

    @Test
    public void correctionRateIsIndependentOfRenderFrameRate() {
        LocalMovementReconciler thirtyFps = new LocalMovementReconciler();
        LocalMovementReconciler sixtyFps = new LocalMovementReconciler();
        thirtyFps.reconcile(state(0L, 0f), 0.5f, 0f, 0f);
        sixtyFps.reconcile(state(0L, 0f), 0.5f, 0f, 0f);

        float oneLargeFrame = thirtyFps.advance(1f / 30f).getX();
        float twoSmallFrames = sixtyFps.advance(1f / 60f).getX()
                + sixtyFps.advance(1f / 60f).getX();

        assertEquals(oneLargeFrame, twoSmallFrames, 0.0001f);
    }

    private MovementEntityState state(long acknowledgedInputTick, float x) {
        return new MovementEntityState(new NetworkEntityId(1L), 1L,
                acknowledgedInputTick, x, 0f, 0f, 0f, 0f, 0f, 0f,
                MovementState.IDLE);
    }
}
