package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class PartyStatusSnapshotTest {
    @Test
    public void sortsPersistentSlotsAndKeepsDisconnectedPresentation() {
        PartyMemberStatus disconnected = member(3, null, "Away",
                PartyMemberState.DISCONNECTED);
        PartyMemberStatus connected = member(1, new NetworkEntityId(1L), "Host",
                PartyMemberState.CONNECTED);

        PartyStatusSnapshot snapshot = new PartyStatusSnapshot(4L,
                Arrays.asList(disconnected, connected));

        assertEquals(1, snapshot.getMembers().get(0).getCampaignSlot());
        assertEquals("Away", snapshot.getMember(3).getNickname());
        assertNull(snapshot.getMember(3).getEntityId());
        assertEquals(PartyMemberState.DISCONNECTED,
                snapshot.getMember(3).getState());
    }

    @Test
    public void rejectsDuplicateNicknameEvenWhenCaseDiffers() {
        try {
            new PartyStatusSnapshot(1L, Arrays.asList(
                    member(1, new NetworkEntityId(1L), "Friend",
                            PartyMemberState.CONNECTED),
                    member(2, new NetworkEntityId(2L), "friend",
                            PartyMemberState.CONNECTED)));
            fail("Duplicate Party Nickname was accepted.");
        }
        catch(IllegalArgumentException expected) {
            assertEquals("Party status contains duplicate Nickname.", expected.getMessage());
        }
    }

    private PartyMemberStatus member(int slot, NetworkEntityId entityId,
            String nickname, PartyMemberState state) {
        return new PartyMemberStatus(slot, entityId, nickname, "humanoid-" + slot,
                8, 8, 3, state);
    }
}
