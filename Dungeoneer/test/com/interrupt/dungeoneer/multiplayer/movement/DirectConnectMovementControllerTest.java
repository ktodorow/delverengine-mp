package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.entities.Player;
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
        @Override public PartyStatusSnapshot getPartyStatus() { return null; }
        @Override public void submitMovementInput(MovementInputFrame input) { }
        @Override public void close() { }
    }
}
