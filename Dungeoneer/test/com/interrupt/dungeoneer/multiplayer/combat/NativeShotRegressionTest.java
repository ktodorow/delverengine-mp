package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.gfx.Material;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.entities.spells.MagicMissile;
import com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar;
import com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.items.Wand;
import com.interrupt.dungeoneer.entities.items.Bow;
import com.interrupt.dungeoneer.entities.items.FusedBomb;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.projectiles.Missile;
import com.interrupt.dungeoneer.gfx.animation.ProjectileAttackAction;
import com.interrupt.dungeoneer.gfx.animation.SpellCastAction;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.*;
import org.junit.Test;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class NativeShotRegressionTest {
    @Test
    public void hostShotPublishesWithoutAnyMonstersOnFloor() {
        final List<CombatRequest> requests = new ArrayList<CombatRequest>();
        final List<Object[]> presentations = new ArrayList<Object[]>();
        ParticipantId id = new ParticipantId("campaign-slot-1");
        requests.add(new CombatRequest(id, 1L, CombatAction.SPELL, 1, 0, 0, 1));
        DirectConnectPeer peer = peer(requests, presentations);
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Game game = game();
        Wand wand = new Wand();
        game.player.inventory.add(wand);
        game.player.heldItem = 0;
        MagicMissileProjectile shot =
                new MagicMissileProjectile();
        shot.owner = game.player; shot.xa = 0.17f; shot.color = Color.BLUE.cpy();
        game.level.entities.add(shot);
        controller.update(game);
        assertEquals("Host shot must reach presentation stream even on empty floor", 1, presentations.size());
        assertEquals("participant:campaign-slot-1", presentations.get(0)[0]);
        assertTrue(requests.isEmpty());
        ProjectileVisual visual = (ProjectileVisual)presentations.get(0)[11];
        assertEquals(Color.rgba8888(Color.BLUE), visual.rgba);
        assertEquals(0.17f, visual.speed, 0.0001f);
    }

    @Test
    public void hostWeaponResolutionRetainsActualBlueWand() throws Exception {
        DirectConnectCombatController controller = new DirectConnectCombatController(
                peer(new ArrayList<CombatRequest>(), new ArrayList<Object[]>()), false);
        Game game = game();
        Wand wand = new Wand();
        wand.spell.spellColor = Color.BLUE.cpy();
        game.player.inventory.add(wand);
        game.player.heldItem = 0;
        controller.prepare(game);
        Method resolve = DirectConnectCombatController.class.getDeclaredMethod(
                "authoritativeWeapon", ParticipantId.class, CombatAction.class);
        resolve.setAccessible(true);
        assertSame("Combat must use actual inventory weapon, not generic purple wand", wand,
                resolve.invoke(controller, new ParticipantId("campaign-slot-1"), CombatAction.SPELL));
    }

    @Test
    public void remoteOwnedBlueWandProducesBlueNativeShotAndBlueObserverEffect() throws Exception {
        ParticipantId remote = new ParticipantId("campaign-slot-2");
        final List<CombatRequest> requests = new ArrayList<CombatRequest>();
        final List<Object[]> events = new ArrayList<Object[]>();
        final List<NativeSpellPresentation> casts = new ArrayList<NativeSpellPresentation>();
        requests.add(new CombatRequest(remote, 1L, CombatAction.SPELL, 1, 0, 0, 1, 17L));
        DirectConnectPeer peer = peer(requests, events, new ArrayList<NativeDynamicCue>(),
                new ArrayList<NativeDynamicState>(), casts);
        DirectConnectMovementController movement =
                new DirectConnectMovementController(peer);
        MovementEntityDescriptor descriptor =
                new MovementEntityDescriptor(1L,
                        new NetworkEntityId(2L), remote, 2, "Client", "humanoid-2");
        RemoteAvatar avatar =
                new RemoteAvatar(descriptor);
        Field avatars = movement.getClass().getDeclaredField("remoteAvatars");
        avatars.setAccessible(true);
        ((Map)avatars.get(movement)).put(new NetworkEntityId(2L), avatar);
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, movement, false);
        final Wand wand = new Wand();
        MagicMissile blueSpell = new MagicMissile();
        blueSpell.doCastVfx = false;
        wand.spell = blueSpell;
        wand.spell.spellColor = Color.BLUE.cpy();
        blueSpell.appearance =
                new Material("sprite", (byte)8);
        controller.setWeaponResolver(new CombatWeaponResolver() {
            public long identity(Weapon weapon) { return 17L; }
            public Weapon ownedWeapon(ParticipantId owner, long id) {
                return remote.equals(owner) && id == 17L ? wand : null;
            }
        });
        Game game = game();
        Game previous = Game.instance;
        Game.instance = game;
        try {
            controller.update(game);
            assertEquals(1, game.level.entities.size);
            assertEquals(Color.BLUE, game.level.entities.first().color);
            assertSame(avatar, game.level.entities.first().owner);
            assertEquals(1, events.size());
            ProjectileVisual visual = (ProjectileVisual)events.get(0)[11];
            CombatPresentationEffect observed = new CombatPresentationEffect(new CombatPresentationEvent(
                    1L, 1L, "participant:campaign-slot-2", "", CombatAction.SPELL,
                    CombatPresentationPhase.ATTACK, 0, 0, 0, 1.7f, 0, 0, false, visual));
            assertEquals(Color.BLUE, observed.color);
            assertEquals("sprite", observed.spriteAtlas);
            assertEquals(8, observed.tex);
            observed.tick(null, 1f);
            assertEquals(0.17f, observed.x, 0.0001f);
            assertFalse(observed.isSolid);
            assertEquals(1, casts.size());
            assertEquals("participant:campaign-slot-2", casts.get(0).sourceId);
            assertEquals(17L, casts.get(0).itemId);
            assertTrue(casts.get(0).zap);
        }
        finally { Game.instance = previous; }
    }

    @Test
    public void nativeImpactPublishesExactLiveCueInsteadOfGenericObserverEffect() {
        final List<CombatRequest> requests = new ArrayList<CombatRequest>();
        final List<Object[]> events = new ArrayList<Object[]>();
        final List<NativeDynamicCue> cues = new ArrayList<NativeDynamicCue>();
        DirectConnectCombatController controller = new DirectConnectCombatController(
                peer(requests, events, cues), false);
        Game game = game(); Game previous = Game.instance; Game.instance = game;
        try {
            MagicMissileProjectile shot = new MagicMissileProjectile();
            shot.owner = game.player; shot.x = 1f; shot.y = 2f; shot.z = 0.5f;
            shot.xa = 0.17f; shot.damageType = Weapon.DamageType.ICE;
            shot.spriteAtlas = "ice-wand"; shot.tex = 8; shot.color = Color.BLUE.cpy();
            shot.hitDecal = null;
            game.level.entities.add(shot);

            controller.update(game);
            controller.onProjectileImpact(shot, new com.interrupt.dungeoneer.entities.Entity(),
                    1.5f, 2f, 0.5f);

            assertEquals(1, cues.size());
            NativeDynamicCue cue = cues.get(0);
            assertEquals(NativeDynamicCue.Kind.PROJECTILE_IMPACT, cue.kind);
            assertEquals("ice-wand", cue.state.apply(null).spriteAtlas);
            assertEquals(Color.BLUE, cue.state.apply(null).color);
            assertTrue(cue.entityHit);
        }
        finally { controller.dispose(); Game.instance = previous; }
    }

    @Test public void remoteSwordPublishesAcceptedSwingAndWorldImpactFromExactItem()
            throws Exception {
        ParticipantId remote = new ParticipantId("campaign-slot-2");
        List<CombatRequest> requests = new ArrayList<CombatRequest>();
        List<NativeMeleePresentation> melee = new ArrayList<NativeMeleePresentation>();
        requests.add(new CombatRequest(remote, 1L, CombatAction.MELEE,
                1f, 0f, 0f, 1f, 29L));
        DirectConnectPeer peer = peer(requests, new ArrayList<Object[]>(),
                new ArrayList<NativeDynamicCue>(), new ArrayList<NativeDynamicState>(),
                new ArrayList<NativeSpellPresentation>(), melee);
        DirectConnectMovementController movement = new DirectConnectMovementController(peer);
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(2L), remote, 2, "Client", "humanoid-2");
        RemoteAvatar avatar = new RemoteAvatar(descriptor);
        Field avatars = movement.getClass().getDeclaredField("remoteAvatars");
        avatars.setAccessible(true);
        ((Map)avatars.get(movement)).put(new NetworkEntityId(2L), avatar);
        DirectConnectCombatController controller =
                new DirectConnectCombatController(peer, movement, false);
        final int[] played = {0, 0};
        final Sword sword = new Sword() {
            @Override public void playNetworkSwingPresentation(float x, float y, float z) {
                played[0]++;
            }
            @Override public void playNetworkWorldHitPresentation(float x, float y, float z,
                    Level level, Vector3 direction) { played[1]++; }
        };
        controller.setWeaponResolver(new CombatWeaponResolver() {
            public long identity(Weapon weapon) { return 29L; }
            public Weapon ownedWeapon(ParticipantId owner, long id) {
                return remote.equals(owner) && id == 29L ? sword : null;
            }
        });
        Game game = game();
        game.level = new Level(4, 4) {
            @Override public boolean canSee(float x, float y, float tx, float ty) { return true; }
            @Override public boolean isFree(float x, float y, float z, Vector3 collision,
                    float stepheight, boolean floating,
                    com.interrupt.dungeoneer.collision.Collision hitLoc) { return false; }
        };
        Game previous = Game.instance; Game.instance = game;
        try {
            controller.update(game);
            assertEquals(1, played[0]); assertEquals(1, played[1]);
            assertEquals(2, melee.size());
            assertEquals(NativeMeleePresentation.Kind.SWING, melee.get(0).kind);
            assertEquals(NativeMeleePresentation.Kind.WORLD_HIT, melee.get(1).kind);
            assertEquals(29L, melee.get(1).itemId);
            assertEquals("participant:campaign-slot-2", melee.get(1).sourceId);
        }
        finally { controller.dispose(); Game.instance = previous; }
    }

    @Test public void remoteSwordRunsNativeCollisionAgainstBreakableWorldObjects()
            throws Exception {
        ParticipantId remote = new ParticipantId("campaign-slot-2");
        List<CombatRequest> requests = new ArrayList<CombatRequest>();
        requests.add(new CombatRequest(remote, 1L, CombatAction.MELEE,
                1f, 0f, 0f, 1f, 30L));
        DirectConnectPeer peer = peer(requests, new ArrayList<Object[]>());
        DirectConnectMovementController movement = new DirectConnectMovementController(peer);
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(2L), remote, 2, "Client", "humanoid-2");
        RemoteAvatar avatar = new RemoteAvatar(descriptor);
        Field avatars = movement.getClass().getDeclaredField("remoteAvatars");
        avatars.setAccessible(true);
        ((Map)avatars.get(movement)).put(new NetworkEntityId(2L), avatar);
        DirectConnectCombatController controller =
                new DirectConnectCombatController(peer, movement, false);
        final Sword sword = new Sword() {
            @Override public void playNetworkSwingPresentation(float x, float y, float z) { }
            @Override public void playNetworkEntityHitPresentation(float x, float y, float z,
                    Level level) { }
        };
        controller.setWeaponResolver(new CombatWeaponResolver() {
            public long identity(Weapon weapon) { return 30L; }
            public Weapon ownedWeapon(ParticipantId owner, long id) {
                return remote.equals(owner) && id == 30L ? sword : null;
            }
        });
        final Breakable crate = new Breakable() {
            @Override public void doHitEffect(float x, float y, float z, Sword item,
                    Level level) { }
        };
        crate.hp = 20;
        Game game = game();
        game.level = new Level(4, 4) {
            @Override public boolean canSee(float x, float y, float tx, float ty) { return true; }
            @Override public Entity checkEntityCollision(float x, float y, float z,
                    float widthX, float widthY, float height, Entity checking,
                    Entity ignore) { return crate; }
            @Override public boolean isFree(float x, float y, float z, Vector3 collision,
                    float stepheight, boolean floating,
                    com.interrupt.dungeoneer.collision.Collision hitLoc) { return true; }
        };
        Game previous = Game.instance; Game.instance = game;
        try {
            controller.update(game);
            assertTrue("Host must apply remote Sword through Breakable.hit", crate.hp < 20);
        }
        finally { controller.dispose(); Game.instance = previous; }
    }

    @Test public void remoteLightningSwordIgnitesNativeFusedBomb() throws Exception {
        ParticipantId remote = new ParticipantId("campaign-slot-2");
        List<CombatRequest> requests = new ArrayList<CombatRequest>();
        requests.add(new CombatRequest(remote, 1L, CombatAction.MELEE,
                1f, 0f, 0f, 1f, 32L));
        DirectConnectPeer peer = peer(requests, new ArrayList<Object[]>());
        DirectConnectMovementController movement = new DirectConnectMovementController(peer);
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(2L), remote, 2, "Client", "humanoid-2");
        RemoteAvatar avatar = new RemoteAvatar(descriptor);
        Field avatars = movement.getClass().getDeclaredField("remoteAvatars");
        avatars.setAccessible(true);
        ((Map)avatars.get(movement)).put(new NetworkEntityId(2L), avatar);
        DirectConnectCombatController controller =
                new DirectConnectCombatController(peer, movement, false);
        final Sword sword = new Sword() {
            @Override public void playNetworkSwingPresentation(float x, float y, float z) { }
            @Override public void playNetworkEntityHitPresentation(float x, float y, float z,
                    Level level) { }
            @Override public void magicHitVfx(float x, float y, float z, Level level) { }
        };
        sword.damageType = Weapon.DamageType.LIGHTNING;
        controller.setWeaponResolver(new CombatWeaponResolver() {
            public long identity(Weapon weapon) { return 32L; }
            public Weapon ownedWeapon(ParticipantId owner, long id) {
                return remote.equals(owner) && id == 32L ? sword : null;
            }
        });
        final FusedBomb bomb = new FusedBomb();
        bomb.isActive = true;
        bomb.isLit = false;
        bomb.countdownTimer = 150f;
        Game game = game();
        game.level = new Level(4, 4) {
            @Override public Entity checkEntityCollision(float x, float y, float z,
                    float widthX, float widthY, float height, Entity checking,
                    Entity ignore) { return bomb; }
            @Override public boolean isFree(float x, float y, float z, Vector3 collision,
                    float stepheight, boolean floating,
                    com.interrupt.dungeoneer.collision.Collision hitLoc) { return true; }
        };
        Game previous = Game.instance; Game.instance = game;
        try {
            controller.update(game);
            assertTrue("Host must apply remote Sword through FusedBomb.hit", bomb.isLit);
            assertTrue("Native volatile fuse must use short chain-reaction delay",
                    bomb.countdownTimer <= 5f);
            assertEquals("Remote participant remains explosion source",
                    "campaign-slot-2", bomb.multiplayerDamageSource);
        }
        finally { controller.dispose(); Game.instance = previous; }
    }

    @Test public void remoteSwordBreaksNativeBreakableDoor() throws Exception {
        ParticipantId remote = new ParticipantId("campaign-slot-2");
        List<CombatRequest> requests = new ArrayList<CombatRequest>();
        requests.add(new CombatRequest(remote, 1L, CombatAction.MELEE,
                1f, 0f, 0f, 1f, 33L));
        DirectConnectPeer peer = peer(requests, new ArrayList<Object[]>());
        DirectConnectMovementController movement = new DirectConnectMovementController(peer);
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(2L), remote, 2, "Client", "humanoid-2");
        RemoteAvatar avatar = new RemoteAvatar(descriptor);
        Field avatars = movement.getClass().getDeclaredField("remoteAvatars");
        avatars.setAccessible(true);
        ((Map)avatars.get(movement)).put(new NetworkEntityId(2L), avatar);
        DirectConnectCombatController controller =
                new DirectConnectCombatController(peer, movement, false);
        final Sword sword = new Sword() {
            @Override public void playNetworkSwingPresentation(float x, float y, float z) { }
            @Override public void playNetworkEntityHitPresentation(float x, float y, float z,
                    Level level) { }
        };
        controller.setWeaponResolver(new CombatWeaponResolver() {
            public long identity(Weapon weapon) { return 33L; }
            public Weapon ownedWeapon(ParticipantId owner, long id) {
                return remote.equals(owner) && id == 33L ? sword : null;
            }
        });
        final int[] gibs = {0};
        final Door door = new Door() {
            @Override public void playNetworkHitPresentation(float x, float y, float z,
                    Sword item, Level level) { }
            @Override public void gib(Level level, Vector3 velocity) {
                gibs[0]++;
                isActive = false;
                isSolid = false;
            }
        };
        door.breakable = true;
        door.hp = 1;
        Game game = game();
        game.level = new Level(4, 4) {
            @Override public Entity checkEntityCollision(float x, float y, float z,
                    float widthX, float widthY, float height, Entity checking,
                    Entity ignore) { return door; }
            @Override public boolean isFree(float x, float y, float z, Vector3 collision,
                    float stepheight, boolean floating,
                    com.interrupt.dungeoneer.collision.Collision hitLoc) { return true; }
        };
        Game previous = Game.instance; Game.instance = game;
        try {
            controller.update(game);
            assertTrue("Host must apply remote Sword through Door.hit", door.hp <= 0);
            assertEquals("Native Door destruction must run exactly once", 1, gibs[0]);
            assertFalse(door.isActive);
        }
        finally { controller.dispose(); Game.instance = previous; }
    }

    @Test public void remoteBowSpendsResolvedAmmoAndPublishesExactNativeArrow()
            throws Exception {
        ParticipantId remote = new ParticipantId("campaign-slot-2");
        List<CombatRequest> requests = new ArrayList<CombatRequest>();
        List<NativeDynamicState> states = new ArrayList<NativeDynamicState>();
        List<NativeRangedPresentation> ranged = new ArrayList<NativeRangedPresentation>();
        requests.add(new CombatRequest(remote, 1L, CombatAction.PROJECTILE,
                1f, 0f, 0f, 0.8f, 31L));
        requests.add(new CombatRequest(remote, 2L, CombatAction.PROJECTILE,
                1f, 0f, 0f, 0.8f, 31L));
        DirectConnectPeer peer = peer(requests, new ArrayList<Object[]>(),
                new ArrayList<NativeDynamicCue>(), states,
                new ArrayList<NativeSpellPresentation>(),
                new ArrayList<NativeMeleePresentation>(), ranged);
        DirectConnectMovementController movement = new DirectConnectMovementController(peer);
        MovementEntityDescriptor descriptor = new MovementEntityDescriptor(1L,
                new NetworkEntityId(2L), remote, 2, "Client", "humanoid-2");
        RemoteAvatar avatar = new RemoteAvatar(descriptor);
        Field avatars = movement.getClass().getDeclaredField("remoteAvatars");
        avatars.setAccessible(true);
        ((Map)avatars.get(movement)).put(new NetworkEntityId(2L), avatar);
        DirectConnectCombatController controller =
                new DirectConnectCombatController(peer, movement, false);
        final int[] releases = {0}, ammoTakes = {0};
        java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        if(previousStrings == null) com.interrupt.managers.StringManager.localizedStrings =
                new java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
        final TestBow bow = new TestBow();
        bow.releases = releases;
        bow.damageType = Weapon.DamageType.ICE;
        final Missile frostArrow = new Missile();
        frostArrow.spriteAtlas = "frost-arrows"; frostArrow.tex = 19;
        frostArrow.color = Color.CYAN.cpy(); frostArrow.breakChance = 0.42f;
        controller.setWeaponResolver(new CombatWeaponResolver() {
            public long identity(Weapon weapon) { return weapon == bow ? 31L : 0L; }
            public Weapon ownedWeapon(ParticipantId owner, long id) {
                return remote.equals(owner) && id == 31L ? bow : null;
            }
            public Missile takeOwnedMissile(ParticipantId owner) {
                if(!remote.equals(owner) || ammoTakes[0]++ > 0) return null;
                return frostArrow;
            }
        });
        Game game = game(); Game previous = Game.instance; Game.instance = game;
        try {
            controller.update(game);

            assertEquals(2, ammoTakes[0]); assertEquals(1, releases[0]);
            assertEquals(1, ranged.size()); assertEquals(31L, ranged.get(0).itemId);
            assertEquals("participant:campaign-slot-2", ranged.get(0).sourceId);
            assertTrue(game.level.entities.contains(frostArrow, true));
            assertSame(avatar, frostArrow.owner);
            assertEquals("frost-arrows", frostArrow.spriteAtlas);
            assertEquals(19, frostArrow.tex); assertEquals(0.42f, frostArrow.breakChance, 0f);
            assertEquals(Weapon.DamageType.ICE, frostArrow.damageType);
            assertTrue(frostArrow.leaveTrail);
            assertNotNull(frostArrow.getAttached(
                    com.interrupt.dungeoneer.entities.DynamicLight.class));
            assertFalse(states.isEmpty());
            Entity replica = states.get(states.size() - 1).apply(null);
            assertTrue(replica instanceof Missile);
            assertEquals("frost-arrows", replica.spriteAtlas);
            assertNotNull(replica.getAttached(
                    com.interrupt.dungeoneer.entities.DynamicLight.class));
        }
        finally {
            controller.dispose(); Game.instance = previous;
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
        }
    }

    @Test
    public void nativeEnemySpecialActionsPublishTheirActualProjectiles() {
        final List<NativeDynamicState> states = new ArrayList<NativeDynamicState>();
        DirectConnectCombatController controller = new DirectConnectCombatController(
                peer(new ArrayList<CombatRequest>(), new ArrayList<Object[]>(),
                        new ArrayList<NativeDynamicCue>(), states), false);
        Game game = game(); Game previous = Game.instance; Game.instance = game;
        try {
            game.player.x = 3f; game.player.y = 2f; game.player.z = 0.5f;
            Monster monster = new Monster(); monster.x = 1f; monster.y = 2f; monster.z = 0.5f;
            monster.setMultiplayerTarget(game.player);
            game.level.entities.add(monster);
            controller.prepare(game);

            MagicMissileProjectile thrown = new MagicMissileProjectile();
            thrown.spriteAtlas = "enemy-dart"; thrown.tex = 17;
            thrown.color = Color.ORANGE.cpy(); thrown.floating = true;
            ProjectileAttackAction projectileAction = new ProjectileAttackAction();
            projectileAction.projectile = thrown;
            projectileAction.doAction(monster);

            MagicMissile spell = new MagicMissile() {
                @Override public void playCastSound(com.interrupt.dungeoneer.entities.Actor owner) { }
            };
            spell.spellColor = Color.BLUE.cpy();
            spell.appearance = new Material("enemy-spell", (byte)23);
            new SpellCastAction(spell).doAction(monster);
            controller.update(game);

            assertEquals(2, states.size());
            boolean dartSeen = false, spellSeen = false;
            for(NativeDynamicState state : states) {
                com.interrupt.dungeoneer.entities.Entity replica = state.apply(null);
                dartSeen |= "enemy-dart".equals(replica.spriteAtlas)
                        && Color.ORANGE.equals(replica.color);
                spellSeen |= "enemy-spell".equals(replica.spriteAtlas)
                        && Color.BLUE.equals(replica.color);
                assertTrue(replica instanceof MagicMissileProjectile);
            }
            assertTrue("ProjectileAttackAction output must retain its native variant", dartSeen);
            assertTrue("SpellCastAction output must retain its native spell variant", spellSeen);
        }
        finally { controller.dispose(); Game.instance = previous; }
    }

    @Test
    public void acceptedScrollRunsNativeSpellOnceOnHostWithSubmittedAim() {
        DirectConnectCombatController controller = new DirectConnectCombatController(
                peer(new ArrayList<CombatRequest>(), new ArrayList<Object[]>()), false);
        Game game = game(); Game previous = Game.instance; Game.instance = game;
        final int[] casts = {0};
        final Vector3 acceptedAim = new Vector3();
        try {
            controller.prepare(game);
            com.interrupt.dungeoneer.entities.items.Scroll scroll =
                    new com.interrupt.dungeoneer.entities.items.Scroll();
            scroll.spell = new com.interrupt.dungeoneer.entities.spells.Spell() {
                @Override public void doCast(com.interrupt.dungeoneer.entities.Entity owner,
                        Vector3 direction, Vector3 position) {
                    casts[0]++; acceptedAim.set(direction);
                    assertSame(game.player, owner);
                }
                @Override public void playCastSound(com.interrupt.dungeoneer.entities.Actor owner) { }
            };
            scroll.spell.doCastVfx = false;
            Vector3 aim = new Vector3(0.25f, -0.5f, 0.75f).nor();

            assertTrue(controller.consumeNativeItem(
                    new ParticipantId("campaign-slot-1"), scroll, aim));
            assertEquals(1, casts[0]); assertEquals(aim, acceptedAim);
        }
        finally { controller.dispose(); Game.instance = previous; }
    }

    static Game game() {
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.level = new Level(4, 4) {
            @Override public boolean canSee(float x, float y, float tx, float ty) { return true; }
        };
        return game;
    }

    private static final class TestBow extends Bow {
        private int[] releases;
        @Override public void playNetworkFirePresentation(float x, float y, float z) {
            releases[0]++;
        }
    }

    private DirectConnectPeer peer(final List<CombatRequest> requests, final List<Object[]> events) {
        return peer(requests, events, new ArrayList<NativeDynamicCue>());
    }

    private DirectConnectPeer peer(final List<CombatRequest> requests,
            final List<Object[]> events, final List<NativeDynamicCue> cues) {
        return peer(requests, events, cues, new ArrayList<NativeDynamicState>());
    }

    private DirectConnectPeer peer(final List<CombatRequest> requests,
            final List<Object[]> events, final List<NativeDynamicCue> cues,
            final List<NativeDynamicState> states) {
        return peer(requests, events, cues, states,
                new ArrayList<NativeSpellPresentation>());
    }

    private DirectConnectPeer peer(final List<CombatRequest> requests,
            final List<Object[]> events, final List<NativeDynamicCue> cues,
            final List<NativeDynamicState> states,
            final List<NativeSpellPresentation> casts) {
        return peer(requests, events, cues, states, casts,
                new ArrayList<NativeMeleePresentation>());
    }

    private DirectConnectPeer peer(final List<CombatRequest> requests,
            final List<Object[]> events, final List<NativeDynamicCue> cues,
            final List<NativeDynamicState> states,
            final List<NativeSpellPresentation> casts,
            final List<NativeMeleePresentation> melee) {
        return peer(requests, events, cues, states, casts, melee,
                new ArrayList<NativeRangedPresentation>());
    }

    private DirectConnectPeer peer(final List<CombatRequest> requests,
            final List<Object[]> events, final List<NativeDynamicCue> cues,
            final List<NativeDynamicState> states,
            final List<NativeSpellPresentation> casts,
            final List<NativeMeleePresentation> melee,
            final List<NativeRangedPresentation> ranged) {
        return (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class, NativeCombatAuthority.class}, (proxy, method, args) -> {
            String name = method.getName();
            if(name.equals("getNextCombatRequestId")) return 1L;
            if(name.equals("getLocalMovementEntityId")) return new NetworkEntityId(1L);
            if(name.equals("getMovementEntities")) return Collections.singletonList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                            new ParticipantId("campaign-slot-1"), 1, "Host", "humanoid-1"));
            if(name.equals("getPartyStatus")) return new PartyStatusSnapshot(1L, Collections.singletonList(
                    new PartyMemberStatus(1, new NetworkEntityId(1L), "Host", "humanoid-1", 8, 8, 3,
                            PartyMemberState.CONNECTED)));
            if(name.equals("drainNativeCombatRequests")) {
                List<CombatRequest> result = new ArrayList<CombatRequest>(requests); requests.clear(); return result;
            }
            if(name.equals("publishNativePresentation")) { events.add(args); return null; }
            if(name.equals("canApplyNativeParticipantEffect")) return true;
            if(name.equals("publishNativeDynamicCue")) { cues.add((NativeDynamicCue)args[0]); return null; }
            if(name.equals("publishNativeSpellPresentation")) {
                casts.add((NativeSpellPresentation)args[0]); return null;
            }
            if(name.equals("publishNativeMeleePresentation")) {
                melee.add((NativeMeleePresentation)args[0]); return null;
            }
            if(name.equals("publishNativeRangedPresentation")) {
                ranged.add((NativeRangedPresentation)args[0]); return null;
            }
            if(name.equals("synchronizeNativeDynamicState")) {
                states.add((NativeDynamicState)args[0]); return null;
            }
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
    }
}
