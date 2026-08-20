package com.interrupt.dungeoneer.multiplayer.communication;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PartyCommunicationStateTest {
    @Test
    public void preservesOrderedBoundedRecentChat() {
        PartyCommunicationState state = PartyCommunicationState.initial();
        for(int sequence = 1; sequence <= 34; sequence++) {
            state = state.withChat(new PartyChatMessage(sequence, 2, "Friend", "message " + sequence));
        }
        assertEquals(32, state.getChatHistory().size());
        assertEquals(3L, state.getChatHistory().get(0).getSequence());
    }

    @Test
    public void ignoresStaleReliableDeliveriesAndTracksHostPauseState() {
        PartyCommunicationState state = PartyCommunicationState.initial();
        PartyChatMessage chat = new PartyChatMessage(2L, 1, "Host", "Regroup.");
        state = state.withChat(chat);
        assertSame(state, state.withChat(new PartyChatMessage(1L, 2, "Friend", "Old")));

        state = state.withPauseRequest(new PauseRequest(1L, 2, "Friend"));
        state = state.withPauseSession(new PauseSessionState(1L, true));
        assertTrue(state.getPauseSession().isPaused());
        assertEquals("Friend", state.getLatestPauseRequest().getNickname());
        assertSame(state, state.withPauseSession(new PauseSessionState(1L, false)));
        state = state.withPauseSession(new PauseSessionState(2L, false));
        assertFalse(state.getPauseSession().isPaused());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsChatWithControlCharacters() {
        new PartyChatMessage(1L, 1, "Host", "line one\nline two");
    }

}
