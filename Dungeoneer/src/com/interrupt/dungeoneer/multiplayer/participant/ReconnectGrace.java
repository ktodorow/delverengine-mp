package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;

/** Host-owned protection interval for one disconnected standing Participant. */
public final class ReconnectGrace {
    private final int campaignSlot;
    private final NetworkEntityId entityId;
    private final long disconnectedAtHostTick;
    private final long expiresAtHostTick;

    public ReconnectGrace(int campaignSlot, NetworkEntityId entityId,
            long disconnectedAtHostTick, long expiresAtHostTick) {
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Campaign Slot must be 1-4.");
        }
        if(entityId == null) throw new IllegalArgumentException("Reconnect grace requires entity.");
        if(disconnectedAtHostTick < 0L || expiresAtHostTick <= disconnectedAtHostTick) {
            throw new IllegalArgumentException("Reconnect grace Host ticks are invalid.");
        }
        this.campaignSlot = campaignSlot;
        this.entityId = entityId;
        this.disconnectedAtHostTick = disconnectedAtHostTick;
        this.expiresAtHostTick = expiresAtHostTick;
    }

    public int getCampaignSlot() {
        return campaignSlot;
    }

    public NetworkEntityId getEntityId() {
        return entityId;
    }

    public long getDisconnectedAtHostTick() {
        return disconnectedAtHostTick;
    }

    public long getExpiresAtHostTick() {
        return expiresAtHostTick;
    }

    public boolean isFrozen() {
        return true;
    }

    /** Combat authority must reject damage while this state exists. */
    public boolean isInvulnerable() {
        return true;
    }

    public long getRemainingUnpausedTicks(long hostTick) {
        return Math.max(0L, expiresAtHostTick - hostTick);
    }
}
