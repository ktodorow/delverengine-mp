package com.interrupt.dungeoneer.multiplayer.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe client view that rejects stale lifecycle and snapshot updates. */
public final class MovementReplicationState {
    private static final int MAX_SNAPSHOT_HISTORY = 32;

    private final Map<NetworkEntityId, MovementEntityDescriptor> entities =
            new LinkedHashMap<NetworkEntityId, MovementEntityDescriptor>();
    private final Map<NetworkEntityId, Long> tombstones =
            new LinkedHashMap<NetworkEntityId, Long>();
    private final List<MovementSnapshot> snapshots = new ArrayList<MovementSnapshot>();
    private long latestLifecycleSequence;
    private long latestSnapshotSequence;

    public synchronized boolean applySpawn(MovementEntityDescriptor descriptor) {
        if(descriptor == null) throw new IllegalArgumentException("Entity spawn cannot be null.");
        if(descriptor.getLifecycleSequence() <= latestLifecycleSequence) return false;
        Long tombstone = tombstones.get(descriptor.getEntityId());
        if(tombstone != null && descriptor.getLifecycleSequence() <= tombstone) return false;
        MovementEntityDescriptor existing = entities.get(descriptor.getEntityId());
        if(existing != null
                && descriptor.getLifecycleSequence() <= existing.getLifecycleSequence()) {
            return false;
        }
        entities.put(descriptor.getEntityId(), descriptor);
        latestLifecycleSequence = descriptor.getLifecycleSequence();
        return true;
    }

    public synchronized boolean applyDespawn(long lifecycleSequence,
            NetworkEntityId entityId) {
        if(lifecycleSequence <= 0L) {
            throw new IllegalArgumentException("Entity lifecycle sequence must be positive.");
        }
        if(entityId == null) throw new IllegalArgumentException("Network Entity ID cannot be null.");
        if(lifecycleSequence <= latestLifecycleSequence) return false;
        MovementEntityDescriptor existing = entities.get(entityId);
        if(existing != null && lifecycleSequence <= existing.getLifecycleSequence()) return false;
        entities.remove(entityId);
        tombstones.put(entityId, lifecycleSequence);
        latestLifecycleSequence = lifecycleSequence;
        return true;
    }

    public synchronized boolean applySnapshot(MovementSnapshot snapshot) {
        if(snapshot == null) throw new IllegalArgumentException("Movement snapshot cannot be null.");
        if(snapshot.getSequence() <= latestSnapshotSequence) return false;
        List<MovementEntityState> accepted = new ArrayList<MovementEntityState>();
        for(MovementEntityState state : snapshot.getEntities()) {
            MovementEntityDescriptor descriptor = entities.get(state.getEntityId());
            if(descriptor != null
                    && descriptor.getLifecycleSequence() == state.getLifecycleSequence()) {
                accepted.add(state);
            }
        }
        MovementSnapshot filtered = new MovementSnapshot(snapshot.getSequence(),
                snapshot.getHostTick(), accepted);
        snapshots.add(filtered);
        while(snapshots.size() > MAX_SNAPSHOT_HISTORY) snapshots.remove(0);
        latestSnapshotSequence = snapshot.getSequence();
        return true;
    }

    public synchronized List<MovementEntityDescriptor> getEntities() {
        List<MovementEntityDescriptor> copy =
                new ArrayList<MovementEntityDescriptor>(entities.values());
        Collections.sort(copy, new Comparator<MovementEntityDescriptor>() {
            @Override
            public int compare(MovementEntityDescriptor first,
                    MovementEntityDescriptor second) {
                return first.getEntityId().compareTo(second.getEntityId());
            }
        });
        return Collections.unmodifiableList(copy);
    }

    public synchronized List<MovementSnapshot> getSnapshots() {
        return Collections.unmodifiableList(new ArrayList<MovementSnapshot>(snapshots));
    }

    public synchronized MovementEntityDescriptor getEntity(NetworkEntityId entityId) {
        return entities.get(entityId);
    }

    public synchronized long getLatestLifecycleSequence() {
        return latestLifecycleSequence;
    }

    public synchronized long getLatestSnapshotSequence() {
        return latestSnapshotSequence;
    }
}
