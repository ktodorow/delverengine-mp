package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.*;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.statuseffects.*;
import com.interrupt.managers.StringManager;
import com.interrupt.dungeoneer.game.LocalizedString;
import org.junit.*;
import org.objenesis.ObjenesisStd;
import java.util.*;
import static org.junit.Assert.*;

public class NativeStatusReplicationTest {
    private Game previousGame;
    private HashMap<String, LocalizedString> previousStrings;

    @Before public void setup() {
        previousStrings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        previousGame = Game.instance;
        Game.instance = new ObjenesisStd().newInstance(Game.class);
        Game.instance.level = new Level(4, 4);
    }
    @After public void restore() {
        StringManager.localizedStrings = previousStrings; Game.instance = previousGame;
    }
    private Monster replica() {
        Monster monster = new Monster(); monster.setNetworkReplica(true);
        monster.applyNetworkState(20, 20, 1, 1, 0);
        return monster;
    }
    @Test public void recoveryRestoresCurrentDrunkAndCameraWithoutReplayingTimeRules() {
        Actor host = new Actor(); host.hp = host.maxHp = 20;
        host.drunkMod = 4.25f;
        SlowTimeEffect slow = new SlowTimeEffect();
        slow.setPresentationFieldOfViewMod(1.075f);
        host.statusEffects = new Array<StatusEffect>(); host.statusEffects.add(slow);
        ActorEffectsSnapshot state = ActorEffectsSnapshot.capture("participant:test", 1, host);
        Actor observer = new Actor(); observer.hp = 20; observer.actorTimeScale = 1f;
        NativeStatusPresentation presentation = new NativeStatusPresentation();
        presentation.apply(observer, state);
        assertEquals(4.25f, observer.drunkMod, 0);
        assertEquals(1.075f, observer.statusEffects.first().getFieldOfViewMod(), 0);
        assertEquals(1f, observer.actorTimeScale, 0);
        host.drunkMod = 0; host.statusEffects.clear();
        presentation.apply(observer, ActorEffectsSnapshot.capture("participant:test", 2, host));
        assertEquals(0, observer.drunkMod, 0);
        assertNull(observer.statusEffects);
        presentation.apply(observer, state);
        assertEquals(0, observer.drunkMod, 0);
        presentation.clear(observer);
        assertEquals(0, observer.drunkMod, 0);
    }

    @Test public void remotePersonalTickUsesCapturedWorldClockDespiteEarlierEffectMutation() throws Exception {
        java.lang.reflect.Field captured = Game.class.getDeclaredField("tickWorldTimeScale");
        captured.setAccessible(true); captured.setFloat(Game.instance, 0.4f);
        Game.instance.SetGameTimeScale(0.2f); // Earlier Actor changed next-frame scale.
        com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar avatar = new com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar(
                new com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor(1,
                        new com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId(1),
                        new com.interrupt.dungeoneer.multiplayer.participant.ParticipantId("campaign-slot-1"),
                        1, "One", "humanoid-1"));
        avatar.addStatusEffect(new StatusEffect());
        avatar.tick(Game.instance.level, 0.4f);
        assertEquals(1f, avatar.statusEffects.first().multiplayerElapsed, 0.00001f);
    }

    @Test public void remoteParticipantSlowTimeUsesPlayerRulesAndRecoversPersonalScale() {
        Game.instance.SetGameTimeScale(1f);
        Actor participant = new Actor() {
            @Override public boolean usesPlayerTime() { return true; }
        };
        participant.hp = participant.maxHp = 20;
        participant.addStatusEffect(new SlowTimeEffect());
        participant.tickStatusEffects(180);
        assertTrue(Game.instance.GetGameTimeScale() < 1f);
        assertEquals(1f, participant.actorTimeScale, 0);
        ActorEffectsSnapshot state = ActorEffectsSnapshot.capture("participant:remote", 1, participant);
        assertEquals(Game.instance.GetGameTimeScale(), state.worldTimeScale, 0);
        Actor observer = new Actor(); observer.hp = 20;
        new NativeStatusPresentation().apply(observer, state);
        assertEquals(participant.actorTimeScale, observer.actorTimeScale, 0);
        assertEquals(participant.statusEffects.first().getFieldOfViewMod(),
                observer.statusEffects.first().getFieldOfViewMod(), 0);
        participant.clearStatusEffects();
        assertEquals(1f, ActorEffectsSnapshot.worldTimeScale(participant), 0);
        assertEquals(1f, participant.actorTimeScale, 0);
    }

