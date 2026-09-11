package com.interrupt.dungeoneer.entities.projectiles;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.collision.Collidor;
import com.interrupt.dungeoneer.entities.*;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.gfx.drawables.DrawableSprite;
import com.interrupt.dungeoneer.tiles.Tile;

import java.util.Random;

public class Projectile extends Entity {
	public int origtex;

	/** Projectile speed.  */
	public float speed = 0.3f;

	/** Damage amount. */
	public int damage = 3;

	/** Strength of knockback effect. */
	public float knockback = 0.1f;

	/** Projectile lifetime in milliseconds. */
	public Float destroyTimer = null;

	/** Destroy delay. */
	public Float destroyDelay = null;

	/** Damage type. */
	public DamageType damageType;

	/** Destroy projectile when it hits something? */
	public boolean destroyOnEntityHit = true;
	
	private transient boolean didCollide = false;
	private transient ProjectileImpactListener multiplayerImpactListener;
	private transient boolean multiplayerImpactReported = false;

	/** Hit decal. */
	public ProjectedDecal hitDecal = new ProjectedDecal(ArtType.sprite, 19, 0.6f);

	/** Sound to play when projectile hits something. */
	public String hitSound = "mg_green_impact_01.mp3,mg_green_impact_02.mp3,mg_green_impact_03.mp3,mg_green_impact_04.mp3";
	
	private boolean sweepCollision = true;
	
	private transient Array<Entity> hasHit = new Array<Entity>();

	/** Make particles when projectile hits something? */
	public boolean makeHitParticles = true;
	
	public Projectile() { canStepUpOn = false; }
	
	public Projectile(float x, float y, float z, int tex, float xa, float za, int damage, DamageType damageType, Entity owner) {
		super(x, y, tex, true);
		this.z = z + 0.1f;
		
		artType = ArtType.sprite;
		origtex = tex;
		
		floating = false;
		isSolid = false;
		
		this.xa = xa;
		this.ya = za;
		
		this.owner = owner;
		this.damage = damage;
		this.damageType = damageType;
		
		if(owner == null || (owner instanceof Player)) ignorePlayerCollision = true;
		
		collision.set(0.1f, 0.1f, 0.5f);

		canStepUpOn = false;
	}
	
	@Override
	public void tick(Level level, float delta)
	{
		// center projectiles within the collision box
		yOffset = -0.5f + collision.z / 2f;

		if(nativePresentationReplica) {
			onTick(delta);
			tickAttached(level, delta);
			return;
		}
		
		if(xa == 0 && ya == 0) return;
		
		if(destroyTimer != null) {
			if(destroyTimer > 0) {
				destroyTimer -= 1 * delta;
				onTick(delta);
			}
			else {
				onDestroy();
				isActive = false;
			}
			return;
		}
		
		// Add gravity
		if(!floating) za -= 0.0035f * delta;
		
		didCollide = false;
		float collisionSweeps = 1f;
		
		if(sweepCollision) {
			float moveMax = Math.max(Math.abs(za * delta), Math.max(Math.abs(xa * delta), Math.abs(ya * delta)));
			collisionSweeps = (collision.x * 2f) / moveMax;
            collisionSweeps = Math.round(1f / collisionSweeps);

            if(collisionSweeps != 0)
			    collisionSweeps = 1f / collisionSweeps;
            else
                collisionSweeps = 1;
		}
		
		for(float i = collisionSweeps; i <= 1; i += collisionSweeps) {
			
			float nextx = x + (xa * delta) * i;
			float nexty = y + (ya * delta) * i;
			float nextz = z + (za * delta) * i;
			
			if (level.isFree(nextx, nexty, nextz, collision, 0, false, null)) {
				Entity encroaching = level.checkEntityCollision(nextx, y, nextz, collision, null, this);

                if(encroaching instanceof Projectile && ((Projectile) encroaching).owner == owner) {
                    // ignore this
                    break;
                }
				else if(encroaching != null && owner != encroaching && !hasHit.contains(encroaching, true)) {
					encroached(encroaching);
					
					if(!encroaching.isDynamic || destroyOnEntityHit) {
						didCollide = true;
						break;
					}
				}
			}
			else {
				encroached(nextx, nexty);
				didCollide = true;
				break;
			}
		}

        lastZ = z;
		if(!didCollide) {
			x += xa * delta;
			y += ya * delta;
			z += za * delta;
		}
		
		if(isActive) {
            onTick(delta);

            // should we make a water splash?
            Tile cTile = level.getTile((int)Math.floor(x), (int)Math.floor(y));
            if(cTile.data.isWater && z < cTile.floorHeight + 0.32f)
            {
                if(lastZ >= cTile.floorHeight + 0.32f) {
                    splash(level, cTile.floorHeight + 0.5f, true, cTile);
                }
            }
        }
		
		// drawable stuff
		if(drawable != null && drawable instanceof DrawableSprite) {
			DrawableSprite drbl = (DrawableSprite) drawable;
			drbl.tex = tex;
			drbl.artType = artType;
			drbl.fullbrite = fullbrite;
			drbl.yOffset = yOffset;
			drbl.color = color;
		}
		
		if(drawable == null) {
			drawable = new DrawableSprite(tex, artType);
		}

		tickAttached(level, delta);
	}
	
	public void encroached(Player player)
	{
		if(isActive && player != owner) {
			notifyMultiplayerImpact(player, x, y, z + yOffset);
			hitEffect();
			player.hit(xa, ya, damage, knockback, damageType, owner);
			
			if(destroyOnEntityHit)
				destroy();
			else
				hasHit.add(player);
		}
	}
	
