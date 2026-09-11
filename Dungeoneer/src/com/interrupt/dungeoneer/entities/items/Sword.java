package com.interrupt.dungeoneer.entities.items;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.annotations.EditorProperty;
import com.interrupt.dungeoneer.entities.*;
import com.interrupt.dungeoneer.entities.triggers.BasicTrigger;
import com.interrupt.dungeoneer.game.CachePools;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation;

import java.util.Random;
import java.util.function.Predicate;

public class Sword extends Weapon {
	
	public Sword() { isSolid = true; attackAnimation = "swordAttack"; chargeAnimation = "swordCharge"; attackStrongAnimation = "swordAttackStrong"; equipSound = "/ui/ui_equip_item.mp3"; }
	
	public ProjectedDecal hitDecal = new ProjectedDecal(ArtType.sprite, 18, 1.0f);

	public Sword(float x, float y) {
		super(x, y, 8, ItemType.sword, "SWORD");
	}

	/** Sound played when Sword hits a wall */
	@EditorProperty
	public String wallHitSound = "clang.mp3,clang_02.mp3,clang_03.mp3,clang_04.mp3";

	/** Sound played when Sword is swung */
	@EditorProperty
	public String swingSound = "whoosh1.mp3,whoosh1_02.mp3,whoosh1_03.mp3,whoosh1_04.mp3";
	
	private float attackTimer = 0;
	private float lastTickTime = 0;
	private float attackPower = 0;
	
	private float hitTime = 10f;
	
	@Override
	public void doAttack(Player p, Level lvl, float attackPower) {
		this.attackPower = attackPower;

		if(p == null || p.handAnimation == null) {
			return;
		}

		p.setAttackSpeed(getSpeed());
		p.handAnimateTimer = (p.handAnimation.length() / p.handAnimation.speed) * 0.75f;
		
		attackTimer = 0;
		lastTickTime = 0;
		
		hitTime = (p.handAnimation.actionTime / p.handAnimation.speed) * 0.5f;
		
		Audio.playSound(swingSound, 0.25f, Game.rand.nextFloat() * 0.1f + 0.95f);
		Vector3 swingDirection = new Vector3(Game.camera.direction.x,
				Game.camera.direction.z, Game.camera.direction.y);
		notifyMeleePresentation(p, lvl, NativeMeleePresentation.Kind.SWING, null,
				new Vector3(p.x, p.y, p.z), swingDirection);
	}
	
	public void tickAttack(Player p, Level lvl, float time) {
		attackTimer += time;
		
		if(attackTimer >= hitTime && lastTickTime < hitTime) {
			Vector3 attackDir = new Vector3(Game.camera.direction);
			notifyWeaponAttack(p, attackDir, attackPower);
			if(p.deferWeaponWorldAttack()) {
				lastTickTime = attackTimer;
				return;
			}
			resolveAttack(p, p, lvl,
					new Vector3(attackDir.x, attackDir.z, attackDir.y),
					attackPower, false, entity -> true);
			}

			lastTickTime = attackTimer;
	}

	public static final class AttackResult {
		public final Entity target;
		public final NativeMeleePresentation.Kind kind;
		public final Vector3 position;
		public final Vector3 direction;

		private AttackResult(Entity target, NativeMeleePresentation.Kind kind,
				Vector3 position, Vector3 direction) {
			this.target = target;
			this.kind = kind;
			this.position = position;
			this.direction = direction;
		}
	}

	/** Host-side native Sword release for an accepted remote Participant attack. */
	public AttackResult resolveNetworkAttack(Entity owner, Player stats, Level lvl,
			Vector3 worldDirection, float power, Predicate<Entity> canAffect) {
		return resolveAttack(owner, stats, lvl, worldDirection, power, true, canAffect);
	}