    @Test public void nativeHealKeepsDirectHealthRuleEvenWithMagicResistance() {
        Actor host = new Actor() {
            @Override public float getMagicResistModBoost() { return 0; }
        };
        host.hp = 1; host.maxHp = 8;
        new com.interrupt.dungeoneer.entities.spells.Heal().doCast(host, null, null);
        assertEquals(7, host.hp);
        new com.interrupt.dungeoneer.entities.spells.Heal().doCast(host, null, null);
        assertEquals(8, host.hp);
        host.setStatusEffectAuthority(false); host.hp = 1;
        new com.interrupt.dungeoneer.entities.spells.Heal().doCast(host, null, null);
        assertEquals(1, host.hp);
    }

    @Test public void participantDrunkRecoveryUsesNativeDecayBeforeEffectTick() {
        Actor owner = new Actor(); owner.hp = 20; owner.drunkMod = 8;
        owner.addStatusEffect(new DrunkEffect());
        owner.tickDrunkRecovery(2);
        owner.tickStatusEffects(2);
        assertEquals(6f - 2f * 0.02f + 2f * 0.025f, owner.drunkMod, 0.00001f);
    }

    @Test public void continuousRefreshStillAdvancesNativeObserverParticles() {
        Monster host = new Monster(); host.hp = host.maxHp = 20;
        host.addStatusEffect(new SlowEffect());
        Monster first = replica(), second = replica();
        ActorEffectsSnapshot initial = ActorEffectsSnapshot.capture("slime", 1, host);
        first.applyNetworkEffects(initial); second.applyNetworkEffects(initial);
        host.tickStatusEffects(70);
        host.addStatusEffect(new SlowEffect());
        ActorEffectsSnapshot refreshed = ActorEffectsSnapshot.capture("slime", 2, host);
        assertEquals(initial.effects.get(0).remaining, refreshed.effects.get(0).remaining, 0);
        int before = Game.instance.level.non_collidable_entities.size;
        first.applyNetworkEffects(refreshed); second.applyNetworkEffects(refreshed);
        assertEquals(before + 2, Game.instance.level.non_collidable_entities.size);
        first.applyNetworkEffects(refreshed);
        assertEquals(before + 2, Game.instance.level.non_collidable_entities.size);
    }

    @Test public void endedEffectRetainsAcceptedStartBurstWithoutRestoringGameplayState() {
        Monster observer = replica();
        PoisonEffect effect = new PoisonEffect();
        NativeStatusCue cue = new NativeStatusCue("slime", NativeStatusEffectState.capture(effect));
        observer.applyNetworkEffects(new ActorEffectsSnapshot("slime", 2, false,
                Collections.<NativeStatusEffectState>emptyList()));
        int particles = Game.instance.level.non_collidable_entities.size;
        observer.playNetworkStatusStart(cue);
        assertTrue(Game.instance.level.non_collidable_entities.size > particles);
        assertNull(observer.statusEffects);
        assertEquals(20, observer.hp);
    }

    @Test public void nativeIceDamageRefreshAndExpiryReachTwoObserversWithoutDamageReplay() {
        Monster host = new Monster(); host.hp = host.maxHp = 20;
        host.takeDamage(1, DamageType.ICE, null);
        assertTrue(host.statusEffects.first() instanceof SlowEffect);
        host.statusEffects.first().showParticleEffect = false;
        Monster first = replica(), second = replica();
        ActorEffectsSnapshot initial = ActorEffectsSnapshot.capture("slime", 1, host);
        first.applyNetworkEffects(initial); second.applyNetworkEffects(initial);
        for(Monster observer : Arrays.asList(first, second)) {
            assertEquals(host.getShader(), observer.getShader());
            assertTrue(observer.statusEffects.first() instanceof SlowEffect);
            assertEquals(0.5f, observer.statusEffects.first().speedMod, 0);
            assertEquals(20, observer.hp);
        }
        host.tickStatusEffects(120);
        ActorEffectsSnapshot elapsed = ActorEffectsSnapshot.capture("slime", 2, host);
        first.applyNetworkEffects(elapsed); second.applyNetworkEffects(elapsed);
        StatusEffect retained = first.statusEffects.first();
        first.applyNetworkEffects(initial); // late packet cannot restart duration
        assertEquals(380, retained.timer, 0);
        host.takeDamage(1, DamageType.ICE, null);
        ActorEffectsSnapshot refreshed = ActorEffectsSnapshot.capture("slime", 3, host);
        assertEquals(initial.effects.get(0).instanceId, refreshed.effects.get(0).instanceId);
        first.applyNetworkEffects(refreshed); second.applyNetworkEffects(refreshed);
        assertSame(retained, first.statusEffects.first());
        assertEquals(500, retained.timer, 0);
        host.tickStatusEffects(501); host.tickStatusEffects(1);
        ActorEffectsSnapshot expired = ActorEffectsSnapshot.capture("slime", 4, host);
        first.applyNetworkEffects(expired); second.applyNetworkEffects(expired);
        assertNull(first.statusEffects); assertNull(second.statusEffects);
        first.applyNetworkEffects(refreshed);
        assertNull(first.statusEffects);
    }