	public void encroached(Entity hit)
	{
		if(isActive && hit != owner)
		{	
			notifyMultiplayerImpact(hit, x, y, z + yOffset);
			hitEffect();
			hit.hit(xa, ya, damage, knockback, damageType, owner);
			
			if(destroyOnEntityHit || !(hit instanceof Actor))
				destroy();
			else
				hasHit.add(hit);
		}
	}
	
	@Override
	public void hit(float xa, float ya, int damage, float force, DamageType damageType, Entity instigator) {
		if(destroyOnEntityHit) {
			hitEffect();
			destroy();
		}
	}
	
	public void encroached(float hitx, float hity)
	{
		notifyMultiplayerImpact(null, hitx, hity, z + yOffset);
		hitEffect();
		destroy();
	}

	public void setMultiplayerImpactListener(ProjectileImpactListener listener) {
		if(multiplayerImpactListener == listener) return;
		multiplayerImpactListener = listener;
		multiplayerImpactReported = false;
	}

	public void clearMultiplayerImpactListener(ProjectileImpactListener listener) {
		if(multiplayerImpactListener == listener) multiplayerImpactListener = null;
	}

	private void notifyMultiplayerImpact(Entity hit, float impactX,
			float impactY, float impactZ) {
		if(multiplayerImpactReported || multiplayerImpactListener == null) return;
		multiplayerImpactReported = true;
		multiplayerImpactListener.onProjectileImpact(
				this, hit, impactX, impactY, impactZ);
	}
	
	// Override this for stuff like dynamic lighting
	public void onTick(float delta) { }
	
	// Override this to make explosion effects
	public void hitEffect()
	{
		if(!isActive) return;
		playImpactPresentation(Game.GetLevel());
	}

	/** Reuses native impact visuals on a non-simulating network replica. */
	public void playNetworkImpactPresentation(Level level, boolean entityHit,
			boolean secondaryExplosion) {
		if(nativePresentationReplica && level != null) playImpactPresentation(level);
	}

	protected void playImpactPresentation(Level level) {
		if(makeHitParticles) {
			Random r = new Random();
			int particleCount = 6;
			particleCount *= Options.instance.gfxQuality;
			
			for(int i = 0; i < particleCount; i++)
			{			
				level.SpawnNonCollidingEntity( new Particle(x - xa, y - ya, z + 0.1f - za * 2, r.nextFloat() * 0.01f - 0.005f, r.nextFloat() * 0.01f - 0.005f, r.nextFloat() * 0.01f - 0.005f, 0, Color.GRAY, false)) ;
			}
		}
		
		makeHitDecal();
	}

	protected void playElementalImpactRing(float hitX, float hitY) {
		Particle ring = new Particle(x, y, z, 0, 0, 0, 0, color, true);
		((DrawableSprite)ring.drawable).billboard = false;
		((DrawableSprite)ring.drawable).dir.set(hitX, za, hitY).nor();
		ring.xa = 0;
		ring.ya = 0;
		ring.za = 0;
		ring.artType = ArtType.particle;
		ring.tex = 16;
		ring.x = x;
		ring.y = y;
		ring.z = z;
		ring.lifetime = 20;
		ring.startScale = 0.1f;
		ring.fullbrite = true;
		ring.endScale = 8f;
		ring.scale = 0f;
		ring.floating = true;
		ring.yOffset = yOffset;
		ring.checkCollision = false;
		ring.color.set(color);
		ring.isActive = true;
		ring.initialized = false;
		ring.isDynamic = true;
		ring.haloMode = HaloMode.CORONA_ONLY;
		Game.instance.level.non_collidable_entities.add(ring);
	}
	
	public void makeHitDecal() {
		if(hitDecal != null) {
			
			Vector3 intersectionHolder = new Vector3();
			Vector3 normalHolder = new Vector3();
			
			// try to find the hit normal
			Ray ray = new Ray(new Vector3(x,z + yOffset,y), new Vector3((float)xa, (float)za, (float)ya).nor());
			if(Collidor.intersectRayTriangles(ray, GameManager.renderer.GetCollisionTrianglesAlong(ray, 2f), intersectionHolder, normalHolder)) {
				hitDecal.direction = new Vector3(normalHolder.x,normalHolder.z,normalHolder.y).scl(-1f);
				hitDecal.x = intersectionHolder.x + normalHolder.x * 0.1f;
				hitDecal.y = intersectionHolder.z + normalHolder.z * 0.1f;
				hitDecal.z = intersectionHolder.y + normalHolder.y * 0.1f;
				
				hitDecal.start = 0.001f;
				hitDecal.end = 0.5f;
				hitDecal.roll = Game.rand.nextFloat() * 360f;
			}
			else {
				hitDecal.direction = new Vector3((float)xa, (float)ya, (float)za).nor();
				hitDecal.x = x;
				hitDecal.y = y;
				hitDecal.z = z + yOffset;
				hitDecal.roll = Game.rand.nextFloat() * 360f;
				
				hitDecal.end = 1f;
				hitDecal.start = 0.01f;
			}
			
			Game.instance.level.entities.add(hitDecal);
		}
	}
	
	public void destroy() {
		isSolid = false;
		if(destroyDelay != null) destroyTimer = destroyDelay;
		else {
			onDestroy();
			isActive = false;
		}
	}

    @Override
    public void updateDrawable() {
        yOffset = -0.5f + collision.z / 2f;
        super.updateDrawable();
    }
	
	// Override this to hide lights and stuff
	public void onDestroy() {
		
	}
}
