package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.host.HostSessionCommand;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Client intent only; Host chooses damage, target validity, and result. */
public final class CombatRequest implements HostSessionCommand {
    public static final long EXHAUSTED_REQUEST_ID = Long.MAX_VALUE;
    public static final long MAX_REQUEST_ID = EXHAUSTED_REQUEST_ID - 1L;
    public static final float MAX_ATTACK_POWER = 1.25f;

    private final ParticipantId participantId;
    private final long requestId;
    private final CombatAction action;
    private final String targetId;
    private final boolean directed;
    private final float aimX;
    private final float aimY;
    private final float aimZ;
    private final float attackPower;

    public CombatRequest(ParticipantId participantId, long requestId,
            CombatAction action, String targetId) {
        if(participantId == null) throw new IllegalArgumentException("Participant ID cannot be null.");
        if(!isValidRequestId(requestId)) {
            throw new IllegalArgumentException("Combat request ID is outside valid bounds.");
        }
        if(action == null) throw new IllegalArgumentException("Combat action cannot be null.");
        if(!action.allowsTargetedRequest()) {
            throw new IllegalArgumentException("Weapon combat requests require an aim direction.");
        }
        if(targetId == null || targetId.trim().isEmpty()) {
            throw new IllegalArgumentException("Combat target ID cannot be blank.");
        }
        this.participantId = participantId;
        this.requestId = requestId;
        this.action = action;
        this.targetId = targetId;
        directed = false;
        aimX = 0f;
        aimY = 0f;
        aimZ = 0f;
        attackPower = 1f;
    }

    public CombatRequest(ParticipantId participantId, long requestId,
            CombatAction action, float aimX, float aimY, float aimZ) {
        this(participantId, requestId, action, aimX, aimY, aimZ, 1f);
    }

    public CombatRequest(ParticipantId participantId, long requestId,
            CombatAction action, float aimX, float aimY, float aimZ,
            float attackPower) {
        if(participantId == null) throw new IllegalArgumentException("Participant ID cannot be null.");
        if(!isValidRequestId(requestId)) {
            throw new IllegalArgumentException("Combat request ID is outside valid bounds.");
        }
        if(action == null || !action.isDirected()) {
            throw new IllegalArgumentException("Directed combat action is invalid.");
        }
        float aimLengthSquared = aimX * aimX + aimY * aimY + aimZ * aimZ;
        if(!isFinite(aimX) || !isFinite(aimY) || !isFinite(aimZ)
                || !isFinite(aimLengthSquared) || aimLengthSquared < 0.000001f
                || aimLengthSquared > 3.01f) {
            throw new IllegalArgumentException("Combat aim must be finite and non-zero.");
        }
        if(!isFinite(attackPower) || attackPower < 0f
                || attackPower > MAX_ATTACK_POWER) {
            throw new IllegalArgumentException("Combat attack power is outside bounds.");
        }
        this.participantId = participantId;
        this.requestId = requestId;
        this.action = action;
        targetId = "";
        directed = true;
        this.aimX = aimX;
        this.aimY = aimY;
        this.aimZ = aimZ;
        this.attackPower = attackPower;
    }

    @Override
    public ParticipantId getParticipantId() { return participantId; }
    public long getRequestId() { return requestId; }
    public CombatAction getAction() { return action; }
    public String getTargetId() { return targetId; }
    public boolean isDirected() { return directed; }
    public float getAimX() { return aimX; }
    public float getAimY() { return aimY; }
    public float getAimZ() { return aimZ; }
    public float getAttackPower() { return attackPower; }

    public static boolean isValidRequestId(long requestId) {
        return requestId >= 1L && requestId <= MAX_REQUEST_ID;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
