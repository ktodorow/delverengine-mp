package com.interrupt.dungeoneer.multiplayer.combat;

/** Ordered visual phase of one Host-authoritative combat action. */
public enum CombatPresentationPhase {
    ATTACK(1),
    IMPACT(2),
    DAMAGE(3);

    private final int wireId;

    CombatPresentationPhase(int wireId) {
        this.wireId = wireId;
    }

    public int getWireId() { return wireId; }

    public static CombatPresentationPhase fromWireId(int wireId) {
        for(CombatPresentationPhase phase : values()) {
            if(phase.wireId == wireId) return phase;
        }
        throw new IllegalArgumentException("Unknown combat presentation phase: " + wireId);
    }
}
