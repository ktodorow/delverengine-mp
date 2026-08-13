package com.interrupt.dungeoneer.multiplayer.movement;

/** Immutable authoritative transform, velocity, acknowledgement, and movement state. */
public final class MovementEntityState {
    private final NetworkEntityId entityId;
    private final long lifecycleSequence;
    private final long lastProcessedInputTick;
    private final float x;
    private final float y;
    private final float z;
    private final float velocityX;
    private final float velocityY;
    private final float velocityZ;
    private final float rotation;
    private final MovementState movementState;

    public MovementEntityState(NetworkEntityId entityId, long lifecycleSequence,
            long lastProcessedInputTick, float x, float y, float z, float velocityX,
            float velocityY, float velocityZ, float rotation, MovementState movementState) {
        if(entityId == null) throw new IllegalArgumentException("Network Entity ID cannot be null.");
        if(lifecycleSequence <= 0L) {
            throw new IllegalArgumentException("Entity lifecycle sequence must be positive.");
        }
        if(lastProcessedInputTick < 0L) {
            throw new IllegalArgumentException("Processed input tick cannot be negative.");
        }
        requireFinite(x, "Authoritative x");
        requireFinite(y, "Authoritative y");
        requireFinite(z, "Authoritative z");
        requireFinite(velocityX, "Authoritative x velocity");
        requireFinite(velocityY, "Authoritative y velocity");
        requireFinite(velocityZ, "Authoritative z velocity");
        requireFinite(rotation, "Authoritative rotation");
        if(movementState == null) {
            throw new IllegalArgumentException("Authoritative movement state cannot be null.");
        }
        this.entityId = entityId;
        this.lifecycleSequence = lifecycleSequence;
        this.lastProcessedInputTick = lastProcessedInputTick;
        this.x = x;
        this.y = y;
        this.z = z;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.rotation = rotation;
        this.movementState = movementState;
    }

    public NetworkEntityId getEntityId() { return entityId; }
    public long getLifecycleSequence() { return lifecycleSequence; }
    public long getLastProcessedInputTick() { return lastProcessedInputTick; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float getVelocityX() { return velocityX; }
    public float getVelocityY() { return velocityY; }
    public float getVelocityZ() { return velocityZ; }
    public float getRotation() { return rotation; }
    public MovementState getMovementState() { return movementState; }

    private static void requireFinite(float value, String label) {
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(label + " must be finite.");
        }
    }
}
