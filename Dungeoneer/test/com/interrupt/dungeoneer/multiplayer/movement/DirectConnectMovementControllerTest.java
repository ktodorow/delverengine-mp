package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DirectConnectMovementControllerTest {
    @Test
    public void appliesHostApprovedSpawnBeforeFirstGameplayTick() {
        NetworkEntityId localEntityId = new NetworkEntityId(2L);
        MovementEntityState state = new MovementEntityState(localEntityId, 1L, 0L,
                8f, 9f, 0.75f, 0.1f, 0.2f, 0.3f, 1.25f, MovementState.MOVING);
        DirectConnectMovementController controller = new DirectConnectMovementController(
                new StubPeer(localEntityId,
                        Arrays.asList(new MovementSnapshot(1L, 3L, Arrays.asList(state)))));
        Player player = new Player();

        assertTrue(controller.applyInitialAuthoritativeState(player));
        assertEquals(8f, player.x, 0f);
        assertEquals(9f, player.y, 0f);
        assertEquals(0.75f, player.z, 0f);
        assertEquals(0.1f, player.xa, 0f);
        assertEquals(0.2f, player.ya, 0f);
        assertEquals(0.3f, player.za, 0f);
        assertEquals(1.25f, player.rot, 0f);
    }

    private static final class StubPeer implements DirectConnectPeer {
        private final NetworkEntityId localEntityId;
        private final List<MovementSnapshot> snapshots;

        private StubPeer(NetworkEntityId localEntityId, List<MovementSnapshot> snapshots) {
            this.localEntityId = localEntityId;
            this.snapshots = snapshots;
        }

        @Override public DirectConnectStatus getStatus() { return null; }
        @Override public String getRole() { return "Test"; }
        @Override public String getEndpoint() { return "memory"; }
        @Override public NetworkEntityId getLocalMovementEntityId() { return localEntityId; }
        @Override public List<MovementEntityDescriptor> getMovementEntities() {
            return Collections.emptyList();
        }
        @Override public List<MovementSnapshot> getMovementSnapshots() { return snapshots; }
        @Override public CombatSnapshot getCombatSnapshot() { return null; }
        @Override public List<CombatPresentationEvent> getCombatPresentationEvents() {
            return Collections.emptyList();
        }
        @Override public long getNextCombatRequestId() { return 1L; }
        @Override public PartyStatusSnapshot getPartyStatus() { return null; }
        @Override public PartyCommunicationState getPartyCommunicationState() {
            return PartyCommunicationState.initial();
        }
        @Override public void submitPartyChat(String text) { }
        @Override public void requestPauseSession() { }
        @Override public boolean isSessionPaused() { return false; }
        @Override public boolean canControlSessionPause() { return false; }
        @Override public void setSessionPaused(boolean paused) { }
        @Override public void submitCombatAction(long requestId, CombatAction action, String targetId) { }
        @Override public void submitCombatAction(long requestId, CombatAction action,
                float aimX, float aimY, float aimZ) { }
        @Override public void submitMovementInput(MovementInputFrame input) { }
        @Override public void close() { }
    }
}