	private AttackResult resolveAttack(Entity owner, Player stats, Level lvl,
			Vector3 worldDirection, float power, boolean networkAttack,
			Predicate<Entity> canAffect) {
		if(owner == null || stats == null || lvl == null || worldDirection == null
				|| worldDirection.len2() < 0.000001f) return null;
		Vector3 direction = worldDirection.cpy().nor();
		Entity near = null;
		float hitX = owner.x;
		float hitY = owner.y;
		float hitZ = owner.z;

		// Keep original Sword sweep distances and collision volume.
		for(int i = 1; i < 10 && near == null; i++) {
			float dstep = (i / 6.0f) * reach;
			float projx = direction.x * dstep;
			float projy = direction.y * dstep;
			float projz = direction.z * dstep;
			hitX = owner.x + projx;
			hitY = owner.y + projy;
			hitZ = owner.z + projz - 0.14f;
			near = lvl.checkEntityCollision(owner.x + projx, owner.y + projy,
					owner.z + projz + 0.4f, 0.2f, 0.2f, 0.2f, null, owner);
		}

		if(near != null) {
			if(canAffect != null && !canAffect.test(near)) return null;
			int attackroll = doAttackRoll(power, stats);
			near.hit(direction.x * reach, direction.y * reach, attackroll,
					power * (knockback + stats.getKnockbackStatBoost()),
					getDamageType(), owner);

			if(near instanceof Breakable) {
				if(networkAttack) ((Breakable)near).playNetworkHitPresentation(
						hitX, hitY, hitZ, this, lvl);
				else ((Breakable)near).doHitEffect(hitX, hitY, hitZ, this, lvl);
			}
			else if(near instanceof Door) {
				if(networkAttack) ((Door)near).playNetworkHitPresentation(
						hitX, hitY, hitZ, this, lvl);
				else ((Door)near).doHitEffect(hitX, hitY, hitZ, this, lvl);
			}
			else if(near instanceof BasicTrigger || !near.isDynamic) {
				if(networkAttack) playNetworkWorldHitPresentation(
						hitX, hitY, hitZ, lvl, direction);
				else doHitEffect(hitX, hitY, hitZ, lvl);
			}

			magicHitVfx(hitX, hitY, hitZ, lvl);
			NativeMeleePresentation.Kind kind = near instanceof BasicTrigger
					|| !near.isDynamic ? NativeMeleePresentation.Kind.WORLD_HIT
					: NativeMeleePresentation.Kind.ENTITY_HIT;
			if(near instanceof Breakable || near instanceof Door)
				kind = NativeMeleePresentation.Kind.ENTITY_HIT;
			AttackResult result = new AttackResult(near, kind,
					new Vector3(hitX, hitY, hitZ), direction);
			notifyMeleePresentation(owner, lvl, kind, near, result.position, direction);
			return result;
		}

		for(int i = 1; i < 10; i++) {
			float dstep = (i / 6.0f) * reach;
			float projx = direction.x * dstep;
			float projy = direction.y * dstep;
			float projz = direction.z * dstep;
			if(!lvl.isFree(owner.x + projx, owner.y + projy,
					owner.z + projz + 0.26f,
					new Vector3(0.15f, 0.15f, 0.25f), 0, false, null)) {
				hitX = owner.x + projx;
				hitY = owner.y + projy;
				hitZ = owner.z + projz - 0.14f;
				if(networkAttack) playNetworkWorldHitPresentation(
						hitX, hitY, hitZ, lvl, direction);
				else doHitEffect(hitX, hitY, hitZ, lvl);
				magicHitVfx(hitX, hitY, hitZ, lvl);
				wasUsed();
				AttackResult result = new AttackResult(null,
						NativeMeleePresentation.Kind.WORLD_HIT,
						new Vector3(hitX, hitY, hitZ), direction);
				notifyMeleePresentation(owner, lvl, result.kind, null,
						result.position, direction);
				return result;
			}
		}
		return null;
	}
	
	public void doHitEffect(float xLoc, float yLoc, float zLoc, Level lvl) {
		playWorldHitPresentation(xLoc, yLoc, zLoc, lvl,
				new Vector3(Game.camera.direction.x, Game.camera.direction.z,
						Game.camera.direction.y), false);
	}

	/** Original Sword wall feedback for observers, without first-person shake or gameplay. */
	public void playNetworkWorldHitPresentation(float xLoc, float yLoc, float zLoc,
			Level lvl, Vector3 direction) {
		playWorldHitPresentation(xLoc, yLoc, zLoc, lvl, direction, true);
	}

