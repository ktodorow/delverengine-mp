package com.interrupt.dungeoneer.entities.projectiles;

import com.interrupt.dungeoneer.entities.Entity;

/** Transient observer for Host-authoritative projectile presentation. */
public interface ProjectileImpactListener {
    void onProjectileImpact(Entity projectile, Entity hit,
            float impactX, float impactY, float impactZ);

    /** A Missile broke on impact; observers spawn only the broken-arrow presentation. */
    default void onProjectileBreak(Entity projectile) { }
}
