package com.interrupt.dungeoneer.multiplayer.items;

/** Transient response to one authenticated physical-item request. */
public final class ItemActionResult {
    public final long requestId, entityId;
    public final boolean accepted;
    public ItemActionResult(long requestId, long entityId, boolean accepted) {
        if(requestId < 1 || requestId == Long.MAX_VALUE || entityId < 1)
            throw new IllegalArgumentException("Invalid item action result.");
        this.requestId = requestId; this.entityId = entityId; this.accepted = accepted;
    }
}
