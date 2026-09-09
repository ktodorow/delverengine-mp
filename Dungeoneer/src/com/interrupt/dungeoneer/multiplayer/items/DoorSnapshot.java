package com.interrupt.dungeoneer.multiplayer.items;

/** Host door state, including animation progress for reconnect during motion. */
public final class DoorSnapshot {
    public final long entityId, revision;
    public final int state;
    public final boolean locked, active, solid;
    public final float x, y, z, rotation, animation;

    public DoorSnapshot(long entityId, long revision, int state, boolean locked,
            boolean active, boolean solid, float x, float y, float z,
            float rotation, float animation) {
        if(entityId < 1L || revision < 1L || state < 0 || state > 4
                || !finite(x) || !finite(y) || !finite(z) || !finite(rotation)
                || !finite(animation) || animation < 0f || animation > 1f) {
            throw new IllegalArgumentException("Invalid door snapshot.");
        }
        this.entityId = entityId; this.revision = revision; this.state = state;
        this.locked = locked; this.active = active; this.solid = solid;
        this.x = x; this.y = y; this.z = z; this.rotation = rotation; this.animation = animation;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
