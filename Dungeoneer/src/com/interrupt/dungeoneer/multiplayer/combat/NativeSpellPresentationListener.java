package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.spells.Spell;

/** Host hook for native cast feedback after gameplay accepts a spell release. */
public interface NativeSpellPresentationListener {
    void onSpellPresentation(Actor owner, Spell spell, Vector3 position, boolean zap);
}
