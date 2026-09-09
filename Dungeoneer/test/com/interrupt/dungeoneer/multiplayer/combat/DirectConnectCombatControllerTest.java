package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Sword;
import com.interrupt.dungeoneer.entities.items.Wand;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.entities.spells.Heal;
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
        peer.presentations.add(event(1L, "participant:campaign-slot-2", CombatAction.SPELL));
        peer.presentations.add(event(2L, "participant:campaign-slot-1", CombatAction.MELEE));
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

        assertEquals(2, level.non_collidable_entities.size);
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
    public void nativeSelfHealIsDeferredToHostWithoutChangingLocalHealth() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Player player = new Player();
        player.hp = 2;
        player.setHealthAuthorityListener(controller);

        new Heal().doCast(player, new Vector3(), new Vector3());

        assertEquals(2, player.hp);
        assertEquals(1L, peer.targetedRequestId);
        assertEquals(CombatAction.BENEFICIAL_SPELL, peer.targetedAction);
        assertEquals("participant:campaign-slot-1", peer.targetId);
    }

    @Test
    public void nativeHealWandSubmitsAimedSpellThenAuthoritativeSelfHeal() {
        StubPeer peer = new StubPeer();
        DirectConnectCombatController controller = new DirectConnectCombatController(peer, false);
        Player player = new Player();
        player.hp = 2;
        player.history = new PlayerHistory() {
            @Override public void usedWand(Item item) { }
        };
        player.setWeaponAttackListener(controller);
        player.setHealthAuthorityListener(controller);

        new TestHealWand().doAttack(player, new Level(4, 4), 1f);

        assertEquals(2, player.hp);
        assertEquals(Arrays.asList(1L, 2L), peer.submittedRequestIds);
        assertEquals(Arrays.asList(CombatAction.SPELL, CombatAction.BENEFICIAL_SPELL),
                peer.submittedActions);
        assertEquals(Arrays.asList(true, false), peer.submittedDirected);
        assertEquals("participant:campaign-slot-1", peer.targetId);
    }

    private CombatPresentationEvent event(long sequence, String sourceId,
            CombatAction action) {
        return new CombatPresentationEvent(sequence, sequence * 3L, sourceId,
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, action,
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
