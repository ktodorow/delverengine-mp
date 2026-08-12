package com.interrupt.dungeoneer.multiplayer.participant;

/** Stable session identity used to address one Participant without relying on presentation data. */
public final class ParticipantId implements Comparable<ParticipantId> {
    private final String value;

    public ParticipantId(String value) {
        if(value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Participant ID must be 1-64 letters, numbers, dots, underscores, or hyphens.");
        }
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public int compareTo(ParticipantId other) {
        if(other == null) throw new NullPointerException("Participant ID cannot be compared to null.");
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        if(this == other) return true;
        if(!(other instanceof ParticipantId)) return false;
        ParticipantId that = (ParticipantId)other;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