	/** Original elemental hit feedback for observers, without applying another hit. */
	public void playNetworkEntityHitPresentation(float xLoc, float yLoc, float zLoc,
			Level lvl) {
		magicHitVfx(xLoc, yLoc, zLoc, lvl);
	}

	public void playNetworkSwingPresentation(float xLoc, float yLoc, float zLoc) {
		Audio.playPositionedSound(swingSound, new Vector3(xLoc, yLoc, zLoc),
				0.25f, Game.rand.nextFloat() * 0.1f + 0.95f, 12f);
	}

	private void playWorldHitPresentation(float xLoc, float yLoc, float zLoc,
			Level lvl, Vector3 direction, boolean networkReplica) {
		if(networkReplica) {
			Audio.playPositionedSound(wallHitSound, new Vector3(xLoc, yLoc, zLoc),
					0.25f, Game.rand.nextFloat() * 0.1f + 0.95f, 12f);
		}
		else Audio.playSound(wallHitSound, 0.25f, Game.rand.nextFloat() * 0.1f + 0.95f);
		
		Color hitColor = getEnchantmentColor();
		boolean fullBright = getDamageType() != DamageType.PHYSICAL;
		
		if(fullBright) {
			// make a light at this location
			DynamicLight l = new DynamicLight(xLoc, yLoc, zLoc, new Vector3(hitColor.r * 0.85f, hitColor.g * 0.85f, hitColor.b * 0.85f));
			l.startLerp(new Vector3(0,0,0), 20, true);
			lvl.non_collidable_entities.add(l);
		}
		
		Random r = Game.rand;
		for(int ii = 0; ii < r.nextInt(5) + 3; ii++)
		{
			Particle p = CachePools.getParticle(xLoc, yLoc, zLoc + 0.6f, r.nextFloat() * 0.01f - 0.005f, r.nextFloat() * 0.01f - 0.005f, r.nextFloat() * 0.03f - 0.015f, 420 + r.nextInt(500), 1f, 0f, 0, hitColor, fullBright);
			p.movementRotateAmount = 10f;
			lvl.SpawnNonCollidingEntity(p);
		}

		// Make dust particle!
		for(int i = 0; i <= 1; i++) {
			Particle p = CachePools.getParticle(xLoc, yLoc, zLoc + 0.15f, 0, 0, 0, Game.rand.nextInt(3), Color.WHITE, false);

			// Randomize location a tad
			p.x += (0.15f * Game.rand.nextFloat()) - 0.075f;
			p.y += (0.15f * Game.rand.nextFloat()) - 0.075f;

			p.spriteAtlas = "dust_puffs";
			p.shader = "dust";
			p.checkCollision = false;
			p.floating = true;
			p.lifetime = (30 + 60 * i);
			p.startScale = 0.5f + (0.5f * Game.rand.nextFloat() - 0.25f);
			p.endScale = p.startScale;
			p.scale = 0.5f;
			p.endColor = new Color(1f, 1f, 1f, 0f);
			p.fullbrite = false;

			if(fullBright) {
				p.color.mul(hitColor);
				p.endColor.mul(hitColor);
				p.fullbrite = true;
				p.shader = "dust-fullbrite";
			}

			p.xa = (0.00125f * Game.rand.nextFloat());
			p.ya = (0.00125f * Game.rand.nextFloat());
			p.za = 0.004f;
			p.maxVelocity = 0.005f;

			lvl.SpawnNonCollidingEntity(p);
		}
		
		makeHitDecal(xLoc, yLoc, zLoc + 0.18f, direction, lvl);

		wallHitSpark(xLoc, yLoc, zLoc, lvl);
		
		if(!networkReplica && Game.instance != null && Game.instance.player != null) {
			Game.instance.player.shake(1.5f);
		}
	}
	
	public void makeHitDecal(float hitx, float hity, float hitz, Vector3 direction) {
		makeHitDecal(hitx, hity, hitz, direction, Game.GetLevel());
	}

