package com.interrupt.dungeoneer.entities.items;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.annotations.EditorProperty;
import com.interrupt.dungeoneer.entities.DynamicLight;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.projectiles.Missile;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentationListener;
import com.interrupt.managers.ItemManager;
import com.interrupt.managers.StringManager;

import java.text.MessageFormat;

public class Bow extends Weapon {
	/** Distance a fully charged shot will travel. */
	@EditorProperty
	public int range = 4;

	/** Sound played when Bow is shot. */
	@EditorProperty
	public String fireSound = "bow.mp3,bow_02.mp3,bow_03.mp3,bow_04.mp3";

	public Bow() { super(0, 0, 15, ItemType.bow, StringManager.get("items.Bow.defaultName")); this.yOffset = 0.085f; attackAnimation = "bowAttack"; chargeAnimation = "bowCharge"; shadowType = ShadowType.BLOB;  }

	public Bow(float x, float y) {
		super(x, y, 15, ItemType.bow, StringManager.get("items.Bow.defaultName"));
	}

	public String GetInfoText() {
		return MessageFormat.format(StringManager.get("items.Bow.rangeText"), this.range) + "\n" + super.GetInfoText();
	}

	@Override
	public void doAttack(Player p, Level lvl, float attackPower) {
		Missile missile = p.hasAuthoritativeAttackAmmo()
				? p.takeAuthoritativeAttackAmmo() : getAmmo();

		if(missile == null) {
			Audio.playSound("ui/ui_noammo_bow.mp3", 0.4f);
			return;
		}

		float power = configureMissile(missile, p, p, attackPower);
        Vector3 direction = setMissileDirectionAndPosition(missile, power, p);
		notifyWeaponAttack(p, direction, attackPower);
		if(p.deferWeaponWorldAttack()) {
			Audio.playSound(fireSound, 0.25f);
			notifyRangedPresentation(p, lvl);
			return;
		}

		lvl.entities.add(missile);
		
		Audio.playSound(fireSound, 0.25f);
		notifyRangedPresentation(p, lvl);
	}

	private void notifyRangedPresentation(Entity owner, Level level) {
		NativeRangedPresentationListener listener = level == null
				? null : level.nativeRangedPresentationListener;
		if(listener != null) listener.onRangedPresentation(owner, this,
				new Vector3(owner.x, owner.y, owner.z));
	}

	/** Configure exact native ammo for a Host-accepted remote Bow release. */
	public void configureNetworkMissile(Missile missile, Player stats, Entity owner,
			Vector3 worldDirection, float attackPower) {
		float power = configureMissile(missile, stats, owner, attackPower);
		missile.SetPositionAndVelocity(new Vector3(owner.x, owner.y, owner.z),
				worldDirection.cpy().scl(power));
	}

	public void playNetworkFirePresentation(float x, float y, float z) {
		Audio.playPositionedSound(fireSound, new Vector3(x, y, z), 0.25f, 12f);
	}

	private float configureMissile(Missile missile, Player stats, Entity owner,
			float attackPower) {
		int damageRoll = doAttackRoll(attackPower, stats);
		if(damageRoll == 0) damageRoll = 1;
		float power = attackPower * (this.range / 4.0f) * 0.5f;
		missile.isActive = true;
		missile.isDynamic = true;
		missile.owner = owner;
		missile.damage = damageRoll;
		missile.damageType = this.getDamageType();
		missile.ignorePlayerCollision = true;
		missile.isOnFloor = false;
		missile.scale = 2.0f;
		missile.leaveTrail = false;
		missile.knockback = (this.knockback + stats.getKnockbackStatBoost()) * attackPower;

		Color hitColor = getEnchantmentColor();
		if(getDamageType() != DamageType.PHYSICAL) {
			DynamicLight light = new DynamicLight();
			light.lightColor.set(hitColor.r, hitColor.g, hitColor.b);
			light.range = 2.0f;
			missile.attach(light);
			missile.color = hitColor;
			missile.leaveTrail = true;
			missile.trailTimer = missile.trailInterval * 0.5f;
			missile.effectLifetime = 200f;
		}
		return power;
	}

	public Vector3 setMissileDirectionAndPosition(Missile missile, float power, Player p) {
        Vector3 dir = getCrosshairDirection(-0.35f);
        if(dir != null) {
            missile.SetPositionAndVelocity(new Vector3(x, y, z + 0.1f - 0.55f), new Vector3(dir.x * power, dir.z * power, dir.y * power));
        }
        else {
			dir = new Vector3(Game.camera.direction);
            missile.SetPositionAndVelocity(new Vector3(p.x, p.y, p.z), new Vector3(dir.x * power, dir.z * power, dir.y * power));
        }
		return dir;
    }

	public Item findAmmo() {
		for(int i = 0; i < Game.instance.player.inventory.size; i++) {
			Item check = Game.instance.player.inventory.get(i);
			if(check instanceof Missile) return check;
			else if(check instanceof ItemStack && ((ItemStack)check).item instanceof Missile) {
				ItemStack stack = (ItemStack)check;
				if(stack.count > 0) return check;
			}
		}
		return null;
	}
	
	public Missile getAmmo() {
		Item found = findAmmo();
		if(found != null) {
			if(found instanceof Missile) {
				Game.instance.player.removeFromInventory(found);
				return (Missile)found;
			}
			else if(found instanceof ItemStack && ((ItemStack)found).item instanceof Missile) {
				ItemStack stack = (ItemStack)found;
				if(stack.count > 0) {
					stack.count--;
					if(stack.count == 0) Game.instance.player.removeFromInventory(found);
					return (Missile) ItemManager.Copy(Missile.class, stack.item);
				}
			}
		}
		return null;
	}
	
	@Override
	public Integer getHeldTex() {
		Item found = findAmmo();
		int offset = 0;

		if(found != null)
			offset = (int)((Game.instance.player.attackCharge / Game.instance.player.attackChargeTime) * 3f) * 8;

		if(heldTex != null)
			return heldTex + offset;
		else
			return tex + offset;
	}
}
