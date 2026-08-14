package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementReplicationStateTest {
    @Test
    public void preservesReliableSpawnUpdateDespawnOrdering() {
        MovementReplicationState replication = new MovementReplicationState();
        NetworkEntityId entityId = new NetworkEntityId(2L);
        MovementEntityDescriptor first = descriptor(1L, entityId);

        assertTrue(replication.applySpawn(first));
        assertFalse(replication.applySpawn(first));
        assertTrue(replication.applySnapshot(snapshot(3L, state(entityId, 1L, 3f))));
        assertFalse(replication.applySnapshot(snapshot(2L, state(entityId, 1L, 2f))));
        assertEquals(3f, replication.getSnapshots().get(0)
                .getEntity(entityId).getX(), 0f);

        assertTrue(replication.applyDespawn(2L, entityId));
        assertFalse(replication.applySnapshot(snapshot(4L, state(entityId, 1L, 4f)))
                && replication.getSnapshots().get(1).getEntity(entityId) != null);
        assertTrue(replication.getEntities().isEmpty());
        assertFalse(replication.applySpawn(first));

        MovementEntityDescriptor respawned = descriptor(3L, entityId);
        assertTrue(replication.applySpawn(respawned));
        assertTrue(replication.applySnapshot(snapshot(5L, state(entityId, 3L, 5f))));
        assertEquals(5f, replication.getSnapshots().get(2)
                .getEntity(entityId).getX(), 0f);
        assertFalse(replication.applySnapshot(new MovementSnapshot(6L, 14L,
                Arrays.asList(state(entityId, 3L, 6f)))));
        assertEquals(15L, replication.getLatestSnapshotHostTick());
    }

    @Test
    public void updateBeforeReliableSpawnCannotCreateAnEntity() {
        MovementReplicationState replication = new MovementReplicationState();
        NetworkEntityId entityId = new NetworkEntityId(2L);

        assertTrue(replication.applySnapshot(snapshot(1L, state(entityId, 1L, 9f))));
        assertTrue(replication.getSnapshots().get(0).getEntities().isEmpty());
        assertTrue(replication.applySpawn(descriptor(1L, entityId)));
        assertTrue(replication.applySnapshot(snapshot(2L, state(entityId, 1L, 10f))));
        assertEquals(10f, replication.getSnapshots().get(1)
                .getEntity(entityId).getX(), 0f);
    }

    private MovementEntityDescriptor descriptor(long lifecycle, NetworkEntityId entityId) {
        return new MovementEntityDescriptor(lifecycle, entityId,
                new ParticipantId("campaign-slot-2"), 2, "Friend", "humanoid-2");
    }

    private MovementEntityState state(NetworkEntityId entityId, long lifecycle, float x) {
        return new MovementEntityState(entityId, lifecycle, 1L,
                x, 2f, 0.5f, 0f, 0f, 0f, 0f, MovementState.IDLE);
    }

    private MovementSnapshot snapshot(long sequence, MovementEntityState state) {
        return new MovementSnapshot(sequence, sequence * 3L,
                state == null ? Collections.<MovementEntityState>emptyList()
                        : Arrays.asList(state));
    }
}
