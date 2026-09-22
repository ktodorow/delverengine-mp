package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.multiplayer.lobby.CampaignSlot;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;

/** Bounded Host-owned status for one persistent Campaign Slot. */
public final class PartyMemberStatus {
    public static final int DEFAULT_HEALTH = 8;
    public static final int DEFAULT_REMAINING_LIVES = 3;
    public static final int MAX_HEALTH = 1000000;
    public static final int MAX_REMAINING_LIVES = 5;
    public static final int MAX_BLEEDOUT_TICKS = 600;
    public static final int MAX_REVIVAL_TICKS = 180;

    private final int campaignSlot;
    private final NetworkEntityId entityId;
    private final String nickname;
    private final String avatarId;
    private final int health;
    private final int maximumHealth;
    private final int remainingLives;
    private final PartyMemberState state;
    private final int bleedoutTicks;
    private final int revivalTicks;
    private final int reviverSlot;

    public PartyMemberStatus(int campaignSlot, NetworkEntityId entityId,
            String nickname, String avatarId, int health, int maximumHealth,
            int remainingLives, PartyMemberState state) {
        this(campaignSlot, entityId, nickname, avatarId, health, maximumHealth,
                remainingLives, state, 0, 0, 0);
    }

    /** Tick counts are values at publication; observers count down locally while unpaused. */
    public PartyMemberStatus(int campaignSlot, NetworkEntityId entityId,
            String nickname, String avatarId, int health, int maximumHealth,
            int remainingLives, PartyMemberState state, int bleedoutTicks,
            int revivalTicks, int reviverSlot) {
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Campaign Slot must be 1-4.");
        }
        if(maximumHealth < 1 || maximumHealth > MAX_HEALTH
                || health < 0 || health > maximumHealth) {
            throw new IllegalArgumentException("Party health is outside protocol bounds.");
        }
        if(remainingLives < 0 || remainingLives > MAX_REMAINING_LIVES) {
            throw new IllegalArgumentException("Remaining Lives must be 0-5.");
        }
        if(state == null) throw new IllegalArgumentException("Party member state cannot be null.");
        if(bleedoutTicks < 0 || bleedoutTicks > MAX_BLEEDOUT_TICKS
                || revivalTicks < 0 || revivalTicks > MAX_REVIVAL_TICKS
                || reviverSlot < 0 || reviverSlot > 4 || reviverSlot == campaignSlot
                || (revivalTicks > 0) != (reviverSlot > 0)) {
            throw new IllegalArgumentException("Party incapacitation timing is outside protocol bounds.");
        }
        if(state == PartyMemberState.DISCONNECTED && entityId != null) {
            throw new IllegalArgumentException("Disconnected Party member cannot own an Active Floor Entity.");
        }
        if(state != PartyMemberState.DISCONNECTED && entityId == null) {
            throw new IllegalArgumentException("Active Party member requires an Active Floor Entity.");
        }
        SlotPresentation presentation = new SlotPresentation(nickname, avatarId);
        this.campaignSlot = campaignSlot;
        this.entityId = entityId;
        this.nickname = presentation.getNickname();
        this.avatarId = presentation.getAvatarId();
        this.health = health;
        this.maximumHealth = maximumHealth;
        this.remainingLives = remainingLives;
        this.state = state;
        this.bleedoutTicks = bleedoutTicks;
        this.revivalTicks = revivalTicks;
        this.reviverSlot = reviverSlot;
    }

    public static PartyMemberStatus initial(CampaignSlot slot,
            MovementEntityDescriptor descriptor) {
        return initial(slot, descriptor, DEFAULT_REMAINING_LIVES);
    }

    public static PartyMemberStatus initial(CampaignSlot slot,
            MovementEntityDescriptor descriptor, int startingLives) {
        if(slot == null) throw new IllegalArgumentException("Campaign Slot cannot be null.");
        if(descriptor != null && descriptor.getCampaignSlot() != slot.getNumber()) {
            throw new IllegalArgumentException("Movement Entity belongs to a different Campaign Slot.");
        }
        return new PartyMemberStatus(slot.getNumber(),
                descriptor == null ? null : descriptor.getEntityId(),
                slot.getPresentation().getNickname(),
                slot.getPresentation().getAvatarId(), DEFAULT_HEALTH, DEFAULT_HEALTH,
                startingLives, descriptor == null
                        ? PartyMemberState.DISCONNECTED : PartyMemberState.CONNECTED);
    }

    public PartyMemberStatus disconnected() {
        return new PartyMemberStatus(campaignSlot, null, nickname, avatarId,
                health, maximumHealth, remainingLives, PartyMemberState.DISCONNECTED);
    }

    /** Keeps a frozen Active Floor body visible during bounded reconnect grace. */
    public PartyMemberStatus reconnecting() {
        if(entityId == null) {
            throw new IllegalStateException(
                    "A reconnecting Party member must retain its Active Floor Entity.");
        }
        return new PartyMemberStatus(campaignSlot, entityId, nickname, avatarId,
                health, maximumHealth, remainingLives, PartyMemberState.RECONNECTING);
    }

    /** Restores an existing Campaign Slot to active control without changing its character state. */
    public PartyMemberStatus connected(NetworkEntityId connectedEntityId) {
        if(connectedEntityId == null) {
            throw new IllegalArgumentException("Connected Party member requires an Active Floor Entity.");
        }
        return new PartyMemberStatus(campaignSlot, connectedEntityId, nickname, avatarId,
                health, maximumHealth, remainingLives, PartyMemberState.CONNECTED);
    }

    /** Projects Host-authoritative combat health without changing lifecycle state. */
    public PartyMemberStatus withHealth(int authoritativeHealth,
            int authoritativeMaximumHealth) {
        return new PartyMemberStatus(campaignSlot, entityId, nickname, avatarId,
                authoritativeHealth, authoritativeMaximumHealth, remainingLives, state,
                bleedoutTicks, revivalTicks, reviverSlot);
    }

    /** Projects Host-authoritative Lives and incapacitation without touching presence identity. */
    public PartyMemberStatus withIncapacitation(PartyMemberState authoritativeState,
            int authoritativeLives, int authoritativeBleedoutTicks,
            int authoritativeRevivalTicks, int authoritativeReviverSlot) {
        return new PartyMemberStatus(campaignSlot,
                authoritativeState == PartyMemberState.DISCONNECTED ? null : entityId,
                nickname, avatarId, health, maximumHealth, authoritativeLives,
                authoritativeState, authoritativeBleedoutTicks, authoritativeRevivalTicks,
                authoritativeReviverSlot);
    }

    public int getCampaignSlot() {
        return campaignSlot;
    }

    public NetworkEntityId getEntityId() {
        return entityId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatarId() {
        return avatarId;
    }

    public int getHealth() {
        return health;
    }

    public int getMaximumHealth() {
        return maximumHealth;
    }

    public int getRemainingLives() {
        return remainingLives;
    }

    public PartyMemberState getState() {
        return state;
    }

    public int getBleedoutTicks() {
        return bleedoutTicks;
    }

    public int getRevivalTicks() {
        return revivalTicks;
    }

    /** Campaign Slot performing Revival, or 0. */
    public int getReviverSlot() {
        return reviverSlot;
    }
}
