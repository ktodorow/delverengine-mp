package com.interrupt.dungeoneer.multiplayer.items;

/** Host transform of a native Mover (lift, crusher, pressure plate, sliding wall). */
public final class MoverSnapshot {
    public final long entityId, revision;
    public final float x, y, z, rotationX, rotationY, rotationZ;
    public final boolean moving;

    public MoverSnapshot(long entityId, long revision, float x, float y, float z,
            float rotationX, float rotationY, float rotationZ, boolean moving) {
        if(entityId < 1L || revision < 1L || !finite(x) || !finite(y) || !finite(z)
                || !finite(rotationX) || !finite(rotationY) || !finite(rotationZ)) {
            throw new IllegalArgumentException("Invalid mover snapshot.");
        }
        this.entityId = entityId; this.revision = revision;
        this.x = x; this.y = y; this.z = z;
        this.rotationX = rotationX; this.rotationY = rotationY; this.rotationZ = rotationZ;
        this.moving = moving;
    }

    public boolean sameTransform(MoverSnapshot other) {
        return other != null && other.entityId == entityId && other.x == x && other.y == y
                && other.z == z && other.rotationX == rotationX && other.rotationY == rotationY
                && other.rotationZ == rotationZ && other.moving == moving;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
