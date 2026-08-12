package com.interrupt.dungeoneer.multiplayer.host;

/** Authoritative Campaign Save boundary. */
public interface HostSessionStorage {
    void persist(long hostTick, HostPersistedState state);
}