    @Test public void periodicPoisonPulseIsDistinctFromStartAndDoesNotReplayGameplay() {
        Actor host = new Actor(); host.hp = host.maxHp = 3;
        PoisonEffect poison = new PoisonEffect(1000, 10, 1, false);
        poison.showParticleEffect = true;
        host.statusEffects = new Array<StatusEffect>(); host.statusEffects.add(poison);
        NativeStatusEffectState initial = NativeStatusEffectState.capture(poison);
        poison.doTick(host, 11);
        NativeStatusEffectState pulsed = NativeStatusEffectState.capture(poison);
        assertEquals(2, host.hp); assertEquals(0, initial.pulses); assertEquals(1, pulsed.pulses);

        NativeStatusCue cue = new NativeStatusCue("slime", pulsed, NativeStatusCue.Kind.PULSE);
        assertEquals(NativeStatusCue.Kind.PULSE, cue.kind);
        assertEquals(poison.getMultiplayerInstanceId(), cue.instanceId);
        Monster observer = replica(); int particles = Game.instance.level.non_collidable_entities.size;
        cue.play(observer);
        assertEquals("Pulse presentation cannot damage replica", 20, observer.hp);
        assertTrue(Game.instance.level.non_collidable_entities.size > particles);

        poison.doTick(host, 11);
        assertEquals(1, host.hp); assertEquals(2, poison.getMultiplayerPulseCount());
        poison.doTick(host, 11);
        assertEquals("Non-lethal native poison emits no rejected pulse", 1, host.hp);
        assertEquals(2, poison.getMultiplayerPulseCount());
    }
    @Test public void nativeZeroDamageDoesNotInventFreezeAndRecoveryKeepsRemainingTime() {
        Monster host = new Monster(); host.hp = host.maxHp = 20;
        host.takeDamage(0, DamageType.ICE, null);
        assertTrue(ActorEffectsSnapshot.capture("slime", 1, host).effects.isEmpty());
        host.addStatusEffect(new SlowEffect(0.25f, 240));
        host.statusEffects.first().showParticleEffect = false;
        host.tickStatusEffects(90);
        Monster recovered = replica();
        recovered.applyNetworkEffects(ActorEffectsSnapshot.capture("slime", 2, host));
        assertEquals(150, recovered.statusEffects.first().timer, 0);
        assertEquals(0.25f, recovered.statusEffects.first().speedMod, 0);
        recovered.applyNetworkState(0, 20, 1, 1, 0);
        assertNull(recovered.statusEffects);
        recovered.applyNetworkEffects(ActorEffectsSnapshot.capture("slime", 3, host));
        assertNull(recovered.statusEffects);
    }
    @Test public void presentationCannotRunHealingDamageFloatingOrGlobalTimeCallbacks() {
        Monster observer = replica();
        observer.floating = false;
        List<NativeStatusEffectState> effects = new ArrayList<NativeStatusEffectState>();
        NativeStatusEffectState.Kind[] kinds = {NativeStatusEffectState.Kind.RESTORE,
            NativeStatusEffectState.Kind.DRUNK, NativeStatusEffectState.Kind.LEVITATE,
            NativeStatusEffectState.Kind.SLOW_TIME, NativeStatusEffectState.Kind.SHIELD,
            NativeStatusEffectState.Kind.SPEED, NativeStatusEffectState.Kind.POISON};
        for(int i = 0; i < kinds.length; i++) effects.add(new NativeStatusEffectState(i + 1,
                kinds[i], 900, 1, "", false));
        observer.applyNetworkEffects(new ActorEffectsSnapshot("monster", 1, false, effects));
        List<NativeStatusEffectState> advanced = new ArrayList<NativeStatusEffectState>();
        for(NativeStatusEffectState effect : effects) advanced.add(new NativeStatusEffectState(
                effect.instanceId, effect.kind, 100, effect.speed, effect.shader, false));
        observer.applyNetworkEffects(new ActorEffectsSnapshot("monster", 2, false, advanced));
        assertEquals(20, observer.hp); assertFalse(observer.floating);
        assertEquals(0, observer.drunkMod, 0); assertEquals(1, observer.actorTimeScale, 0);
    }
    @Test public void replicaUsesOriginalIceAndParalysisParticlesWithoutMovingOwnerOrCamera() {
        final List<Entity> particles = new ArrayList<Entity>();
        Game.instance.level = new Level(4, 4) {
            @Override public void SpawnNonCollidingEntity(Entity entity) { particles.add(entity); }
        };
        com.badlogic.gdx.graphics.PerspectiveCamera previousCamera = Game.camera;
        Game.camera = new com.badlogic.gdx.graphics.PerspectiveCamera();
        Game.camera.direction.set(1, 0, 0);
        try {
            Monster observer = replica(); observer.floating = true;
            NativeStatusEffectState slow = new NativeStatusEffectState(1,
                    NativeStatusEffectState.Kind.SLOW, 500, 0.5f, "magic-item-white", true);
            observer.applyNetworkEffects(new ActorEffectsSnapshot("slime", 1, false,
                    Collections.singletonList(slow), 0, 1, 1, true, 0.4f));
            observer.applyNetworkEffects(new ActorEffectsSnapshot("slime", 2, false,
                    Collections.singletonList(new NativeStatusEffectState(1, slow.kind, 430,
                            slow.speed, slow.shader, true, 70)), 0, 1, 1, true, 0.4f));
            assertEquals(1, particles.size());
            assertEquals(83, particles.get(0).tex); // Original SlowEffect ice particle.
            particles.clear();
            observer.applyNetworkEffects(new ActorEffectsSnapshot("slime", 3, false,
                    Collections.singletonList(new NativeStatusEffectState(2,
                            NativeStatusEffectState.Kind.PARALYZE, 200, 0, "", true)), 0, 1, 1, true, 0.4f));
            assertTrue(observer.floating);
            assertEquals(new com.badlogic.gdx.math.Vector3(1, 0, 0), Game.camera.direction);
            ParalyzeEffect paralyze = (ParalyzeEffect)observer.statusEffects.first();
            Particle ring = paralyze.effectParticle;
            assertNotNull(ring); assertTrue(particles.contains(ring));
            assertEquals("Recovering state must not replay impact burst", 1, particles.size());
            // Simulate traffic arriving after the originally observed remaining lifetime.
            ring.tick(Game.instance.level, 400);
            assertTrue("Ring cannot enter pool before Host removal", ring.isActive);
            observer.x = 3;
            paralyze.updatePresentationAttachment(observer);
            assertEquals(3, ring.x, 0);
            observer.playNetworkStatusStart(2);
            assertTrue("Live native paralysis start must include impact", particles.size() >= 3);
            int afterStart = particles.size();
            observer.playNetworkStatusStart(2);
            assertEquals("Duplicate start cannot replay impact", afterStart, particles.size());
            observer.applyNetworkEffects(new ActorEffectsSnapshot("slime", 4, false,
                    Collections.<NativeStatusEffectState>emptyList(), 0, 1, 1, true, 0.4f));
            assertFalse(ring.isActive); assertTrue(observer.floating);
        }
        finally { Game.camera = previousCamera; }
    }

