package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.host.HostSessionSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Approximately 20 Hz authoritative movement update. */
public final class MovementSnapshot implements HostSessionSnapshot {
    private final long sequence;
    private final long hostTick;
    private final Map<NetworkEntityId, MovementEntityState> entities;

    public MovementSnapshot(long sequence, long hostTick, List<MovementEntityState> entities) {
        if(sequence <= 0L) throw new IllegalArgumentException("Movement snapshot sequence must be positive.");
        if(hostTick <= 0L) throw new IllegalArgumentException("Movement snapshot Host tick must be positive.");
        if(entities == null || entities.size() > 4) {
            throw new IllegalArgumentException("Movement snapshot must contain zero to four entities.");
        }
        LinkedHashMap<NetworkEntityId, MovementEntityState> byId =
                new LinkedHashMap<NetworkEntityId, MovementEntityState>();
        for(MovementEntityState entity : entities) {
            if(entity == null) throw new IllegalArgumentException("Movement snapshot entity cannot be null.");
            if(byId.put(entity.getEntityId(), entity) != null) {
                throw new IllegalArgumentException("Movement snapshot contains duplicate Network Entity ID.");
            }
        }
        this.sequence = sequence;
        this.hostTick = hostTick;
        this.entities = Collections.unmodifiableMap(byId);
    }

    public long getSequence() { return sequence; }
    public long getHostTick() { return hostTick; }

    public List<MovementEntityState> getEntities() {
        return Collections.unmodifiableList(new ArrayList<MovementEntityState>(entities.values()));
    }

    public MovementEntityState getEntity(NetworkEntityId entityId) {
        return entities.get(entityId);
    }
}
