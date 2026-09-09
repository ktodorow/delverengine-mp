package com.interrupt.dungeoneer.gfx.animation;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.spells.Spell;

public class SpellCastAction extends AnimationAction {
	
	private Spell spell;
	
	public SpellCastAction() { }
	public SpellCastAction(Spell spell) {
		this.spell = spell;
	}
	
	public void setSpell(Spell spell) { 
		this.spell = spell;
	}

	@Override
	public void doAction(Entity instigator) {
		if(instigator instanceof Monster) {
			Monster m = (Monster)instigator;
			Entity target = m.getAttackTarget();
			if(target == null || !target.isActive) return;
			
			Vector3 dir = new Vector3(target.x, target.z, target.y)
					.sub(m.x, m.z + m.projectileOffset, m.y).nor();
			spell.cast(m, dir);
		}
	}
}
