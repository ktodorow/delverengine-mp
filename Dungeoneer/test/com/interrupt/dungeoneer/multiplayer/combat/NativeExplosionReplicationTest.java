package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.*;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.game.*;
import org.junit.*;
import org.objenesis.ObjenesisStd;
import static org.junit.Assert.*;

public class NativeExplosionReplicationTest {
    private Game previousGame;
    private Options previousOptions;

    @Before public void setup() {
        previousGame = Game.instance; previousOptions = Options.instance;
        Game.instance = new ObjenesisStd().newInstance(Game.class);
        Game.instance.player = new Player();
        Game.instance.level = new Level(4, 4);
        Options.instance = new Options(); Options.instance.graphicsDetailLevel = 1;
    }
    @After public void restore() { Game.instance = previousGame; Options.instance = previousOptions; }

    @Test public void nativeExplosionDamagesOnceWhileTwoObserversOnlyRenderOriginalEffects() throws Exception {
        final int[] hits = {0};
        Entity target = new Entity() {
            @Override public void hit(float x, float y, int damage, float force, DamageType type, Entity source) {
                hits[0]++; assertTrue(damage > 0);
            }
        };
        target.x = 0.1f; target.isSolid = true;
        Game.instance.level.spatialhash.AddEntity(target);
        Explosion host = new Explosion(); host.damage = 20; host.explodeSound = null;
        host.color = new Color(0.1f, 0.4f, 0.9f, 1); host.explosionStartTex = 5; host.explosionEndTex = 7;
        host.particleCount = 3;
        NativeExplosionPresentation state = NativeExplosionPresentation.capture(host, 1);
        host.explode(Game.instance.level, 1);
        assertEquals(1, hits[0]);
        assertTrue(target.xa != 0);
        float impulse = target.xa;
        int hostCount = Game.instance.level.non_collidable_entities.size;
        assertEquals(5, hostCount); // Three particles, original animated sprite, original dynamic light.
        for(int observer = 0; observer < 2; observer++) {
            Game.instance.level.non_collidable_entities.clear();
            state.replay(Game.instance.level);
            assertEquals(1, hits[0]); assertEquals(impulse, target.xa, 0);
            assertEquals(hostCount, Game.instance.level.non_collidable_entities.size);
            Particle animation = (Particle)Game.instance.level.non_collidable_entities.get(3);
            assertEquals(host.color, animation.color);
            assertTrue(Game.instance.level.non_collidable_entities.get(4) instanceof DynamicLight);
        }
        Explosion reconstructed = state.create();
        assertEquals(0, reconstructed.damage, 0); assertNull(reconstructed.spawns);
        assertNull(reconstructed.applyStatusEffect); assertNull(reconstructed.owner);
    }

    @Test public void rejectedReplicaExplosionCannotCauseVisualsDamageOrSpawns() {
        Game.instance.level.nativeExplosionListener = (explosion, amount) -> false;
        Explosion replica = new Explosion() {
            @Override protected void applyGameplay(Level level) { fail("Replica damage"); }
            @Override public void spawnStuff(Level level) { fail("Replica secondary spawn"); }
        };
        replica.explode(Game.instance.level, 1);
        assertEquals(0, Game.instance.level.non_collidable_entities.size);
    }

    @Test public void presentationCannotRunSecondarySpawnEvenWithNativeDefinitionAttached() {
        Explosion replica = new Explosion() {
            @Override protected void applyGameplay(Level level) { fail("Presentation damage"); }
            @Override public void spawnStuff(Level level) { fail("Presentation secondary spawn"); }
        };
        replica.explodeSound = null;
        replica.playPresentation(Game.instance.level, 1);
        assertTrue(Game.instance.level.non_collidable_entities.size > 0);
    }

    @Test public void clientBombCannotDestroyItselfOrSpawnSecondaryGameplay() {
        Game.instance.level.nativeExplosionListener = new NativeExplosionListener() {
            @Override public boolean isSimulationAuthority() { return false; }
            @Override public boolean onExplosion(Explosion explosion, float amount) { return false; }
        };
        com.interrupt.dungeoneer.entities.items.FusedBomb bomb = new com.interrupt.dungeoneer.entities.items.FusedBomb();
        bomb.explode(Game.instance.level);
        assertTrue("Only Host destruction may retire bomb identity", bomb.isActive);
        new Bomb().explode(Game.instance.level);
        assertEquals(0, Game.instance.level.entities.size);
        assertEquals(0, Game.instance.level.non_collidable_entities.size);
    }

    @Test public void hostDestructionLeavesTombstoneAndCannotBeMovedOrRepeated() {
        com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld world = new com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld();
        com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState item = world.spawn("native-bomb", null, 1, 1, 0);
        world.destroyWorldItem(item.entityId);
        com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState destroyed = world.get(item.entityId);
        assertTrue(destroyed.consumed);
        world.move(item.entityId, 2, 2, 0); world.destroyWorldItem(item.entityId);
        assertSame(destroyed, world.get(item.entityId));
    }