	public void makeHitDecal(float hitx, float hity, float hitz, Vector3 direction,
			Level lvl) {
		if(hitDecal != null) {
			ProjectedDecal proj = new ProjectedDecal(hitDecal.artType, hitDecal.tex, hitDecal.decalWidth);
			proj.x = hitx;
			proj.y = hity;
			proj.z = hitz;
			proj.direction = direction.cpy();
			proj.roll = Game.rand.nextFloat() * 360f;
			
			proj.end = 0.6f;
			proj.start = 0.01f;
			proj.isOrtho = true;
			
			lvl.entities.add(proj);
		}
	}

	private void notifyMeleePresentation(Entity owner, Level level,
			NativeMeleePresentation.Kind kind, Entity target, Vector3 position,
			Vector3 direction) {
		if(level != null && level.nativeMeleePresentationListener != null) {
			level.nativeMeleePresentationListener.onMeleePresentation(owner, this, kind,
					target, position.cpy(), direction.cpy());
		}
	}

	@Override
	public void bonkEntity(Entity hit, float speed) {
		if(hit == null) return;

		hit.hit(0, 0, doAttackRoll(1f, Game.instance.player), 0, getDamageType(), Game.instance.player);
		if(hit.isDynamic) {
			xa = 0;
			ya = 0;
		}

		if (hit instanceof Breakable) {
			((Breakable)hit).doHitEffect(x - 0.5f, y - 0.5f, z - 0.5f, this, Game.instance.level);
		}
		else if (hit instanceof Door) {
			((Door)hit).doHitEffect(x - 0.5f, y - 0.5f, z - 0.5f, this, Game.instance.level);
		}

		if(hit instanceof Player) {
			ignorePlayerCollision = true;
			Audio.playSound("hit.mp3,hit_02.mp3,hit_03.mp3,hit_04.mp3", speed * 6f);
		}
	}

	public void wallHitSpark(float xLoc, float yLoc, float zLoc, Level lvl) {
		if(getDamageType() == DamageType.PHYSICAL) {
			Color hitColor = Color.GRAY;
			Particle vfx = CachePools.getParticle(xLoc, yLoc, zLoc + 0.15f, 0, 0, 0, 4, Color.WHITE, true);
			vfx.spriteAtlas = "dust_puffs";
			vfx.shader = "spark";
			vfx.checkCollision = false;
			vfx.floating = true;
			vfx.lifetime = (8);
			vfx.startScale = 0.25f + (0.25f * Game.rand.nextFloat() - 0.25f);
			vfx.endScale = vfx.startScale;
			vfx.scale = 0.5f;
			vfx.color.set(1f, 1f, 1f, 0.25f);
			vfx.endColor = new Color(1f, 1f, 1f, 1.2f);
			vfx.fullbrite = false;
			vfx.color.mul(hitColor);
			vfx.endColor.mul(hitColor);
			vfx.fullbrite = true;
			lvl.SpawnNonCollidingEntity(vfx);
		}
	}

	public void magicHitVfx(float xLoc, float yLoc, float zLoc, Level lvl) {
		if(getDamageType() != DamageType.PHYSICAL) {
			Color hitColor = new Color(getEnchantmentColor());
			hitColor.mul(0.3f, 0.3f, 0.3f, 1f);
			Particle vfx = CachePools.getParticle(xLoc, yLoc, zLoc + 0.15f, 0, 0, 0.01f, 4, Color.WHITE, true);
			vfx.spriteAtlas = "vfx";
			vfx.shader = "fire";
			vfx.checkCollision = false;
			vfx.floating = true;
			vfx.lifetime = (30);
			vfx.startScale = 0.5f;
			vfx.endScale = 0.2f;
			vfx.scale = 0.5f;
			vfx.color.set(1f, 1f, 1f, 0.0325f);
			vfx.endColor = new Color(1f, 1f, 1f, 1f);
			vfx.fullbrite = false;
			vfx.color.mul(hitColor);
			vfx.endColor.mul(hitColor);
			vfx.fullbrite = true;
			vfx.playAnimation(0, 4, 30);
			lvl.SpawnNonCollidingEntity(vfx);
		}
	}
}
