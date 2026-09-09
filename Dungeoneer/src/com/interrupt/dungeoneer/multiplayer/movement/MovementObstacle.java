package com.interrupt.dungeoneer.multiplayer.movement;

/** Immutable native-object bounds copied at the render/Host movement boundary. */
public final class MovementObstacle {
    public final float minX, minY, minZ, maxX, maxY, maxZ;

    public MovementObstacle(float x, float y, float z, float radiusX, float radiusY, float height) {
        if(!finite(x) || !finite(y) || !finite(z) || !finite(radiusX) || !finite(radiusY)
                || !finite(height) || radiusX < 0 || radiusY < 0 || height < 0) {
            throw new IllegalArgumentException("Invalid movement obstacle bounds.");
        }
        minX = x - radiusX; maxX = x + radiusX;
        minY = y - radiusY; maxY = y + radiusY;
        minZ = z; maxZ = z + height;
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
