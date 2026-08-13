package com.interrupt.dungeoneer.multiplayer.lobby;

import java.security.SecureRandom;

/** Private installation identity. It is never derived from network or presentation data. */
public final class LauncherIdentity implements Comparable<LauncherIdentity> {
    public static final int RANDOM_BYTES = 32;
    public static final int ENCODED_LENGTH = RANDOM_BYTES * 2;

    private final String value;

    public LauncherIdentity(String value) {
        if(value == null || !value.matches("[0-9a-f]{" + ENCODED_LENGTH + "}")) {
            throw new IllegalArgumentException(
                    "Launcher Identity must be " + ENCODED_LENGTH + " lowercase hexadecimal characters.");
        }
        this.value = value;
    }

    public static LauncherIdentity random(SecureRandom random) {
        if(random == null) throw new IllegalArgumentException("Secure random source cannot be null.");
        return new LauncherIdentity(PrivateToken.randomHex(random));
    }

    public String getValue() {
        return value;
    }

    /** Short, non-authoritative label suitable for Host approval UI. */
    public String getFingerprint() {
        return value.substring(0, 12);
    }

    @Override
    public int compareTo(LauncherIdentity other) {
        if(other == null) throw new NullPointerException("Launcher Identity cannot be compared to null.");
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        if(this == other) return true;
        if(!(other instanceof LauncherIdentity)) return false;
        return value.equals(((LauncherIdentity)other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return getFingerprint();
    }
}
