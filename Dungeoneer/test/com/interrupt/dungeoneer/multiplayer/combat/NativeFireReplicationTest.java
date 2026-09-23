package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Fire;
import com.interrupt.dungeoneer.game.Level;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** Bomb-spawned fire replicates once as a presentation-only world fire; floor fires never do. */
public class NativeFireReplicationTest {
    private static Fire spawnedFire(Level level) {
        Fire fire = new Fire();
        fire.randomLifeTime = 0f;
        fire.lifeTime = 300f;
        fire.scaleMod = 1.5f;
        fire.x = 2.5f; fire.y = 3.5f; fire.z = 0.25f;
        // Fire.init marks Source.SPAWNED this way; init itself needs a renderer in this test.
        fire.setRuntimeSpawned(true);
        return fire;
    }

    @Test public void floorStartFiresAreNotReplicatedButRuntimeSpawnsAre() {
        Level level = new Level(4, 4);
        Fire placed = new Fire();
        assertFalse(NativeDynamicState.supports(placed));
        assertTrue(NativeDynamicState.supports(spawnedFire(level)));
    }

    @Test public void replicaIsPresentationOnlyAndBurnsOutOnItsOwnClock() {
        Level level = new Level(4, 4);
        Fire fire = spawnedFire(level);
        NativeDynamicState state = NativeDynamicState.capture(7L, 0L, fire);
        Entity replica = state.apply(null);
        assertTrue(replica instanceof Fire);
        Fire copy = (Fire)replica;
        assertNotSame(fire, copy);
        assertEquals(2.5f, copy.x, 0f);
        assertEquals(300f, copy.lifeTime, 0f);
        assertEquals(1.5f, copy.scaleMod, 0f);
        assertTrue(copy.burnsOut());
        assertTrue(copy.nativePresentationReplica);
        assertTrue(copy.isActive);
        assertEquals("replica lifetime must not re-roll on init", 0f, copy.randomLifeTime, 0f);
        int[] hits = { 0 };
        Entity victim = new Entity() {
            @Override public void hit(float x, float y, int damage, float force,
                    com.interrupt.dungeoneer.entities.items.Weapon.DamageType type, Entity source) { hits[0]++; }
        };
        victim.x = 2.5f; victim.y = 3.5f; victim.isActive = true;
        level.entities.add(victim);
        copy.burn(level);
        assertEquals("replica never burns", 0, hits[0]);
    }

    @Test public void animationAndShrinkingDoNotChurnState() {
        Level level = new Level(4, 4);
        Fire fire = spawnedFire(level);
        NativeDynamicState first = NativeDynamicState.capture(7L, 0L, fire);
        fire.tex = 5;
        fire.scale = 0.4f;
        fire.lifeTimeTimer = 120f;
        NativeDynamicState later = NativeDynamicState.capture(7L, 0L, fire);
        assertTrue(first.sameState(later));
        fire.isActive = false;
        assertFalse(first.sameState(NativeDynamicState.capture(7L, 0L, fire)));
    }
}
