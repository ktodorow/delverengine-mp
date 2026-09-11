package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Bow;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.items.Wand;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.entities.spells.Heal;
import com.interrupt.dungeoneer.entities.spells.Spell;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.helpers.PlayerHistory;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DirectConnectCombatControllerTest {
    @Test
    public void replaysEachRemotePresentationOnceAndSkipsLocalEcho() {
        StubPeer peer = new StubPeer();
        peer.presentations.add(event(1L, "participant:campaign-slot-2", CombatAction.MELEE));
        peer.presentations.add(event(2L, "participant:campaign-slot-1", CombatAction.SPELL));
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Level level = new Level(4, 4);

        controller.replayPresentations(level, "participant:campaign-slot-1");

        assertEquals(1, level.non_collidable_entities.size);
        assertTrue(level.non_collidable_entities.first() instanceof CombatPresentationEffect);
        assertEquals(2L, controller.getLastPresentationSequence());

        controller.replayPresentations(level, "participant:campaign-slot-1");
        assertEquals(1, level.non_collidable_entities.size);

        peer.presentations.add(event(3L, "participant:campaign-slot-2",
                CombatAction.PROJECTILE));
        controller.replayPresentations(level, "participant:campaign-slot-1");

        assertEquals("Native dynamic state owns projectile travel without a duplicate trace",
                1, level.non_collidable_entities.size);
        assertEquals(3L, controller.getLastPresentationSequence());
    }

    @Test
    public void nativeWeaponAttackSubmitsWorldAxisAimToHost() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);

        controller.onWeaponAttack(new Sword(), new Vector3(0.25f, -0.5f, 0.75f));

        assertEquals(1L, peer.directedRequestId);
        assertEquals(CombatAction.MELEE, peer.directedAction);
        assertEquals(0.25f, peer.aimX, 0f);
        assertEquals(0.75f, peer.aimY, 0f);
        assertEquals(-0.5f, peer.aimZ, 0f);
    }

    @Test
    public void elementalSwordStillSubmitsNativeMeleeRelease() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Sword sword = new Sword();
        sword.damageType = DamageType.ICE;

        controller.onWeaponAttack(sword, new Vector3(1f, 0f, 0f));

        assertEquals(CombatAction.MELEE, peer.directedAction);
    }

    @Test
    public void startsAtHostIssuedCombatRequestIdAfterReconnect() {
        StubPeer peer = new StubPeer();
        peer.nextCombatRequestId = 42L;
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);

        controller.onWeaponAttack(new Sword(), new Vector3(1f, 0f, 0f));

        assertEquals(42L, peer.directedRequestId);
    }

    @Test
    public void exhaustedHostIssuedRequestIdRefusesNewIntents() {
        StubPeer peer = new StubPeer();
        peer.nextCombatRequestId = Long.MAX_VALUE;
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);

        controller.onWeaponAttack(new Sword(), new Vector3(1f, 0f, 0f));
        boolean consumed = controller.onHealthIntent(new Player(), 1, DamageType.FIRE, null);

        assertTrue(consumed);
        assertTrue(peer.submittedRequestIds.isEmpty());
    }

    @Test
    public void playerWeaponListenerDeliversAttackToCombatController() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Player player = new Player();
        player.setWeaponAttackListener(controller);

        player.notifyWeaponAttack(new Sword(), new Vector3(1f, 0f, 0f));

        assertEquals(1L, peer.directedRequestId);
        assertEquals(CombatAction.MELEE, peer.directedAction);
        assertEquals(1f, peer.aimX, 0f);
    }

    @Test
    public void nativeDamageIsDeferredToHostWithoutChangingLocalHealth() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Player player = new Player();
        player.hp = 8;
        player.setHealthAuthorityListener(controller);

        int tookDamage = player.takeDamage(7, DamageType.FIRE, null);

        assertEquals(0, tookDamage);
        assertEquals(8, player.hp);
        assertEquals(1L, peer.targetedRequestId);
        assertEquals(CombatAction.ENVIRONMENTAL_HAZARD, peer.targetedAction);
        assertEquals("participant:campaign-slot-1", peer.targetId);

        player.takeDamage(7, DamageType.PHYSICAL, player);
        assertEquals(2L, peer.targetedRequestId);
        assertEquals(CombatAction.SELF_DAMAGE, peer.targetedAction);
        assertEquals(8, player.hp);
    }

    @Test
    public void replicaSelfHealDoesNotInventASecondGameplayRequest() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Player player = new Player();
        player.hp = 2;
        player.setHealthAuthorityListener(controller);
        player.setStatusEffectAuthority(false);

        new Heal().doCast(player, new Vector3(), new Vector3());

        assertEquals(2, player.hp);
        assertEquals(0L, peer.targetedRequestId);
        assertTrue(peer.submittedRequestIds.isEmpty());
    }

    @Test
    public void nativeHealWandSubmitsOnlyAcceptedWeaponAttack() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Player player = new Player();
        player.hp = 2;
        player.history = new PlayerHistory() {
            @Override public void usedWand(Item item) { }
        };
        player.setWeaponAttackListener(controller);
        player.setHealthAuthorityListener(controller);
        player.setStatusEffectAuthority(false);

        new TestHealWand().doAttack(player, new Level(4, 4), 1f);

        assertEquals(2, player.hp);
        assertEquals(Arrays.asList(1L), peer.submittedRequestIds);
        assertEquals(Arrays.asList(CombatAction.SPELL),
                peer.submittedActions);
        assertEquals(Arrays.asList(true), peer.submittedDirected);
        assertEquals(0L, peer.targetedRequestId);
    }

    @Test
    public void clientWandKeepsCastFeedbackButDefersWorldSpellToHost() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Player player = new Player();
        player.history = new PlayerHistory() {
            @Override public void usedWand(Item item) { }
        };
        player.setWeaponAttackListener(controller);
        final int[] gameplayCasts = { 0 }, presentationCasts = { 0 };
        Wand wand = new TestPresentationWand(gameplayCasts, presentationCasts);

        wand.doAttack(player, new Level(4, 4), 1f);

        assertEquals(0, gameplayCasts[0]);
        assertEquals(1, presentationCasts[0]);
        assertEquals(Arrays.asList(1L), peer.submittedRequestIds);
    }

    @Test
    public void clientSwordSubmitsReleaseWithoutMutatingLocalWorld() {
        com.badlogic.gdx.graphics.PerspectiveCamera previous =
                com.interrupt.dungeoneer.game.Game.camera;
        com.interrupt.dungeoneer.game.Game.camera =
                new com.badlogic.gdx.graphics.PerspectiveCamera();
        com.interrupt.dungeoneer.game.Game.camera.direction.set(1f, 0f, 0f);
        try {
            StubPeer peer = new StubPeer();
            DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
            Player player = new Player();
            player.setWeaponAttackListener(controller);
            final int[] collisionChecks = { 0 };
            Level level = new Level(4, 4) {
                @Override public Entity checkEntityCollision(float x, float y, float z,
                        float widthX, float widthY, float height, Entity checking, Entity ignore) {
                    collisionChecks[0]++;
                    return new Entity();
                }
            };

            new Sword().tickAttack(player, level, 11f);

            assertEquals(Arrays.asList(1L), peer.submittedRequestIds);
            assertEquals(Arrays.asList(CombatAction.MELEE), peer.submittedActions);
            assertEquals(0, collisionChecks[0]);
        }
        finally {
            com.interrupt.dungeoneer.game.Game.camera = previous;
        }
    }

    @Test
    public void nativeDynamicReplicaTracksHostStateAndClearsOnFloorGeneration() {
        com.interrupt.dungeoneer.game.Game previous = com.interrupt.dungeoneer.game.Game.instance;
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd()
                    .newInstance(com.interrupt.dungeoneer.game.Game.class);
            com.interrupt.dungeoneer.game.Game.instance = game;
            game.player = new Player(); game.level = new Level(4, 4);
            com.interrupt.dungeoneer.entities.Bomb host = new com.interrupt.dungeoneer.entities.Bomb();
            host.x = 1f; host.y = 2f; host.countdownTimer = 40f;
            peer.dynamicStates.add(NativeDynamicState.capture(5L, 0L, host));

            controller.prepare(game);

            assertEquals(1, game.level.non_collidable_entities.size);
            com.interrupt.dungeoneer.entities.Bomb replica =
                    (com.interrupt.dungeoneer.entities.Bomb)game.level.non_collidable_entities.first();
            assertTrue(replica.nativePresentationReplica);
            replica.tick(game.level, 10f);
            assertEquals("Replica cannot advance or explode its own fuse", 40f,
                    replica.countdownTimer, 0f);

            host.x = 3f; host.countdownTimer = 25f;
            peer.dynamicStates.set(0, NativeDynamicState.capture(5L, 0L, host));
            controller.prepare(game);
            assertEquals(1, game.level.non_collidable_entities.size);
            assertEquals(3f, replica.x, 0f); assertEquals(25f, replica.countdownTimer, 0f);

            peer.dynamicStates.clear(); peer.nativeGeneration++;
            controller.prepare(game);
            assertEquals(0, game.level.non_collidable_entities.size);
            assertTrue(!replica.isActive);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void acceptedPhysicalMissileIsInsertedAndKeepsElementalLight() {
        com.interrupt.dungeoneer.game.Game previousGame =
                com.interrupt.dungeoneer.game.Game.instance;
        java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        if(previousStrings == null) com.interrupt.managers.StringManager.localizedStrings =
                new java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
        StubPeer peer = new StubPeer();
        com.interrupt.dungeoneer.entities.projectiles.Missile hostMissile =
                new com.interrupt.dungeoneer.entities.projectiles.Missile();
        hostMissile.spriteAtlas = "ice-arrows"; hostMissile.tex = 21;
        hostMissile.damageType = DamageType.ICE;
        com.interrupt.dungeoneer.entities.DynamicLight light =
                new com.interrupt.dungeoneer.entities.DynamicLight();
        light.lightColor.set(0.1f, 0.4f, 1f); light.range = 2.5f;
        hostMissile.attach(light);
        peer.dynamicStates.add(NativeDynamicState.capture(8L, 44L, hostMissile));
        final com.interrupt.dungeoneer.entities.projectiles.Missile physicalMissile =
                new com.interrupt.dungeoneer.entities.projectiles.Missile();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        controller.setWeaponResolver(new CombatWeaponResolver() {
            @Override public long identity(com.interrupt.dungeoneer.entities.items.Weapon weapon) {
                return 0L;
            }
            @Override public Entity physicalEntity(long entityId) {
                return entityId == 44L ? physicalMissile : null;
            }
            @Override public com.interrupt.dungeoneer.entities.items.Weapon ownedWeapon(
                    com.interrupt.dungeoneer.multiplayer.participant.ParticipantId participant,
                    long entityId) {
                return null;
            }
        });
        try {
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd()
                    .newInstance(com.interrupt.dungeoneer.game.Game.class);
            com.interrupt.dungeoneer.game.Game.instance = game;
            game.player = new Player(); game.level = new Level(4, 4);

            controller.prepare(game);

            assertEquals(1, game.level.entities.size
                    + game.level.non_collidable_entities.size
                    + game.level.static_entities.size);
            assertTrue(game.level.entities.contains(physicalMissile, true)
                    || game.level.non_collidable_entities.contains(physicalMissile, true)
                    || game.level.static_entities.contains(physicalMissile, true));
            assertTrue(physicalMissile.nativePresentationReplica);
            assertEquals("ice-arrows", physicalMissile.spriteAtlas);
            com.interrupt.dungeoneer.entities.DynamicLight replicaLight =
                    (com.interrupt.dungeoneer.entities.DynamicLight)physicalMissile.getAttached(
                            com.interrupt.dungeoneer.entities.DynamicLight.class);
            assertEquals(0.1f, replicaLight.lightColor.x, 0f);
            assertEquals(2.5f, replicaLight.range, 0f);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previousGame;
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
        }
    }

    @Test
    public void replaysAcceptedNativeItemFeedbackAndSuppressesOnlyOwnerStartupEcho() {
        com.interrupt.dungeoneer.game.Game previous = com.interrupt.dungeoneer.game.Game.instance;
        StubPeer peer = new StubPeer();
        final int[] spellCasts = { 0 };
        final int[] swordSwings = { 0 }, swordEntityHits = { 0 }, swordWorldHits = { 0 };
        final int[] breakableHits = { 0 };
        final int[] bowReleases = { 0 };
        Wand wand = new TestPresentationWand(new int[] { 0 }, spellCasts);
        Sword sword = new TestPresentationSword(swordSwings, swordEntityHits, swordWorldHits);
        Breakable breakable = new Breakable() {
            @Override public void playNetworkHitPresentation(float x, float y, float z,
                    Sword item, Level level) { breakableHits[0]++; }
        };
        TestPresentationBow bow = new org.objenesis.ObjenesisStd()
                .newInstance(TestPresentationBow.class);
        bow.releases = bowReleases;
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        controller.setWeaponResolver(new StubWeaponResolver(wand, sword, bow, breakable));
        try {
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd()
                    .newInstance(com.interrupt.dungeoneer.game.Game.class);
            com.interrupt.dungeoneer.game.Game.instance = game;
            game.player = new Player(); game.level = new Level(4, 4);
            Monster monster = new Monster(); monster.hostile = true;
            game.level.entities.add(monster);
            controller.prepare(game);
            assertEquals(1, controller.getMonsters().size());

            peer.spellPresentations.add(NativeSpellPresentation.capture(
                    "monster:1", 11L, wand.spell, new Vector3(1f, 2f, 0.5f), true));
            peer.spellPresentations.add(NativeSpellPresentation.capture(
                    "participant:campaign-slot-1", 11L, wand.spell,
                    new Vector3(1f, 2f, 0.5f), true));
            peer.meleePresentations.add(NativeMeleePresentation.capture(
                    "monster:1", 12L, NativeMeleePresentation.Kind.SWING,
                    new Vector3(1f, 2f, 0.5f), new Vector3(1f, 0f, 0f)));
            peer.meleePresentations.add(NativeMeleePresentation.capture(
                    "participant:campaign-slot-1", 12L,
                    NativeMeleePresentation.Kind.SWING, new Vector3(1f, 2f, 0.5f),
                    new Vector3(1f, 0f, 0f)));
            peer.meleePresentations.add(NativeMeleePresentation.capture(
                    "participant:campaign-slot-1", 12L,
                    NativeMeleePresentation.Kind.ENTITY_HIT, new Vector3(1f, 2f, 0.5f),
                    new Vector3(1f, 0f, 0f)));
            peer.meleePresentations.add(NativeMeleePresentation.capture(
                    "participant:campaign-slot-1", 12L,
                    NativeMeleePresentation.Kind.ENTITY_HIT, new Vector3(1f, 2f, 0.5f),
                    new Vector3(1f, 0f, 0f), 1000000L));
            peer.meleePresentations.add(NativeMeleePresentation.capture(
                    "participant:campaign-slot-1", 12L,
                    NativeMeleePresentation.Kind.WORLD_HIT, new Vector3(1f, 2f, 0.5f),
                    new Vector3(1f, 0f, 0f)));
            peer.rangedPresentations.add(NativeRangedPresentation.capture(
                    "monster:1", 13L, new Vector3(1f, 2f, 0.5f)));
            peer.rangedPresentations.add(NativeRangedPresentation.capture(
                    "participant:campaign-slot-1", 13L,
                    new Vector3(1f, 2f, 0.5f)));

            controller.update(game);

            assertTrue(peer.presentationFailures.toString(), peer.presentationFailures.isEmpty());
            assertEquals("Remote exact spell feedback replays once", 1, spellCasts[0]);
            assertEquals("Remote Sword startup replays once", 1, swordSwings[0]);
            assertEquals("Owner still receives each Host-accepted impact", 2, swordEntityHits[0]);
            assertEquals("Bound native object receives exact hit presentation", 1, breakableHits[0]);
            assertEquals("Owner receives Host-accepted wall mark", 1, swordWorldHits[0]);
            assertEquals("Remote exact Bow release replays once", 1, bowReleases[0]);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    private CombatPresentationEvent event(long sequence, String sourceId,
            CombatAction action) {
        return new CombatPresentationEvent(sequence, sequence * 3L, sourceId,
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, action, CombatPresentationPhase.ATTACK,
                1f, 2f, 0.5f, 5f, 6f, 0.25f, true);
    }

    private static final class TestHealWand extends Wand {
        private TestHealWand() {
            usesCharges = false;
            autoFire = true;
            spell = new Heal() {
                @Override public void playCastSound(Actor owner) { }
            };
            spell.doCastVfx = false;
        }

        @Override public Vector3 getCrosshairDirection(float zOffset) {
            return new Vector3(1f, 0f, 0f);
        }

        @Override public void makeFireEffect(Level level) { }
    }

    private static final class TestPresentationWand extends Wand {
        private TestPresentationWand(final int[] gameplay, final int[] presentation) {
            usesCharges = false;
            autoFire = true;
            spell = new Spell() {
                @Override public void doCast(com.interrupt.dungeoneer.entities.Entity owner,
                        Vector3 direction, Vector3 position) { gameplay[0]++; }
                @Override public void playCastSound(Actor owner) { presentation[0]++; }
            };
            spell.doCastVfx = false;
        }

        @Override public Vector3 getCrosshairDirection(float zOffset) {
            return new Vector3(1f, 0f, 0f);
        }

        @Override public void makeFireEffect(Level level) { }
    }

    private static final class TestPresentationSword extends Sword {
        private final int[] swings;
        private final int[] entityHits;
        private final int[] worldHits;

        private TestPresentationSword(int[] swings, int[] entityHits, int[] worldHits) {
            this.swings = swings; this.entityHits = entityHits; this.worldHits = worldHits;
        }

        @Override public void playNetworkSwingPresentation(float x, float y, float z) {
            swings[0]++;
        }

        @Override public void playNetworkEntityHitPresentation(float x, float y, float z,
                Level level) {
            entityHits[0]++;
        }

        @Override public void playNetworkWorldHitPresentation(float x, float y, float z,
                Level level, Vector3 direction) {
            worldHits[0]++;
        }
    }

    private static final class TestPresentationBow extends Bow {
        private int[] releases;

        @Override public void playNetworkFirePresentation(float x, float y, float z) {
            releases[0]++;
        }
    }

    private static final class StubWeaponResolver implements CombatWeaponResolver {
        private final Wand wand;
        private final Sword sword;
        private final Bow bow;
        private final Entity worldObject;

        private StubWeaponResolver(Wand wand, Sword sword, Bow bow) {
            this(wand, sword, bow, null);
        }

        private StubWeaponResolver(Wand wand, Sword sword, Bow bow, Entity worldObject) {
            this.wand = wand; this.sword = sword; this.bow = bow;
            this.worldObject = worldObject;
        }

        @Override public long identity(com.interrupt.dungeoneer.entities.items.Weapon weapon) {
            return weapon == wand ? 11L : weapon == sword ? 12L : weapon == bow ? 13L : 0L;
        }

        @Override public Entity physicalEntity(long entityId) {
            return entityId == 11L ? wand : entityId == 12L ? sword
                    : entityId == 13L ? bow : null;
        }

        @Override public Entity worldObject(long entityId) {
            return entityId == 1000000L ? worldObject : null;
        }

        @Override public com.interrupt.dungeoneer.entities.items.Weapon ownedWeapon(
                com.interrupt.dungeoneer.multiplayer.participant.ParticipantId participant,
                long entityId) {
            Entity entity = physicalEntity(entityId);
            return entity instanceof com.interrupt.dungeoneer.entities.items.Weapon
                    ? (com.interrupt.dungeoneer.entities.items.Weapon)entity : null;
        }
    }

    private static final class StubPeer implements DirectConnectPeer {
        private final List<CombatPresentationEvent> presentations =
                new ArrayList<CombatPresentationEvent>();
        private long directedRequestId;
        private CombatAction directedAction;
        private float aimX;
        private float aimY;
        private float aimZ;
        private long targetedRequestId;
        private CombatAction targetedAction;
        private String targetId;
        private final List<Long> submittedRequestIds = new ArrayList<Long>();
        private final List<CombatAction> submittedActions = new ArrayList<CombatAction>();
        private final List<Boolean> submittedDirected = new ArrayList<Boolean>();
        private long nextCombatRequestId = 1L;
        private long nativeGeneration = 1L;
        private final List<NativeDynamicState> dynamicStates = new ArrayList<NativeDynamicState>();
        private final List<NativeSpellPresentation> spellPresentations =
                new ArrayList<NativeSpellPresentation>();
        private final List<NativeMeleePresentation> meleePresentations =
                new ArrayList<NativeMeleePresentation>();
        private final List<NativeRangedPresentation> rangedPresentations =
                new ArrayList<NativeRangedPresentation>();
        private final List<String> presentationFailures = new ArrayList<String>();
        private final NetworkEntityId localEntityId = new NetworkEntityId(1L);
        private final PartyStatusSnapshot partyStatus = new PartyStatusSnapshot(1L,
                Arrays.asList(new PartyMemberStatus(1, localEntityId, "Host",
                        "humanoid-1", 8, 8, 3, PartyMemberState.CONNECTED)));

        @Override public DirectConnectStatus getStatus() { return null; }
        @Override public String getRole() { return "Test"; }
        @Override public String getEndpoint() { return "memory"; }
        @Override public NetworkEntityId getLocalMovementEntityId() { return localEntityId; }
        @Override public List<MovementEntityDescriptor> getMovementEntities() {
            return Collections.emptyList();
        }
        @Override public List<MovementSnapshot> getMovementSnapshots() {
            return Collections.emptyList();
        }
        @Override public CombatSnapshot getCombatSnapshot() { return null; }
        @Override public List<CombatPresentationEvent> getCombatPresentationEvents() {
            return new ArrayList<CombatPresentationEvent>(presentations);
        }
        @Override public long getNextCombatRequestId() { return nextCombatRequestId; }
        @Override public PartyStatusSnapshot getPartyStatus() { return partyStatus; }
        @Override public long getNativeWorldGeneration() { return nativeGeneration; }
        @Override public List<NativeDynamicState> getNativeDynamicStates() {
            return new ArrayList<NativeDynamicState>(dynamicStates);
        }
        @Override public List<NativeSpellPresentation> drainNativeSpellPresentations() {
            List<NativeSpellPresentation> drained =
                    new ArrayList<NativeSpellPresentation>(spellPresentations);
            spellPresentations.clear();
            return drained;
        }
        @Override public List<NativeMeleePresentation> drainNativeMeleePresentations() {
            List<NativeMeleePresentation> drained =
                    new ArrayList<NativeMeleePresentation>(meleePresentations);
            meleePresentations.clear();
            return drained;
        }
        @Override public List<NativeRangedPresentation> drainNativeRangedPresentations() {
            List<NativeRangedPresentation> drained =
                    new ArrayList<NativeRangedPresentation>(rangedPresentations);
            rangedPresentations.clear();
            return drained;
        }
        @Override public void failNativePresentation(String reason) {
            presentationFailures.add(reason);
        }
        @Override public PartyCommunicationState getPartyCommunicationState() {
            return PartyCommunicationState.initial();
        }
        @Override public void submitPartyChat(String text) { }
        @Override public void requestPauseSession() { }
        @Override public boolean isSessionPaused() { return false; }
        @Override public boolean canControlSessionPause() { return false; }
        @Override public void setSessionPaused(boolean paused) { }
        @Override public void submitCombatAction(long requestId, CombatAction action,
                String targetId) {
            submittedRequestIds.add(requestId);
            submittedActions.add(action);
            submittedDirected.add(false);
            targetedRequestId = requestId;
            targetedAction = action;
            this.targetId = targetId;
        }
        @Override public void submitCombatAction(long requestId, CombatAction action,
                float aimX, float aimY, float aimZ) {
            submittedRequestIds.add(requestId);
            submittedActions.add(action);
            submittedDirected.add(true);
            directedRequestId = requestId;
            directedAction = action;
            this.aimX = aimX;
            this.aimY = aimY;
            this.aimZ = aimZ;
        }
        @Override public void submitMovementInput(MovementInputFrame input) { }
        @Override public void close() { }
    }
}
