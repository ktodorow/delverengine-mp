package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Reliable spawn metadata for one authoritative Participant character. */
public final class MovementEntityDescriptor {
    private final long lifecycleSequence;
    private final NetworkEntityId entityId;
    private final ParticipantId participantId;
    private final int campaignSlot;
    private final String nickname;
    private final String avatarId;

    public MovementEntityDescriptor(long lifecycleSequence, NetworkEntityId entityId,
            ParticipantId participantId, int campaignSlot, String nickname, String avatarId) {
        if(lifecycleSequence <= 0L) {
            throw new IllegalArgumentException("Entity lifecycle sequence must be positive.");
        }
        if(entityId == null) throw new IllegalArgumentException("Network Entity ID cannot be null.");
        if(participantId == null) throw new IllegalArgumentException("Participant ID cannot be null.");
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Campaign Slot must be 1-4.");
        }
        SlotPresentation presentation = new SlotPresentation(nickname, avatarId);
        this.lifecycleSequence = lifecycleSequence;
        this.entityId = entityId;
        this.participantId = participantId;
        this.campaignSlot = campaignSlot;
        this.nickname = presentation.getNickname();
        this.avatarId = presentation.getAvatarId();
    }

    public long getLifecycleSequence() {
        return lifecycleSequence;
    }

    public NetworkEntityId getEntityId() {
        return entityId;
    }

    public ParticipantId getParticipantId() {
        return participantId;
    }

    public int getCampaignSlot() {
        return campaignSlot;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatarId() {
        return avatarId;
    }
}
