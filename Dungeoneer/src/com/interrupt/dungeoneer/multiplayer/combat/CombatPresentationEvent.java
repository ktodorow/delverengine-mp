package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;

/** Ordered, engine-free description of one Host-resolved combat presentation. */
public final class CombatPresentationEvent implements HostSessionEvent {
    private final long sequence;
    private final long hostTick;
    private final String sourceId;
    private final String targetId;
    private final CombatAction action;
    private final CombatPresentationPhase phase;
    private final float originX;
    private final float originY;
    private final float originZ;
    private final float impactX;
    private final float impactY;
    private final float impactZ;
    private final boolean stateChanged;

    public CombatPresentationEvent(long sequence, long hostTick, String sourceId,
            String targetId, CombatAction action, float originX, float originY,
            float originZ, float impactX, float impactY, float impactZ,
            boolean stateChanged) {
        this(sequence, hostTick, sourceId, targetId, action,
                stateChanged && action != null && action.isHarmful()
                        ? CombatPresentationPhase.DAMAGE
                        : CombatPresentationPhase.ATTACK,
                originX, originY, originZ, impactX, impactY, impactZ,
                stateChanged);
    }

    public CombatPresentationEvent(long sequence, long hostTick, String sourceId,
            String targetId, CombatAction action, CombatPresentationPhase phase,
            float originX, float originY, float originZ, float impactX,
            float impactY, float impactZ, boolean stateChanged) {
        if(sequence < 1L || hostTick < 0L || sourceId == null
                || sourceId.trim().isEmpty() || targetId == null || action == null
                || phase == null
                || !isFinite(originX) || !isFinite(originY) || !isFinite(originZ)
                || !isFinite(impactX) || !isFinite(impactY) || !isFinite(impactZ)) {
            throw new IllegalArgumentException("Combat presentation event is invalid.");
        }
        this.sequence = sequence;
        this.hostTick = hostTick;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.action = action;
        this.phase = phase;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.impactX = impactX;
        this.impactY = impactY;
        this.impactZ = impactZ;
        this.stateChanged = stateChanged;
    }

    public long getSequence() { return sequence; }
    public long getHostTick() { return hostTick; }
    public String getSourceId() { return sourceId; }
    public String getTargetId() { return targetId; }
    public CombatAction getAction() { return action; }
    public CombatPresentationPhase getPhase() { return phase; }
    public float getOriginX() { return originX; }
    public float getOriginY() { return originY; }
    public float getOriginZ() { return originZ; }
    public float getImpactX() { return impactX; }
    public float getImpactY() { return impactY; }
    public float getImpactZ() { return impactZ; }
    public boolean isStateChanged() { return stateChanged; }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
