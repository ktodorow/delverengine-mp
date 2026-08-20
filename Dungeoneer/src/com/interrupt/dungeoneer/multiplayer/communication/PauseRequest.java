package com.interrupt.dungeoneer.multiplayer.communication;

import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;

/** Reliable request that asks Host to decide whether shared simulation should pause. */
public final class PauseRequest {
    private final long sequence;
    private final int campaignSlot;
    private final String nickname;

    public PauseRequest(long sequence, int campaignSlot, String nickname) {
        if(sequence <= 0L) throw new IllegalArgumentException("Pause request sequence must be positive.");
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Pause request Campaign Slot must be 1-4.");
        }
        this.sequence = sequence;
        this.campaignSlot = campaignSlot;
        this.nickname = new SlotPresentation(nickname, "humanoid-1").getNickname();
    }

    public long getSequence() { return sequence; }
    public int getCampaignSlot() { return campaignSlot; }
    public String getNickname() { return nickname; }
}
