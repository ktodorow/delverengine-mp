package com.interrupt.dungeoneer.multiplayer.network;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue;
import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.DirectConnectCombatController;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.managers.StringManager;
import com.interrupt.dungeoneer.game.LocalizedString;

import com.interrupt.dungeoneer.multiplayer.combat.CombatRequest;
import com.interrupt.dungeoneer.multiplayer.combat.ProjectileVisual;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationPhase;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.participant.SharedPartyProgression;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantCharacterState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantContext;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.combat.AuthoritativeCombatEncounter;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.ReconnectTokenStore;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.Message;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectWire.ServerRejected;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DirectConnectIntegrationTest {
    private static final long TIMEOUT_MILLIS = 8000L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int campaignStoreCounter;

    @Test public void nativeParticipantDamageUsesResistanceStatusAndHealingForBothRoles() throws Exception {
        HashMap<String, LocalizedString> strings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        DirectConnectCompatibility compatibility = compatibility("native-participant");
        HostFixture fixture = host(compatibility, 3, "native-participant");
        DirectConnectClient first = null, second = null;
        try {
            first = approveClient(fixture, compatibility, '2', "Two", AvatarCatalog.HUMANOID_2);
            second = approveClient(fixture, compatibility, '3', "Three", AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(first, DirectConnectPhase.READY); awaitPhase(second, DirectConnectPhase.READY);
            DirectConnectCombatController controller = new DirectConnectCombatController(fixture.host, false);
            for(MovementEntityDescriptor descriptor : fixture.host.getMovementEntities()) {
                com.interrupt.dungeoneer.entities.Actor target;
                if(descriptor.getEntityId().equals(fixture.host.getLocalMovementEntityId())) {
                    com.interrupt.dungeoneer.entities.Player player = new com.interrupt.dungeoneer.entities.Player();
                    player.hp = player.maxHp = 8; player.calculatedStats.Recalculate(player);
                    player.setHealthAuthorityListener(controller); target = player;
                }
                else {
                    com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar avatar =
                            new com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar(descriptor);
                    avatar.setDamageAuthorityListener(controller); target = avatar;
                }
                String id = AuthoritativeCombatEncounter.participantTargetId(descriptor.getParticipantId());
                target.takeDamage(3, DamageType.ICE, null);
                assertTrue(target.statusEffects.first() instanceof com.interrupt.dungeoneer.statuseffects.SlowEffect);
                assertEquals(5, target.hp);
                awaitCombatHealth(first, id, 5); awaitCombatHealth(second, id, 5);
                awaitEffects(first, id, 500); awaitEffects(second, id, 500);
                for(DirectConnectClient observer : java.util.Arrays.asList(first, second)) {
                    com.interrupt.dungeoneer.entities.Actor replica = applyParticipantObserver(observer, descriptor);
                    assertEquals(target.getShader(), replica.getShader());
                    assertEquals(0.5f, replica.statusEffects.first().speedMod, 0);
                    assertEquals(5, replica.hp);
                    assertFalse(replica.hasStatusEffectAuthority());
                }
                com.interrupt.dungeoneer.entities.Explosion healing = new com.interrupt.dungeoneer.entities.Explosion();
                target.takeDamage(2, DamageType.HEALING, healing);
                assertEquals("Healing explosion must heal", 7, target.hp);
                awaitCombatHealth(first, id, 7); awaitCombatHealth(second, id, 7);
                com.interrupt.dungeoneer.statuseffects.StatusEffect shield = new com.interrupt.dungeoneer.statuseffects.StatusEffect();
                shield.damageMod = 0.5f; target.addStatusEffect(shield);
                target.takeDamage(4, DamageType.PHYSICAL, null);
                assertEquals("Native shield multiplier runs once", 5, target.hp);
                awaitCombatHealth(first, id, 5); awaitCombatHealth(second, id, 5);
                fixture.host.setSessionPaused(true);
                target.takeDamage(2, DamageType.HEALING, healing);
                assertEquals("Shared pause blocks mutation", 5, target.hp);
                fixture.host.setSessionPaused(false);
            }
        } finally {
            if(first != null) first.close(); if(second != null) second.close(); fixture.close();
            StringManager.localizedStrings = strings;
        }
    }

    private com.interrupt.dungeoneer.entities.Actor applyParticipantObserver(DirectConnectClient peer,
            MovementEntityDescriptor target) throws Exception {
        com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController movement =
                new com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController(peer);
        java.lang.reflect.Field avatarsField = movement.getClass().getDeclaredField("remoteAvatars");
        avatarsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<NetworkEntityId, com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar> avatars =
                (Map<NetworkEntityId, com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar>)avatarsField.get(movement);
        for(MovementEntityDescriptor descriptor : peer.getMovementEntities()) {
            if(!descriptor.getEntityId().equals(peer.getLocalMovementEntityId())) avatars.put(descriptor.getEntityId(),
                    new com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar(descriptor));
        }
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, movement, false);
        com.interrupt.dungeoneer.entities.Player player = new com.interrupt.dungeoneer.entities.Player();
        java.lang.reflect.Method attach = controller.getClass().getDeclaredMethod("attachToPlayer", com.interrupt.dungeoneer.entities.Player.class);
        attach.setAccessible(true); attach.invoke(controller, player);
        java.lang.reflect.Method apply = controller.getClass().getDeclaredMethod("applySnapshot", CombatSnapshot.class);
        apply.setAccessible(true); apply.invoke(controller, peer.getCombatSnapshot());
        return target.getEntityId().equals(peer.getLocalMovementEntityId()) ? player : avatars.get(target.getEntityId());
    }

    @Test public void nativeExplosionTraversesControllerAndTcpToTwoObservers() throws Exception {
        com.interrupt.dungeoneer.game.Game previousGame = com.interrupt.dungeoneer.game.Game.instance;
        com.interrupt.dungeoneer.game.Options previousOptions = com.interrupt.dungeoneer.game.Options.instance;
        DirectConnectCompatibility compatibility = compatibility("native-explosion");
        HostFixture fixture = host(compatibility, 3, "native-explosion");
        DirectConnectClient first = null, second = null;
        DirectConnectCombatController controller = new DirectConnectCombatController(fixture.host, false);
        try {
            first = approveClient(fixture, compatibility, '2', "Two", AvatarCatalog.HUMANOID_2);
            second = approveClient(fixture, compatibility, '3', "Three", AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(first, DirectConnectPhase.READY); awaitPhase(second, DirectConnectPhase.READY);
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd().newInstance(com.interrupt.dungeoneer.game.Game.class);
            com.interrupt.dungeoneer.game.Game.instance = game;
            game.player = new com.interrupt.dungeoneer.entities.Player();
            game.level = new com.interrupt.dungeoneer.game.Level(4, 4);
            com.interrupt.dungeoneer.game.Options.instance = new com.interrupt.dungeoneer.game.Options();
            com.interrupt.dungeoneer.game.Options.instance.graphicsDetailLevel = 1;
            java.lang.reflect.Method attach = DirectConnectCombatController.class.getDeclaredMethod("attachToLevel", com.interrupt.dungeoneer.game.Level.class);
            attach.setAccessible(true); attach.invoke(controller, game.level);
            com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.gfx.animation.AnimationAction> actions = new com.badlogic.gdx.utils.Array<>();
            actions.add(new com.interrupt.dungeoneer.gfx.animation.LightAnimationAction());
            java.util.HashMap<String, com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.gfx.animation.AnimationAction>> frames = new java.util.HashMap<>();
            frames.put("0", actions);
            com.interrupt.dungeoneer.gfx.animation.SpriteAnimation animation =
                    new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(0, 2, 60, frames);
            animation.play(); animation.animate(1, game.player);
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                List<com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationCue> cues = new java.util.ArrayList<>();
                long cueDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
                while(cues.isEmpty() && System.currentTimeMillis() < cueDeadline) {
                    cues.addAll(observer.drainNativeAnimationCues()); Thread.sleep(5);
                }
                assertEquals("First native animation frame reaches each observer once", 1, cues.size());
                int entityCount = game.level.entities.size;
                cues.get(0).replay();
                assertEquals(entityCount + 1, game.level.entities.size);
                java.lang.reflect.Method deliverCue = DirectConnectClient.class.getDeclaredMethod("nativeAnimationCue", DirectConnectWire.NativeAnimationCueMessage.class);
                deliverCue.setAccessible(true);
                java.lang.reflect.Field cueSession = DirectConnectClient.class.getDeclaredField("sessionId"); cueSession.setAccessible(true);
                deliverCue.invoke(observer, new DirectConnectWire.NativeAnimationCueMessage((String)cueSession.get(observer), 1, cues.get(0), observer.getNativeWorldGeneration()));
                assertTrue(observer.drainNativeAnimationCues().isEmpty());
            }
            final int[] hits = {0};
            com.interrupt.dungeoneer.entities.Entity target = new com.interrupt.dungeoneer.entities.Entity() {
                @Override public void hit(float x, float y, int damage, float force, DamageType type, com.interrupt.dungeoneer.entities.Entity source) { hits[0]++; }
            };
            target.x = 0.1f; target.isSolid = true; game.level.spatialhash.AddEntity(target);
            com.interrupt.dungeoneer.entities.Explosion explosion = new com.interrupt.dungeoneer.entities.Explosion();
            explosion.damage = 8; explosion.explodeSound = null; explosion.particleCount = 2;
            explosion.color = new com.badlogic.gdx.graphics.Color(com.badlogic.gdx.graphics.Color.BLUE);
            explosion.explode(game.level, 1);
            assertEquals(1, hits[0]);
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                List<com.interrupt.dungeoneer.multiplayer.combat.NativeExplosionPresentation> received = new java.util.ArrayList<>();
                long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
                while(received.isEmpty() && System.currentTimeMillis() < deadline) {
                    received.addAll(observer.drainNativeExplosions()); Thread.sleep(5);
                }
                assertEquals(1, received.size());
                game.level.non_collidable_entities.clear(); received.get(0).replay(game.level);
                assertEquals(4, game.level.non_collidable_entities.size);
                assertEquals(com.badlogic.gdx.graphics.Color.BLUE, game.level.non_collidable_entities.get(2).color);
                assertEquals("Observer replay cannot repeat Host damage", 1, hits[0]);
                java.lang.reflect.Method deliver = DirectConnectClient.class.getDeclaredMethod("nativeExplosion", DirectConnectWire.NativeExplosionMessage.class);
                deliver.setAccessible(true);
                java.lang.reflect.Field session = DirectConnectClient.class.getDeclaredField("sessionId"); session.setAccessible(true);
                deliver.invoke(observer, new DirectConnectWire.NativeExplosionMessage((String)session.get(observer), 1, received.get(0), observer.getNativeWorldGeneration()));
                assertTrue("Duplicate reliable event cannot replay", observer.drainNativeExplosions().isEmpty());
            }
            long obsoleteGeneration = fixture.host.getNativeWorldGeneration();
            String monsterId = AuthoritativeCombatEncounter.monsterTargetId(1);
            ActorEffectsSnapshot effect = new ActorEffectsSnapshot(monsterId, 1, false,
                    java.util.Collections.singletonList(new NativeStatusEffectState(99,
                            NativeStatusEffectState.Kind.SLOW, 500, 0.5f, "magic-item-white", false)));
            fixture.host.synchronizeNativeActorEffects(effect);
            awaitEffects(first, monsterId, 500); awaitEffects(second, monsterId, 500);
            fixture.host.beginNativeWorld();
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
                while(observer.getNativeWorldGeneration() == obsoleteGeneration && System.currentTimeMillis() < deadline) Thread.sleep(5);
                assertEquals(obsoleteGeneration + 1, observer.getNativeWorldGeneration());
                assertTrue(observer.getActorEffects().isEmpty());
                java.lang.reflect.Field session = DirectConnectClient.class.getDeclaredField("sessionId"); session.setAccessible(true);
                String sessionId = (String)session.get(observer);
                java.lang.reflect.Method effects = DirectConnectClient.class.getDeclaredMethod("monsterEffects", DirectConnectWire.MonsterEffectsMessage.class);
                effects.setAccessible(true);
                effects.invoke(observer, new DirectConnectWire.MonsterEffectsMessage(sessionId, effect, true, obsoleteGeneration));
                assertTrue("Old floor cannot resurrect status", observer.getActorEffects().isEmpty());
                java.lang.reflect.Method deliver = DirectConnectClient.class.getDeclaredMethod("nativeExplosion", DirectConnectWire.NativeExplosionMessage.class);
                deliver.setAccessible(true);
                deliver.invoke(observer, new DirectConnectWire.NativeExplosionMessage(sessionId, 999,
                        com.interrupt.dungeoneer.multiplayer.combat.NativeExplosionPresentation.capture(explosion, 1), obsoleteGeneration));
                assertTrue("Old floor cannot replay explosion", observer.drainNativeExplosions().isEmpty());
                assertTrue(observer.drainNativeStatusCues().isEmpty());
            }
            assertTrue(fixture.host.drainNativeExplosions().isEmpty());
        }
        finally {
            controller.dispose(); if(first != null) first.close(); if(second != null) second.close(); fixture.close();
            com.interrupt.dungeoneer.game.Game.instance = previousGame;
            com.interrupt.dungeoneer.game.Options.instance = previousOptions;
        }
    }

    @Test public void nativeProjectileTravelAndCleanupReachTwoObservers() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("native-projectile-travel");
        HostFixture fixture = host(compatibility, 3, "native-projectile-travel");
        DirectConnectClient first = null, second = null;
        try {
            first = approveClient(fixture, compatibility, '2', "Two", AvatarCatalog.HUMANOID_2);
            second = approveClient(fixture, compatibility, '3', "Three", AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(first, DirectConnectPhase.READY); awaitPhase(second, DirectConnectPhase.READY);

            com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile projectile =
                    new com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile();
            projectile.x = 1f; projectile.y = 2f; projectile.z = 0.5f;
            projectile.xa = 0.25f; projectile.ya = 0.125f; projectile.floating = true;
            projectile.spriteAtlas = "magic-blue"; projectile.tex = 12;
            projectile.color.set(0.1f, 0.35f, 1f, 1f);
            fixture.host.synchronizeNativeDynamicState(
                    com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(
                            77L, 0L, projectile));
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState received =
                        awaitNativeDynamic(observer, 77L, true);
                com.interrupt.dungeoneer.entities.Entity replica = received.apply(null);
                assertEquals(1f, replica.x, 0f); assertEquals("magic-blue", replica.spriteAtlas);
                assertEquals(0.35f, replica.color.g, 0f); assertTrue(replica.nativePresentationReplica);
            }

            Thread.sleep(75);
            projectile.x = 3.5f; projectile.y = 2.75f;
            fixture.host.synchronizeNativeDynamicState(
                    com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(
                            77L, 0L, projectile));
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState received =
                        awaitNativeDynamicPosition(observer, 77L, 3.5f);
                assertEquals(2.75f, received.apply(null).y, 0f);
            }

            com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue impact =
                    com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue.captureImpact(
                            77L, 0L, projectile, true, 3.5f, 2.75f, 0.5f);
            fixture.host.publishNativeDynamicCue(impact);
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue received =
                        awaitNativeDynamicCue(observer);
                assertEquals(com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue.Kind.PROJECTILE_IMPACT,
                        received.kind);
                assertEquals("magic-blue", received.state.apply(null).spriteAtlas);
                assertTrue(received.entityHit);
            }

            fixture.host.synchronizeNativeDynamicState(
                    com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(
                            77L, 0L, projectile, false));
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                assertFalse(awaitNativeDynamic(observer, 77L, false).active);
                assertTrue(observer.getNativeDynamicStates().isEmpty());
            }

            java.lang.reflect.Field session = DirectConnectClient.class.getDeclaredField("sessionId");
            session.setAccessible(true);
            String sessionId = (String)session.get(first);
            java.lang.reflect.Method deliver = DirectConnectClient.class.getDeclaredMethod(
                    "nativeDynamicCue", DirectConnectWire.NativeDynamicCueMessage.class);
            deliver.setAccessible(true);
            long generation = first.getNativeWorldGeneration();
            deliver.invoke(first, new DirectConnectWire.NativeDynamicCueMessage(
                    sessionId, 1L, impact, generation));
            assertTrue("Duplicate cue cannot replay", first.drainNativeDynamicCues().isEmpty());
            deliver.invoke(first, new DirectConnectWire.NativeDynamicCueMessage(
                    sessionId, 3L, impact, generation));
            deliver.invoke(first, new DirectConnectWire.NativeDynamicCueMessage(
                    sessionId, 2L, impact, generation));
            assertEquals("Out-of-order cue cannot replay", 1, first.drainNativeDynamicCues().size());

            fixture.host.beginNativeWorld();
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(first.getNativeWorldGeneration() == generation && System.currentTimeMillis() < deadline)
                Thread.sleep(10L);
            assertTrue(first.getNativeWorldGeneration() > generation);
            deliver.invoke(first, new DirectConnectWire.NativeDynamicCueMessage(
                    sessionId, 999L, impact, generation));
            assertTrue("Old floor cue cannot replay", first.drainNativeDynamicCues().isEmpty());
        }
        finally {
            if(first != null) first.close(); if(second != null) second.close(); fixture.close();
        }
    }

    private com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState awaitNativeDynamic(
            DirectConnectPeer peer, long id, boolean active) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            for(com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState state :
                    peer.getNativeDynamicStates())
                if(state.id == id && state.active == active) return state;
            Thread.sleep(5);
        }
        fail("Timed out waiting for native dynamic state " + id + " active=" + active);
        return null;
    }

    private com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState awaitNativeDynamicPosition(
            DirectConnectPeer peer, long id, float x) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            for(com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState state :
                    peer.getNativeDynamicStates())
                if(state.id == id && state.active && state.apply(null).x == x) return state;
            Thread.sleep(5);
        }
        fail("Timed out waiting for native dynamic position " + x);
        return null;
    }

    private com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue awaitNativeDynamicCue(
            DirectConnectPeer peer) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            java.util.List<com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue> cues =
                    peer.drainNativeDynamicCues();
            if(!cues.isEmpty()) return cues.get(0);
            Thread.sleep(5);
        }
        fail("Timed out waiting for native dynamic cue");
        return null;
    }

    @Test public void acceptedNativeItemFeedbackReachesTwoObserversAndRejectsStaleReplay()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("native-cast-presentation");
        HostFixture fixture = host(compatibility, 3, "native-cast-presentation");
        DirectConnectClient first = null, second = null;
        try {
            first = approveClient(fixture, compatibility, '2', "Two", AvatarCatalog.HUMANOID_2);
            second = approveClient(fixture, compatibility, '3', "Three", AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(first, DirectConnectPhase.READY); awaitPhase(second, DirectConnectPhase.READY);

            com.interrupt.dungeoneer.entities.spells.Beam spell =
                    new com.interrupt.dungeoneer.entities.spells.Beam();
            com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation cast =
                    com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation.capture(
                            "participant:campaign-slot-2", 71L, spell,
                            new com.badlogic.gdx.math.Vector3(2f, 3f, 0.75f), true);
            fixture.host.publishNativeSpellPresentation(cast);
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation received =
                        awaitNativeSpellPresentation(observer);
                assertEquals(71L, received.itemId); assertTrue(received.zap);
                assertEquals(spell.getCastSoundAsset(), received.sound);
                assertEquals(2f, received.x, 0f);
            }
            com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation impact =
                    com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation.capture(
                            "participant:campaign-slot-2", 72L,
                            com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation.Kind.WORLD_HIT,
                            new com.badlogic.gdx.math.Vector3(4f, 5f, 0.5f),
                            new com.badlogic.gdx.math.Vector3(1f, 0f, 0f), 1000007L);
            fixture.host.publishNativeMeleePresentation(impact);
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation received =
                        awaitNativeMeleePresentation(observer);
                assertEquals(72L, received.itemId);
                assertEquals(1000007L, received.targetObjectId);
                assertEquals(com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation.Kind.WORLD_HIT,
                        received.kind);
                assertEquals(5f, received.y, 0f);
            }
            com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation release =
                    com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation.capture(
                            "participant:campaign-slot-2", 73L,
                            new com.badlogic.gdx.math.Vector3(6f, 7f, 0.5f));
            fixture.host.publishNativeRangedPresentation(release);
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation received =
                        awaitNativeRangedPresentation(observer);
                assertEquals(73L, received.itemId);
                assertEquals("participant:campaign-slot-2", received.sourceId);
                assertEquals(7f, received.y, 0f);
            }
            com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot broken =
                    new com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot(
                            1000007L, 3L, 0, false, false, 4f, 5f, 0.5f,
                            0.1f, 0.2f, 0.3f, 1f, 2f, 3f);
            fixture.host.publishBreakable(broken);
            for(DirectConnectClient observer : new DirectConnectClient[]{first, second}) {
                com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot received =
                        awaitBreakableSnapshot(observer, 1000007L);
                assertEquals(3L, received.revision);
                assertEquals(0, received.hp);
                assertFalse(received.active);
                assertEquals(0.3f, received.velocityZ, 0f);
            }

            long generation = first.getNativeWorldGeneration();
            fixture.host.beginNativeWorld();
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(first.getNativeWorldGeneration() == generation
                    && System.currentTimeMillis() < deadline) Thread.sleep(10L);
            java.lang.reflect.Field session = DirectConnectClient.class.getDeclaredField("sessionId");
            session.setAccessible(true);
            java.lang.reflect.Method deliver = DirectConnectClient.class.getDeclaredMethod(
                    "nativeSpellPresentation",
                    DirectConnectWire.NativeSpellPresentationMessage.class);
            deliver.setAccessible(true);
            deliver.invoke(first, new DirectConnectWire.NativeSpellPresentationMessage(
                    (String)session.get(first), 999L, cast, generation));
            assertTrue("Old floor cannot replay cast feedback",
                    first.drainNativeSpellPresentations().isEmpty());
            java.lang.reflect.Method deliverMelee = DirectConnectClient.class.getDeclaredMethod(
                    "nativeMeleePresentation",
                    DirectConnectWire.NativeMeleePresentationMessage.class);
            deliverMelee.setAccessible(true);
            deliverMelee.invoke(first, new DirectConnectWire.NativeMeleePresentationMessage(
                    (String)session.get(first), 999L, impact, generation));
            assertTrue("Old floor cannot replay melee feedback",
                    first.drainNativeMeleePresentations().isEmpty());
            java.lang.reflect.Method deliverRanged = DirectConnectClient.class.getDeclaredMethod(
                    "nativeRangedPresentation",
                    DirectConnectWire.NativeRangedPresentationMessage.class);
            deliverRanged.setAccessible(true);
            deliverRanged.invoke(first, new DirectConnectWire.NativeRangedPresentationMessage(
                    (String)session.get(first), 999L, release, generation));
            assertTrue("Old floor cannot replay ranged feedback",
                    first.drainNativeRangedPresentations().isEmpty());
        }
        finally {
            if(first != null) first.close(); if(second != null) second.close(); fixture.close();
        }
    }

    private com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation
            awaitNativeSpellPresentation(DirectConnectPeer peer) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            java.util.List<com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation> casts =
                    peer.drainNativeSpellPresentations();
            if(!casts.isEmpty()) return casts.get(0);
            Thread.sleep(5L);
        }
        fail("Timed out waiting for native spell presentation");
        return null;
    }

    private com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation
            awaitNativeMeleePresentation(DirectConnectPeer peer) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            java.util.List<com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation> events =
                    peer.drainNativeMeleePresentations();
            if(!events.isEmpty()) return events.get(0);
            Thread.sleep(5L);
        }
        fail("Timed out waiting for native melee presentation");
        return null;
    }

    private com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation
            awaitNativeRangedPresentation(DirectConnectPeer peer) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            java.util.List<com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation> events =
                    peer.drainNativeRangedPresentations();
            if(!events.isEmpty()) return events.get(0);
            Thread.sleep(5L);
        }
        fail("Timed out waiting for native ranged presentation");
        return null;
    }

    private com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot awaitBreakableSnapshot(
            DirectConnectPeer peer, long entityId) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            for(com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot state
                    : peer.getBreakableSnapshots()) {
                if(state.entityId == entityId) return state;
            }
            Thread.sleep(5L);
        }
        fail("Timed out waiting for breakable state " + entityId);
        return null;
    }

    @Test public void nativeDoorFeedbackReachesOnlyInitiatingParticipant() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("door-feedback");
        HostFixture fixture = host(compatibility, 3, "door-feedback");
        DirectConnectClient first = null, second = null;
        try {
            first = approveClient(fixture, compatibility, '2', "Two", AvatarCatalog.HUMANOID_2);
            second = approveClient(fixture, compatibility, '3', "Three", AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(first, DirectConnectPhase.READY); awaitPhase(second, DirectConnectPhase.READY);
            com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController controller =
                    new com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController(fixture.host);
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd().newInstance(
                    com.interrupt.dungeoneer.game.Game.class);
            game.level = new com.interrupt.dungeoneer.game.Level(4, 4) {
                @Override public boolean canSee(float x, float y, float targetX, float targetY) { return true; }
            };
            java.lang.reflect.Field gameField = controller.getClass().getDeclaredField("game");
            gameField.setAccessible(true); gameField.set(controller, game);
            java.lang.reflect.Field objectsField = controller.getClass().getDeclaredField("objects");
            objectsField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Long, com.interrupt.dungeoneer.entities.Entity> objects =
                    (Map<Long, com.interrupt.dungeoneer.entities.Entity>)objectsField.get(controller);
            java.lang.reflect.Field boundaryField = controller.getClass().getDeclaredField("boundary");
            boundaryField.setAccessible(true);
            AuthoritativeItemWorld.InteractionBoundary boundary =
                    (AuthoritativeItemWorld.InteractionBoundary)boundaryField.get(controller);
            for(MovementEntityDescriptor descriptor : fixture.host.getMovementEntities()) {
                ParticipantContext participant = new ParticipantContext(descriptor.getParticipantId(),
                        new ParticipantCharacterState(1, 1, 0, 0), new SharedPartyProgression());
                com.interrupt.dungeoneer.entities.Door door = new com.interrupt.dungeoneer.entities.Door();
                door.doorState = com.interrupt.dungeoneer.entities.Door.DoorState.OPEN;
                door.getsStuckOpen = true;
                door.x = door.y = 1; door.z = 0;
                objects.put(1L, door);
                assertTrue(boundary.useObject(1L, participant));
                DirectConnectPeer recipient = descriptor.getCampaignSlot() == 1 ? fixture.host
                        : descriptor.getCampaignSlot() == 2 ? first : second;
                List<com.interrupt.dungeoneer.multiplayer.items.DoorFeedback> received = new java.util.ArrayList<>();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(received.isEmpty() && System.nanoTime() < deadline) {
                    received.addAll(recipient.drainDoorFeedback()); Thread.sleep(5);
                }
                assertEquals(java.util.Collections.singletonList(
                        com.interrupt.dungeoneer.multiplayer.items.DoorFeedback.STUCK), received);
                assertTrue(fixture.host.drainDoorFeedback().isEmpty());
                assertTrue(first.drainDoorFeedback().isEmpty()); assertTrue(second.drainDoorFeedback().isEmpty());
                fixture.host.publishItemActionResult(descriptor.getParticipantId(),
                        new com.interrupt.dungeoneer.multiplayer.items.ItemActionResult(19, 41, false));
                List<com.interrupt.dungeoneer.multiplayer.items.ItemActionResult> results = new java.util.ArrayList<>();
                deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(results.isEmpty() && System.nanoTime() < deadline) {
                    results.addAll(recipient.drainItemActionResults()); Thread.sleep(5);
                }
                assertEquals(1, results.size()); assertEquals(19, results.get(0).requestId);
                assertEquals(41, results.get(0).entityId); assertFalse(results.get(0).accepted);
                assertTrue(fixture.host.drainItemActionResults().isEmpty());
                assertTrue(first.drainItemActionResults().isEmpty()); assertTrue(second.drainItemActionResults().isEmpty());
            }
        } finally {
            if(first != null) first.close(); if(second != null) second.close(); fixture.close();
        }
    }

    @Test public void nativeIceStateReachesTwoClientsAndReconnectRestoresRemainingEffect() throws Exception {
        HashMap<String, LocalizedString> strings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        DirectConnectCompatibility compatibility = compatibility("native-status");
        HostFixture fixture = host(compatibility, 3, "native-status");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient first = client(fixture.host.getBoundPort(), '2', "Two",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        DirectConnectClient second = null, returning = null;
        try {
            awaitPhase(first, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(first, DirectConnectPhase.LOBBY);
            second = approveClient(fixture, compatibility, '3', "Three", AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(first, DirectConnectPhase.READY); awaitPhase(second, DirectConnectPhase.READY);
            Monster hostMonster = new Monster(); hostMonster.hp = hostMonster.maxHp = 20;
            hostMonster.takeDamage(1, DamageType.ICE, null);
            hostMonster.statusEffects.first().showParticleEffect = false;
            DirectConnectCombatController authority = new DirectConnectCombatController(fixture.host, false);
            java.lang.reflect.Method capture = DirectConnectCombatController.class.getDeclaredMethod(
                    "synchronizeNativeMonster", String.class, Monster.class);
            capture.setAccessible(true);
            String id = AuthoritativeCombatEncounter.monsterTargetId(1);
            capture.invoke(authority, id, hostMonster);
            final java.util.List<Long> startsA = new java.util.ArrayList<>(), startsB = new java.util.ArrayList<>();
            Monster a = new Monster() {
                @Override public void playNetworkStatusStart(long id) { startsA.add(id); super.playNetworkStatusStart(id); }
            }, b = new Monster() {
                @Override public void playNetworkStatusStart(long id) { startsB.add(id); super.playNetworkStatusStart(id); }
            };
            a.setNetworkReplica(true); b.setNetworkReplica(true);
            a.applyNetworkState(19, 20, 1, 1, 0); b.applyNetworkState(19, 20, 1, 1, 0);
            awaitEffects(first, id, 500); awaitEffects(second, id, 500);
            capture.invoke(authority, id, hostMonster); // Unchanged state cannot create another start.
            applyNativeObserver(first, a, awaitEffects(first, id, 500));
            applyNativeObserver(second, b, awaitEffects(second, id, 500));
            assertEquals(1, startsA.size()); assertEquals(1, startsB.size());
            applyNativeObserver(first, a, awaitEffects(first, id, 500));
            assertEquals("Rendering same snapshot cannot replay start", 1, startsA.size());
            assertEquals(hostMonster.getShader(), a.getShader());
            assertEquals(hostMonster.getShader(), b.getShader());
            assertEquals(19, a.hp); assertEquals(19, b.hp);
            first.close();
            awaitPartyState(second, 2, PartyMemberState.RECONNECTING);
            hostMonster.tickStatusEffects(120);
            capture.invoke(authority, id, hostMonster);
            fixture.host.setSessionPaused(true); // Flush even a throttled last countdown update.
            awaitEffects(second, id, 380);
            assertTrue("Countdown is not another status start", second.drainNativeStatusCues().isEmpty());
            fixture.host.setSessionPaused(false);
            returning = client(fixture.host.getBoundPort(), '2', "Two", AvatarCatalog.HUMANOID_2,
                    0, tokens, compatibility);
            awaitPhase(returning, DirectConnectPhase.READY);
            Monster recovered = new Monster() {
                @Override public void playNetworkStatusStart(long id) { fail("Recovery must not deliver a live start"); }
            }; recovered.setNetworkReplica(true);
            recovered.applyNetworkState(19, 20, 1, 1, 0);
            applyNativeObserver(returning, recovered, awaitEffects(returning, id, 380));
            assertTrue("Reconnect baseline must not replay live starts", returning.drainNativeStatusCues().isEmpty());
            assertEquals(380, recovered.statusEffects.first().timer, 0);
            assertEquals(hostMonster.getShader(), recovered.getShader());
            hostMonster.tickStatusEffects(381); hostMonster.tickStatusEffects(1);
            capture.invoke(authority, id, hostMonster);
            applyNativeObserver(second, b, awaitEffects(second, id, -1));
            applyNativeObserver(returning, recovered, awaitEffects(returning, id, -1));
            assertNull(b.statusEffects); assertNull(recovered.statusEffects);
        }
        finally {
            first.close(); if(second != null) second.close(); if(returning != null) returning.close();
            fixture.close(); StringManager.localizedStrings = strings;
        }
    }

    @Test public void nativePeriodicStatusPulseReachesTwoObserversExactlyOnce() throws Exception {
        HashMap<String, LocalizedString> strings = StringManager.localizedStrings;
        StringManager.localizedStrings = new HashMap<String, LocalizedString>();
        DirectConnectCompatibility compatibility = compatibility("native-status-pulse");
        HostFixture fixture = host(compatibility, 3, "native-status-pulse");
        DirectConnectClient first = null, second = null;
        try {
            first = approveClient(fixture, compatibility, '2', "Two", AvatarCatalog.HUMANOID_2);
            second = approveClient(fixture, compatibility, '3', "Three", AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(first, DirectConnectPhase.READY); awaitPhase(second, DirectConnectPhase.READY);
            Monster hostMonster = new Monster(); hostMonster.hp = hostMonster.maxHp = 20;
            com.interrupt.dungeoneer.statuseffects.PoisonEffect poison =
                    new com.interrupt.dungeoneer.statuseffects.PoisonEffect(1000, 10, 1, false);
            poison.showParticleEffect = false;
            hostMonster.statusEffects = new com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.statuseffects.StatusEffect>();
            hostMonster.statusEffects.add(poison);
            DirectConnectCombatController authority = new DirectConnectCombatController(fixture.host, false);
            java.lang.reflect.Method capture = DirectConnectCombatController.class.getDeclaredMethod(
                    "synchronizeNativeMonster", String.class, Monster.class);
            capture.setAccessible(true);
            String id = AuthoritativeCombatEncounter.monsterTargetId(1);
            capture.invoke(authority, id, hostMonster);
            awaitEffectsPulse(first, id, 0); awaitEffectsPulse(second, id, 0);
            assertEquals(NativeStatusCue.Kind.START, first.drainNativeStatusCues().get(0).kind);
            assertEquals(NativeStatusCue.Kind.START, second.drainNativeStatusCues().get(0).kind);

            hostMonster.tickStatusEffects(11);
            capture.invoke(authority, id, hostMonster);
            awaitEffectsPulse(first, id, 1); awaitEffectsPulse(second, id, 1);
            List<NativeStatusCue> firstPulses = first.drainNativeStatusCues();
            List<NativeStatusCue> secondPulses = second.drainNativeStatusCues();
            assertEquals(1, firstPulses.size()); assertEquals(1, secondPulses.size());
            assertEquals(NativeStatusCue.Kind.PULSE, firstPulses.get(0).kind);
            assertEquals(NativeStatusCue.Kind.PULSE, secondPulses.get(0).kind);
            assertEquals(firstPulses.get(0).instanceId, secondPulses.get(0).instanceId);
            capture.invoke(authority, id, hostMonster);
            Thread.sleep(50);
            assertTrue(first.drainNativeStatusCues().isEmpty());
            assertTrue(second.drainNativeStatusCues().isEmpty());
        }
        finally {
            if(first != null) first.close(); if(second != null) second.close();
            fixture.close(); StringManager.localizedStrings = strings;
        }
    }

    private ActorEffectsSnapshot awaitEffectsPulse(DirectConnectPeer peer, String id, long pulses)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            for(ActorEffectsSnapshot state : peer.getActorEffects()) {
                if(state.monsterId.equals(id) && !state.effects.isEmpty()
                        && state.effects.get(0).pulses == pulses) return state;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Native effect pulse did not converge: " + pulses);
    }

    private ActorEffectsSnapshot awaitEffects(DirectConnectPeer peer, String id, float remaining)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            for(ActorEffectsSnapshot state : peer.getActorEffects()) {
                if(state.monsterId.equals(id) && (remaining < 0 ? state.effects.isEmpty()
                        : !state.effects.isEmpty() && state.effects.get(0).remaining == remaining)) return state;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Native effect did not converge: " + remaining);
    }

    @SuppressWarnings("unchecked")
    private void applyNativeObserver(DirectConnectPeer peer, Monster monster, ActorEffectsSnapshot effects)
            throws Exception {
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        java.lang.reflect.Field monsters = DirectConnectCombatController.class.getDeclaredField("monsters");
        monsters.setAccessible(true);
        ((Map<String, Monster>)monsters.get(controller)).put(effects.monsterId, monster);
        java.lang.reflect.Method apply = DirectConnectCombatController.class.getDeclaredMethod(
                "applySnapshot", CombatSnapshot.class);
        apply.setAccessible(true); apply.invoke(controller, peer.getCombatSnapshot());
    }

    @Test
    public void reliableItemRequestsUseAuthenticatedParticipantAndConvergeAfterSharing() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("item-sharing");
        HostFixture fixture = host(compatibility, 2, "item-sharing");
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            awaitPendingCount(fixture.host, 1);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            AuthoritativeItemWorld world = fixture.host.getItemWorld();
            ParticipantId remote = null;
            for(MovementEntityDescriptor descriptor
                    : fixture.host.getMovementEntities()) {
                world.registerParticipant(descriptor.getParticipantId(), 8);
                if(descriptor.getCampaignSlot() == 2) remote = descriptor.getParticipantId();
            }
            assertNotNull(remote);
            PhysicalItemState item =
                    world.spawn("test-item", null, 1f, 1f, 0f);
            fixture.host.publishPhysicalItems();
            awaitPhysicalItem(client, item.entityId, null);
            client.submitItemAction(1L, ItemAction.PICKUP,
                    item.entityId);
            ItemRequest request = awaitItemRequest(fixture.host);
            assertEquals(remote, request.getParticipantId());
            ParticipantContext participant =
                    new ParticipantContext(remote,
                            new ParticipantCharacterState(1, 1, 0, 0),
                            new SharedPartyProgression());
            AuthoritativeItemWorld.InteractionBoundary boundary =
                    new AuthoritativeItemWorld.InteractionBoundary() {
                public boolean canAct(ParticipantContext p) { return true; }
                public boolean canReach(ParticipantContext p,
                        float x, float y, float z) { return true; }
                public boolean useObject(long id, ParticipantContext p) { return false; }
            };
            assertEquals(AuthoritativeItemWorld.Outcome.ACCEPTED,
                    world.apply(request, participant, boundary));
            fixture.host.publishPhysicalItems();
            awaitPhysicalItem(client, item.entityId, remote);
            client.submitCombatAction(1L, CombatAction.SPELL, 1f, 0f, 0f, 1f, item.entityId);
            long shotDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            List<CombatRequest> shots = java.util.Collections.emptyList();
            while(shots.isEmpty() && System.currentTimeMillis() < shotDeadline) {
                shots = fixture.host.drainNativeCombatRequests();
                if(shots.isEmpty()) Thread.sleep(10L);
            }
            assertEquals(1, shots.size());
            assertEquals(item.entityId, shots.get(0).getWeaponEntityId());
            assertEquals(remote, shots.get(0).getParticipantId());
            ProjectileVisual visual =
                    new ProjectileVisual(
                            "sprite", 8, 0x0000ffff, 0.5f, 0.17f, true, true);
            fixture.host.publishNativePresentation("participant:campaign-slot-1", "", CombatAction.SPELL,
                    CombatPresentationPhase.ATTACK,
                    1f, 1f, 0f, 2f, 1f, 0f, false, visual);
            long visualDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(client.getCombatPresentationEvents().isEmpty()
                    && System.currentTimeMillis() < visualDeadline) Thread.sleep(10L);
            assertEquals(1, client.getCombatPresentationEvents().size());
            assertEquals(0x0000ffff, client.getCombatPresentationEvents().get(0).getProjectileVisual().rgba);
            client.submitItemAction(2L, ItemAction.DROP, item.entityId);
            assertEquals(AuthoritativeItemWorld.Outcome.ACCEPTED,
                    world.apply(awaitItemRequest(fixture.host), participant, boundary));
            fixture.host.publishPhysicalItems();
            awaitPhysicalItem(client, item.entityId, null);
            assertEquals(1, client.getPhysicalItems().size());
            world.registerEquipment(item.entityId, "ARMOR", false);
            client.submitItemAction(3L, ItemAction.PICKUP, item.entityId);
            assertEquals(AuthoritativeItemWorld.Outcome.ACCEPTED,
                    world.apply(awaitItemRequest(fixture.host), participant, boundary));
            client.submitItemAction(4L, ItemAction.EQUIP, item.entityId);
            assertEquals(AuthoritativeItemWorld.Outcome.ACCEPTED,
                    world.apply(awaitItemRequest(fixture.host), participant, boundary));
            fixture.host.publishPhysicalItems();
            long equipmentDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(client.getPhysicalItems().get(0).equipmentSlot.isEmpty()
                    && System.currentTimeMillis() < equipmentDeadline) Thread.sleep(10L);
            assertEquals("ARMOR", client.getPhysicalItems().get(0).equipmentSlot);

            PhysicalItemState key = world.spawn("key", null, 1, 1, 0);
            world.registerKey(key.entityId);
            client.submitItemAction(5L, ItemAction.PICKUP, key.entityId);
            assertEquals(AuthoritativeItemWorld.Outcome.ACCEPTED,
                    world.apply(awaitItemRequest(fixture.host), participant, boundary));
            fixture.host.publishPhysicalItems();
            awaitPartyKeys(client, 1);
            assertEquals(1, fixture.host.getPartyKeys());
            assertTrue(world.spendPartyKey());
            fixture.host.publishPhysicalItems();
            awaitPartyKeys(client, 0);
        }
        finally { client.close(); fixture.close(); }
    }

    @Test public void reliableScrollRequestRetainsAuthenticatedParticipantAndAim() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("scroll-aim");
        HostFixture fixture = host(compatibility, 2, "scroll-aim");
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);

            client.submitItemAction(1L, ItemAction.CONSUME, 41L, 2, 1,
                    true, 0.25f, -0.5f, 0.75f);
            ItemRequest request = awaitItemRequest(fixture.host);

            assertEquals(new ParticipantId("campaign-slot-2"), request.getParticipantId());
            assertTrue(request.hasAim); assertEquals(0.25f, request.aimX, 0f);
            assertEquals(-0.5f, request.aimY, 0f); assertEquals(0.75f, request.aimZ, 0f);
        }
        finally {
            client.close(); fixture.close();
        }
    }

    private void awaitPartyKeys(DirectConnectPeer peer, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(peer.getPartyKeys() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10L);
        assertEquals(expected, peer.getPartyKeys());
    }

    private ItemRequest awaitItemRequest(DirectConnectHost host)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            List<ItemRequest> requests = host.drainItemRequests();
            if(!requests.isEmpty()) return requests.get(0);
            Thread.sleep(10L);
        }
        throw new AssertionError("Host did not receive reliable item request.");
    }

    private void awaitPhysicalItem(DirectConnectPeer peer, long id, ParticipantId owner)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            for(PhysicalItemState state : peer.getPhysicalItems()) {
                if(state.entityId == id && java.util.Objects.equals(owner, state.owner)) return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Physical item ownership did not converge.");
    }

    @Test
    public void refusedConnectionExplainsHowToStartHost() throws Exception {
        ServerSocket unusedPort = new ServerSocket(0);
        int port = unusedPort.getLocalPort();
        unusedPort.close();

        DirectConnectClient client = client(port, '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(),
                compatibility("unavailable-host"));
        try {
            awaitPhase(client, DirectConnectPhase.FAILED);
            assertEquals("No Direct Connect Host is listening at 127.0.0.1:" + port
                            + ". Start Host first and check that both ports match.",
                    client.getStatus().getMessage());
        }
        finally {
            client.close();
        }
    }

    @Test
    public void unknownIdentityWaitsForHostApprovalBeforeLobbyAndFloorEntry() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("same-floor");
        HostFixture fixture = host(compatibility, 2, "approval");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            awaitPendingCount(fixture.host, 1);
            assertEquals(1, fixture.roster.getSlots().size());

            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            awaitPhase(fixture.host, DirectConnectPhase.LOBBY);
            assertEquals(2, fixture.roster.getSlots().size());
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertTrue(tokens.load("approval") != null);

            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitPhase(fixture.host, DirectConnectPhase.READY);
            assertEquals(GameApplication.OPEN_SOURCE_TEST_LEVEL,
                    client.getStatus().getFloorId());
            assertEquals(2, client.getCampaignSlot());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void ownedCompatibilityReportsOwnedTutorialAsSharedFloor() throws Exception {
        DirectConnectCompatibility compatibility = new DirectConnectCompatibility(
                DirectConnectProtocol.BUILD_ID, "delver-owned-assets-v1",
                "0000000000000000000000000000000000000000000000000000000000000000");
        HostFixture fixture = host(compatibility, 2, "owned-floor");
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            awaitPendingCount(fixture.host, 1);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);

            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            assertEquals("owned-game-copy-tutorial", client.getStatus().getFloorId());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void capacityFourLobbyAdmitsThreeExplicitlyApprovedRemoteIdentities() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("four-party-floor");
        HostFixture fixture = host(compatibility, 4, "four-party");
        DirectConnectClient second = null;
        DirectConnectClient third = null;
        DirectConnectClient fourth = null;
        try {
            second = approveClient(fixture, compatibility, '2', "Two",
                    AvatarCatalog.HUMANOID_2);
            third = approveClient(fixture, compatibility, '3', "Three",
                    AvatarCatalog.HUMANOID_3);
            fourth = approveClient(fixture, compatibility, '4', "Four",
                    AvatarCatalog.HUMANOID_4);

            assertEquals(4, fixture.host.getConnectedParticipantCount());
            assertEquals(4, fixture.host.getUdpReadyParticipantCount());
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            awaitPhase(fourth, DirectConnectPhase.READY);
            assertEquals(4, fixture.roster.getSlots().size());
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertEquals(identity('3'), fixture.roster.getSlot(3).getLauncherIdentity());
            assertEquals(identity('4'), fixture.roster.getSlot(4).getLauncherIdentity());
        }
        finally {
            if(second != null) second.close();
            if(third != null) third.close();
            if(fourth != null) fourth.close();
            fixture.close();
        }
    }

    @Test
    public void activeFloorCarriesTickedInputsAndTwentyHertzAuthoritativeSnapshots()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("movement-floor");
        HostFixture fixture = host(compatibility, 2, "movement");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitMovementSnapshots(client, 3);
            assertEquals(new NetworkEntityId(1L), fixture.host.getLocalMovementEntityId());
            assertEquals(new NetworkEntityId(2L), client.getLocalMovementEntityId());
            assertEquals(2, client.getMovementEntities().size());

            List<MovementSnapshot> snapshotsBefore = client.getMovementSnapshots();
            MovementSnapshot before = snapshotsBefore.get(snapshotsBefore.size() - 1);
            float startingY = before.getEntity(client.getLocalMovementEntityId()).getY();
            client.submitMovementInput(new MovementInputFrame(1L, 1f, 0f, 0f, false));
            client.submitMovementInput(new MovementInputFrame(4L, 1f, 0f, 0f, false));
            fixture.host.submitMovementInput(new MovementInputFrame(
                    1L, 0f, 1f, 0f, false));

            awaitAcknowledgedInput(client, 4L);
            List<MovementSnapshot> snapshotsAfter = client.getMovementSnapshots();
            MovementSnapshot after = snapshotsAfter.get(snapshotsAfter.size() - 1);
            MovementEntityState local = after.getEntity(client.getLocalMovementEntityId());
            assertTrue(local.getY() > startingY);
            assertEquals(4L, local.getLastProcessedInputTick());
            assertTrue(after.getEntity(new NetworkEntityId(1L)).getX() > 16.5f);

            List<MovementSnapshot> snapshots = client.getMovementSnapshots();
            for(int i = 1; i < snapshots.size(); i++) {
                assertEquals(3L, snapshots.get(i).getHostTick()
                        - snapshots.get(i - 1).getHostTick());
            }
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void twoParticipantsObserveOneHostPublishedNativeCombatResult() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("combat-floor");
        HostFixture fixture = host(compatibility, 2, "combat");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitCombatSnapshot(client);
            assertEquals(24, combatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID));

            submitDirectedAtMonster(client, 1L, CombatAction.MELEE);
            CombatRequest clientMelee = awaitNativeCombatRequest(fixture.host);
            assertEquals(new ParticipantId("campaign-slot-2"), clientMelee.getParticipantId());
            assertEquals(CombatAction.MELEE, clientMelee.getAction());
            CombatSnapshot initial = fixture.host.getCombatSnapshot();
            fixture.host.synchronizeNativeMonster(20, 24, initial.getMonsterX(),
                    initial.getMonsterY(), initial.getMonsterZ());
            fixture.host.publishNativePresentation(
                    AuthoritativeCombatEncounter.participantTargetId(clientMelee.getParticipantId()),
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID, CombatAction.MELEE,
                    CombatPresentationPhase.DAMAGE, 0f, 0f, 0.5f,
                    initial.getMonsterX(), initial.getMonsterY(), initial.getMonsterZ(), true);
            awaitCombatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 20);
            awaitCombatPresentations(client, 1);
            assertEquals(20, combatHealth(fixture.host,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(1, fixture.host.getCombatPresentationEvents().size());
            assertEquals(CombatAction.MELEE,
                    client.getCombatPresentationEvents().get(0).getAction());

            submitDirectedAtMonster(fixture.host, 1L, CombatAction.SPELL);
            CombatRequest hostSpell = awaitNativeCombatRequest(fixture.host);
            assertEquals(new ParticipantId("campaign-slot-1"), hostSpell.getParticipantId());
            assertEquals(CombatAction.SPELL, hostSpell.getAction());
            fixture.host.synchronizeNativeMonster(15, 24, initial.getMonsterX(),
                    initial.getMonsterY(), initial.getMonsterZ());
            fixture.host.publishNativePresentation(
                    AuthoritativeCombatEncounter.participantTargetId(hostSpell.getParticipantId()),
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID, CombatAction.SPELL,
                    CombatPresentationPhase.DAMAGE, 0f, 0f, 0.5f,
                    initial.getMonsterX(), initial.getMonsterY(), initial.getMonsterZ(), true);
            awaitCombatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 15);
            awaitCombatPresentations(client, 2);
            assertEquals(15, combatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(15, combatHealth(fixture.host,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(2, client.getCombatPresentationEvents().size());
            assertEquals(2, fixture.host.getCombatPresentationEvents().size());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void twoClientsConvergeOnFriendlyFireBenefitsHazardsAndDeath() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("combat-policy-floor");
        HostFixture fixture = host(compatibility, 3, "combat-policy");
        DirectConnectClient second = approveClient(fixture, compatibility, '2', "Two",
                AvatarCatalog.HUMANOID_2);
        DirectConnectClient third = approveClient(fixture, compatibility, '3', "Three",
                AvatarCatalog.HUMANOID_3);
        String secondTarget = AuthoritativeCombatEncounter.participantTargetId(
                new ParticipantId("campaign-slot-2"));
        try {
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            awaitCombatSnapshot(second);
            awaitCombatSnapshot(third);
            awaitPartyHealth(second, 2, 8);
            awaitPartyHealth(third, 2, 8);

            second.submitCombatAction(1L, CombatAction.SELF_DAMAGE, secondTarget);
            awaitCombatHealth(third, secondTarget, 6);
            awaitPartyHealth(second, 2, 6);
            awaitPartyHealth(third, 2, 6);
            awaitPartyHealth(fixture.host, 2, 6);
            second.submitCombatAction(2L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 4);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);

            third.submitCombatAction(1L, CombatAction.BENEFICIAL_SPELL, secondTarget);
            awaitCombatHealth(second, secondTarget, 7);
            awaitCombatHealth(third, secondTarget, 7);
            awaitCombatHealth(fixture.host, secondTarget, 7);
            awaitPartyHealth(second, 2, 7);
            awaitPartyHealth(third, 2, 7);
            awaitPartyHealth(fixture.host, 2, 7);

            submitDirectedAtParticipant(third, 2L, CombatAction.MELEE, 2);
            CombatRequest friendlyFire = awaitNativeCombatRequest(fixture.host);
            assertEquals(new ParticipantId("campaign-slot-3"), friendlyFire.getParticipantId());
            fixture.host.publishNativePresentation(
                    AuthoritativeCombatEncounter.participantTargetId(
                            friendlyFire.getParticipantId()),
                    "", CombatAction.MELEE, CombatPresentationPhase.ATTACK,
                    0f, 0f, 0.5f, 1f, 0f, 0.5f, false);
            awaitCombatPresentations(second, 4);
            awaitCombatPresentations(third, 4);
            assertEquals(7, combatHealth(second, secondTarget));
            assertEquals(7, combatHealth(third, secondTarget));
            assertEquals(7, combatHealth(fixture.host, secondTarget));

            second.submitCombatAction(3L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 5);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);
            second.submitCombatAction(3L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            second.submitCombatAction(4L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 3);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);
            second.submitCombatAction(5L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(third, secondTarget, 1);
            awaitCombatCadence(fixture.host, CombatAction.ENVIRONMENTAL_HAZARD);
            second.submitCombatAction(6L, CombatAction.ENVIRONMENTAL_HAZARD, secondTarget);
            awaitCombatHealth(second, secondTarget, 0);
            awaitCombatHealth(third, secondTarget, 0);
            awaitCombatHealth(fixture.host, secondTarget, 0);
            awaitPartyHealth(second, 2, 0);
            awaitPartyHealth(third, 2, 0);
            awaitPartyHealth(fixture.host, 2, 0);

            third.submitCombatAction(3L, CombatAction.BENEFICIAL_SPELL, secondTarget);
            submitDirectedAtMonster(third, 4L, CombatAction.MELEE);
            CombatRequest postDeathAttack = awaitNativeCombatRequest(fixture.host);
            assertEquals(CombatAction.MELEE, postDeathAttack.getAction());
            assertEquals(0, combatHealth(second, secondTarget));
            assertEquals(0, combatHealth(third, secondTarget));
            assertEquals(0, combatHealth(fixture.host, secondTarget));
        }
        finally {
            third.close();
            second.close();
            fixture.close();
        }
    }

    @Test
    public void directedClientAttackIsTracedAndReplicatedByHost() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("directed-combat-floor");
        HostFixture fixture = host(compatibility, 2, "directed-combat");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitCombatSnapshot(client);
            awaitMovementSnapshots(client, 1);

            List<MovementSnapshot> movementSnapshots = client.getMovementSnapshots();
            MovementEntityState source = movementSnapshots.get(movementSnapshots.size() - 1)
                    .getEntity(client.getLocalMovementEntityId());
            CombatSnapshot before = client.getCombatSnapshot();
            float aimX = before.getMonsterX() - source.getX();
            float aimY = before.getMonsterY() - source.getY();
            float aimZ = before.getMonsterZ() - (source.getZ() + 0.35f);
            float aimLength = (float)Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);

            client.submitCombatAction(1L, CombatAction.SPELL,
                    aimX / aimLength, aimY / aimLength, aimZ / aimLength);

            CombatRequest request = awaitNativeCombatRequest(fixture.host);
            assertEquals(new ParticipantId("campaign-slot-2"), request.getParticipantId());
            assertEquals(CombatAction.SPELL, request.getAction());
            assertEquals(aimX / aimLength, request.getAimX(), 0.0001f);
            assertEquals(aimY / aimLength, request.getAimY(), 0.0001f);
            assertEquals(aimZ / aimLength, request.getAimZ(), 0.0001f);
            fixture.host.synchronizeNativeMonster(19, 24, before.getMonsterX(),
                    before.getMonsterY(), before.getMonsterZ());
            fixture.host.publishNativePresentation(
                    AuthoritativeCombatEncounter.participantTargetId(request.getParticipantId()),
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID, CombatAction.SPELL,
                    CombatPresentationPhase.DAMAGE, source.getX(), source.getY(),
                    source.getZ() + 0.35f, before.getMonsterX(), before.getMonsterY(),
                    before.getMonsterZ(), true);

            awaitCombatPresentations(client, 1);
            CombatPresentationEvent presentation =
                    client.getCombatPresentationEvents().get(0);
            assertEquals(CombatAction.SPELL, presentation.getAction());
            assertEquals("origin=" + presentation.getOriginX() + ","
                            + presentation.getOriginY() + "," + presentation.getOriginZ()
                            + " impact=" + presentation.getImpactX() + ","
                            + presentation.getImpactY() + "," + presentation.getImpactZ(),
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                    presentation.getTargetId());
            assertTrue(presentation.isStateChanged());
            awaitCombatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 19);
            assertEquals(19, combatHealth(fixture.host,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void reliablePartyStatusPreservesFrozenReconnectGrace() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("party-status-floor");
        HostFixture fixture = host(compatibility, 3, "party-status");
        DirectConnectClient second = approveClient(fixture, compatibility, '2', "Two",
                AvatarCatalog.HUMANOID_2);
        DirectConnectClient third = approveClient(fixture, compatibility, '3', "Three",
                AvatarCatalog.HUMANOID_3);
        try {
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            assertEquals(3, third.getPartyStatus().getMembers().size());
            assertEquals(PartyMemberState.CONNECTED,
                    third.getPartyStatus().getMember(2).getState());
            long connectedSequence = third.getPartyStatus().getSequence();

            NetworkEntityId secondEntity = second.getLocalMovementEntityId();
            second.close();
            PartyMemberStatus reconnecting = awaitPartyState(third, 2,
                    PartyMemberState.RECONNECTING);

            assertTrue(third.getPartyStatus().getSequence() > connectedSequence);
            assertEquals("Two", reconnecting.getNickname());
            assertEquals(secondEntity, reconnecting.getEntityId());
            assertEquals(3, reconnecting.getRemainingLives());
            assertTrue(fixture.host.getReconnectGrace(2).isFrozen());
            assertTrue(fixture.host.getReconnectGrace(2).isInvulnerable());
        }
        finally {
            second.close();
            third.close();
            fixture.close();
        }
    }

    @Test
    public void reliablePartyCommunicationUsesHostOwnedChatAndPauseControl()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("communication-floor");
        HostFixture fixture = host(compatibility, 2, "communication");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitMovementSnapshots(client, 2);

            client.submitPartyChat("Watch left.");
            awaitChatCount(fixture.host, 1);
            awaitChatCount(client, 1);
            assertEquals("Friend", fixture.host.getPartyCommunicationState()
                    .getChatHistory().get(0).getNickname());
            assertEquals("Watch left.", client.getPartyCommunicationState()
                    .getChatHistory().get(0).getText());

            fixture.host.submitPartyChat("Moving in.");
            awaitChatCount(client, 2);
            assertEquals("Host", client.getPartyCommunicationState()
                    .getChatHistory().get(1).getNickname());

            client.requestPauseSession();
            awaitPauseRequest(client, 2);
            assertFalse(client.isSessionPaused());
            assertFalse(client.canControlSessionPause());
            try {
                client.setSessionPaused(true);
                fail("Participant changed Host-owned Pause Session state.");
            }
            catch(UnsupportedOperationException expected) { }

            fixture.host.setSessionPaused(true);
            awaitPauseState(client, true);
            Thread.sleep(100L);
            int pausedSnapshotCount = fixture.host.getMovementSnapshots().size();
            Thread.sleep(200L);
            assertEquals(pausedSnapshotCount, fixture.host.getMovementSnapshots().size());

            fixture.host.setSessionPaused(false);
            awaitPauseState(client, false);
            awaitMovementSnapshotCount(fixture.host, pausedSnapshotCount + 1);
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void lostAndDuplicatedMovementDatagramsRecoverWithoutDuplicatingMovement()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("loss-duplication-floor");
        HostFixture fixture = host(compatibility, 2, "loss-duplication");
        final int[] submittedBundles = { 0 };
        DirectConnectClient client = DirectConnectClient.connectForTest("127.0.0.1",
                fixture.host.getBoundPort(), identity('2'),
                new SlotPresentation("Friend", AvatarCatalog.HUMANOID_2), 0,
                new MemoryReconnectTokens(), compatibility,
                new DirectConnectClient.MovementDatagramPolicy() {
                    @Override
                    public int copiesForBundle(List<MovementInputFrame> inputs) {
                        return submittedBundles[0]++ == 0 ? 0 : 2;
                    }
                });
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitMovementSnapshots(client, 2);

            client.submitMovementInput(new MovementInputFrame(1L, 0f, 0f, 0f, true));
            client.submitMovementInput(new MovementInputFrame(2L, 0f, 0f, 0f, false));

            awaitAcknowledgedInput(client, 2L);
            MovementEntityState recovered = latestLocalMovement(client);
            assertEquals(2L, recovered.getLastProcessedInputTick());
            assertTrue("Dropped jump input was not recovered from duplicated bundle.",
                    recovered.getZ() > 0.5f);
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void approvedIdentityAutomaticallyReclaimsSameSlotAcrossLobbySessions() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("persistent-floor");
        File storageRoot = temporaryFolder.newFolder("persistent-campaign");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        HostFixture first = host(compatibility, 3, "persistent", storageRoot);
        DirectConnectClient firstClient = client(first.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        try {
            awaitPhase(firstClient, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(first.host.approve(identity('2').getValue()));
            awaitPhase(firstClient, DirectConnectPhase.LOBBY);
            assertEquals(2, firstClient.getCampaignSlot());
        }
        finally {
            firstClient.close();
            first.close();
        }

        HostFixture resumed = host(compatibility, 3, "persistent", storageRoot);
        DirectConnectClient returning = client(resumed.host.getBoundPort(), '2', "Renamed",
                AvatarCatalog.HUMANOID_3, 0, tokens, compatibility);
        try {
            awaitPhase(returning, DirectConnectPhase.LOBBY);
            assertTrue(resumed.host.getPendingClaims().isEmpty());
            assertEquals(2, returning.getCampaignSlot());
            assertEquals("Renamed",
                    resumed.roster.getSlot(2).getPresentation().getNickname());
            assertEquals(identity('2'),
                    resumed.roster.getSlot(2).getLauncherIdentity());
        }
        finally {
            returning.close();
            resumed.close();
        }
    }

    @Test
    public void validReconnectCredentialReclaimsFrozenActiveFloorEntity() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("reconnect-floor");
        HostFixture fixture = host(compatibility, 3, "reconnect");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient second = client(fixture.host.getBoundPort(), '2', "Two",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        DirectConnectClient third = null;
        DirectConnectClient returning = null;
        try {
            awaitPhase(second, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(second, DirectConnectPhase.LOBBY);
            third = approveClient(fixture, compatibility, '3', "Three",
                    AvatarCatalog.HUMANOID_3);
            fixture.host.startSession();
            awaitPhase(second, DirectConnectPhase.READY);
            awaitPhase(third, DirectConnectPhase.READY);
            NetworkEntityId preservedEntity = second.getLocalMovementEntityId();
            String secondTarget = AuthoritativeCombatEncounter.participantTargetId(
                    new ParticipantId("campaign-slot-2"));
            awaitCombatSnapshot(third);
            submitDirectedAtMonster(second, 1L, CombatAction.MELEE);
            CombatRequest initialAttack = awaitNativeCombatRequest(fixture.host);
            assertEquals(new ParticipantId("campaign-slot-2"), initialAttack.getParticipantId());
            fixture.host.recordNativeMonsterAttacker(
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID,
                    initialAttack.getParticipantId());
            awaitMonsterTarget(fixture.host, secondTarget);

            second.close();
            PartyMemberStatus frozen = awaitPartyState(third, 2,
                    PartyMemberState.RECONNECTING);
            assertEquals(preservedEntity, frozen.getEntityId());
            assertTrue(fixture.host.getReconnectGrace(2).isFrozen());
            assertTrue(fixture.host.getReconnectGrace(2).isInvulnerable());
            awaitMonsterTargetOtherThan(fixture.host, secondTarget);

            returning = client(fixture.host.getBoundPort(), '2', "Different Display",
                    AvatarCatalog.HUMANOID_4, 0, tokens, compatibility);
            awaitPhase(returning, DirectConnectPhase.READY);
            assertEquals(2, returning.getCampaignSlot());
            assertEquals(preservedEntity, returning.getLocalMovementEntityId());
            assertNull(fixture.host.getReconnectGrace(2));
            assertEquals(PartyMemberState.CONNECTED,
                    awaitPartyState(third, 2, PartyMemberState.CONNECTED).getState());
            assertEquals("Two", fixture.roster.getSlot(2).getPresentation().getNickname());
            awaitCombatCadence(fixture.host, CombatAction.PROJECTILE);
            assertEquals(2L, returning.getNextCombatRequestId());
            submitDirectedAtMonster(returning, returning.getNextCombatRequestId(),
                    CombatAction.PROJECTILE);
            CombatRequest returningAttack = awaitNativeCombatRequest(fixture.host);
            assertEquals(new ParticipantId("campaign-slot-2"),
                    returningAttack.getParticipantId());
            assertEquals(CombatAction.PROJECTILE, returningAttack.getAction());
        }
        finally {
            second.close();
            if(third != null) third.close();
            if(returning != null) returning.close();
            fixture.close();
        }
    }

    @Test
    public void reconnectReadyIncludesCurrentCombatStateAfterEncounterBecomesIdle()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("idle-reconnect-floor");
        HostFixture fixture = host(compatibility, 2, "idle-reconnect");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        DirectConnectClient returning = null;
        String clientTarget = AuthoritativeCombatEncounter.participantTargetId(
                new ParticipantId("campaign-slot-2"));
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            awaitCombatSnapshot(client);

            client.submitCombatAction(1L, CombatAction.SELF_DAMAGE, clientTarget);
            awaitCombatHealth(client, clientTarget, 6);
            CombatSnapshot current = fixture.host.getCombatSnapshot();
            fixture.host.synchronizeNativeMonster(0, 24, current.getMonsterX(),
                    current.getMonsterY(), current.getMonsterZ());
            awaitCombatHealth(client, AuthoritativeCombatEncounter.SHARED_MONSTER_ID, 0);

            client.close();
            returning = client(fixture.host.getBoundPort(), '2', "Friend",
                    AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
            awaitPhase(returning, DirectConnectPhase.READY);

            assertEquals(2L, returning.getNextCombatRequestId());
            assertNotNull(returning.getCombatSnapshot());
            assertEquals(0, combatHealth(returning,
                    AuthoritativeCombatEncounter.SHARED_MONSTER_ID));
            assertEquals(6, combatHealth(returning, clientTarget));
        }
        finally {
            client.close();
            if(returning != null) returning.close();
            fixture.close();
        }
    }

    @Test public void reconnectReadyRestoresActiveNativeProjectile() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("dynamic-reconnect-floor");
        HostFixture fixture = host(compatibility, 2, "dynamic-reconnect");
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        DirectConnectClient returning = null;
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);

            com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile liveProjectile =
                    new com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile();
            liveProjectile.x = 4f; liveProjectile.y = 5f; liveProjectile.z = 0.75f;
            liveProjectile.spriteAtlas = "reconnect-blue"; liveProjectile.tex = 9;
            fixture.host.synchronizeNativeDynamicState(
                    com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState.capture(
                            88L, 0L, liveProjectile));
            assertTrue(awaitNativeDynamic(client, 88L, true).active);
            long tokenDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(tokens.load("dynamic-reconnect") == null
                    && System.currentTimeMillis() < tokenDeadline) Thread.sleep(10L);
            assertNotNull(tokens.load("dynamic-reconnect"));

            client.close();
            long reconnectDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(fixture.host.getReconnectGrace(2) == null
                    && System.currentTimeMillis() < reconnectDeadline) Thread.sleep(10L);
            assertNotNull(fixture.host.getReconnectGrace(2));
            returning = client(fixture.host.getBoundPort(), '2', "Friend",
                    AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
            long readyDeadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while(returning.getStatus().getPhase() != DirectConnectPhase.READY
                    && System.currentTimeMillis() < readyDeadline) Thread.sleep(10L);
            assertEquals("Client: " + returning.getStatus().getMessage() + "; Host: "
                            + fixture.host.getStatus().getPhase() + " / "
                            + fixture.host.getStatus().getMessage(), DirectConnectPhase.READY,
                    returning.getStatus().getPhase());

            com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState restored =
                    awaitNativeDynamic(returning, 88L, true);
            assertEquals("reconnect-blue", restored.apply(null).spriteAtlas);
            assertEquals(4f, restored.apply(null).x, 0f);
        }
        finally {
            client.close();
            if(returning != null) returning.close();
            fixture.close();
        }
    }

    @Test
    public void reconnectGraceExpiresOnlyAfterUnpausedHostTicksAndReturnsSlotSafely()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("reconnect-expiry-floor");
        HostFixture fixture = host(compatibility, 2, "reconnect-expiry",
                temporaryFolder.newFolder("reconnect-expiry-store"), 8L);
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            String reconnectToken = fixture.roster.getSlot(2).getReconnectToken();
            client.close();

            fixture.host.setSessionPaused(true);
            Thread.sleep(250L);
            assertTrue(fixture.host.getReconnectGrace(2) != null);
            fixture.host.setSessionPaused(false);
            awaitPartyState(fixture.host, 2, PartyMemberState.DISCONNECTED);
            assertNull(fixture.host.getReconnectGrace(2));
            assertEquals(1, fixture.host.getMovementEntities().size());
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertEquals(reconnectToken, fixture.roster.getSlot(2).getReconnectToken());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void expiredReconnectHandshakeCannotStartAnotherGraceWindow() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("expired-reconnect-floor");
        HostFixture fixture = host(compatibility, 2, "expired-reconnect",
                temporaryFolder.newFolder("expired-reconnect-store"), 60L);
        MemoryReconnectTokens tokens = new MemoryReconnectTokens();
        DirectConnectClient client = client(fixture.host.getBoundPort(), '2', "Friend",
                AvatarCatalog.HUMANOID_2, 0, tokens, compatibility);
        Socket reconnecting = null;
        try {
            awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.LOBBY);
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            client.close();
            awaitPartyState(fixture.host, 2, PartyMemberState.RECONNECTING);
            assertTrue(fixture.host.getReconnectGrace(2) != null);

            reconnecting = new Socket();
            reconnecting.connect(new InetSocketAddress("127.0.0.1",
                    fixture.host.getBoundPort()));
            reconnecting.setSoTimeout(4000);
            writeTcp(reconnecting, new DirectConnectWire.ClientHello(
                    DirectConnectProtocol.VERSION, compatibility.getBuildId(),
                    compatibility.getContentFormat(), compatibility.getContentSha256(),
                    identity('2').getValue()));
            Message challengeMessage = readTcp(reconnecting);
            assertTrue(challengeMessage instanceof DirectConnectWire.CampaignChallenge);
            DirectConnectWire.CampaignChallenge challenge =
                    (DirectConnectWire.CampaignChallenge)challengeMessage;
            writeTcp(reconnecting, new DirectConnectWire.SlotClaim(challenge.sessionId,
                    "Friend", AvatarCatalog.HUMANOID_2, 2,
                    tokens.load("expired-reconnect")));
            assertTrue(readTcp(reconnecting) instanceof DirectConnectWire.ServerAccepted);

            Message expired = readTcp(reconnecting);
            assertTrue(expired instanceof DirectConnectWire.ServerDisconnect);
            awaitPartyState(fixture.host, 2, PartyMemberState.DISCONNECTED);
            assertNull(fixture.host.getReconnectGrace(2));
        }
        finally {
            if(reconnecting != null) reconnecting.close();
            client.close();
            fixture.close();
        }
    }

    @Test
    public void hostKickDisconnectsOnlyLiveSessionAndPreservesCampaignOwnership()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("kick-floor");
        HostFixture fixture = host(compatibility, 2, "kick");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.startSession();
            awaitPhase(client, DirectConnectPhase.READY);
            String reconnectToken = fixture.roster.getSlot(2).getReconnectToken();

            assertTrue(fixture.host.kick(identity('2').getValue()));
            awaitPhase(client, DirectConnectPhase.DISCONNECTED);
            awaitPartyState(fixture.host, 2, PartyMemberState.DISCONNECTED);
            assertNull(fixture.host.getReconnectGrace(2));
            assertEquals(identity('2'), fixture.roster.getSlot(2).getLauncherIdentity());
            assertEquals(reconnectToken, fixture.roster.getSlot(2).getReconnectToken());
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    public void fullRosterAndOccupiedHostSlotRejectUnknownIdentityWithoutReassignment()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("protected-roster");
        HostFixture full = host(compatibility, 2, "full");
        full.roster.approve(new com.interrupt.dungeoneer.multiplayer.lobby.SlotClaimRequest(
                        identity('2'), new SlotPresentation("Owner", AvatarCatalog.HUMANOID_2),
                        2, null), new SecureRandom());
        full.store.save(full.roster);
        DirectConnectClient unknown = client(full.host.getBoundPort(), '3', "Unknown",
                AvatarCatalog.HUMANOID_3, 0, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(unknown, DirectConnectPhase.REJECTED);
            assertTrue(unknown.getStatus().getMessage().startsWith("CAMPAIGN_FULL"));
            assertEquals(identity('2'), full.roster.getSlot(2).getLauncherIdentity());
        }
        finally {
            unknown.close();
            full.close();
        }

        HostFixture occupied = host(compatibility, 3, "occupied");
        DirectConnectClient claimant = client(occupied.host.getBoundPort(), '3', "Claimant",
                AvatarCatalog.HUMANOID_3, 1, new MemoryReconnectTokens(), compatibility);
        try {
            awaitPhase(claimant, DirectConnectPhase.REJECTED);
            assertTrue(claimant.getStatus().getMessage().startsWith("SLOT_OCCUPIED"));
            assertEquals(identity('1'), occupied.roster.getSlot(1).getLauncherIdentity());
        }
        finally {
            claimant.close();
            occupied.close();
        }
    }

    @Test
    public void explicitBuildAndContentMismatchesAreRejectedBeforeSlotClaim() throws Exception {
        DirectConnectCompatibility hostCompatibility = compatibility("host-floor");
        HostFixture fixture = host(hostCompatibility, 2, "compatibility");
        DirectConnectClient client = null;
        try {
            DirectConnectCompatibility wrongBuild = new DirectConnectCompatibility(
                    "different-build", hostCompatibility.getContentFormat(),
                    hostCompatibility.getContentSha256());
            client = client(fixture.host.getBoundPort(), '2', "Wrong Build",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), wrongBuild);
            awaitPhase(client, DirectConnectPhase.REJECTED);
            assertTrue(client.getStatus().getMessage().contains("Build mismatch"));
            client.close();

            DirectConnectCompatibility wrongContent = compatibility("different-floor");
            client = client(fixture.host.getBoundPort(), '2', "Wrong Content",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), wrongContent);
            awaitPhase(client, DirectConnectPhase.REJECTED);
            assertTrue(client.getStatus().getMessage().contains("Content mismatch"));
            assertTrue(fixture.roster.findSlot(identity('2')) == null);
        }
        finally {
            if(client != null) client.close();
            fixture.close();
        }
    }

    @Test
    public void malformedHandshakeRejectsOnlyOffendingConnection() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("safe-floor");
        HostFixture fixture = host(compatibility, 2, "malformed");
        DirectConnectClient validClient = null;
        try {
            Socket malformed = new Socket();
            malformed.connect(new InetSocketAddress("127.0.0.1", fixture.host.getBoundPort()));
            malformed.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(malformed.getOutputStream());
            output.writeInt(5);
            output.writeInt(0x01020304);
            output.writeByte(99);
            output.flush();

            DataInputStream input = new DataInputStream(malformed.getInputStream());
            int responseLength = input.readInt();
            assertTrue(responseLength > 0);
            assertTrue(responseLength <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
            byte[] response = new byte[responseLength];
            input.readFully(response);
            malformed.close();

            ByteBuf responseBuffer = Unpooled.wrappedBuffer(response);
            try {
                Message rejection = DirectConnectWire.decodeDatagram(responseBuffer);
                assertTrue(rejection instanceof ServerRejected);
                assertEquals(DirectConnectWire.RejectCode.MALFORMED_HANDSHAKE,
                        ((ServerRejected)rejection).code);
            }
            finally {
                responseBuffer.release();
            }

            validClient = client(fixture.host.getBoundPort(), '2', "After Malformed",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
            awaitPhase(validClient, DirectConnectPhase.AWAITING_APPROVAL);
            assertTrue(fixture.host.approve(identity('2').getValue()));
            awaitPhase(validClient, DirectConnectPhase.LOBBY);
        }
        finally {
            if(validClient != null) validClient.close();
            fixture.close();
        }
    }

    @Test
    public void protocolMismatchIsExplicitAndDoesNotStopHost() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("protocol-floor");
        HostFixture fixture = host(compatibility, 2, "protocol");
        ByteBuf wrongProtocol = DirectConnectWire.encodeDatagram(
                UnpooledByteBufAllocator.DEFAULT,
                new DirectConnectWire.ClientHello(DirectConnectProtocol.VERSION + 1,
                        compatibility.getBuildId(), compatibility.getContentFormat(),
                        compatibility.getContentSha256(), identity('2').getValue()));
        DirectConnectClient validClient = null;
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", fixture.host.getBoundPort()));
            socket.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(wrongProtocol.readableBytes());
            wrongProtocol.readBytes(output, wrongProtocol.readableBytes());
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int responseLength = input.readInt();
            byte[] response = new byte[responseLength];
            input.readFully(response);
            socket.close();

            ByteBuf responseBuffer = Unpooled.wrappedBuffer(response);
            try {
                Message rejection = DirectConnectWire.decodeDatagram(responseBuffer);
                assertTrue(rejection instanceof ServerRejected);
                assertEquals(DirectConnectWire.RejectCode.PROTOCOL_MISMATCH,
                        ((ServerRejected)rejection).code);
            }
            finally {
                responseBuffer.release();
            }

            validClient = client(fixture.host.getBoundPort(), '2', "Valid",
                    AvatarCatalog.HUMANOID_2, 0, new MemoryReconnectTokens(), compatibility);
            awaitPhase(validClient, DirectConnectPhase.AWAITING_APPROVAL);
        }
        finally {
            wrongProtocol.release();
            if(validClient != null) validClient.close();
            fixture.close();
        }
    }

    @Test
    public void hostCanDisconnectApprovedClientCleanly() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("disconnect-floor");
        HostFixture fixture = host(compatibility, 2, "disconnect");
        DirectConnectClient client = approveClient(fixture, compatibility, '2', "Friend",
                AvatarCatalog.HUMANOID_2);
        try {
            fixture.host.close();
            awaitPhase(client, DirectConnectPhase.DISCONNECTED);
            assertTrue(client.getStatus().getMessage().contains("Host disconnected cleanly"));
        }
        finally {
            client.close();
            fixture.close();
        }
    }

    private DirectConnectClient approveClient(HostFixture fixture,
            DirectConnectCompatibility compatibility, char identity, String nickname,
            String avatar) throws Exception {
        DirectConnectClient client = client(fixture.host.getBoundPort(), identity, nickname,
                avatar, 0, new MemoryReconnectTokens(), compatibility);
        awaitPhase(client, DirectConnectPhase.AWAITING_APPROVAL);
        assertTrue(fixture.host.approve(identity(identity).getValue()));
        awaitPhase(client, DirectConnectPhase.LOBBY);
        return client;
    }

    private DirectConnectClient client(int port, char identity, String nickname, String avatar,
            int requestedSlot, ReconnectTokenStore reconnectTokens,
            DirectConnectCompatibility compatibility) {
        return DirectConnectClient.connect("127.0.0.1", port, identity(identity),
                new SlotPresentation(nickname, avatar), requestedSlot, reconnectTokens,
                compatibility);
    }

    private HostFixture host(DirectConnectCompatibility compatibility, int capacity,
            String campaignId) throws Exception {
        return host(compatibility, capacity, campaignId,
                temporaryFolder.newFolder("campaign-store-" + campaignStoreCounter++));
    }

    private HostFixture host(DirectConnectCompatibility compatibility, int capacity,
            String campaignId, File storageRoot) {
        return host(compatibility, capacity, campaignId, storageRoot,
                DirectConnectHost.RECONNECT_GRACE_TICKS);
    }

    private HostFixture host(DirectConnectCompatibility compatibility, int capacity,
            String campaignId, File storageRoot, long reconnectGraceTicks) {
        CampaignRosterStore store = new CampaignRosterStore(storageRoot, new SecureRandom());
        CampaignRoster roster = store.loadOrCreate(campaignId, capacity,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        DirectConnectHost host = DirectConnectHost.startForTest(0, compatibility, roster, store,
                new com.interrupt.dungeoneer.multiplayer.movement.RectangularMovementCollisionWorld(
                        32f, 32f, 16.5f, 16.5f, 0.5f), reconnectGraceTicks);
        return new HostFixture(host, roster, store);
    }

    private DirectConnectCompatibility compatibility(String floor) {
        return DirectConnectCompatibility.forOpenSourceTestFloor(
                floor.getBytes(StandardCharsets.UTF_8));
    }

    private LauncherIdentity identity(char value) {
        StringBuilder result = new StringBuilder(LauncherIdentity.ENCODED_LENGTH);
        while(result.length() < LauncherIdentity.ENCODED_LENGTH) result.append(value);
        return new LauncherIdentity(result.toString());
    }

    private void awaitPendingCount(DirectConnectHost host, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(host.getPendingClaims().size() == count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " pending Campaign Slot claims.");
    }

    private void awaitMovementSnapshots(DirectConnectPeer peer, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getMovementSnapshots().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " authoritative movement snapshots.");
    }

    private void awaitCombatSnapshot(DirectConnectPeer peer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getCombatSnapshot() != null) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for authoritative combat state.");
    }

    private CombatRequest awaitNativeCombatRequest(DirectConnectHost host)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            List<CombatRequest> requests = host.drainNativeCombatRequests();
            if(!requests.isEmpty()) {
                assertEquals("Only submitted native action expected", 1, requests.size());
                return requests.get(0);
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Host-native combat request.");
        return null;
    }

    private void submitDirectedAtMonster(DirectConnectPeer peer, long requestId,
            CombatAction action) throws InterruptedException {
        awaitMovementSnapshots(peer, 1);
        awaitCombatSnapshot(peer);
        List<MovementSnapshot> movementSnapshots = peer.getMovementSnapshots();
        MovementEntityState source = movementSnapshots.get(movementSnapshots.size() - 1)
                .getEntity(peer.getLocalMovementEntityId());
        CombatSnapshot combat = peer.getCombatSnapshot();
        float aimX = combat.getMonsterX() - source.getX();
        float aimY = combat.getMonsterY() - source.getY();
        float aimLength = (float)Math.sqrt(aimX * aimX + aimY * aimY);
        assertTrue(aimLength > 0.0001f);
        peer.submitCombatAction(requestId, action,
                aimX / aimLength, aimY / aimLength, 0f);
    }

    private void submitDirectedAtParticipant(DirectConnectPeer peer, long requestId,
            CombatAction action, int campaignSlot) throws InterruptedException {
        awaitMovementSnapshots(peer, 1);
        List<MovementSnapshot> movementSnapshots = peer.getMovementSnapshots();
        MovementSnapshot movement = movementSnapshots.get(movementSnapshots.size() - 1);
        MovementEntityState source = movement.getEntity(peer.getLocalMovementEntityId());
        MovementEntityState target = movement.getEntity(new NetworkEntityId(campaignSlot));
        assertNotNull(source);
        assertNotNull(target);
        float aimX = target.getX() - source.getX();
        float aimY = target.getY() - source.getY();
        float aimLength = (float)Math.sqrt(aimX * aimX + aimY * aimY);
        assertTrue(aimLength > 0.0001f);
        peer.submitCombatAction(requestId, action,
                aimX / aimLength, aimY / aimLength, 0f);
    }

    private void awaitCombatHealth(DirectConnectPeer peer, String targetId, int health)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(combatHealth(peer, targetId) == health) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for authoritative combat health=" + health + ".");
    }

    private void awaitCombatCadence(DirectConnectHost host, CombatAction action)
            throws InterruptedException {
        long readyTick = host.getCombatSnapshot().getHostTick()
                + action.getMinimumIntervalTicks();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(host.getCombatSnapshot().getHostTick() >= readyTick) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for combat cadence " + action + ".");
    }

    private void awaitCombatPresentations(DirectConnectPeer peer, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getCombatPresentationEvents().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " combat presentations.");
    }

    private void awaitMonsterTarget(DirectConnectPeer peer, String targetId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            CombatSnapshot snapshot = peer.getCombatSnapshot();
            if(snapshot != null && targetId.equals(snapshot.getMonsterTargetId())) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for monster target " + targetId + ".");
    }

    private void awaitMonsterTargetOtherThan(DirectConnectPeer peer, String targetId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            CombatSnapshot snapshot = peer.getCombatSnapshot();
            if(snapshot != null && !targetId.equals(snapshot.getMonsterTargetId())) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for monster to release target " + targetId + ".");
    }

    private int combatHealth(DirectConnectPeer peer, String targetId) {
        CombatSnapshot snapshot = peer.getCombatSnapshot();
        return snapshot == null || snapshot.getCombatant(targetId) == null
                ? -1 : snapshot.getCombatant(targetId).getHealth();
    }

    private PartyMemberStatus awaitPartyState(DirectConnectPeer peer, int campaignSlot,
            PartyMemberState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getPartyStatus() != null) {
                PartyMemberStatus member = peer.getPartyStatus().getMember(campaignSlot);
                if(member != null && member.getState() == expected) return member;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Campaign Slot " + campaignSlot
                + " Party state " + expected + ".");
        return null;
    }

    private void awaitPartyHealth(DirectConnectPeer peer, int campaignSlot, int health)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            PartyStatusSnapshot snapshot = peer.getPartyStatus();
            PartyMemberStatus member = snapshot == null ? null : snapshot.getMember(campaignSlot);
            if(member != null && member.getHealth() == health) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Campaign Slot " + campaignSlot
                + " Party health=" + health + ".");
    }

    private void awaitChatCount(DirectConnectPeer peer, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getPartyCommunicationState().getChatHistory().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + count + " Party chat deliveries.");
    }

    private void awaitPauseRequest(DirectConnectPeer peer, int campaignSlot)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            PartyCommunicationState state = peer.getPartyCommunicationState();
            if(state.getLatestPauseRequest() != null
                    && state.getLatestPauseRequest().getCampaignSlot() == campaignSlot) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Pause Session request.");
    }

    private void awaitPauseState(DirectConnectPeer peer, boolean paused)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.isSessionPaused() == paused) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for Pause Session=" + paused + ".");
    }

    private void awaitMovementSnapshotCount(DirectConnectPeer peer, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            if(peer.getMovementSnapshots().size() >= count) return;
            Thread.sleep(10L);
        }
        fail("Timed out waiting for resumed authoritative movement.");
    }

    private void awaitAcknowledgedInput(DirectConnectClient client, long inputTick)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            List<MovementSnapshot> snapshots = client.getMovementSnapshots();
            if(!snapshots.isEmpty() && client.getLocalMovementEntityId() != null) {
                MovementEntityState state = snapshots.get(snapshots.size() - 1)
                        .getEntity(client.getLocalMovementEntityId());
                if(state != null && state.getLastProcessedInputTick() >= inputTick) return;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for movement input acknowledgement " + inputTick + ".");
    }

    private MovementEntityState latestLocalMovement(DirectConnectClient client) {
        List<MovementSnapshot> snapshots = client.getMovementSnapshots();
        return snapshots.get(snapshots.size() - 1)
                .getEntity(client.getLocalMovementEntityId());
    }

    private void writeTcp(Socket socket, Message message) throws Exception {
        ByteBuf encoded = DirectConnectWire.encodeDatagram(UnpooledByteBufAllocator.DEFAULT,
                message);
        try {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(encoded.readableBytes());
            encoded.readBytes(output, encoded.readableBytes());
            output.flush();
        }
        finally {
            encoded.release();
        }
    }

    private Message readTcp(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        assertTrue(length > 0 && length <= DirectConnectProtocol.MAX_TCP_FRAME_BYTES);
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        ByteBuf buffer = Unpooled.wrappedBuffer(encoded);
        try {
            return DirectConnectWire.decodeDatagram(buffer);
        }
        finally {
            buffer.release();
        }
    }

    private void awaitPhase(DirectConnectPeer peer, DirectConnectPhase phase)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while(System.currentTimeMillis() < deadline) {
            DirectConnectStatus status = peer.getStatus();
            if(status.getPhase() == phase) return;
            if(status.getPhase() == DirectConnectPhase.FAILED
                    && phase != DirectConnectPhase.FAILED) {
                fail("Direct Connect failed while waiting for " + phase + ": "
                        + status.getMessage());
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for " + phase + "; last state was "
                + peer.getStatus().getPhase() + ": " + peer.getStatus().getMessage());
    }

    private static final class HostFixture implements AutoCloseable {
        private final DirectConnectHost host;
        private final CampaignRoster roster;
        private final CampaignRosterStore store;

        private HostFixture(DirectConnectHost host, CampaignRoster roster,
                CampaignRosterStore store) {
            this.host = host;
            this.roster = roster;
            this.store = store;
        }

        @Override
        public void close() {
            host.close();
        }
    }

    private static final class MemoryReconnectTokens implements ReconnectTokenStore {
        private final Map<String, String> tokens = new HashMap<String, String>();

        @Override
        public synchronized String load(String campaignId) {
            return tokens.get(campaignId);
        }

        @Override
        public synchronized void save(String campaignId, String reconnectToken) {
            tokens.put(campaignId, reconnectToken);
        }
    }
}
