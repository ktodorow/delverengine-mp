package com.interrupt.dungeoneer.ui;

/** Presentation rule for Party nameplates in world space. */
public final class PartyNameplateVisibility {
    public static final float MAX_DISTANCE = 12f;

    private PartyNameplateVisibility() { }

    public static boolean isWithinRange(float distance) {
        return distance <= MAX_DISTANCE;
    }
}
