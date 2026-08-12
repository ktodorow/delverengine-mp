package com.interrupt.dungeoneer.multiplayer.participant;

/** Explicit initiating Participant and state available to one authoritative action chain. */
public final class ParticipantContext {
    private final ParticipantId participantId;
    private final ParticipantCharacter character;
    private final PartyProgression partyProgression;

    public ParticipantContext(ParticipantId participantId, ParticipantCharacter character,
            PartyProgression partyProgression) {
        if(participantId == null) throw new IllegalArgumentException("Participant ID is required.");
        if(character == null) throw new IllegalArgumentException("Participant character is required.");
        if(partyProgression == null) throw new IllegalArgumentException("Party progression is required.");
        this.participantId = participantId;
        this.character = character;
        this.partyProgression = partyProgression;
    }

    public ParticipantId getParticipantId() {
        return participantId;
    }

    public ParticipantCharacter getCharacter() {
        return character;
    }

    public PartyProgression getPartyProgression() {
        return partyProgression;
    }
}
