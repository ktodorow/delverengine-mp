package com.interrupt.dungeoneer.multiplayer.lobby;

import java.nio.charset.StandardCharsets;

/** Correlated Host decision; carries current presentation, never ownership credentials. */
public final class PresentationEditResult {
    private final long requestId;
    private final boolean accepted;
    private final SlotPresentation presentation;
    private final String reason;

    public PresentationEditResult(long requestId, boolean accepted, SlotPresentation presentation, String reason) {
        if(requestId < 1L || presentation == null || reason == null || reason.isEmpty()
                || reason.getBytes(StandardCharsets.UTF_8).length > 256)
            throw new IllegalArgumentException("Invalid presentation edit result.");
        this.requestId = requestId; this.accepted = accepted;
        this.presentation = presentation; this.reason = reason;
    }
    public long getRequestId() { return requestId; }
    public boolean isAccepted() { return accepted; }
    public SlotPresentation getPresentation() { return presentation; }
    public String getReason() { return reason; }
}
