package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.statuseffects.StatusEffect;
import java.util.*;

/** Render-thread reconciliation. Never enters addStatusEffect/tick/onStatusBegin gameplay. */
public final class NativeStatusPresentation {
    private final Map<Long, StatusEffect> active = new LinkedHashMap<Long, StatusEffect>();
    private long sequence;
    private boolean originalFloating;
    private boolean initialized;
    private final Set<Long> playedStarts = new HashSet<Long>();

    public boolean contains(long instanceId) { return active.containsKey(instanceId); }

    public void playStart(Actor actor, NativeStatusCue cue) {
        if(cue.kind == NativeStatusCue.Kind.PULSE && contains(cue.instanceId))
            active.get(cue.instanceId).playPulsePresentation(actor);
        else if(contains(cue.instanceId)) playStart(actor, cue.instanceId);
        else cue.play(actor);
    }

    public void playStart(Actor actor, long instanceId) {
        StatusEffect effect = active.get(instanceId);
        if(effect != null && playedStarts.add(instanceId)) effect.playStartPresentation(actor);
    }

    public void apply(Actor actor, ActorEffectsSnapshot snapshot) {
        if(snapshot.sequence <= sequence) return;
        if(!initialized) { originalFloating = actor.floating; initialized = true; }
        sequence = snapshot.sequence;
        Set<Long> retained = new HashSet<Long>();
        Array<StatusEffect> ordered = new Array<StatusEffect>();
        for(NativeStatusEffectState state : snapshot.effects) {
            retained.add(state.instanceId);
            StatusEffect effect = active.get(state.instanceId);
            boolean added = effect == null;
            if(added) { effect = state.createPresentation(); active.put(state.instanceId, effect); }
            float elapsed = added ? 0 : Math.max(0, state.elapsed - effect.multiplayerElapsed);
            effect.multiplayerElapsed = state.elapsed;
            state.apply(effect);
            if(added) effect.beginPresentation(actor);
            // Advance only by observed Host effect time. Pause/duplicate packets do not advance it.
            effect.tickPresentation(actor, elapsed);
            ordered.add(effect);
        }
        Iterator<Map.Entry<Long, StatusEffect>> it = active.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<Long, StatusEffect> entry = it.next();
            if(!retained.contains(entry.getKey())) { entry.getValue().endPresentation(actor); it.remove(); }
        }
        playedStarts.retainAll(retained);
        actor.statusEffects = ordered.size == 0 ? null : ordered;
        actor.invisible = snapshot.invisible;
        actor.floating = snapshot.floating;
        actor.drunkMod = snapshot.drunk;
        actor.actorTimeScale = snapshot.actorTimeScale;
    }

    public void updateAttachments(Actor actor) {
        for(StatusEffect effect : active.values()) effect.updatePresentationAttachment(actor);
    }

    public void clear(Actor actor) {
        for(StatusEffect effect : active.values()) effect.endPresentation(actor);
        active.clear(); playedStarts.clear(); actor.statusEffects = null; actor.invisible = false; actor.floating = actor.hp > 0 && originalFloating; actor.drunkMod = 0; actor.actorTimeScale = 1f;
    }
}
