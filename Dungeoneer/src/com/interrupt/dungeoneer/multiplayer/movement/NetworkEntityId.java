package com.interrupt.dungeoneer.multiplayer.movement;

/** Stable session identifier for one replicated entity. */
public final class NetworkEntityId implements Comparable<NetworkEntityId> {
    private final long value;

    public NetworkEntityId(long value) {
        if(value <= 0L) throw new IllegalArgumentException("Network Entity ID must be positive.");
        this.value = value;
    }

    public long getValue() {
        return value;
    }

    @Override
    public int compareTo(NetworkEntityId other) {
        if(other == null) throw new NullPointerException("Network Entity ID cannot be compared to null.");
        return value < other.value ? -1 : value == other.value ? 0 : 1;
    }

    @Override
    public boolean equals(Object other) {
        if(this == other) return true;
        if(!(other instanceof NetworkEntityId)) return false;
        return value == ((NetworkEntityId)other).value;
    }

    @Override
    public int hashCode() {
        return (int)(value ^ (value >>> 32));
    }

    @Override
    public String toString() {
        return "net-" + value;
    }
}