    @Test public void snapshotIsImmutableAndRejectsInvalidVisualParameters() throws Exception {
        Explosion nativeExplosion = new Explosion();
        nativeExplosion.color = new Color(Color.BLUE);
        NativeExplosionPresentation snapshot = NativeExplosionPresentation.capture(nativeExplosion, 1);
        nativeExplosion.color.set(Color.RED);
        byte[] bytes = snapshot.bytes(); bytes[0] = 127;
        assertEquals(Color.BLUE, snapshot.create().color);
        nativeExplosion.particleCount = Integer.MAX_VALUE;
        try { NativeExplosionPresentation.capture(nativeExplosion, 1); fail("Unbounded particles"); }
        catch(IllegalArgumentException expected) { }
        try { NativeExplosionPresentation.decode(new byte[901]); fail("Unbounded payload"); }
        catch(java.io.IOException expected) { }
    }

    @Test public void fusedBombChainReactionCarriesParticipantSourceAndFuseState() {
        com.interrupt.dungeoneer.entities.items.FusedBomb bomb =
                new com.interrupt.dungeoneer.entities.items.FusedBomb();
        bomb.artType = Entity.ArtType.item; bomb.countdownTimer = 80f; bomb.timerStart = 100f;
        Entity source = new com.interrupt.dungeoneer.entities.Fire();
        source.multiplayerDamageSource = "campaign-slot-2";

        bomb.hit(1f, 0f, 0, 0f, DamageType.LIGHTNING, source);

        assertEquals("campaign-slot-2", bomb.multiplayerDamageSource);
        assertTrue(bomb.isLit); assertTrue(bomb.countdownTimer <= 5f);
        NativeDynamicState state = NativeDynamicState.capture(9L, 0L, bomb);
        com.interrupt.dungeoneer.entities.items.FusedBomb observer =
                (com.interrupt.dungeoneer.entities.items.FusedBomb)state.apply(null);
        assertTrue(observer.isLit); assertEquals(bomb.countdownTimer, observer.countdownTimer, 0f);
        observer.tick(Game.instance.level, 100f);
        assertTrue("Observer fuse cannot run gameplay", observer.isActive);
    }

    @Test public void fusedBombFizzleUsesLiveNativeCueWithoutRepublishingGameplay() {
        final java.util.List<NativeDynamicCue> cues = new java.util.ArrayList<NativeDynamicCue>();
        com.interrupt.dungeoneer.entities.items.FusedBomb bomb =
                new com.interrupt.dungeoneer.entities.items.FusedBomb();
        bomb.artType = Entity.ArtType.item; bomb.x = 1f; bomb.y = 1f;
        Game.instance.level.nativeDynamicListener = value ->
                cues.add(NativeDynamicCue.captureFizzle(12L, 0L, value));

        bomb.fizzle(Game.instance.level);
        assertEquals(1, cues.size());
        int nativeParticles = Game.instance.level.non_collidable_entities.size;
        Game.instance.level.non_collidable_entities.clear();
        cues.get(0).replay(Game.instance.level);

        assertEquals(nativeParticles, Game.instance.level.non_collidable_entities.size);
        assertEquals("Replica fizzle cannot publish another cue", 1, cues.size());
    }

    @Test public void floorCheckpointFreezesNativeBombProjectileAndEffectsWithoutRefiringExplosion() {
        com.interrupt.dungeoneer.entities.items.FusedBomb bomb =
                new com.interrupt.dungeoneer.entities.items.FusedBomb();
        bomb.artType = Entity.ArtType.item; bomb.countdownTimer = 77f; bomb.isLit = true;
        bomb.multiplayerIdentity = "item:44"; bomb.multiplayerDamageSource = "campaign-slot-2";
        Game.instance.level.entities.add(bomb);
        com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile projectile =
                new com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile();
        projectile.multiplayerIdentity = "projectile:45"; projectile.xa = 0.16f;
        assertTrue("Native missile opts into persistent floor state", projectile.persists);
        Game.instance.level.entities.add(projectile);
        Explosion explosion = new Explosion(); explosion.explodeSound = null;
        explosion.particleCount = 3;
        explosion.playPresentation(Game.instance.level, 1);
        assertEquals(5, Game.instance.level.non_collidable_entities.size);
        for(int i = 0; i < 5; i++) {
            Entity effect = Game.instance.level.non_collidable_entities.get(i);
            assertTrue("Native explosion effect opts into floor persistence", effect.persists);
            effect.multiplayerIdentity = "effect:" + i;
        }

        byte[] checkpoint = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.capture(Game.instance.level);
        Level returned = com.interrupt.dungeoneer.multiplayer.floor.NativeFloorSave.restore(checkpoint);
        assertEquals("Native persistent projectile freezes with floor", 2, returned.entities.size);
        assertEquals("Native persistent visuals return as state, without another explosion", 5,
                returned.non_collidable_entities.size);
        for(int i = 0; i < 5; i++) assertEquals("effect:" + i,
                returned.non_collidable_entities.get(i).multiplayerIdentity);
        com.interrupt.dungeoneer.entities.items.FusedBomb saved =
                (com.interrupt.dungeoneer.entities.items.FusedBomb)returned.entities.first();
        assertEquals(77f, saved.countdownTimer, 0f); assertTrue(saved.isLit);
        assertEquals("item:44", saved.multiplayerIdentity);
        assertEquals("campaign-slot-2", saved.multiplayerDamageSource);
        assertEquals("projectile:45", returned.entities.get(1).multiplayerIdentity);
        assertEquals(0.16f, returned.entities.get(1).xa, 0f);
        assertEquals("Checkpoint does not tick or destroy live bomb", 77f, bomb.countdownTimer, 0f);
    }
}
