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

    public ItemRequest(ParticipantId participantId, long requestId,
            ItemAction action, long entityId) {
        this(participantId, requestId, action, entityId, 0, 0);
    }

    public ItemRequest(ParticipantId participantId, long requestId,
            ItemAction action, long entityId, int condition, int quantity) {
        if(condition < 0 || condition > 4 || quantity < 0 || quantity > 1000000) {
            throw new IllegalArgumentException("Invalid item spending request.");
        }
        this.condition = condition;
        this.quantity = quantity;
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
