package com.interrupt.dungeoneer.multiplayer.movement;

/** Flat bounded world used by transport tests and fallback headless sessions. */
public final class RectangularMovementCollisionWorld implements MovementCollisionWorld {
    private static final float RADIUS = 0.2f;

    private final float width;
    private final float height;
    private final float spawnX;
    private final float spawnY;
    private final float floorZ;

    public RectangularMovementCollisionWorld(float width, float height, float spawnX,
            float spawnY, float floorZ) {
        if(width <= RADIUS * 2f || height <= RADIUS * 2f) {
            throw new IllegalArgumentException("Movement world dimensions are too small.");
        }
        this.width = width;
        this.height = height;
        this.spawnX = clamp(spawnX, RADIUS, width - RADIUS);
        this.spawnY = clamp(spawnY, RADIUS, height - RADIUS);
        this.floorZ = floorZ;
    }

    @Override
    public MovementSpawn getSpawn(int campaignSlot) {
        requireSlot(campaignSlot);
        float offsetX = campaignSlot == 2 ? 0.4f : campaignSlot == 3 ? -0.4f : 0f;
        float offsetY = campaignSlot == 4 ? 0.4f : 0f;
        return new MovementSpawn(clamp(spawnX + offsetX, RADIUS, width - RADIUS),
                clamp(spawnY + offsetY, RADIUS, height - RADIUS), floorZ, 0f);
    }

    @Override
    public boolean canOccupy(float x, float y, float z) {
        return finite(x) && finite(y) && finite(z)
                && x >= RADIUS && x <= width - RADIUS
                && y >= RADIUS && y <= height - RADIUS
                && z >= floorZ;
    }

    @Override
    public float getFloorZ(float x, float y, float currentZ) {
        return floorZ;
    }

    private static void requireSlot(int campaignSlot) {
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Campaign Slot must be 1-4.");
        }
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
