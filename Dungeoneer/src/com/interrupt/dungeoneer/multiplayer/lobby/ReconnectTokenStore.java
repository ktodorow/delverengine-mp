package com.interrupt.dungeoneer.multiplayer.lobby;

/** Participant-local storage boundary for private per-campaign reconnect credentials. */
public interface ReconnectTokenStore {
    String load(String campaignId);

    void save(String campaignId, String reconnectToken);
}
