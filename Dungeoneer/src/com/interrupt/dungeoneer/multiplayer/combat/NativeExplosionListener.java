package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.Explosion;

/** Called on native simulation thread before an explosion has any world consequences. */
public interface NativeExplosionListener {
    default boolean isSimulationAuthority() { return true; }
    default void addTargets(Explosion explosion, com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Entity> targets) { }
    default boolean canAffect(Explosion explosion, com.interrupt.dungeoneer.entities.Entity target) { return true; }
    boolean onExplosion(Explosion explosion, float particleAmountMod);
}
