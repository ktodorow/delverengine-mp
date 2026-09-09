package com.interrupt.dungeoneer.multiplayer.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CombatPresentationEffectTest {
    @Test
    public void rangedPresentationTravelsToHostImpactThenExpiresWithoutCollision() {
        CombatPresentationEffect effect = new CombatPresentationEffect(event(
                1L, "participant:campaign-slot-2", CombatAction.SPELL));

        assertFalse(effect.isSolid);
        assertFalse(effect.persists);
        assertEquals(1f, effect.x, 0f);
        assertEquals(2f, effect.y, 0f);
        assertEquals(0.5f, effect.z, 0f);

        for(int i = 0; i < 64 && effect.isTravelling(); i++) effect.tick(null, 1f);

        assertEquals(5f, effect.x, 0f);
        assertEquals(6f, effect.y, 0f);
        assertEquals(0.25f, effect.z, 0f);
        assertTrue(effect.isActive);

        for(int i = 0; i < 64 && effect.isActive; i++) effect.tick(null, 1f);

        assertFalse(effect.isActive);
    }

    private CombatPresentationEvent event(long sequence, String sourceId,
            CombatAction action) {
        return new CombatPresentationEvent(sequence, 30L, sourceId,
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, action, CombatPresentationPhase.ATTACK,
                1f, 2f, 0.5f, 5f, 6f, 0.25f, true);
    }
}
