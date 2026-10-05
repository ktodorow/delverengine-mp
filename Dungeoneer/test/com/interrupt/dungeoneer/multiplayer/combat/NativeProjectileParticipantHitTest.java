package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.entities.projectiles.Projectile;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import com.interrupt.dungeoneer.tiles.Tile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Host: a Monster's shot hits a client's Participant as native shots hit the Player. */
public class NativeProjectileParticipantHitTest {
    private static final ParticipantId CLIENT = new ParticipantId("campaign-slot-2");

    private final List<Object[]> damage = new ArrayList<Object[]>();
    private Game previous;
    private Game game;
    private RemoteAvatar avatar;
    private DirectConnectCombatController controller;

    @Before
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setUp() throws Exception {
        previous = Game.instance;
        game = NativeShotRegressionTest.game();
        for(int index = 0; index < game.level.tiles.length; index++) {
            game.level.tiles[index] = Tile.EmptyTile();
        }
        Game.instance = game;
        DirectConnectPeer peer = peer();
        DirectConnectMovementController movement = new DirectConnectMovementController(peer);
        avatar = new RemoteAvatar(new MovementEntityDescriptor(2L, new NetworkEntityId(2L),
                CLIENT, 2, "Client", "humanoid-2"));
        Field avatars = movement.getClass().getDeclaredField("remoteAvatars");
        avatars.setAccessible(true);
        ((Map)avatars.get(movement)).put(new NetworkEntityId(2L), avatar);
        controller = new DirectConnectCombatController(peer, movement, false);
        controller.update(game);
        avatar.x = 2.5f;
        avatar.y = 1.5f;
        avatar.z = 0f;
    }

    @After
    public void tearDown() {
        controller.dispose();
        Game.instance = previous;
    }

    @Test
    public void monsterMagicHitsTheClientInsteadOfFlyingThroughIt() {
        Projectile bolt = shot(new Monster());

        fly(bolt);

        assertFalse("The bolt stops at the client", bolt.isActive && bolt.x > avatar.x);
        assertEquals(1, damage.size());
        assertEquals(CLIENT, damage.get(0)[1]);
        assertTrue((Integer)damage.get(0)[2] > 0);
    }

    @Test
    public void teammateShotAndSpectatorLetMagicPass() {
        fly(shot(game.player));
        assertTrue("No friendly fire between Participants", damage.isEmpty());

        avatar.setIncapacitated(true);
        avatar.hidden = true;
        Projectile bolt = shot(new Monster());
        fly(bolt);

        assertTrue("A Spectator leaves no body to hit", damage.isEmpty());
        assertTrue(bolt.x > avatar.x);
    }

    private Projectile shot(com.interrupt.dungeoneer.entities.Entity owner) {
        Projectile bolt = new Projectile(1.2f, 1.5f, 0.1f, 0, 0.2f, 0f, 3, DamageType.MAGIC, owner) {
            // Impact decals and particles need a renderer.
            @Override public void hitEffect() { }
        };
        bolt.floating = true;
        game.level.entities.add(bolt);
        return bolt;
    }

    private void fly(Projectile bolt) {
        for(int frame = 0; frame < 12 && bolt.isActive && bolt.x < 3.8f; frame++) bolt.tick(game.level, 1f);
    }

    private DirectConnectPeer peer() {
        return (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class, NativeCombatAuthority.class}, (proxy, method, args) -> {
            String name = method.getName();
            if(name.equals("getNextCombatRequestId")) return 1L;
            if(name.equals("getLocalMovementEntityId")) return new NetworkEntityId(1L);
            if(name.equals("getMovementEntities")) return Arrays.asList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                            new ParticipantId("campaign-slot-1"), 1, "Host", "humanoid-1"),
                    new MovementEntityDescriptor(2L, new NetworkEntityId(2L),
                            CLIENT, 2, "Client", "humanoid-2"));
            if(name.equals("getPartyStatus")) return new PartyStatusSnapshot(1L, Arrays.asList(
                    new PartyMemberStatus(1, new NetworkEntityId(1L), "Host", "humanoid-1", 8, 8, 3,
                            PartyMemberState.CONNECTED),
                    new PartyMemberStatus(2, new NetworkEntityId(2L), "Client", "humanoid-2", 8, 8, 3,
                            PartyMemberState.CONNECTED)));
            if(name.equals("getCombatSnapshot")) return new CombatSnapshot(1L, 1L,
                    Collections.<MonsterSnapshot>emptyList(), Arrays.asList(
                            new CombatantSnapshot("participant:campaign-slot-1",
                                    CombatantKind.PARTICIPANT, 8, 8),
                            new CombatantSnapshot("participant:campaign-slot-2",
                                    CombatantKind.PARTICIPANT, 8, 8)));
            if(name.equals("canApplyNativeParticipantEffect")) return true;
            if(name.equals("applyNativeParticipantDamage")) { damage.add(args); return null; }
            Class<?> type = method.getReturnType();
            if(type == List.class) return Collections.emptyList();
            if(type == boolean.class) return false;
            if(type == long.class) return 0L;
            if(type == int.class) return 0;
            if(type == float.class) return 0f;
            return null;
        });
    }
}
