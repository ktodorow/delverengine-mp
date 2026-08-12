package com.interrupt.dungeoneer.multiplayer.participant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory authoritative Party progression shared by every Participant context in a session. */
public final class SharedPartyProgression implements PartyProgression {
    private final Map<String, String> persistent = new LinkedHashMap<String, String>();
    private final Map<String, String> untilDeath = new LinkedHashMap<String, String>();
    private long mutationCount;

    @Override
    public String getPersistent(String key) {
        return persistent.get(key);
    }

    @Override
    public String getUntilDeath(String key) {
        return untilDeath.get(key);
    }

    @Override
    public void putPersistent(String key, String value) {
        put(persistent, key, value);
    }

    @Override
    public void putUntilDeath(String key, String value) {
        put(untilDeath, key, value);
    }

    public long getMutationCount() {
        return mutationCount;
    }

    public Map<String, String> snapshotPersistent() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(persistent));
    }

    public Map<String, String> snapshotUntilDeath() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(untilDeath));
    }

    private void put(Map<String, String> storage, String key, String value) {
        if(key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Party progression key is required.");
        }
        if(value == null) throw new IllegalArgumentException("Party progression value is required.");
        storage.put(key, value);
        mutationCount++;
    }
}
