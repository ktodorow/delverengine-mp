package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.editor.EditorMarker;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.generator.GenInfo.Markers;
import com.interrupt.dungeoneer.tiles.Tile;

/** Authoritative tile collision extracted from a Host-owned Level instance. */
public final class LevelMovementCollisionWorld implements MovementCollisionWorld {
    private static final float RADIUS = 0.2f;
    private static final float HEIGHT = 0.65f;
    private static final float STEP_HEIGHT = 0.35f;
    private static final float[] SPAWN_X_OFFSETS = { 0f, 0.4f, -0.4f, 0f };
    private static final float[] SPAWN_Y_OFFSETS = { 0f, 0f, 0f, 0.4f };

    private volatile java.util.List<MovementObstacle> doorObstacles = java.util.Collections.emptyList();
    private final Level level;
    private final Vector3 collision = new Vector3(RADIUS, RADIUS, HEIGHT);
    private final float baseSpawnX;
    private final float baseSpawnY;
    private final float baseRotation;

    public LevelMovementCollisionWorld(Level level) {
        if(level == null) throw new IllegalArgumentException("Host movement Level cannot be null.");
        if(level.width <= 0 || level.height <= 0) {
            throw new IllegalArgumentException("Host movement Level dimensions must be positive.");
        }
        this.level = level;
        int startX = level.playerStartX == null ? level.width / 2 : level.playerStartX;
        int startY = level.playerStartY == null ? level.height / 2 : level.playerStartY;
        Integer startRotation = level.playerStartRot;
        if((level.playerStartX == null || level.playerStartY == null)
                && level.editorMarkers != null) {
            for(EditorMarker marker : level.editorMarkers) {
                if(marker.type == Markers.playerStart) {
                    startX = marker.x;
                    startY = marker.y;
                    startRotation = marker.rot;
                    break;
                }
            }
        }
        baseSpawnX = clamp(startX + 0.5f, RADIUS, level.width - RADIUS);
        baseSpawnY = clamp(startY + 0.5f, RADIUS, level.height - RADIUS);
        baseRotation = startRotation == null ? 0f
                : (float)Math.toRadians(-(startRotation + 180f));
    }

    @Override
    public MovementSpawn getSpawn(int campaignSlot) {
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Campaign Slot must be 1-4.");
        }
        float x = baseSpawnX + SPAWN_X_OFFSETS[campaignSlot - 1];
        float y = baseSpawnY + SPAWN_Y_OFFSETS[campaignSlot - 1];
        float z = getFloorZ(x, y, 0f);
        if(!canOccupy(x, y, z)) {
            x = baseSpawnX;
            y = baseSpawnY;
            z = getFloorZ(x, y, 0f);
        }
        if(!canOccupy(x, y, z)) {
            throw new IllegalArgumentException("Active Floor has no valid Player spawn.");
        }
        return new MovementSpawn(x, y, z, baseRotation);
    }

    @Override
    public boolean canOccupy(float x, float y, float z) {
        if(!finite(x) || !finite(y) || !finite(z)
                || x < RADIUS || x > level.width - RADIUS
                || y < RADIUS || y > level.height - RADIUS) return false;
        for(MovementObstacle obstacle : doorObstacles) {
            if(obstacle.overlaps(x, y, z, RADIUS, HEIGHT)) return false;
        }
        return level.isFree(x, y, z, collision, STEP_HEIGHT, false, null);
    }

    public void setDoorObstacles(java.util.List<MovementObstacle> obstacles) {
        if(obstacles == null || obstacles.size() > 4096) {
            throw new IllegalArgumentException("Door obstacle count is outside bounds.");
        }
        doorObstacles = java.util.Collections.unmodifiableList(
                new java.util.ArrayList<MovementObstacle>(obstacles));
    }

    @Override
    public float getFloorZ(float x, float y, float currentZ) {
        if(!finite(x) || !finite(y)
                || x < RADIUS || x > level.width - RADIUS
                || y < RADIUS || y > level.height - RADIUS) return currentZ;
        Tile tile = level.getTile((int)Math.floor(x), (int)Math.floor(y));
        if(tile == null) return currentZ;
        return level.maxFloorHeight(x, y, currentZ, RADIUS) + 0.5f;
    }

    @Override
    public boolean hasLineOfSight(float fromX, float fromY, float toX, float toY) {
        if(!finite(fromX) || !finite(fromY) || !finite(toX) || !finite(toY)) return false;
        for(MovementObstacle obstacle : doorObstacles) {
            if(obstacle.blocksSegment(fromX, fromY, toX, toY)) return false;
        }
        return level.canSee(fromX, fromY, toX, toY);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
