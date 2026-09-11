package com.interrupt.dungeoneer.multiplayer.items;

/** Host-owned current state for a native breakable already present in local content. */
public final class BreakableSnapshot {
    public final long entityId, revision;
    public final int hp;
    public final boolean active, solid;
    public final float x, y, z, velocityX, velocityY, velocityZ;
    public final float rotationX, rotationY, rotationZ;

    public BreakableSnapshot(long entityId, long revision, int hp,
            boolean active, boolean solid, float x, float y, float z,
            float velocityX, float velocityY, float velocityZ,
            float rotationX, float rotationY, float rotationZ) {
        if(entityId < 1L || revision < 1L || hp < -1000000 || hp > 1000000
                || !finite(x) || !finite(y) || !finite(z)
                || !finite(velocityX) || !finite(velocityY) || !finite(velocityZ)
                || !finite(rotationX) || !finite(rotationY) || !finite(rotationZ)) {
            throw new IllegalArgumentException("Invalid breakable snapshot.");
        }
        this.entityId = entityId;
        this.revision = revision;
        this.hp = hp;
        this.active = active;
        this.solid = solid;
        this.x = x;
        this.y = y;
        this.z = z;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.rotationX = rotationX;
        this.rotationY = rotationY;
        this.rotationZ = rotationZ;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && Math.abs(value) <= 1000000f;
    }
}
