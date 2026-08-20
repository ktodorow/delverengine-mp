package com.interrupt.dungeoneer.multiplayer.communication;

import java.nio.charset.StandardCharsets;

/** Validates bounded, single-line Party text before it crosses a wire boundary. */
public final class PartyCommunicationText {
    public static final int MAX_CHAT_BYTES = 240;

    private PartyCommunicationText() { }

    public static String requireChat(String value) {
        if(value == null) throw new IllegalArgumentException("Party chat cannot be null.");
        String normalized = value.trim();
        if(normalized.isEmpty()) throw new IllegalArgumentException("Party chat cannot be empty.");
        if(normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
            throw new IllegalArgumentException("Party chat exceeds " + MAX_CHAT_BYTES + " bytes.");
        }
        for(int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if(Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("Party chat cannot contain control characters.");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }
}
