package com.interrupt.dungeoneer.multiplayer.combat;

/** Host-observed transform and target for one native Delver Monster. */
public final class MonsterSnapshot {
    private final String id;
    private final String targetId;
    private final float x;
    private final float y;
    private final float z;
    private final boolean gibbed;

    public MonsterSnapshot(String id, String targetId, float x, float y, float z) {
        this(id, targetId, x, y, z, false);
    }

    public MonsterSnapshot(String id, String targetId, float x, float y, float z,
            boolean gibbed) {
        if(id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Monster network identity cannot be empty.");
        }
        if(targetId == null) {
            throw new IllegalArgumentException("Monster target identity cannot be null.");
        }
        requireFinite(x, "Monster x");
        requireFinite(y, "Monster y");
        requireFinite(z, "Monster z");
        this.id = id;
        this.targetId = targetId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.gibbed = gibbed;
    }

    public String getId() { return id; }
    public String getTargetId() { return targetId; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public boolean isGibbed() { return gibbed; }

    private static void requireFinite(float value, String label) {
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(label + " must be finite.");
        }
    }
}
