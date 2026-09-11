package com.interrupt.dungeoneer.multiplayer.items;

/** Native door feedback, localized only on the initiating Participant's instance. */
public enum DoorFeedback {
    STUCK("stuckText"), LOCKED("lockedText"), UNLOCKED("unlockedText"), OPENS_ELSEWHERE("opensElsewhereText");
    public final String localizationKey;
    DoorFeedback(String suffix) { localizationKey = "entities.Door." + suffix; }
}
