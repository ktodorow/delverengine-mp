package com.interrupt.dungeoneer.multiplayer.combat;

/** A live status start inferred from ordered Host state, never from a recovery baseline. */
public final class NativeStatusCue {
    public enum Kind { START, PULSE }
    public final String monsterId;
    public final long instanceId;
    public final NativeStatusEffectState effect;
    public final Kind kind;
    public NativeStatusCue(String monsterId, NativeStatusEffectState effect) {
        this(monsterId, effect, Kind.START);
    }

    public NativeStatusCue(String monsterId, NativeStatusEffectState effect, Kind kind) {
        if(monsterId == null || monsterId.isEmpty() || effect == null || kind == null)
            throw new IllegalArgumentException("Invalid native status cue.");
        this.monsterId = monsterId; this.instanceId = effect.instanceId;
        this.effect = effect; this.kind = kind;
    }

    public void play(com.interrupt.dungeoneer.entities.Actor actor) {
        if(effect == null || actor == null || !actor.isActive || actor.hp <= 0) return;
        com.interrupt.dungeoneer.statuseffects.StatusEffect presentation = effect.createPresentation();
        effect.apply(presentation);
        if(kind == Kind.PULSE) presentation.playPulsePresentation(actor);
        else presentation.playStartPresentation(actor);
    }

    public NativeStatusCue(String monsterId, long instanceId) {
        if(monsterId == null || monsterId.isEmpty() || instanceId <= 0)
            throw new IllegalArgumentException("Invalid native status cue.");
        this.monsterId = monsterId; this.instanceId = instanceId;
        this.effect = null; this.kind = Kind.START;
    }
}
