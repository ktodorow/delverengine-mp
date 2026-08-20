package com.interrupt.dungeoneer.multiplayer.participant;

/** Host-authoritative Party presence or incapacitation state shown to Participants. */
public enum PartyMemberState {
    CONNECTED(1, "CONNECTED"),
    DOWNED(2, "DOWNED"),
    SPECTATING(3, "SPECTATING"),
    DISCONNECTED(4, "DISCONNECTED"),
    RECONNECTING(5, "RECONNECTING");

    private final int wireId;
    private final String displayName;

    PartyMemberState(int wireId, String displayName) {
        this.wireId = wireId;
        this.displayName = displayName;
    }

    public int getWireId() {
        return wireId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isIncapacitated() {
        return this == DOWNED || this == SPECTATING || this == RECONNECTING;
    }

    public static PartyMemberState fromWireId(int wireId) {
        for(PartyMemberState state : values()) {
            if(state.wireId == wireId) return state;
        }
        throw new IllegalArgumentException("Unknown Party member state: " + wireId);
    }
}
