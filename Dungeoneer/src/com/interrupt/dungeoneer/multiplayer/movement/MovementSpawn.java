package com.interrupt.dungeoneer.multiplayer.movement;

/** Host-selected valid spawn transform for one Campaign Slot. */
public final class MovementSpawn {
    private final float x;
    private final float y;
    private final float z;
    private final float rotation;

    public MovementSpawn(float x, float y, float z, float rotation) {
        requireFinite(x, "Spawn x");
        requireFinite(y, "Spawn y");
        requireFinite(z, "Spawn z");
        requireFinite(rotation, "Spawn rotation");
        this.x = x;
        this.y = y;
        this.z = z;
        this.rotation = rotation;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float getRotation() { return rotation; }

    private static void requireFinite(float value, String label) {
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(label + " must be finite.");
        }
    }
}
