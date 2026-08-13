package com.interrupt.dungeoneer.multiplayer.lobby;

/** Bounded Campaign Slot claim received after compatibility handshake. */
public final class SlotClaimRequest {
    private final LauncherIdentity launcherIdentity;
    private final SlotPresentation presentation;
    private final int requestedSlot;
    private final String reconnectToken;

    public SlotClaimRequest(LauncherIdentity launcherIdentity, SlotPresentation presentation,
            int requestedSlot, String reconnectToken) {
        if(launcherIdentity == null) throw new IllegalArgumentException("Launcher Identity cannot be null.");
        if(presentation == null) throw new IllegalArgumentException("Slot presentation cannot be null.");
        if(requestedSlot < 0 || requestedSlot > 4) {
            throw new IllegalArgumentException("Requested Campaign Slot must be zero (any) or 1-4.");
        }
        if(reconnectToken != null && !reconnectToken.isEmpty() && !PrivateToken.isValid(reconnectToken)) {
            throw new IllegalArgumentException("Reconnect token has invalid format.");
        }
        this.launcherIdentity = launcherIdentity;
        this.presentation = presentation;
        this.requestedSlot = requestedSlot;
        this.reconnectToken = reconnectToken == null || reconnectToken.isEmpty()
                ? null : reconnectToken;
    }

    public LauncherIdentity getLauncherIdentity() {
        return launcherIdentity;
    }

    public SlotPresentation getPresentation() {
        return presentation;
    }

    public int getRequestedSlot() {
        return requestedSlot;
    }

    public String getReconnectToken() {
        return reconnectToken;
    }
}