    @Test public void nativeAreaAndLocalTicksCannotRefreshClearOrApplyReplicaEffects() {
        final Monster observer = replica();
        observer.applyNetworkEffects(new ActorEffectsSnapshot("slime", 1, false,
                Collections.singletonList(new NativeStatusEffectState(1,
                        NativeStatusEffectState.Kind.SLOW, 80, 0.5f, "magic-item-white", false))));
        com.interrupt.dungeoneer.entities.areas.StatusEffectArea area =
                new com.interrupt.dungeoneer.entities.areas.StatusEffectArea();
        area.effectType = StatusEffect.StatusEffectType.SLOW;
        area.statusEffectTime = 500;
        Level level = new Level(4, 4) {
            @Override public Array<Entity> getEntitiesEncroaching(Entity entity) {
                Array<Entity> actors = new Array<Entity>(); actors.add(observer); return actors;
            }
        };
        area.tick(level, 100);
        observer.tickStatusEffects(500);
        observer.clearStatusEffects();
        observer.addStatusEffect(new RestoreHealthEffect());
        assertEquals(1, observer.statusEffects.size);
        assertEquals(80, observer.statusEffects.first().timer, 0);
        assertEquals(20, observer.hp);
    }

    @Test public void replicaAnimationKeepsNativeSoundAndLightWithoutGameplayCallbacks() {
        final int[] calls = new int[3];
        Array<com.interrupt.dungeoneer.gfx.animation.AnimationAction> actions = new Array<>();
        actions.add(new com.interrupt.dungeoneer.gfx.animation.SoundAction() {
            @Override public void doAction(Entity owner) { calls[0]++; }
        });
        actions.add(new com.interrupt.dungeoneer.gfx.animation.LightAnimationAction() {
            @Override public void doAction(Entity owner) { calls[1]++; }
        });
        actions.add(new com.interrupt.dungeoneer.gfx.animation.DamageAction() {
            @Override public void doAction(Entity owner) { calls[2]++; }
        });
        HashMap<String, Array<com.interrupt.dungeoneer.gfx.animation.AnimationAction>> frames = new HashMap<>();
        frames.put("0", actions);
        com.interrupt.dungeoneer.gfx.animation.SpriteAnimation animation =
                new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(0, 3, 60, frames);
        animation.play(); animation.animatePresentation(1, new Entity());
        assertArrayEquals(new int[]{1, 1, 0}, calls);
        assertSame(frames, animation.actions);
        animation.play(); animation.animate(1, new Entity());
        assertArrayEquals(new int[]{2, 2, 1}, calls); // Native single-player path remains intact.
    }

