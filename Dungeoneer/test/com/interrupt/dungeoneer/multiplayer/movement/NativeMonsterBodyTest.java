package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.tiles.Tile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Monsters and Participants block each other's native movement as in single-player Delver. */
public class NativeMonsterBodyTest {
    private static final ParticipantId PARTICIPANT = new ParticipantId("campaign-slot-2");
    private static final NetworkEntityId ENTITY = new NetworkEntityId(2L);
    private Game previousGame;

    @Before public void game() {
        previousGame = Game.instance;
        Game.instance = new ObjenesisStd().newInstance(Game.class);
        Game.instance.player = new Player();
    }

    @After public void restore() {
        Game.instance = previousGame;
    }

    @Test public void hostMonsterStopsWhereHostMovementHasTheParticipantNotWhereItsAvatarIsDrawn() {
        Level level = floor();
        NativeMovementBodies bodies = new NativeMovementBodies();
        level.movementBodies = bodies;
        RemoteAvatar avatar = avatar();
        // Drawn a little behind: Avatars interpolate, Host movement already moved on.
        avatar.x = 3f;
        avatar.y = 2.5f;
        bodies.updateParticipants(Collections.singletonList(avatar), Collections.singletonList(state(5f, 2.5f)));
        Monster monster = monster(4.7f, 2.5f);

        assertSame(avatar, level.checkEntityCollision(monster.x, monster.y, monster.z, monster.collision, monster));
        assertEquals("Drawn position no longer blocks", 0, level.getEntitiesColliding(3f, 2.5f, 0f, monster).size);
    }

    @Test public void onlyMovingMonstersAreBlockedByParticipantsNeverTrapsOrWeapons() {
        Level level = floor();
        NativeMovementBodies bodies = new NativeMovementBodies();
        level.movementBodies = bodies;
        RemoteAvatar avatar = avatar();
        bodies.updateParticipants(Collections.singletonList(avatar), Collections.singletonList(state(5f, 2.5f)));
        // A trap or trigger sensing what touches it.
        Entity sensor = new Entity();
        sensor.collision.set(0.5f, 0.5f, 1f);

        assertEquals(0, level.getEntitiesColliding(5f, 2.5f, 0f, sensor).size);
        // Native weapon sweeps pass no mover.
        assertNull(level.checkEntityCollision(5f, 2.5f, 0.4f, 0.2f, 0.2f, 0.2f, null, null));
    }

    @Test public void lifeExhaustedSpectatorLeavesNoBody() {
        Level level = floor();
        NativeMovementBodies bodies = new NativeMovementBodies();
        level.movementBodies = bodies;
        RemoteAvatar avatar = avatar();
        avatar.setIncapacitated(true);
        avatar.hidden = true;
        bodies.updateParticipants(Collections.singletonList(avatar), Collections.singletonList(state(5f, 2.5f)));

        assertEquals(0, bodies.getParticipantBodyCount());
        Monster monster = monster(4.8f, 2.5f);
        assertNull(level.checkEntityCollision(monster.x, monster.y, monster.z, monster.collision, monster));
    }

    @Test public void clientPlayerStopsAtLivingReplicaMonstersOnly() {
        Level level = floor();
        NativeMovementBodies bodies = new NativeMovementBodies();
        level.movementBodies = bodies;
        Monster replica = monster(5f, 2.5f);
        replica.setNetworkReplica(true);
        Monster corpse = monster(7f, 2.5f);
        corpse.setNetworkReplica(true);
        corpse.hp = 0;
        bodies.setReplicaMonsters(Arrays.asList(replica, corpse));
        Player player = Game.instance.player;

        assertTrue("Replica stays non-solid for everything else", !replica.isSolid);
        assertSame(replica, level.getHighestEntityCollision(4.7f, 2.5f, 0f, player.collision, player));
        assertEquals(0, level.getEntitiesColliding(6.8f, 2.5f, 0f, player).size);
        Player other = new Player();
        assertEquals("Only the local Player predicts against replicas",
                0, level.getEntitiesColliding(4.7f, 2.5f, 0f, other).size);
    }

    @Test public void bumpingAReplicaNeverStartsALocalMonsterAttack() throws Exception {
        Monster replica = monster(5f, 2.5f);
        replica.hostile = true;
        replica.setNetworkReplica(true);
        java.lang.reflect.Field timer = Monster.class.getDeclaredField("attacktimer");
        timer.setAccessible(true);
        timer.setFloat(replica, 0f);
        java.lang.reflect.Field target = Monster.class.getDeclaredField("attackTarget");
        target.setAccessible(true);

        replica.encroached(Game.instance.player);

        assertNull("Host alone resolves Monster attacks", target.get(replica));
    }

    @Test public void hostMovementStopsAtMonstersAndBouncesOffTheirTops() {
        Level level = floor();
        Monster monster = monster(4.5f, 2.5f);
        level.entities.add(monster);
        LevelMovementCollisionWorld world = new LevelMovementCollisionWorld(floor());
        world.setActorObstacles(NativeMovementObstacles.monsters(level));
        AuthoritativeMovementSimulation simulation = new AuthoritativeMovementSimulation(world,
                Collections.singletonList(new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                        PARTICIPANT, 1, "Friend", "humanoid-2")));
        for(int tick = 1; tick <= 120; tick++) {
            simulation.applyCommand(tick, new MovementInputCommand(PARTICIPANT,
                    new MovementInputFrame(tick, 1f, 0f, (float)(Math.PI / 2.0), false)), null);
            simulation.tick(tick, 1f / 60f, null);
        }
        MovementEntityState state = simulation.getState(PARTICIPANT);
        assertTrue("Participant stopped at the Monster, reached " + state.getX(),
                state.getX() <= 4.5f - monster.collision.x - 0.2f + 0.001f);
        assertTrue("Monsters never shape where a Participant may be placed",
                world.canOccupy(4.5f, 2.5f, 0f));
    }

    @Test public void deadOrPlayerIgnoringMonstersDoNotBlock() {
        Level level = floor();
        Monster dead = monster(3.5f, 2.5f);
        dead.hp = 0;
        Monster ghost = monster(5.5f, 2.5f);
        ghost.ignorePlayerCollision = true;
        Monster slime = monster(7.5f, 2.5f);
        level.entities.addAll(dead, ghost, slime);

        assertEquals(1, NativeMovementObstacles.monsters(level).size());
    }

    private static Monster monster(float x, float y) {
        Monster monster = new Monster();
        monster.x = x;
        monster.y = y;
        monster.z = 0f;
        monster.hp = monster.maxHp = 5;
        monster.isSolid = true;
        monster.isActive = true;
        monster.collision.set(0.25f, 0.25f, 0.6f);
        return monster;
    }

    private static RemoteAvatar avatar() {
        return new RemoteAvatar(new MovementEntityDescriptor(2L, ENTITY, PARTICIPANT, 2, "Friend", "humanoid-2"));
    }

    private static MovementEntityState state(float x, float y) {
        return new MovementEntityState(ENTITY, 2L, 0L, x, y, 0f, 0f, 0f, 0f, 0f, MovementState.IDLE);
    }

    /** 10x5 floor with feet at height 0. */
    private static Level floor() {
        Level level = new Level(10, 5);
        for(int index = 0; index < level.tiles.length; index++) {
            Tile tile = new Tile();
            tile.ceilHeight = 3f;
            level.tiles[index] = tile;
        }
        level.playerStartX = 1;
        level.playerStartY = 2;
        return level;
    }
}
