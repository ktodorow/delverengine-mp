package com.interrupt.dungeoneer.multiplayer.movement;

/** Host-owned movement presentation state. */
public enum MovementState {
    IDLE(1),
    MOVING(2),
    AIRBORNE(3),
    BLOCKED(4);

    private final int wireId;

    MovementState(int wireId) {
        this.wireId = wireId;
    }

    public int getWireId() {
        return wireId;
    }

    public static MovementState fromWireId(int wireId) {
        for(MovementState state : values()) {
            if(state.wireId == wireId) return state;
        }
        throw new IllegalArgumentException("Unknown movement state: " + wireId);
    }
}
