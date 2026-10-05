package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.MonsterSpawner;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.ProjectedDecal;
import com.interrupt.dungeoneer.entities.items.Bow;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.items.Wand;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.entities.spells.Heal;
import com.interrupt.dungeoneer.entities.spells.Spell;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.tiles.Tile;
import com.interrupt.helpers.PlayerHistory;
import com.interrupt.managers.MonsterManager;
import com.badlogic.gdx.utils.Array;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DirectConnectCombatControllerTest {
    @Test
    public void coldResumeHostRebuildsSavedMonsterCorpseSeenByClient() throws Exception {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer peer = new HostStubPeer();
        peer.combatSnapshot = new CombatSnapshot(1L, 0L,
                Collections.singletonList(new MonsterSnapshot("monster:1", "", 2f, 3f, 0f)),
                Collections.singletonList(new CombatantSnapshot(
                        "monster:1", CombatantKind.MONSTER, 0, 20)));
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd()
                    .newInstance(com.interrupt.dungeoneer.game.Game.class);
            com.interrupt.dungeoneer.game.Game.instance = game;
            game.player = new Player(); game.level = new Level(4, 4);
            Monster monster = new Monster();
            monster.hostile = true; monster.hp = monster.maxHp = 20;
            java.lang.reflect.Field death = Monster.class.getDeclaredField("dieAnimation");
            death.setAccessible(true);
            death.set(monster, new com.interrupt.dungeoneer.gfx.animation.SpriteAnimation(
                    50, 59, 100, null));
            game.level.entities.add(monster);

            controller.prepare(game);

            assertNotNull("Host must rebuild saved corpse presented by Client",
                    monster.getMultiplayerCorpse());
            assertTrue(game.level.entities.contains(monster.getMultiplayerCorpse(), true));
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void hostAnnouncesMonstersPastSixtyFourOnceDeadOnesGiveUpTheirSlots() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer peer = new HostStubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();
            controller.prepare(game);
            List<Monster> wave = new ArrayList<Monster>();
            for(int index = 0; index < CombatSnapshot.MAX_NATIVE_MONSTERS; index++) {
                Monster slime = slime();
                slime.x = 1.1f + (index % 8) * 0.2f;
                slime.y = 1.1f + (index / 8) * 0.2f;
                game.level.entities.add(slime);
                wave.add(slime);
            }
            controller.prepare(game);
            assertEquals(CombatSnapshot.MAX_NATIVE_MONSTERS, peer.publishedSpawns.size());

            // The tutorial's last wave keeps coming as the first ones die.
            wave.get(0).hp = 0;
            Monster arrival = slime();
            arrival.x = arrival.y = 3.5f;
            game.level.entities.add(arrival);
            controller.update(game);
            assertEquals("Clients have not seen the death yet", CombatSnapshot.MAX_NATIVE_MONSTERS,
                    peer.publishedSpawns.size());

            for(int frame = 0; frame < 130; frame++) controller.update(game);

            assertEquals("The next Monster reaches clients", CombatSnapshot.MAX_NATIVE_MONSTERS + 1,
                    peer.publishedSpawns.size());
            assertEquals("monster:64", peer.publishedSpawns.get(CombatSnapshot.MAX_NATIVE_MONSTERS).monsterId);
            assertEquals("Only the dead one gave up its slot", 1, peer.retiredMonsters.size());
            assertTrue(peer.presentationFailures.isEmpty());
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void resumedHostFreesSlotsOfMonstersThatDiedBeforeTheSave() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer peer = new HostStubPeer();
        // Saved after the tutorial finale: every slot held a Monster that had already died.
        List<MonsterSnapshot> saved = new ArrayList<MonsterSnapshot>();
        List<CombatantSnapshot> combatants = new ArrayList<CombatantSnapshot>();
        for(int index = 1; index <= CombatSnapshot.MAX_NATIVE_MONSTERS; index++) {
            saved.add(new MonsterSnapshot("monster:" + index, "", 2f, 2f, 0.5f));
            combatants.add(new CombatantSnapshot("monster:" + index, CombatantKind.MONSTER, 0, 4));
        }
        peer.combatSnapshot = new CombatSnapshot(1L, 0L, saved, combatants);
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();
            // The floor checkpoint keeps living Monsters only, so none of them is on it.
            game.level.restoredCampaignFloor = true;
            controller.prepare(game);
            Monster arrival = slime();
            arrival.x = arrival.y = 3.5f;
            game.level.entities.add(arrival);
            controller.update(game);
            assertTrue("Resumed clients have not drawn the saved corpses yet",
                    peer.publishedSpawns.isEmpty());

            for(int frame = 0; frame < 130; frame++) controller.update(game);

            assertEquals(1, peer.publishedSpawns.size());
            assertEquals("monster:64", peer.publishedSpawns.get(0).monsterId);
            assertEquals("The oldest saved corpse gave up its slot",
                    Collections.singletonList("monster:1"), peer.retiredMonsters);
            assertTrue(peer.presentationFailures.isEmpty());
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void undescribableProjectileIsSkippedInsteadOfEndingTheSession() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer peer = new HostStubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();
            controller.prepare(game);
            com.interrupt.dungeoneer.entities.projectiles.Projectile bolt =
                    new com.interrupt.dungeoneer.entities.projectiles.Projectile();
            bolt.x = bolt.y = 2f;
            bolt.xa = Float.NaN;
            game.level.non_collidable_entities.add(bolt);

            controller.update(game);
            controller.update(game);

            assertTrue("A projectile clients cannot draw never stops the session: "
                    + peer.presentationFailures, peer.presentationFailures.isEmpty());
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void coldResumeHostRecreatesSavedLateMonsterItsRebuiltFloorLacks() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer peer = new HostStubPeer();
        peer.restoredSpawns.add(new NativeMonsterSpawn("monster:2", "DUNGEON", "SLIME",
                1.5f, 1.5f, 0f, 4, 4));
        peer.combatSnapshot = new CombatSnapshot(1L, 0L,
                Collections.singletonList(new MonsterSnapshot("monster:2", "", 2.5f, 2.25f, 0f)),
                Collections.singletonList(new CombatantSnapshot(
                        "monster:2", CombatantKind.MONSTER, 3, 4)));
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();

            controller.prepare(game);

            List<Monster> restored = activeMonsters(game.level);
            assertEquals("Host must show the saved slime its Client shows", 1, restored.size());
            assertEquals("SLIME", restored.get(0).name);
            assertEquals(3, restored.get(0).hp);
            assertEquals(4, restored.get(0).maxHp);
            assertEquals(2.5f, restored.get(0).x, 0f);
            assertEquals(2.25f, restored.get(0).y, 0f);
            assertTrue("Clients already get saved spawns replayed", peer.publishedSpawns.isEmpty());

            Monster arrival = slime();
            arrival.x = arrival.y = 3.5f;
            game.level.entities.add(arrival);
            controller.prepare(game);

            assertEquals("A new arrival must not take a saved Monster id",
                    "monster:3", peer.publishedSpawns.get(0).monsterId);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void restoredHostFloorKeepsSavedMonsterIdsAndNeverRecreatesItsLateMonsters() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer peer = new HostStubPeer();
        // A dead late slime: the checkpoint holds its corpse, not a Monster to recreate.
        peer.restoredSpawns.add(new NativeMonsterSpawn("monster:9", "DUNGEON", "SLIME",
                1.5f, 1.5f, 0f, 4, 4));
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();
            game.level.restoredCampaignFloor = true;
            Monster saved = slime();
            saved.x = saved.y = 2.5f;
            saved.hp = 3;
            saved.multiplayerIdentity = "monster:7";
            game.level.entities.add(saved);

            controller.prepare(game);

            assertEquals(Collections.singletonList(saved), activeMonsters(game.level));
            assertEquals("Saved Monster keeps the id its clients know",
                    "monster:7", saved.multiplayerIdentity);
            assertEquals(3, saved.hp);
            assertTrue(peer.publishedSpawns.isEmpty());

            Monster arrival = slime();
            arrival.x = arrival.y = 3.5f;
            game.level.entities.add(arrival);
            controller.prepare(game);

            assertEquals("monster:10", peer.publishedSpawns.get(0).monsterId);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void floorSpawnerAddsItsMonstersOncePerCampaignAcrossColdResume() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer first = new HostStubPeer();
        HostStubPeer resumed = new HostStubPeer();
        DirectConnectCombatController before = new DirectConnectCombatController(first, false);
        DirectConnectCombatController after = new DirectConnectCombatController(resumed, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();
            MonsterSpawner spawner = slimeSpawner(game.level);
            before.prepare(game);
            spawner.spawn(game.level);
            assertEquals(1, activeMonsters(game.level).size());
            assertEquals(1, first.consumedSpawners.size());

            // Resumed Host rebuilds the same floor: its spawner is armed again.
            resumed.consumedSpawners.addAll(first.consumedSpawners);
            game = campaignGame();
            MonsterSpawner rebuilt = slimeSpawner(game.level);
            after.prepare(game);
            rebuilt.spawn(game.level);

            assertTrue("Saved slime returns from the save, not from its spent spawner",
                    activeMonsters(game.level).isEmpty());
            assertTrue(!rebuilt.isActive);
        }
        finally {
            before.dispose(); after.dispose();
            com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void clientNeverKeepsMonstersItsOwnFloorAdds() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();
            MonsterSpawner spawner = slimeSpawner(game.level);
            controller.prepare(game);

            spawner.spawn(game.level);
            assertTrue("Client spawner must wait for Host announcements",
                    activeMonsters(game.level).isEmpty());

            Monster stray = slime();
            stray.x = stray.y = 2.5f;
            game.level.entities.add(stray);
            controller.prepare(game);

            assertTrue("Monster Host never announced is not in the session", !stray.isActive);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void hostSendsRestoredFloorMarksAndKeepsNewOnesForLaterJoiners() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        HostStubPeer peer = new HostStubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();
            game.level.restoredCampaignFloor = true;
            ProjectedDecal restored = new ProjectedDecal(Entity.ArtType.sprite, 16, 0.8f);
            restored.multiplayerIdentity = "decal:7";
            ProjectedDecal floor = new ProjectedDecal(Entity.ArtType.sprite, 18, 1f);
            game.level.entities.add(restored);
            game.level.entities.add(floor);

            controller.prepare(game);

            assertEquals("Connected clients never saw the restored blood made",
                    1, peer.announcedDecals.size());
            assertEquals(16, peer.announcedDecals.get(0).tex);
            assertEquals("Every peer's build has the floor's own decal",
                    "decal:floor", floor.multiplayerIdentity);

            ProjectedDecal fresh = new ProjectedDecal(Entity.ArtType.sprite, 17, 0.5f);
            game.level.entities.add(fresh);
            controller.update(game);

            assertEquals("decal:8", fresh.multiplayerIdentity);
            assertEquals("Live clients drew it themselves; later joiners get it",
                    1, peer.keptDecals.size());
            assertEquals(1, peer.announcedDecals.size());
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    @Test
    public void joiningClientRebuildsHostMarksMadeBeforeIt() {
        com.interrupt.dungeoneer.game.Game previous =
                com.interrupt.dungeoneer.game.Game.instance;
        StubPeer peer = new StubPeer();
        peer.decals.add(new NativeDecalState(1.5f, 1.5f, 0.2f, 0.05f, 0f, -0.95f, 12f, 0f, 0f, 0f,
                0.01f, 1f, 20f, 0.8f, 0.8f, true, Entity.ArtType.sprite.ordinal(), 16, "",
                1f, 1f, 1f, 1f));
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        try {
            com.interrupt.dungeoneer.game.Game game = campaignGame();

            controller.prepare(game);

            ProjectedDecal rebuilt = null;
            for(Entity entity : game.level.entities) {
                if(entity instanceof ProjectedDecal) rebuilt = (ProjectedDecal)entity;
            }
            assertNotNull(rebuilt);
            assertEquals(16, rebuilt.tex);
            assertEquals(12f, rebuilt.roll, 0f);
            assertTrue(rebuilt.isOrtho);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
        }
    }

    private static com.interrupt.dungeoneer.game.Game campaignGame() {
        com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd()
                .newInstance(com.interrupt.dungeoneer.game.Game.class);
        com.interrupt.dungeoneer.game.Game.instance = game;
        game.player = new Player();
        game.level = new Level(4, 4);
        // Open floor without TileManager content; setTile would resolve tile materials.
        for(int index = 0; index < game.level.tiles.length; index++) {
            game.level.tiles[index] = Tile.EmptyTile();
        }
        game.monsterManager = new MonsterManager();
        game.monsterManager.monsters = new HashMap<String, Array<Monster>>();
        Array<Monster> dungeon = new Array<Monster>();
        dungeon.add(slime());
        game.monsterManager.monsters.put("DUNGEON", dungeon);
        return game;
    }

    private static Monster slime() {
        Monster slime = new Monster();
        slime.name = "SLIME";
        slime.hostile = true;
        slime.hp = slime.maxHp = 4;
        slime.artType = Entity.ArtType.hidden; // No sprite: native init needs no renderer.
        return slime;
    }

    private static MonsterSpawner slimeSpawner(Level level) {
        MonsterSpawner spawner = new MonsterSpawner();
        spawner.monsterTheme = "DUNGEON";
        spawner.monsterName = "SLIME";
        spawner.x = spawner.y = 1.5f;
        level.entities.add(spawner);
        return spawner;
    }

    private static List<Monster> activeMonsters(Level level) {
        List<Monster> result = new ArrayList<Monster>();
        for(Entity entity : level.entities) {
            if(entity instanceof Monster && entity.isActive) result.add((Monster)entity);
        }
        return result;
    }

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

    @Test
    public void swordHitOnSharedShopkeeperReplaysNativeHitAndOnlyUnknownObjectsFail() {
        com.interrupt.dungeoneer.game.Game previous = com.interrupt.dungeoneer.game.Game.instance;
        java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString> previousStrings =
                com.interrupt.managers.StringManager.localizedStrings;
        com.interrupt.managers.StringManager.localizedStrings =
                new java.util.HashMap<String, com.interrupt.dungeoneer.game.LocalizedString>();
        StubPeer peer = new StubPeer();
        final int[] swordSwings = { 0 }, swordEntityHits = { 0 }, swordWorldHits = { 0 };
        Sword sword = new TestPresentationSword(swordSwings, swordEntityHits, swordWorldHits);
        // Shop floors place the shopkeeper as a solid TriggeredShop, a shared world object.
        com.interrupt.dungeoneer.entities.triggers.TriggeredShop shopkeeper =
                new com.interrupt.dungeoneer.entities.triggers.TriggeredShop();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        controller.setWeaponResolver(new StubWeaponResolver(null, sword, null, shopkeeper));
        try {
            com.interrupt.dungeoneer.game.Game game = new org.objenesis.ObjenesisStd()
                    .newInstance(com.interrupt.dungeoneer.game.Game.class);
            com.interrupt.dungeoneer.game.Game.instance = game;
            game.player = new Player(); game.level = new Level(4, 4);
            controller.prepare(game);

            peer.meleePresentations.add(NativeMeleePresentation.capture(
                    "participant:campaign-slot-2", 12L,
                    NativeMeleePresentation.Kind.ENTITY_HIT, new Vector3(1f, 2f, 0.5f),
                    new Vector3(1f, 0f, 0f), 1000000L));
            controller.update(game);

            assertTrue(peer.presentationFailures.toString(), peer.presentationFailures.isEmpty());
            assertEquals("Shopkeeper hit plays native Sword hit feedback", 1, swordEntityHits[0]);

            peer.meleePresentations.add(NativeMeleePresentation.capture(
                    "participant:campaign-slot-2", 12L,
                    NativeMeleePresentation.Kind.ENTITY_HIT, new Vector3(1f, 2f, 0.5f),
                    new Vector3(1f, 0f, 0f), 1000001L));
            controller.update(game);

            assertEquals(1, peer.presentationFailures.size());
            assertTrue(peer.presentationFailures.get(0),
                    peer.presentationFailures.get(0).contains("Accepted Sword target 1000001"));
            assertEquals("Unknown shared object plays nothing", 1, swordEntityHits[0]);
        }
        finally {
            controller.dispose(); com.interrupt.dungeoneer.game.Game.instance = previous;
            com.interrupt.managers.StringManager.localizedStrings = previousStrings;
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

    private static class StubPeer implements DirectConnectPeer {
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
        final List<String> presentationFailures = new ArrayList<String>();
        CombatSnapshot combatSnapshot;
        final List<NativeDecalState> decals = new ArrayList<NativeDecalState>();
        @Override public List<NativeDecalState> drainNativeDecals() {
            List<NativeDecalState> drained = new ArrayList<NativeDecalState>(decals);
            decals.clear();
            return drained;
        }
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
        @Override public CombatSnapshot getCombatSnapshot() { return combatSnapshot; }
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

    private static final class HostStubPeer extends StubPeer implements NativeCombatAuthority {
        final List<NativeDecalState> announcedDecals = new ArrayList<NativeDecalState>();
        final List<NativeDecalState> keptDecals = new ArrayList<NativeDecalState>();
        @Override public void recordNativeDecal(NativeDecalState decal, boolean announce) {
            (announce ? announcedDecals : keptDecals).add(decal);
        }
        final List<NativeMonsterSpawn> restoredSpawns = new ArrayList<NativeMonsterSpawn>();
        final List<NativeMonsterSpawn> publishedSpawns = new ArrayList<NativeMonsterSpawn>();
        final List<String> retiredMonsters = new ArrayList<String>();
        @Override public void retireNativeMonster(String monsterId) {
            retiredMonsters.add(monsterId);
        }
        final Set<String> consumedSpawners = new LinkedHashSet<String>();

        @Override public List<NativeMonsterSpawn> getRestoredNativeMonsterSpawns() {
            return restoredSpawns;
        }
        @Override public void publishNativeMonsterSpawn(NativeMonsterSpawn spawn) {
            publishedSpawns.add(spawn);
        }
        @Override public boolean isNativeMonsterSpawnerConsumed(String spawnerKey) {
            return consumedSpawners.contains(spawnerKey);
        }
        @Override public void consumeNativeMonsterSpawner(String spawnerKey) {
            consumedSpawners.add(spawnerKey);
        }
        @Override public void bindNativeMonster(int health, int maximumHealth,
                float x, float y, float z) { }
        @Override public void synchronizeNativeMonster(int health, int maximumHealth,
                float x, float y, float z) { }
        @Override public List<CombatRequest> drainNativeCombatRequests() {
            return Collections.emptyList();
        }
        @Override public void applyNativeMonsterDamage(
                com.interrupt.dungeoneer.multiplayer.participant.ParticipantId targetId,
                int damage, CombatAction action, float originX, float originY, float originZ,
                float impactX, float impactY, float impactZ) { }
        @Override public void publishNativePresentation(String sourceId, String targetId,
                CombatAction action, float originX, float originY, float originZ,
                float impactX, float impactY, float impactZ, boolean stateChanged) { }
    }
}
