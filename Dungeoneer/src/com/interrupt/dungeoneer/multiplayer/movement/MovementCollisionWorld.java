package com.interrupt.dungeoneer.multiplayer.movement;

/** World-only collision boundary; Participant characters are intentionally excluded. */
public interface MovementCollisionWorld {
    MovementSpawn getSpawn(int campaignSlot);

    boolean canOccupy(float x, float y, float z);

    float getFloorZ(float x, float y, float currentZ);

    boolean hasLineOfSight(float fromX, float fromY, float toX, float toY);
}
