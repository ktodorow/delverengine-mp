package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.editor.EditorMarker;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.generator.GenInfo.Markers;
import com.interrupt.dungeoneer.tiles.Tile;

/**
 * Authoritative collision for a Host-owned Level: its tiles plus solid native objects copied from
 * the live floor, which block Participants, carry them on their tops, and let them step up.
 */
public final class LevelMovementCollisionWorld implements MovementCollisionWorld {
    private static final float RADIUS = 0.2f;
    private static final float HEIGHT = 0.65f;
    private static final float STEP_HEIGHT = 0.35f;
    /** Host-local copies of floor objects; bounds a runaway copy, not network input. */
    public static final int MAX_OBSTACLES = 16384;
    private static final float[] SPAWN_X_OFFSETS = { 0f, 0.4f, -0.4f, 0f };
    private static final float[] SPAWN_Y_OFFSETS = { 0f, 0f, 0f, 0.4f };

    /** Immutable obstacle set with a per-tile grid, swapped whole by the render thread. */
    private static final class ObstacleIndex {
        final java.util.List<MovementObstacle> sight;
        final java.util.List<MovementObstacle>[] grid;
        final int width, height;

        @SuppressWarnings("unchecked")
        ObstacleIndex(java.util.List<MovementObstacle> obstacles, int width, int height) {
            this.width = width;
            this.height = height;
            grid = new java.util.List[width * height];
            java.util.List<MovementObstacle> sightBlocking = new java.util.ArrayList<MovementObstacle>();
            for(MovementObstacle obstacle : obstacles) {
                if(obstacle.blocksSight) sightBlocking.add(obstacle);
                int fromX = clampTile(obstacle.minX, width), toX = clampTile(obstacle.maxX, width);
                int fromY = clampTile(obstacle.minY, height), toY = clampTile(obstacle.maxY, height);
                for(int tileY = fromY; tileY <= toY; tileY++) {
                    for(int tileX = fromX; tileX <= toX; tileX++) {
                        int cell = tileX + tileY * width;
                        if(grid[cell] == null) grid[cell] = new java.util.ArrayList<MovementObstacle>(2);
                        grid[cell].add(obstacle);
                    }
                }
            }
            sight = java.util.Collections.unmodifiableList(sightBlocking);
        }

        java.util.List<MovementObstacle> at(int tileX, int tileY) {
            if(tileX < 0 || tileY < 0 || tileX >= width || tileY >= height) return null;
            return grid[tileX + tileY * width];
        }

        private static int clampTile(float value, int size) {
            return Math.max(0, Math.min(size - 1, (int)Math.floor(value)));
        }
    }

    private volatile ObstacleIndex obstacles;
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
        obstacles = new ObstacleIndex(java.util.Collections.<MovementObstacle>emptyList(),
                level.width, level.height);
        Integer startX = level.playerStartX, startY = level.playerStartY;
        Integer startRotation = level.playerStartRot;
        EditorMarker arrival = null;
        if((startX == null || startY == null) && level.editorMarkers != null) {
            for(EditorMarker marker : level.editorMarkers) {
                if(marker.type == Markers.playerStart) {
                    startX = marker.x;
                    startY = marker.y;
                    startRotation = marker.rot;
                    break;
                }
                if(marker.type == Markers.stairUp && arrival == null) arrival = marker;
            }
        }
        float spawnX, spawnY, rotation;
        if(startX != null && startY != null) {
            spawnX = startX + 0.5f;
            spawnY = startY + 0.5f;
            rotation = startRotation == null ? 0f : (float)Math.toRadians(-(startRotation + 180f));
        }
        else if(arrival != null) {
            // Native Game.changeLevel: an arriving Player stands on the up stairs, facing away.
            spawnX = arrival.x + 0.5f;
            spawnY = arrival.y + 0.55f;
            rotation = (float)Math.PI;
        }
        else {
            spawnX = level.width / 2 + 0.5f;
            spawnY = level.height / 2 + 0.5f;
            rotation = 0f;
        }
        baseSpawnX = clamp(spawnX, RADIUS, level.width - RADIUS);
        baseSpawnY = clamp(spawnY, RADIUS, level.height - RADIUS);
        baseRotation = rotation;
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
        ObstacleIndex index = obstacles;
        for(int tileY = (int)Math.floor(y - RADIUS); tileY <= (int)Math.floor(y + RADIUS); tileY++) {
            for(int tileX = (int)Math.floor(x - RADIUS); tileX <= (int)Math.floor(x + RADIUS); tileX++) {
                java.util.List<MovementObstacle> cell = index.at(tileX, tileY);
                if(cell == null) continue;
                for(MovementObstacle obstacle : cell) {
                    // A low native object is climbed onto; getFloorZ then lifts the Participant.
                    if(obstacle.overlaps(x, y, z, RADIUS, HEIGHT)
                            && !obstacle.canStepOnto(z, STEP_HEIGHT)) return false;
                }
            }
        }
        return level.isFree(x, y, z, collision, stepHeight(x, y, z), false, null);
    }

    /** Native Player raises its step height in water so it can climb out. */
    private float stepHeight(float x, float y, float z) {
        float surface = getWaterSurfaceZ(x, y, z);
        return Float.isNaN(surface) ? STEP_HEIGHT : 0.3499f + (surface - z);
    }

    @Override
    public float getWaterSurfaceZ(float x, float y, float z) {
        if(!finite(x) || !finite(y) || !finite(z)) return Float.NaN;
        com.interrupt.dungeoneer.tiles.Tile water = level.findWaterTile(x, y, z, collision);
        return water == null ? Float.NaN : water.floorHeight + 0.5f;
    }

    public void setWorldObstacles(java.util.List<MovementObstacle> obstacles) {
        if(obstacles == null || obstacles.size() > MAX_OBSTACLES) {
            throw new IllegalArgumentException("World obstacle count is outside bounds.");
        }
        this.obstacles = new ObstacleIndex(new java.util.ArrayList<MovementObstacle>(obstacles),
                level.width, level.height);
    }

    public void setDoorObstacles(java.util.List<MovementObstacle> obstacles) {
        setWorldObstacles(obstacles);
    }

    @Override
    public float getFloorZ(float x, float y, float currentZ) {
        if(!finite(x) || !finite(y)
                || x < RADIUS || x > level.width - RADIUS
                || y < RADIUS || y > level.height - RADIUS) return currentZ;
        Tile tile = level.getTile((int)Math.floor(x), (int)Math.floor(y));
        if(tile == null) return currentZ;
        float floor = level.maxFloorHeight(x, y, currentZ, RADIUS) + 0.5f;
        // Native Player stands on the highest solid object below its feet or within one step.
        ObstacleIndex index = obstacles;
        for(int tileY = (int)Math.floor(y - RADIUS); tileY <= (int)Math.floor(y + RADIUS); tileY++) {
            for(int tileX = (int)Math.floor(x - RADIUS); tileX <= (int)Math.floor(x + RADIUS); tileX++) {
                java.util.List<MovementObstacle> cell = index.at(tileX, tileY);
                if(cell == null) continue;
                for(MovementObstacle obstacle : cell) {
                    if(obstacle.standable && obstacle.maxZ > floor && obstacle.under(x, y, RADIUS)
                            && obstacle.maxZ - currentZ < STEP_HEIGHT) floor = obstacle.maxZ;
                }
            }
        }
        return floor;
    }

    @Override
    public boolean hasLineOfSight(float fromX, float fromY, float toX, float toY) {
        if(!finite(fromX) || !finite(fromY) || !finite(toX) || !finite(toY)) return false;
        for(MovementObstacle obstacle : obstacles.sight) {
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
