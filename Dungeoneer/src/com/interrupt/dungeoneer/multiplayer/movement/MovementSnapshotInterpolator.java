package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.host.AuthoritativeHostSession;

import java.util.List;

/** Render-time clock and sampler for buffered remote Avatar interpolation. */
public final class MovementSnapshotInterpolator {
    /** One hundred milliseconds behind a 60 Hz Host clock. */
    public static final long INTERPOLATION_DELAY_TICKS = 6L;

    private static final long MAX_BUFFER_GAP_TICKS = 18L;

    private double renderHostTick = Double.NaN;
    private long latestHostTick;

    public void advance(List<MovementSnapshot> snapshots, float deltaSeconds) {
        if(snapshots == null) {
            throw new IllegalArgumentException("Movement snapshots cannot be null.");
        }
        if(Float.isNaN(deltaSeconds) || Float.isInfinite(deltaSeconds)) {
            throw new IllegalArgumentException("Interpolation delta must be finite.");
        }
        MovementSnapshot latest = latest(snapshots);
        if(latest == null) return;

        if(Double.isNaN(renderHostTick) || latest.getHostTick() < latestHostTick) {
            renderHostTick = latest.getHostTick() - INTERPOLATION_DELAY_TICKS;
        }
        else {
            float boundedDelta = Math.max(0f, Math.min(deltaSeconds, 0.1f));
            renderHostTick += boundedDelta * AuthoritativeHostSession.TICKS_PER_SECOND;
            if(latest.getHostTick() - renderHostTick > MAX_BUFFER_GAP_TICKS) {
                renderHostTick = latest.getHostTick() - INTERPOLATION_DELAY_TICKS;
            }
            if(renderHostTick > latest.getHostTick()) {
                renderHostTick = latest.getHostTick();
            }
        }
        latestHostTick = latest.getHostTick();
    }

    public InterpolatedMovementState sample(NetworkEntityId entityId,
            List<MovementSnapshot> snapshots) {
        if(entityId == null) throw new IllegalArgumentException("Network Entity ID cannot be null.");
        if(snapshots == null) {
            throw new IllegalArgumentException("Movement snapshots cannot be null.");
        }
        if(Double.isNaN(renderHostTick)) return null;

        MovementEntityState beforeState = null;
        MovementEntityState afterState = null;
        long beforeTick = Long.MIN_VALUE;
        long afterTick = Long.MAX_VALUE;
        for(MovementSnapshot snapshot : snapshots) {
            MovementEntityState state = snapshot.getEntity(entityId);
            if(state == null) continue;
            long tick = snapshot.getHostTick();
            if(tick <= renderHostTick && tick > beforeTick) {
                beforeTick = tick;
                beforeState = state;
            }
            if(tick >= renderHostTick && tick < afterTick) {
                afterTick = tick;
                afterState = state;
            }
        }

        if(beforeState == null) {
            beforeState = afterState;
            beforeTick = afterTick;
        }
        if(afterState == null) {
            afterState = beforeState;
            afterTick = beforeTick;
        }
        if(beforeState == null) return null;

        float blend = beforeTick == afterTick ? 1f
                : (float)((renderHostTick - beforeTick) / (double)(afterTick - beforeTick));
        blend = Math.max(0f, Math.min(1f, blend));
        MovementState movementState = blend < 0.5f
                ? beforeState.getMovementState() : afterState.getMovementState();
        return new InterpolatedMovementState(
                lerp(beforeState.getX(), afterState.getX(), blend),
                lerp(beforeState.getY(), afterState.getY(), blend),
                lerp(beforeState.getZ(), afterState.getZ(), blend),
                lerp(beforeState.getVelocityX(), afterState.getVelocityX(), blend),
                lerp(beforeState.getVelocityY(), afterState.getVelocityY(), blend),
                lerp(beforeState.getVelocityZ(), afterState.getVelocityZ(), blend),
                lerpRotation(beforeState.getRotation(), afterState.getRotation(), blend),
                movementState);
    }

    public double getRenderHostTick() {
        return renderHostTick;
    }

    public void reset() {
        renderHostTick = Double.NaN;
        latestHostTick = 0L;
    }

    private static MovementSnapshot latest(List<MovementSnapshot> snapshots) {
        MovementSnapshot latest = null;
        for(MovementSnapshot snapshot : snapshots) {
            if(snapshot != null && (latest == null
                    || snapshot.getHostTick() > latest.getHostTick())) {
                latest = snapshot;
            }
        }
        return latest;
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static float lerpRotation(float first, float second, float amount) {
        float difference = second - first;
        float fullTurn = (float)(Math.PI * 2.0);
        while(difference > Math.PI) difference -= fullTurn;
        while(difference < -Math.PI) difference += fullTurn;
        return first + difference * amount;
    }

    public static final class InterpolatedMovementState {
        private final float x;
        private final float y;
        private final float z;
        private final float velocityX;
        private final float velocityY;
        private final float velocityZ;
        private final float rotation;
        private final MovementState movementState;

        private InterpolatedMovementState(float x, float y, float z, float velocityX,
                float velocityY, float velocityZ, float rotation,
                MovementState movementState) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.rotation = rotation;
            this.movementState = movementState;
        }

        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
        public float getVelocityX() { return velocityX; }
        public float getVelocityY() { return velocityY; }
        public float getVelocityZ() { return velocityZ; }
        public float getRotation() { return rotation; }
        public MovementState getMovementState() { return movementState; }
    }
}
