package com.interrupt.dungeoneer.multiplayer.movement;

import java.util.ArrayList;
import java.util.List;

/** Replays local predicted displacement after an authoritative acknowledgement. */
public final class MovementPredictionBuffer {
    private static final int MAX_PENDING_INPUTS = 256;

    private final List<PredictionDelta> pending = new ArrayList<PredictionDelta>();

    public synchronized void add(long inputTick, float deltaX, float deltaY, float deltaZ) {
        if(inputTick <= 0L) throw new IllegalArgumentException("Prediction input tick must be positive.");
        requireFinite(deltaX, "Predicted x displacement");
        requireFinite(deltaY, "Predicted y displacement");
        requireFinite(deltaZ, "Predicted z displacement");
        if(!pending.isEmpty()
                && inputTick <= pending.get(pending.size() - 1).inputTick) {
            throw new IllegalArgumentException("Prediction input ticks must increase.");
        }
        pending.add(new PredictionDelta(inputTick, deltaX, deltaY, deltaZ));
        while(pending.size() > MAX_PENDING_INPUTS) pending.remove(0);
    }

    public synchronized PredictedPosition replay(MovementEntityState authoritative) {
        if(authoritative == null) {
            throw new IllegalArgumentException("Authoritative movement state cannot be null.");
        }
        long acknowledged = authoritative.getLastProcessedInputTick();
        while(!pending.isEmpty() && pending.get(0).inputTick <= acknowledged) {
            pending.remove(0);
        }
        float x = authoritative.getX();
        float y = authoritative.getY();
        float z = authoritative.getZ();
        for(PredictionDelta prediction : pending) {
            x += prediction.deltaX;
            y += prediction.deltaY;
            z += prediction.deltaZ;
        }
        return new PredictedPosition(x, y, z);
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized void clear() {
        pending.clear();
    }

    private static void requireFinite(float value, String label) {
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(label + " must be finite.");
        }
    }

    public static final class PredictedPosition {
        private final float x;
        private final float y;
        private final float z;

        private PredictedPosition(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
    }

    private static final class PredictionDelta {
        private final long inputTick;
        private final float deltaX;
        private final float deltaY;
        private final float deltaZ;

        private PredictionDelta(long inputTick, float deltaX, float deltaY, float deltaZ) {
            this.inputTick = inputTick;
            this.deltaX = deltaX;
            this.deltaY = deltaY;
            this.deltaZ = deltaZ;
        }
    }
}
