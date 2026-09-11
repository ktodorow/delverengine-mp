package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.statuseffects.*;
import java.nio.charset.StandardCharsets;

/** Accepted native effect instance. No executable content or gameplay callback crosses wire. */
public final class NativeStatusEffectState {
    public enum Kind { BASE, BURNING, DRUNK, INVISIBLE, PARALYZE, POISON,
        RESTORE, SHIELD, SLOW, LEVITATE, SPEED, SLOW_TIME }
    public final long instanceId;
    public final Kind kind;
    public final float remaining, speed, elapsed, fieldOfView;
    public final long pulses;
    public final String shader;
    public final boolean particles;

    public NativeStatusEffectState(long instanceId, Kind kind, float remaining,
            float speed, String shader, boolean particles) {
        this(instanceId, kind, remaining, speed, shader, particles, 0);
    }

    public NativeStatusEffectState(long instanceId, Kind kind, float remaining,
            float speed, String shader, boolean particles, float elapsed) {
        this(instanceId, kind, remaining, speed, shader, particles, elapsed, 1f);
    }

    public NativeStatusEffectState(long instanceId, Kind kind, float remaining,
            float speed, String shader, boolean particles, float elapsed, float fieldOfView) {
        this(instanceId, kind, remaining, speed, shader, particles, elapsed, fieldOfView, 0);
    }

    public NativeStatusEffectState(long instanceId, Kind kind, float remaining,
            float speed, String shader, boolean particles, float elapsed, float fieldOfView,
            long pulses) {
        if(pulses < 0) throw new IllegalArgumentException("Invalid native effect pulse count.");
        this.pulses = pulses;
        if(!finite(fieldOfView) || fieldOfView <= 0) throw new IllegalArgumentException("Invalid native field of view.");
        this.fieldOfView = fieldOfView;
        if(!finite(elapsed) || elapsed < 0) throw new IllegalArgumentException("Invalid native effect elapsed time.");
        this.elapsed = elapsed;
        if(instanceId < 1 || kind == null || !finite(remaining) || remaining < 0
                || !finite(speed) || speed < 0 || shader == null
                || shader.getBytes(StandardCharsets.UTF_8).length > 32) {
            throw new IllegalArgumentException("Invalid native status presentation state.");
        }
        this.instanceId = instanceId; this.kind = kind; this.remaining = remaining;
        this.speed = speed; this.shader = shader; this.particles = particles;
    }

    public static NativeStatusEffectState capture(StatusEffect effect) {
        Kind kind;
        Class<?> type = effect.getClass();
        if(type == StatusEffect.class) kind = Kind.BASE;
        else if(type == BurningEffect.class) kind = Kind.BURNING;
        else if(type == DrunkEffect.class) kind = Kind.DRUNK;
        else if(type == InvisibilityEffect.class) kind = Kind.INVISIBLE;
        else if(type == ParalyzeEffect.class) kind = Kind.PARALYZE;
        else if(type == PoisonEffect.class) kind = Kind.POISON;
        else if(type == RestoreHealthEffect.class) kind = Kind.RESTORE;
        else if(type == ShieldEffect.class) kind = Kind.SHIELD;
        else if(type == SlowEffect.class) kind = Kind.SLOW;
        else if(type == LevitateEffect.class) kind = Kind.LEVITATE;
        else if(type == SpeedEffect.class) kind = Kind.SPEED;
        else if(type == SlowTimeEffect.class) kind = Kind.SLOW_TIME;
        else throw new IllegalArgumentException("Unsupported native status class: " + type.getName());
        return new NativeStatusEffectState(effect.getMultiplayerInstanceId(), kind,
                Math.max(0, effect.timer), effect.speedMod,
                effect.shader == null ? "" : effect.shader, effect.showParticleEffect,
                effect.multiplayerElapsed, effect.getFieldOfViewMod(),
                effect.getMultiplayerPulseCount());
    }

    /** Constructors with world side effects must use their presentation-safe entry point. */
    public StatusEffect createPresentation() {
        switch(kind) {
            case BURNING: return new BurningEffect();
            case DRUNK: return new DrunkEffect();
            case INVISIBLE: return new InvisibilityEffect();
            case PARALYZE: return new ParalyzeEffect(false);
            case POISON: return new PoisonEffect();
            case RESTORE: return new RestoreHealthEffect();
            case SHIELD: return new ShieldEffect();
            case SLOW: return new SlowEffect();
            case LEVITATE: return new LevitateEffect();
            case SPEED: return new SpeedEffect();
            case SLOW_TIME: return new SlowTimeEffect();
            default: return new StatusEffect();
        }
    }

    public void apply(StatusEffect effect) {
        effect.setPresentationFieldOfViewMod(fieldOfView);
        effect.timer = remaining; effect.speedMod = speed;
        effect.shader = shader.isEmpty() ? null : shader;
        effect.showParticleEffect = particles; effect.active = true;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
