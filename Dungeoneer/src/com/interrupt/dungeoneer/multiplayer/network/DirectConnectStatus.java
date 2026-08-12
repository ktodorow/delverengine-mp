package com.interrupt.dungeoneer.multiplayer.network;

/** Immutable Participant-visible status safe to poll from the render thread. */
public final class DirectConnectStatus {
    private final DirectConnectPhase phase;
    private final String message;
    private final String sessionId;
    private final String remoteParticipantId;
    private final String floorId;

    DirectConnectStatus(DirectConnectPhase phase, String message, String sessionId,
            String remoteParticipantId, String floorId) {
        if(phase == null) throw new IllegalArgumentException("Direct Connect phase cannot be null.");
        this.phase = phase;
        this.message = message == null ? "" : message;
        this.sessionId = sessionId;
        this.remoteParticipantId = remoteParticipantId;
        this.floorId = floorId;
    }

    public DirectConnectPhase getPhase() {
        return phase;
    }

    public String getMessage() {
        return message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRemoteParticipantId() {
        return remoteParticipantId;
    }

    public String getFloorId() {
        return floorId;
    }
}
