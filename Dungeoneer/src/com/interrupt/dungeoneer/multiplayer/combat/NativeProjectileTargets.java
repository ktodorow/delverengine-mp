package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;

/**
 * Host: remote Participants' bodies for native projectiles. Their Avatars stay out of the
 * Level's spatial hash, where native projectile collision looks, so without this a Monster's
 * magic missile or arrow flew through every client.
 */
public interface NativeProjectileTargets {
    /** First body a Projectile's box overlaps, by native Level.checkEntityCollision's test. */
    Entity collidingTarget(Entity projectile, float x, float y, float z,
            float widthX, float widthY, float height);

    /** Adds the bodies a Missile may hit to the candidates of its native line test. */
    void addLineTargets(Entity projectile, Array<Entity> candidates);
}
