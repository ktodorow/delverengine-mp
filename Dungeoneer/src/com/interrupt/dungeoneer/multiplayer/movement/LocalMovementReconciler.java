package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.movement.MovementPredictionBuffer.PredictedPosition;

/** Replays local prediction and turns Host corrections into bounded render steps. */
public final class LocalMovementReconciler {
    public static final float SNAP_DISTANCE = 1.25f;
    public static final float CORRECTION_RATE = 12f;

    private static final float SETTLED_DISTANCE = 0.0001f;

    private final MovementPredictionBuffer predictions = new MovementPredictionBuffer();
    private float correctionX;
    private float correctionY;
    private float correctionZ;

    public void addPrediction(long inputTick, float deltaX, float deltaY, float deltaZ) {
        predictions.add(inputTick, deltaX, deltaY, deltaZ);
    }

    public Reconciliation reconcile(MovementEntityState authoritative,
            float currentX, float currentY, float currentZ) {
        if(authoritative == null) {
            throw new IllegalArgumentException("Authoritative movement state cannot be null.");
        }
        PredictedPosition replayed = predictions.replay(authoritative);
        float targetX = replayed.getX();
        float targetY = replayed.getY();
        float targetZ = replayed.getZ();
        boolean invalidPosition = !finite(currentX) || !finite(currentY) || !finite(currentZ)
                || !finite(targetX) || !finite(targetY) || !finite(targetZ);
        if(invalidPosition) {
            predictions.clear();
            correctionX = correctionY = correctionZ = 0f;
            return new Reconciliation(authoritative.getX(), authoritative.getY(),
                    authoritative.getZ(), true, Float.POSITIVE_INFINITY);
        }

        float errorX = targetX - currentX;
        float errorY = targetY - currentY;
        float errorZ = targetZ - currentZ;
        float errorSquared = errorX * errorX + errorY * errorY + errorZ * errorZ;
        if(!finite(errorSquared) || errorSquared >= SNAP_DISTANCE * SNAP_DISTANCE) {
            correctionX = correctionY = correctionZ = 0f;
            return new Reconciliation(targetX, targetY, targetZ, true,
                    finite(errorSquared) ? (float)Math.sqrt(errorSquared)
                            : Float.POSITIVE_INFINITY);
        }

        correctionX = errorX;
        correctionY = errorY;
        correctionZ = errorZ;
        if(errorSquared <= SETTLED_DISTANCE * SETTLED_DISTANCE) {
            correctionX = correctionY = correctionZ = 0f;
        }
        return new Reconciliation(targetX, targetY, targetZ, false,
                (float)Math.sqrt(errorSquared));
    }

    public CorrectionStep advance(float deltaSeconds) {
        if(!finite(deltaSeconds)) {
            throw new IllegalArgumentException("Correction delta must be finite.");
        }
        float boundedDelta = Math.max(0f, Math.min(deltaSeconds, 0.1f));
        float blend = 1f - (float)Math.exp(-CORRECTION_RATE * boundedDelta);
        float appliedX = correctionX * blend;
        float appliedY = correctionY * blend;
        float appliedZ = correctionZ * blend;
        correctionX -= appliedX;
        correctionY -= appliedY;
        correctionZ -= appliedZ;

        float remainingSquared = correctionX * correctionX
                + correctionY * correctionY + correctionZ * correctionZ;
        if(remainingSquared <= SETTLED_DISTANCE * SETTLED_DISTANCE) {
            appliedX += correctionX;
            appliedY += correctionY;
            appliedZ += correctionZ;
            correctionX = correctionY = correctionZ = 0f;
        }
        return new CorrectionStep(appliedX, appliedY, appliedZ);
    }

    public int getPendingInputCount() {
        return predictions.size();
    }

    public float getRemainingCorrectionDistance() {
        return (float)Math.sqrt(correctionX * correctionX
                + correctionY * correctionY + correctionZ * correctionZ);
    }

    public void reset() {
        predictions.clear();
        correctionX = correctionY = correctionZ = 0f;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static final class Reconciliation {
        private final float targetX;
        private final float targetY;
        private final float targetZ;
        private final boolean snapRequired;
        private final float errorDistance;

        private Reconciliation(float targetX, float targetY, float targetZ,
                boolean snapRequired, float errorDistance) {
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.snapRequired = snapRequired;
            this.errorDistance = errorDistance;
        }

        public float getTargetX() { return targetX; }
        public float getTargetY() { return targetY; }
        public float getTargetZ() { return targetZ; }
        public boolean isSnapRequired() { return snapRequired; }
        public float getErrorDistance() { return errorDistance; }
    }

    public static final class CorrectionStep {
        private final float x;
        private final float y;
        private final float z;

        private CorrectionStep(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
    }
}