    @Test public void nativeFrameCueIsLiveOnlyAndCursorRecoveryCannotReplayIt() {
        final List<NativeAnimationCue> cues = new ArrayList<>();
        Game.instance.level.nativeAnimationListener = (owner, action) -> cues.add(NativeAnimationCue.capture(owner, action));
        Array<com.interrupt.dungeoneer.gfx.animation.AnimationAction> actions = new Array<>();
        actions.add(new com.interrupt.dungeoneer.gfx.animation.LightAnimationAction());
        HashMap<String, Array<com.interrupt.dungeoneer.gfx.animation.AnimationAction>> frames = new HashMap<>(); frames.put("0", actions);
        com.interrupt.dungeoneer.gfx.animation.SpriteAnimation animation =
                new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(0, 2, 60, frames);
        animation.play(); animation.animate(1, new Entity());
        assertEquals(1, cues.size());
        int lights = Game.instance.level.entities.size;
        animation.applyPresentationCursor(1, true, false, new Entity(), false);
        animation.applyPresentationCursor(1, true, false, new Entity(), false);
        assertEquals(lights, Game.instance.level.entities.size);
        assertEquals(1, cues.size());
        cues.get(0).replay();
        assertEquals(lights + 1, Game.instance.level.entities.size);
        assertEquals("Replay cannot republish", 1, cues.size());
    }

