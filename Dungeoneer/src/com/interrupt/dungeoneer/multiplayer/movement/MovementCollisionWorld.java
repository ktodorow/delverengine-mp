package com.interrupt.dungeoneer.multiplayer.movement;

/** World-only collision boundary; Participant characters are intentionally excluded. */
public interface MovementCollisionWorld {
    MovementSpawn getSpawn(int campaignSlot);

    boolean canOccupy(float x, float y, float z);

    float getFloorZ(float x, float y, float currentZ);

    boolean hasLineOfSight(float fromX, float fromY, float toX, float toY);

    /**
     * Water surface height when a native Player at this position counts as in water
     * (below the tile's floor height plus 0.5), otherwise NaN.
     */
    default float getWaterSurfaceZ(float x, float y, float z) { return Float.NaN; }
}
