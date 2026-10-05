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
        final java.util.List<MovementObstacle> ladders;
        final java.util.List<MovementObstacle>[] grid;
        final int width, height;

        @SuppressWarnings("unchecked")
        ObstacleIndex(java.util.List<MovementObstacle> obstacles, int width, int height) {
            this.width = width;
            this.height = height;
            grid = new java.util.List[width * height];
            java.util.List<MovementObstacle> sightBlocking = new java.util.ArrayList<MovementObstacle>();
            java.util.List<MovementObstacle> climbAreas = new java.util.ArrayList<MovementObstacle>();
            for(MovementObstacle obstacle : obstacles) {
                if(obstacle.climbable) {
                    climbAreas.add(obstacle);
                    continue;
                }
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
            ladders = java.util.Collections.unmodifiableList(climbAreas);
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
    /** Host Monsters, replaced every frame; native Player physics collides with them. */
    private volatile java.util.List<MovementObstacle> actors =
            java.util.Collections.<MovementObstacle>emptyList();
    public static final int MAX_ACTOR_OBSTACLES = 256;
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

    /** Current bounds of the floor's Monsters; they block and bounce Participants like Actors. */
    public void setActorObstacles(java.util.List<MovementObstacle> monsters) {
        if(monsters == null || monsters.size() > MAX_ACTOR_OBSTACLES) {
            throw new IllegalArgumentException("Monster obstacle count is outside bounds.");
        }
        actors = java.util.Collections.unmodifiableList(new java.util.ArrayList<MovementObstacle>(monsters));
    }

    /**
     * Native tile rules (water, pits, friction, closed tiles) only exist once a floor is
     * initialized, so the Host takes them from the live Active Floor it built from the same file.
     */
    public void adoptTileRules(Level initialized) {
        if(initialized == null || initialized.tiles == null || level.tiles == null
                || initialized.width != level.width || initialized.height != level.height) {
            throw new IllegalArgumentException("Live Active Floor does not match Host movement floor.");
        }
        for(int index = 0; index < level.tiles.length; index++) {
            Tile tile = level.tiles[index], live = initialized.tiles[index];
            if(tile == null || live == null) continue;
            tile.data = live.data;
            tile.blockMotion = live.blockMotion;
        }
    }

    @Override
    public boolean isTileFree(float x, float y, float z, float stepHeight) {
        if(!finite(x) || !finite(y) || !finite(z) || !finite(stepHeight)) return false;
        return level.isFree(x, y, z, collision, stepHeight, false, null);
    }

    @Override
    public float getTileFloorZ(float x, float y, float z) {
        if(!finite(x) || !finite(y)) return z;
        return level.maxFloorHeight(x, y, z, RADIUS) + 0.5f;
    }

    @Override
    public float getPointFloorZ(float x, float y) {
        return level.getTile((int)Math.floor(x), (int)Math.floor(y)).getFloorHeight(x, y) + 0.5f;
    }

    /** Native Level.getSlope without its render-thread vector pool. */
    @Override
    public void getFloorNormal(float x, float y, float z, float[] normal) {
        float xx0 = x - RADIUS, xx1 = x + RADIUS, yy0 = y - RADIUS, yy1 = y + RADIUS;
        int x0 = (int)Math.floor(xx0), x1 = (int)Math.floor(xx1);
        int y0 = (int)Math.floor(yy0), y1 = (int)Math.floor(yy1);
        float height1 = level.getTile(x0, y0).getFloorHeight(xx0, yy0);
        float height2 = level.getTile(x1, y0).getFloorHeight(xx1, yy0);
        float height3 = level.getTile(x0, y1).getFloorHeight(xx0, yy1);
        float height4 = level.getTile(x1, y1).getFloorHeight(xx1, yy1);
        float maxHeight = Math.max(Math.max(height1, height2), Math.max(height3, height4));
        Vector3 result = new Vector3();
        if(maxHeight == height1) level.getTile(x0, y0).getFloorNormal(xx0, yy0, result);
        else if(maxHeight == height2) level.getTile(x1, y0).getFloorNormal(xx1, yy0, result);
        else if(maxHeight == height3) level.getTile(x0, y1).getFloorNormal(xx0, yy1, result);
        else level.getTile(x1, y1).getFloorNormal(xx1, yy1, result);
        normal[0] = result.x;
        normal[1] = result.y;
        normal[2] = result.z;
    }

    @Override
    public float getFloorFriction(float x, float y) {
        Tile current = level.getTileOrNull((int)x, (int)y);
        return (current == null ? Tile.solidWall : current).data.friction;
    }

    @Override
    public java.util.List<MovementObstacle> getObstacles(float x, float y) {
        ObstacleIndex index = obstacles;
        java.util.List<MovementObstacle> found = null;
        for(int tileY = (int)Math.floor(y - RADIUS); tileY <= (int)Math.floor(y + RADIUS); tileY++) {
            for(int tileX = (int)Math.floor(x - RADIUS); tileX <= (int)Math.floor(x + RADIUS); tileX++) {
                java.util.List<MovementObstacle> cell = index.at(tileX, tileY);
                if(cell == null) continue;
                if(found == null) found = new java.util.ArrayList<MovementObstacle>(cell.size());
                for(MovementObstacle obstacle : cell) {
                    if(!containsSame(found, obstacle)) found.add(obstacle);
                }
            }
        }
        for(MovementObstacle actor : actors) {
            if(!actor.under(x, y, RADIUS)) continue;
            if(found == null) found = new java.util.ArrayList<MovementObstacle>(2);
            found.add(actor);
        }
        return found == null ? java.util.Collections.<MovementObstacle>emptyList() : found;
    }

    @Override
    public boolean isOnLadder(float x, float y, float z) {
        for(MovementObstacle ladder : obstacles.ladders) {
            if(ladder.overlaps(x, y, z, RADIUS, HEIGHT)) return true;
        }
        return false;
    }

    private static boolean containsSame(java.util.List<MovementObstacle> obstacles, MovementObstacle wanted) {
        for(MovementObstacle obstacle : obstacles) if(obstacle == wanted) return true;
        return false;
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
