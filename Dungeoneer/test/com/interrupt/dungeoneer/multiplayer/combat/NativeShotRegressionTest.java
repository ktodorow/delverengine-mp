package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.gfx.Material;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.entities.spells.MagicMissile;
import com.interrupt.dungeoneer.multiplayer.movement.RemoteAvatar;
import com.interrupt.dungeoneer.entities.projectiles.MagicMissileProjectile;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.DirectConnectMovementController;
import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Wand;
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
        requests.add(new CombatRequest(remote, 1L, CombatAction.SPELL, 1, 0, 0, 1, 17L));
        DirectConnectPeer peer = peer(requests, events);
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
        wand.spell.spellColor = Color.BLUE.cpy();
        ((MagicMissile)wand.spell).appearance =
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
        }
        finally { Game.instance = previous; }
    }

    static Game game() {
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new Player();
        game.level = new Level(4, 4) {
            @Override public boolean canSee(float x, float y, float tx, float ty) { return true; }
        };
        return game;
    }

    private DirectConnectPeer peer(final List<CombatRequest> requests, final List<Object[]> events) {
        return (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class, NativeCombatAuthority.class}, (proxy, method, args) -> {
            String name = method.getName();
            if(name.equals("getNextCombatRequestId")) return 1L;
            if(name.equals("getLocalMovementEntityId")) return new NetworkEntityId(1L);
            if(name.equals("getPartyStatus")) return new PartyStatusSnapshot(1L, Collections.singletonList(
                    new PartyMemberStatus(1, new NetworkEntityId(1L), "Host", "humanoid-1", 8, 8, 3,
                            PartyMemberState.CONNECTED)));
            if(name.equals("drainNativeCombatRequests")) {
                List<CombatRequest> result = new ArrayList<CombatRequest>(requests); requests.clear(); return result;
            }
            if(name.equals("publishNativePresentation")) { events.add(args); return null; }
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
    }
}
