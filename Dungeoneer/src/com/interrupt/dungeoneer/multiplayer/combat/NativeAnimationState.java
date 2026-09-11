package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.gfx.animation.SpriteAnimation;

/** Recoverable native animation cursor; no animation actions or content cross the wire. */
public final class NativeAnimationState {
    public enum Kind { NONE, WALK, HURT, CAST, ATTACK, RANGED, DODGE, DEATH }
    public final Kind kind;
    public final long instanceId;
    public final float time;
    public final boolean playing, looping;
    public final int texture;

    public NativeAnimationState(Kind kind, long instanceId, float time,
            boolean playing, boolean looping, int texture) {
        if(kind == null || instanceId < 0 || Float.isNaN(time) || Float.isInfinite(time)
                || time < 0 || texture < -1 || texture > 65535)
            throw new IllegalArgumentException("Invalid native animation cursor.");
        this.kind = kind; this.instanceId = instanceId; this.time = time;
        this.playing = playing; this.looping = looping; this.texture = texture;
    }

    public static NativeAnimationState capture(Kind kind, SpriteAnimation animation, int texture) {
        return new NativeAnimationState(kind, animation == null ? 0 : animation.getPlaybackId(),
                animation == null ? 0 : Math.max(0, animation.getPlaybackTime()),
                animation != null && animation.playing, animation != null && animation.looping, texture);
    }

    public boolean sameState(NativeAnimationState other) {
        return other != null && kind == other.kind && instanceId == other.instanceId
                && time == other.time && playing == other.playing && looping == other.looping && texture == other.texture;
    }
}
