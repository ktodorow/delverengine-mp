package com.interrupt.dungeoneer.multiplayer.combat;

import java.nio.charset.StandardCharsets;

/** Bounded cosmetic data copied from Host projectile, never an engine object graph. */
public final class ProjectileVisual {
    public final String atlas;
    public final int texture, rgba;
    public final float scale, speed;
    public final boolean additive, fullbrite;

    public ProjectileVisual(String atlas, int texture, int rgba, float scale, float speed,
            boolean additive, boolean fullbrite) {
        if(atlas == null || atlas.isEmpty() || atlas.getBytes(StandardCharsets.UTF_8).length > 128
                || texture < 0 || texture > 65535 || !finite(scale) || scale <= 0 || scale > 64
                || !finite(speed) || speed <= 0 || speed > 64) {
            throw new IllegalArgumentException("Invalid projectile presentation.");
        }
        this.atlas = atlas; this.texture = texture; this.rgba = rgba;
        this.scale = scale; this.speed = speed;
        this.additive = additive; this.fullbrite = fullbrite;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
