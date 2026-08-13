package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.multiplayer.host.HostSessionCommand;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Host command adapter for one authenticated movement input. */
public final class MovementInputCommand implements HostSessionCommand {
    private final ParticipantId participantId;
    private final MovementInputFrame input;

    public MovementInputCommand(ParticipantId participantId, MovementInputFrame input) {
        if(participantId == null) throw new IllegalArgumentException("Participant ID cannot be null.");
        if(input == null) throw new IllegalArgumentException("Movement input cannot be null.");
        this.participantId = participantId;
        this.input = input;
    }

    @Override
    public ParticipantId getParticipantId() {
        return participantId;
    }

    public MovementInputFrame getInput() {
        return input;
    }
}