    @Test public void nativeAnimationRecoveryPauseAndInterruptionUseHostCursor() throws Exception {
        Monster host = new Monster(); host.hp = host.maxHp = 20;
        Monster first = replica(), second = replica();
        for(Monster monster : Arrays.asList(host, first, second)) {
            java.lang.reflect.Field walk = Monster.class.getDeclaredField("walkAnimation"); walk.setAccessible(true);
            walk.set(monster, new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(10, 19, 100, null));
            java.lang.reflect.Field hurt = Monster.class.getDeclaredField("hurtAnimation"); hurt.setAccessible(true);
            hurt.set(monster, new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(30, 39, 100, null));
        }
        java.lang.reflect.Field walk = Monster.class.getDeclaredField("walkAnimation"); walk.setAccessible(true);
        com.interrupt.dungeoneer.gfx.animation.SpriteAnimation nativeWalk =
                (com.interrupt.dungeoneer.gfx.animation.SpriteAnimation)walk.get(host);
        nativeWalk.loop(); nativeWalk.animate(55, host);
        ActorEffectsSnapshot walking = ActorEffectsSnapshot.capture("slime", 1, host);
        first.applyNetworkEffects(walking); second.applyNetworkEffects(walking);
        assertEquals(host.tex, first.tex); assertEquals(host.tex, second.tex);
        first.tick(Game.instance.level, 25); second.tick(Game.instance.level, 75);
        assertEquals("No local phase drift during pause", host.tex, first.tex);
        assertEquals(host.tex, second.tex);
        java.lang.reflect.Field hurt = Monster.class.getDeclaredField("hurtAnimation"); hurt.setAccessible(true);
        com.interrupt.dungeoneer.gfx.animation.SpriteAnimation nativeHurt =
                (com.interrupt.dungeoneer.gfx.animation.SpriteAnimation)hurt.get(host);
        nativeHurt.play(); nativeHurt.animate(25, host);
        ActorEffectsSnapshot interrupted = ActorEffectsSnapshot.capture("slime", 2, host);
        first.applyNetworkEffects(interrupted); second.applyNetworkEffects(interrupted);
        assertEquals(host.tex, first.tex); assertEquals(host.tex, second.tex);
        first.applyNetworkEffects(walking);
        assertEquals("Stale cursor cannot restart walk", host.tex, first.tex);
        Monster reconnect = replica(); hurt.set(reconnect, new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(30, 39, 100, null));
        reconnect.applyNetworkEffects(interrupted);
        assertEquals(host.tex, reconnect.tex);
    }

    @Test public void reconnectDuringDeathRestoresCorpseCursorWithoutReplayingDeathBurst()
            throws Exception {
        Monster host = new Monster(); host.hp = 0; host.maxHp = 20;
        java.lang.reflect.Field death = Monster.class.getDeclaredField("dieAnimation");
        death.setAccessible(true);
        death.set(host, new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(50, 59, 100, null));
        Corpse hostCorpse = new Corpse(host);
        hostCorpse.tick(Game.instance.level, 45);
        java.lang.reflect.Field corpse = Monster.class.getDeclaredField("multiplayerCorpse");
        corpse.setAccessible(true); corpse.set(host, hostCorpse);
        ActorEffectsSnapshot state = ActorEffectsSnapshot.capture("slime", 7, host);
        assertEquals(NativeAnimationState.Kind.DEATH, state.animation.kind);

        Monster reconnect = new Monster(); reconnect.maxHp = reconnect.hp = 20;
        death.set(reconnect, new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(50, 59, 100, null));
        reconnect.setNetworkReplica(true);
        reconnect.applyNetworkState(0, 20, 2, 3, 0, false);
        reconnect.applyNetworkEffects(state);
        int particles = Game.instance.level.non_collidable_entities.size;
        reconnect.tick(Game.instance.level, 1);
        Corpse recovered = reconnect.getMultiplayerCorpse();
        assertNotNull(recovered);
        assertEquals(hostCorpse.tex, recovered.tex);
        assertEquals("Recovery cannot replay death particles", particles,
                Game.instance.level.non_collidable_entities.size);
        recovered.tick(Game.instance.level, 30);
        assertEquals("Recovered corpse phase follows Host cursor", hostCorpse.tex, recovered.tex);
    }

    @Test public void nativeFirePresentationCannotBurnOrSpreadEvenWhenCalledDirectly() {
        Fire visual = new Fire(); visual.setPresentationOnly();
        // Null level would fail if either gameplay path were entered.
        visual.burn(null); visual.spread(null); visual.spreadTo(new Breakable(), null);
    }
    @Test public void hiddenStateAndRemovalUseHostState() {
        Monster observer = replica();
        observer.applyNetworkEffects(new ActorEffectsSnapshot("monster", 1, true,
                Collections.<NativeStatusEffectState>emptyList()));
        assertTrue(observer.invisible);
        observer.applyNetworkEffects(new ActorEffectsSnapshot("monster", 2, false,
                Collections.<NativeStatusEffectState>emptyList()));
        assertFalse(observer.invisible);
    }
}
