package com.interrupt.dungeoneer.multiplayer.movement;

import java.util.Collections;
import java.util.List;

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

    // Native Player physics queries. Defaults describe a flat world with nothing on it.

    /** Native Level.isFree for the Participant box: tiles only, rising at most stepHeight. */
    default boolean isTileFree(float x, float y, float z, float stepHeight) {
        float floor = getTileFloorZ(x, y, z);
        return floor - z <= stepHeight && canOccupy(x, y, Math.max(z, floor));
    }

    /** Native standing height on tiles under the Participant footprint (maxFloorHeight + 0.5). */
    default float getTileFloorZ(float x, float y, float z) { return getFloorZ(x, y, z); }

    /** Native standing height on the tile under one point (Tile.getFloorHeight + 0.5). */
    default float getPointFloorZ(float x, float y) { return getTileFloorZ(x, y, 0f); }

    /** Native Level.getSlope: floor normal under the Participant as {x, y, z}. */
    default void getFloorNormal(float x, float y, float z, float[] normal) {
        normal[0] = 0f;
        normal[1] = 0f;
        normal[2] = 1f;
    }

    /** Native floor friction of the tile under this point. */
    default float getFloorFriction(float x, float y) { return 1f; }

    /** Solid native objects that may touch a Participant footprint centred here. */
    default List<MovementObstacle> getObstacles(float x, float y) {
        return Collections.<MovementObstacle>emptyList();
    }

    /** A native Ladder climb area touches the Participant box. */
    default boolean isOnLadder(float x, float y, float z) { return false; }
}
