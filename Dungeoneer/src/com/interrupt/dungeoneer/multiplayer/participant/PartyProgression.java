package com.interrupt.dungeoneer.multiplayer.participant;

/** Shared Party-owned progression exposed to authoritative trigger actions. */
public interface PartyProgression {
    String getPersistent(String key);

    String getUntilDeath(String key);

    void putPersistent(String key, String value);

    void putUntilDeath(String key, String value);
}
