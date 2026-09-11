package com.interrupt.dungeoneer.gfx.animation;

import com.interrupt.dungeoneer.entities.Entity;

public abstract class AnimationAction {
	public AnimationAction() { }
	
    /** Opt-in only for callbacks that cannot mutate authoritative gameplay. */
    public boolean isPresentationOnly() { return false; }

	public abstract void doAction(Entity instigator);
}
