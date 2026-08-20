package com.interrupt.dungeoneer.multiplayer.communication;

import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;

/** Host-sequenced reliable Party chat delivery. */
public final class PartyChatMessage {
    private final long sequence;
    private final int campaignSlot;
    private final String nickname;
    private final String text;

    public PartyChatMessage(long sequence, int campaignSlot, String nickname, String text) {
        if(sequence <= 0L) throw new IllegalArgumentException("Party chat sequence must be positive.");
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Party chat Campaign Slot must be 1-4.");
        }
        this.sequence = sequence;
        this.campaignSlot = campaignSlot;
        this.nickname = new SlotPresentation(nickname, "humanoid-1").getNickname();
        this.text = PartyCommunicationText.requireChat(text);
    }

    public long getSequence() { return sequence; }
    public int getCampaignSlot() { return campaignSlot; }
    public String getNickname() { return nickname; }
    public String getText() { return text; }
}
