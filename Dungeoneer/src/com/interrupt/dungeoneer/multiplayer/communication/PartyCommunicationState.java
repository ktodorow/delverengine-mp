package com.interrupt.dungeoneer.multiplayer.communication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable client-visible Party communications, bounded to recent reliable events. */
public final class PartyCommunicationState {
    private static final int MAX_CHAT_HISTORY = 32;
    private final List<PartyChatMessage> chatHistory;
    private final PauseRequest latestPauseRequest;
    private final PauseSessionState pauseSession;

    private PartyCommunicationState(List<PartyChatMessage> chatHistory,
            PauseRequest latestPauseRequest, PauseSessionState pauseSession) {
        this.chatHistory = Collections.unmodifiableList(chatHistory);
        this.latestPauseRequest = latestPauseRequest;
        this.pauseSession = pauseSession;
    }

    public static PartyCommunicationState initial() {
        return new PartyCommunicationState(Collections.<PartyChatMessage>emptyList(),
                null, new PauseSessionState(0L, false));
    }

    public PartyCommunicationState withChat(PartyChatMessage message) {
        if(message == null) throw new IllegalArgumentException("Party chat cannot be null.");
        if(!isNewer(message.getSequence(), lastChatSequence())) return this;
        List<PartyChatMessage> copy = new ArrayList<PartyChatMessage>(chatHistory);
        copy.add(message);
        trim(copy, MAX_CHAT_HISTORY);
        return new PartyCommunicationState(copy, latestPauseRequest, pauseSession);
    }

    public PartyCommunicationState withPauseRequest(PauseRequest request) {
        if(request == null) throw new IllegalArgumentException("Pause request cannot be null.");
        if(latestPauseRequest != null
                && !isNewer(request.getSequence(), latestPauseRequest.getSequence())) return this;
        return new PartyCommunicationState(chatHistory, request, pauseSession);
    }

    public PartyCommunicationState withPauseSession(PauseSessionState state) {
        if(state == null) throw new IllegalArgumentException("Pause Session state cannot be null.");
        if(!isNewer(state.getSequence(), pauseSession.getSequence())) return this;
        return new PartyCommunicationState(chatHistory, latestPauseRequest, state);
    }

    public List<PartyChatMessage> getChatHistory() { return chatHistory; }
    public PauseRequest getLatestPauseRequest() { return latestPauseRequest; }
    public PauseSessionState getPauseSession() { return pauseSession; }

    private long lastChatSequence() {
        return chatHistory.isEmpty() ? 0L : chatHistory.get(chatHistory.size() - 1).getSequence();
    }

    private static boolean isNewer(long candidate, long current) {
        return candidate > current;
    }

    private static <T> void trim(List<T> values, int maximum) {
        while(values.size() > maximum) values.remove(0);
    }
}
