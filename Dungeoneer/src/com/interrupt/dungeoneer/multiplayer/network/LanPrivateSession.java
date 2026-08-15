package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;

import java.net.Inet6Address;
import java.net.InetAddress;

/** Compatible Private Session announced by one Host on the local network. */
public final class LanPrivateSession {
    private final InetAddress address;
    private final int port;
    private final String sessionId;
    private final String campaignId;
    private final int capacity;
    private final int claimedSlots;
    private final boolean lobbyOpen;

    LanPrivateSession(InetAddress address, int port, String sessionId, String campaignId,
            int capacity, int claimedSlots, boolean lobbyOpen) {
        if(address == null) throw new IllegalArgumentException("LAN Host address cannot be null.");
        if(port < 1 || port > 65535) throw new IllegalArgumentException("LAN Host port is invalid.");
        if(sessionId == null || !sessionId.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException(
                    "Private Session identity must be 16 lowercase hexadecimal characters.");
        }
        String validatedCampaignId = CampaignRoster.requireCampaignId(campaignId);
        if(capacity < 2 || capacity > 4 || claimedSlots < 1 || claimedSlots > capacity) {
            throw new IllegalArgumentException("Campaign Slot counts are invalid.");
        }
        this.address = address;
        this.port = port;
        this.sessionId = sessionId;
        this.campaignId = validatedCampaignId;
        this.capacity = capacity;
        this.claimedSlots = claimedSlots;
        this.lobbyOpen = lobbyOpen;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getClaimedSlots() {
        return claimedSlots;
    }

    public boolean isLobbyOpen() {
        return lobbyOpen;
    }

    public String getEndpoint() {
        String host = address.getHostAddress();
        if(address instanceof Inet6Address) host = "[" + host + "]";
        return host + ":" + port;
    }
}
