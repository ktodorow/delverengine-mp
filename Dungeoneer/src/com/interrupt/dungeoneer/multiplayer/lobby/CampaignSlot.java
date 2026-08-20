package com.interrupt.dungeoneer.multiplayer.lobby;

/** Persistent ownership and presentation for one Campaign Slot. */
public final class CampaignSlot {
    private final int number;
    private final LauncherIdentity launcherIdentity;
    private final String reconnectToken;
    private final SlotPresentation presentation;

    CampaignSlot(int number, LauncherIdentity launcherIdentity, String reconnectToken,
            SlotPresentation presentation) {
        if(number < 1 || number > 4) throw new IllegalArgumentException("Campaign Slot number must be 1-4.");
        if(launcherIdentity == null) throw new IllegalArgumentException("Launcher Identity cannot be null.");
        if(!PrivateToken.isValid(reconnectToken)) {
            throw new IllegalArgumentException("Campaign reconnect token has invalid format.");
        }
        if(presentation == null) throw new IllegalArgumentException("Slot presentation cannot be null.");
        this.number = number;
        this.launcherIdentity = launcherIdentity;
        this.reconnectToken = reconnectToken;
        this.presentation = presentation;
    }

    CampaignSlot withPresentation(SlotPresentation updatedPresentation) {
        return new CampaignSlot(number, launcherIdentity, reconnectToken, updatedPresentation);
    }

    CampaignSlot withOwnership(LauncherIdentity replacementIdentity,
            String replacementReconnectToken, SlotPresentation replacementPresentation) {
        return new CampaignSlot(number, replacementIdentity, replacementReconnectToken,
                replacementPresentation);
    }

    public int getNumber() {
        return number;
    }

    public LauncherIdentity getLauncherIdentity() {
        return launcherIdentity;
    }

    public String getReconnectToken() {
        return reconnectToken;
    }

    public SlotPresentation getPresentation() {
        return presentation;
    }
}
