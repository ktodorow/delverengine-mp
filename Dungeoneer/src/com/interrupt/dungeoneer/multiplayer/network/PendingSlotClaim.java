package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotClaimRequest;

/** Immutable Host approval view. Private reconnect credentials are intentionally absent. */
public final class PendingSlotClaim {
    private final SlotClaimRequest request;

    PendingSlotClaim(SlotClaimRequest request) {
        this.request = request;
    }

    SlotClaimRequest getRequest() {
        return request;
    }

    public LauncherIdentity getLauncherIdentity() {
        return request.getLauncherIdentity();
    }

    public String getNickname() {
        return request.getPresentation().getNickname();
    }

    public String getAvatarId() {
        return request.getPresentation().getAvatarId();
    }

    public int getRequestedSlot() {
        return request.getRequestedSlot();
    }
}
