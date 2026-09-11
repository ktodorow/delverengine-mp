package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.multiplayer.host.HostSessionCommand;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Participant identity is assigned by Host from the authenticated connection. */
public final class ItemRequest implements HostSessionCommand {
    private final ParticipantId participantId;
    public final long requestId;
    public final long entityId;
    public final ItemAction action;
    public final int condition, quantity;
    public final boolean hasAim;
    public final float aimX, aimY, aimZ;

    public ItemRequest(ParticipantId participantId, long requestId,
            ItemAction action, long entityId) {
        this(participantId, requestId, action, entityId, 0, 0);
    }

    public ItemRequest(ParticipantId participantId, long requestId,
            ItemAction action, long entityId, int condition, int quantity) {
        this(participantId, requestId, action, entityId, condition, quantity,
                false, 0f, 0f, 0f);
    }

    public ItemRequest(ParticipantId participantId, long requestId,
            ItemAction action, long entityId, int condition, int quantity,
            boolean hasAim, float aimX, float aimY, float aimZ) {
        if(condition < 0 || condition > 4 || quantity < 0 || quantity > 1000000) {
            throw new IllegalArgumentException("Invalid item spending request.");
        }
        float aimLength = aimX * aimX + aimY * aimY + aimZ * aimZ;
        if(hasAim && (Float.isNaN(aimLength) || Float.isInfinite(aimLength)
                || aimLength < 0.25f || aimLength > 2.25f)) {
            throw new IllegalArgumentException("Invalid item action aim.");
        }
        if(!hasAim && (aimX != 0f || aimY != 0f || aimZ != 0f)) {
            throw new IllegalArgumentException("Unexpected item action aim.");
        }
        this.condition = condition;
        this.quantity = quantity;
        this.hasAim = hasAim;
        this.aimX = aimX;
        this.aimY = aimY;
        this.aimZ = aimZ;
        if(participantId == null || action == null || requestId < 1L
                || requestId == Long.MAX_VALUE || entityId < 1L) {
            throw new IllegalArgumentException("Invalid physical item request.");
        }
        this.participantId = participantId;
        this.requestId = requestId;
        this.action = action;
        this.entityId = entityId;
    }

    @Override
    public ParticipantId getParticipantId() { return participantId; }
}
