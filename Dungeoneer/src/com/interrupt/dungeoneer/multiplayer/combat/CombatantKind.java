package com.interrupt.dungeoneer.multiplayer.combat;

public enum CombatantKind {
    PARTICIPANT(1),
    MONSTER(2);

    private final int wireId;

    CombatantKind(int wireId) {
        this.wireId = wireId;
    }

    public int getWireId() { return wireId; }

    public static CombatantKind fromWireId(int wireId) {
        for(CombatantKind kind : values()) {
            if(kind.wireId == wireId) return kind;
        }
        throw new IllegalArgumentException("Unknown combatant kind wire ID: " + wireId);
    }
}
