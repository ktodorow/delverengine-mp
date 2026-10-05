package com.interrupt.dungeoneer.multiplayer.participant;

/** Explicit initiating Participant and state available to one authoritative action chain. */
public final class ParticipantContext {
    private final ParticipantId participantId;
    private final ParticipantCharacter character;
    private final PartyProgression partyProgression;
    private final boolean presentedByActivator;

    public ParticipantContext(ParticipantId participantId, ParticipantCharacter character,
            PartyProgression partyProgression) {
        this(participantId, character, partyProgression, false);
    }

    /**
     * @param presentedByActivator the activator's own peer runs this chain too (a client
     *     walking into a touch trigger fires its local copy), so screen-only effects such as
     *     messages already show there and must not also show on Host.
     */
    public ParticipantContext(ParticipantId participantId, ParticipantCharacter character,
            PartyProgression partyProgression, boolean presentedByActivator) {
        if(participantId == null) throw new IllegalArgumentException("Participant ID is required.");
        if(character == null) throw new IllegalArgumentException("Participant character is required.");
        if(partyProgression == null) throw new IllegalArgumentException("Party progression is required.");
        this.participantId = participantId;
        this.character = character;
        this.partyProgression = partyProgression;
        this.presentedByActivator = presentedByActivator;
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

    public boolean isPresentedByActivator() {
        return presentedByActivator;
    }
}
