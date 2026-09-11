package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.statuseffects.StatusEffect;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One Actor's recoverable effects, sent reliably within one bounded TCP frame. */
public final class ActorEffectsSnapshot {
    public static final int MAX_EFFECTS = NativeStatusEffectState.Kind.values().length;
    public final String monsterId;
    public final long sequence;
    public final boolean invisible, floating;
    public final float flightSpeed;
    public final NativeAnimationState animation;
    public final float drunk, actorTimeScale, worldTimeScale;
    public final List<NativeStatusEffectState> effects;

    public ActorEffectsSnapshot(String monsterId, long sequence, boolean invisible,
            List<NativeStatusEffectState> effects) {
        this(monsterId, sequence, invisible, effects, 0f);
    }

    public ActorEffectsSnapshot(String monsterId, long sequence, boolean invisible,
            List<NativeStatusEffectState> effects, float drunk) {
        this(monsterId, sequence, invisible, effects, drunk, 1f, 1f);
    }

    public ActorEffectsSnapshot(String monsterId, long sequence, boolean invisible,
            List<NativeStatusEffectState> effects, float drunk, float actorTimeScale, float worldTimeScale) {
        this(monsterId, sequence, invisible, effects, drunk, actorTimeScale, worldTimeScale, false, 0f);
    }

    public ActorEffectsSnapshot(String monsterId, long sequence, boolean invisible,
            List<NativeStatusEffectState> effects, float drunk, float actorTimeScale, float worldTimeScale,
            boolean floating, float flightSpeed) {
        this(monsterId, sequence, invisible, effects, drunk, actorTimeScale, worldTimeScale, floating, flightSpeed, null);
    }

    public ActorEffectsSnapshot(String monsterId, long sequence, boolean invisible,
            List<NativeStatusEffectState> effects, float drunk, float actorTimeScale, float worldTimeScale,
            boolean floating, float flightSpeed, NativeAnimationState animation) {
        this.animation = animation;
        if(Float.isNaN(flightSpeed) || Float.isInfinite(flightSpeed) || flightSpeed < 0)
            throw new IllegalArgumentException("Invalid native flight speed.");
        this.floating = floating; this.flightSpeed = flightSpeed;
        if(!positiveFinite(actorTimeScale) || !positiveFinite(worldTimeScale))
            throw new IllegalArgumentException("Invalid native time scale.");
        this.actorTimeScale = actorTimeScale; this.worldTimeScale = worldTimeScale;
        if(Float.isNaN(drunk) || Float.isInfinite(drunk) || drunk < 0)
            throw new IllegalArgumentException("Invalid native drunk presentation.");
        this.drunk = drunk;
        if(monsterId == null || monsterId.isEmpty()
                || monsterId.getBytes(StandardCharsets.UTF_8).length > 64
                || sequence < 1 || effects == null || effects.size() > MAX_EFFECTS) {
            throw new IllegalArgumentException("Invalid monster effect snapshot.");
        }
        Set<Long> ids = new HashSet<Long>();
        Set<NativeStatusEffectState.Kind> kinds = new HashSet<NativeStatusEffectState.Kind>();
        for(NativeStatusEffectState effect : effects) {
            if(effect == null || !ids.add(effect.instanceId) || !kinds.add(effect.kind)) {
                throw new IllegalArgumentException("Duplicate native status identity or class.");
            }
        }
        this.monsterId = monsterId; this.sequence = sequence; this.invisible = invisible;
        this.effects = Collections.unmodifiableList(new ArrayList<NativeStatusEffectState>(effects));
    }

    public ActorEffectsSnapshot withSequence(long value) {
        return new ActorEffectsSnapshot(monsterId, value, invisible, effects, drunk,
                actorTimeScale, worldTimeScale, floating, flightSpeed, animation);
    }

    private static boolean positiveFinite(float value) {
        return value > 0 && !Float.isInfinite(value) && !Float.isNaN(value);
    }

    public static float worldTimeScale(Actor actor) {
        float scale = 1f;
        if(actor.usesPlayerTime() && actor.hp > 0 && actor.isActive && actor.statusEffects != null)
            for(StatusEffect effect : actor.statusEffects)
                if(effect.active && effect instanceof com.interrupt.dungeoneer.statuseffects.SlowTimeEffect)
                    scale = Math.min(scale, ((com.interrupt.dungeoneer.statuseffects.SlowTimeEffect)effect).getWorldTimeMod());
        return scale;
    }

    public static ActorEffectsSnapshot capture(String id, long sequence, Actor monster) {
        List<NativeStatusEffectState> effects = new ArrayList<NativeStatusEffectState>();
        if(monster.hp > 0 && monster.isActive && monster.statusEffects != null) {
            for(StatusEffect effect : monster.statusEffects) {
                if(effect.active) effects.add(NativeStatusEffectState.capture(effect));
            }
        }
        return new ActorEffectsSnapshot(id, sequence,
                monster.hp > 0 && monster.isActive && monster.invisible, effects,
                monster.hp > 0 && monster.isActive ? Math.max(0, monster.drunkMod) : 0,
                monster.hp > 0 && monster.isActive ? monster.actorTimeScale : 1f, worldTimeScale(monster),
                monster.hp > 0 && monster.isActive && monster.floating, Math.max(0, monster.stats.SPD * 0.1f),
                monster instanceof com.interrupt.dungeoneer.entities.Monster
                        ? ((com.interrupt.dungeoneer.entities.Monster)monster).captureNativeAnimation() : null);
    }

    public boolean sameState(ActorEffectsSnapshot other) {
        if(other == null || invisible != other.invisible || floating != other.floating || flightSpeed != other.flightSpeed || drunk != other.drunk || actorTimeScale != other.actorTimeScale
                || worldTimeScale != other.worldTimeScale || effects.size() != other.effects.size()) return false;
        if(animation == null ? other.animation != null : !animation.sameState(other.animation)) return false;
        for(int i = 0; i < effects.size(); i++) {
            NativeStatusEffectState a = effects.get(i), b = other.effects.get(i);
            if(a.instanceId != b.instanceId || a.kind != b.kind || a.remaining != b.remaining || a.elapsed != b.elapsed
                    || a.pulses != b.pulses || a.fieldOfView != b.fieldOfView || a.speed != b.speed
                    || a.particles != b.particles || !a.shader.equals(b.shader)) return false;
        }
        return true;
    }
}
