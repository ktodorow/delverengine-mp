package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyMemberStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Host forwards only health the native tick changed since the snapshot it last applied. */
public class NativeHealthSyncRegressionTest {
    private final List<Integer> forwarded = new ArrayList<Integer>();
    private int hostHealth = 8;

    private DirectConnectPeer peer() {
        return (DirectConnectPeer)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DirectConnectPeer.class, NativeCombatAuthority.class}, (proxy, method, args) -> {
            String name = method.getName();
            if(name.equals("getNextCombatRequestId")) return 1L;
            if(name.equals("getLocalMovementEntityId")) return new NetworkEntityId(1L);
            if(name.equals("getMovementEntities")) return Collections.singletonList(
                    new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                            new ParticipantId("campaign-slot-1"), 1, "Host", "humanoid-1"));
            if(name.equals("getPartyStatus")) return new PartyStatusSnapshot(1L, Collections.singletonList(
                    new PartyMemberStatus(1, new NetworkEntityId(1L), "Host", "humanoid-1", hostHealth, 8, 3,
                            PartyMemberState.CONNECTED)));
            if(name.equals("getCombatSnapshot")) return new CombatSnapshot(1L, 1L,
                    Collections.<MonsterSnapshot>emptyList(), Collections.singletonList(
                            new CombatantSnapshot("participant:campaign-slot-1",
                                    CombatantKind.PARTICIPANT, hostHealth, 8)));
            if(name.equals("canApplyNativeParticipantEffect")) return true;
            if(name.equals("applyNativeParticipantDamage")) { forwarded.add((Integer)args[2]); return null; }
            if(method.getReturnType() == List.class) return Collections.emptyList();
            if(method.getReturnType() == boolean.class) return false;
            return null;
        });
    }

    @Test
    public void respawnRestoreLandingBetweenPrepareAndUpdateIsNotForwardedAsDamage() {
        DirectConnectCombatController controller = new DirectConnectCombatController(peer(), false);
        Game game = NativeShotRegressionTest.game();
        hostHealth = 0;
        controller.prepare(game);
        assertEquals(0, game.player.hp);

        // Host tick thread restores 50 percent after prepare applied the Downed snapshot.
        hostHealth = 4;
        controller.update(game);
        assertTrue("restore must not be mirrored back as native damage " + forwarded, forwarded.isEmpty());
        assertEquals(4, game.player.hp);

        // Native effects that change health directly are still forwarded as a delta.
        game.player.hp = 3;
        controller.update(game);
        assertEquals(Collections.singletonList(1), forwarded);
        // update re-applies the unchanged Host snapshot (4) afterwards, so a native heal to 5 is +1.
        assertEquals(4, game.player.hp);
        game.player.hp = 5;
        controller.update(game);
        assertEquals(-1, forwarded.get(1).intValue());
        assertEquals(2, forwarded.size());
    }
}
