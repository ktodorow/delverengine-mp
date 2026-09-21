package com.interrupt.dungeoneer.multiplayer.movement;

/** Immutable native-object bounds copied at the render/Host movement boundary. */
public final class MovementObstacle {
    public final float minX, minY, minZ, maxX, maxY, maxZ;
    /** Native Players stand on its top (Actors and Triggers bounce them off instead). */
    public final boolean standable;
    /** Native Players climb onto it when its top is within their step height. */
    public final boolean stepable;
    /** Blocks Host monster line of sight (native Doors and Breakables). */
    public final boolean blocksSight;

    /** Door or Breakable bounds: blocks movement and sight, never a floor. */
    public MovementObstacle(float x, float y, float z, float radiusX, float radiusY, float height) {
        this(x, y, z, radiusX, radiusY, height, false, false, true);
    }

    public MovementObstacle(float x, float y, float z, float radiusX, float radiusY, float height,
            boolean standable, boolean stepable, boolean blocksSight) {
        if(!finite(x) || !finite(y) || !finite(z) || !finite(radiusX) || !finite(radiusY)
                || !finite(height) || radiusX < 0 || radiusY < 0 || height < 0) {
            throw new IllegalArgumentException("Invalid movement obstacle bounds.");
        }
        minX = x - radiusX; maxX = x + radiusX;
        minY = y - radiusY; maxY = y + radiusY;
        minZ = z; maxZ = z + height;
        this.standable = standable;
        this.stepable = stepable;
        this.blocksSight = blocksSight;
    }

    /** Native Player climbs onto an encroached entity whose top is less than one step above its feet. */
    public boolean canStepOnto(float z, float stepHeight) {
        return stepable && maxZ - z < stepHeight;
    }

    /** Footprint overlap in the horizontal plane only. */
    public boolean under(float x, float y, float radius) {
        return x + radius > minX && x - radius < maxX && y + radius > minY && y - radius < maxY;
    }

    public boolean overlaps(float x, float y, float z, float radius, float height) {
        return x + radius > minX && x - radius < maxX
                && y + radius > minY && y - radius < maxY
                && z + height > minZ && z < maxZ;
    }

    /** Segment/rectangle slab intersection, also handling axis-aligned rays. */
    public boolean blocksSegment(float fromX, float fromY, float toX, float toY) {
        float enter = 0f, leave = 1f;
        float[] start = { fromX, fromY }, delta = { toX - fromX, toY - fromY };
        float[] minimum = { minX, minY }, maximum = { maxX, maxY };
        for(int axis = 0; axis < 2; axis++) {
            if(Math.abs(delta[axis]) < 0.000001f) {
                if(start[axis] < minimum[axis] || start[axis] > maximum[axis]) return false;
                continue;
            }
            float a = (minimum[axis] - start[axis]) / delta[axis];
            float b = (maximum[axis] - start[axis]) / delta[axis];
            enter = Math.max(enter, Math.min(a, b));
            leave = Math.min(leave, Math.max(a, b));
            if(enter > leave) return false;
        }
        return leave > 0f && enter < 1f;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
