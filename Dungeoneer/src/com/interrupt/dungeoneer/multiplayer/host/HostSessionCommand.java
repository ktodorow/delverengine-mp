package com.interrupt.dungeoneer.multiplayer.host;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/**
 * Command accepted by an authoritative Host session after local input or a wire message has
 * crossed its adapter boundary. Commands must contain only explicit session data, never live
 * engine object graphs.
 */
public interface HostSessionCommand {
    ParticipantId getParticipantId();
}
