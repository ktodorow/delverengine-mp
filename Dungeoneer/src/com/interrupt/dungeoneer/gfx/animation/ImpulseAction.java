package com.interrupt.dungeoneer.gfx.animation;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.game.Game;

public class ImpulseAction extends AnimationAction {
	
	private Vector3 impulseVector = null;
	
	public ImpulseAction() { }
	
	public ImpulseAction(Vector3 impulse) {
		impulseVector = impulse;
	}

	@Override
	public void doAction(Entity instigator) {
		
		if(impulseVector == null) return;
		Entity target = instigator instanceof Monster
				? ((Monster)instigator).getAttackTarget() : Game.instance.player;
		if(target == null || !target.isActive) return;
		
		Vector2 dir = new Vector2(target.x, target.y)
				.sub(new Vector2(instigator.x, instigator.y));
		dir = dir.nor();
		
		Vector2 rotDir = new Vector2(impulseVector.x, impulseVector.y);
		rotDir.rotate(dir.angle());
		
		instigator.xa += rotDir.x;
		instigator.ya += rotDir.y;
		instigator.za += impulseVector.z;
	}
}
