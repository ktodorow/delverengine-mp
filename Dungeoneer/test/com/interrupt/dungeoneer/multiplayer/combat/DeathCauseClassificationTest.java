package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Explosion;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Spikes;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.entities.projectiles.Projectile;
import com.interrupt.dungeoneer.statuseffects.BurningEffect;
import com.interrupt.dungeoneer.statuseffects.PoisonEffect;
import com.interrupt.dungeoneer.statuseffects.StatusEffect;
import com.interrupt.managers.StringManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;

public class DeathCauseClassificationTest {
    private HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings;
    private final Player player = new Player();

    @Before public void strings() {
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
    }

    @After public void restore() {
        StringManager.localizedStrings = previousStrings;
    }

    private static DeathCause classify(Player target, DamageType type, Entity instigator) {
        return DirectConnectCombatController.classifyCause(target, type, instigator, false);
    }

    @Test public void explosionAnywhereInTheOwnerChainIsAnExplosion() {
        Explosion explosion = new Explosion();
        assertEquals(DeathCause.EXPLOSION, classify(player, DamageType.PHYSICAL, explosion));
        Entity shard = new Entity();
        shard.owner = explosion;
        assertEquals(DeathCause.EXPLOSION, classify(player, DamageType.FIRE, shard));
    }

    @Test public void spikesAndReportedTrapsAreTraps() {
        assertEquals(DeathCause.TRAP, classify(player, DamageType.PHYSICAL, new Spikes()));
        assertEquals(DeathCause.TRAP,
                DirectConnectCombatController.classifyCause(player, DamageType.PHYSICAL, null, true));
    }

    @Test public void tileFireWithoutInstigatorIsLavaWhileIceIsFrostRegardlessOfSource() {
        assertEquals(DeathCause.LAVA, classify(player, DamageType.FIRE, null));
        assertEquals(DeathCause.FROST, classify(player, DamageType.ICE, null));
        assertEquals(DeathCause.FROST, classify(player, DamageType.ICE, new Explosion()));
    }

    @Test public void burningStatusTicksAreBurningButPoisonTicksAreOrdinaryCombat() {
        player.statusEffects = new Array<StatusEffect>();
        player.statusEffects.add(new PoisonEffect());
        assertEquals(DeathCause.COMBAT, classify(player, DamageType.PHYSICAL, null));
        BurningEffect burning = new BurningEffect();
        burning.active = true;
        player.statusEffects.add(burning);
        assertEquals(DeathCause.BURNING, classify(player, DamageType.PHYSICAL, null));
    }

    @Test public void monstersProjectilesAndParticipantsAreCombat() {
        assertEquals(DeathCause.COMBAT, classify(player, DamageType.PHYSICAL, new Entity()));
        assertEquals(DeathCause.COMBAT, classify(player, DamageType.MAGIC, new Projectile()));
        assertEquals(DeathCause.COMBAT, classify(player, DamageType.FIRE, new Entity()));
        assertEquals(DeathCause.COMBAT, classify(null, DamageType.POISON, null));
    }
}
