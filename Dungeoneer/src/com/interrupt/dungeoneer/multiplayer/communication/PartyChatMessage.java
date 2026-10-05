package com.interrupt.dungeoneer.multiplayer.communication;

import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;

/** Host-sequenced reliable Party chat delivery. */
public final class PartyChatMessage {
    private final long sequence;
    private final int campaignSlot;
    private final String nickname;
    private final String text;
    private final boolean system;

    public PartyChatMessage(long sequence, int campaignSlot, String nickname, String text) {
        this(sequence, campaignSlot, nickname, text, false);
    }

    private PartyChatMessage(long sequence, int campaignSlot, String nickname, String text, boolean system) {
        if(sequence <= 0L) throw new IllegalArgumentException("Party chat sequence must be positive.");
        if(campaignSlot < 1 || campaignSlot > 4) {
            throw new IllegalArgumentException("Party chat Campaign Slot must be 1-4.");
        }
        this.sequence = sequence;
        this.campaignSlot = campaignSlot;
        this.nickname = new SlotPresentation(nickname, "humanoid-1").getNickname();
        this.text = PartyCommunicationText.requireChat(text);
        this.system = system;
    }

    public static PartyChatMessage system(long sequence, int campaignSlot, String nickname, String text) {
        return new PartyChatMessage(sequence, campaignSlot, nickname, text, true);
    }

    public long getSequence() { return sequence; }
    public int getCampaignSlot() { return campaignSlot; }
    public String getNickname() { return nickname; }
    public String getText() { return text; }
    public boolean isSystem() { return system; }
    public String getDisplayText() { return system ? text : nickname + ": " + text; }
}
