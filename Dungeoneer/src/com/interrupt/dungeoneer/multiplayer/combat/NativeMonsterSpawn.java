package com.interrupt.dungeoneer.multiplayer.combat;

import java.nio.charset.StandardCharsets;

/**
 * A native Monster that appeared on Host after the floor's initial attach (ambient spawn,
 * spawner, egg or dev menu). Clients materialize a replica from local content by theme and name.
 */
public final class NativeMonsterSpawn {
    public static final int MAX_NAME_BYTES = 128;
    public final String monsterId;
    public final String theme;
    public final String name;
    public final float x, y, z;
    public final int health, maximumHealth;

    public NativeMonsterSpawn(String monsterId, String theme, String name,
            float x, float y, float z, int health, int maximumHealth) {
        if(monsterId == null || monsterId.isEmpty() || monsterId.getBytes(StandardCharsets.UTF_8).length > 64
                || theme == null || theme.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES
                || name == null || name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES
                || !finite(x) || !finite(y) || !finite(z)
                || maximumHealth < 1 || health < 0 || health > maximumHealth) {
            throw new IllegalArgumentException("Invalid native monster spawn.");
        }
        this.monsterId = monsterId;
        this.theme = theme;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.health = health;
        this.maximumHealth = maximumHealth;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
