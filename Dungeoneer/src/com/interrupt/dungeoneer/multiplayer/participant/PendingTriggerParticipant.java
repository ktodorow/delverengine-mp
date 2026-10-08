package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.game.Game;

/** Native delayed-action attribution without serializing a Player or old Party progression. */
public final class PendingTriggerParticipant {
    private String id;
    private float x, y, z, rotation;
    private boolean holdingOrb, presented;

    public static PendingTriggerParticipant capture(ParticipantContext participant) {
        if(participant == null) return null;
        PendingTriggerParticipant saved = new PendingTriggerParticipant();
        saved.id = participant.getParticipantId().getValue();
        ParticipantCharacter character = participant.getCharacter();
        saved.x = character.getX(); saved.y = character.getY(); saved.z = character.getZ();
        saved.rotation = character.getRotation(); saved.holdingOrb = character.isHoldingOrb();
        saved.presented = participant.isPresentedByActivator();
        return saved;
    }

    public ParticipantContext restore() {
        if(Game.instance == null || Game.instance.player == null || Game.instance.progression == null) return null;
        ParticipantContext local = LocalPlayerCompatibilityAdapter.fromGame();
        if(LocalPlayerCompatibilityAdapter.LOCAL_PARTICIPANT_ID.getValue().equals(id)) return local;
        // Remote triggers already capture activation position; retain that detached native context.
        ParticipantCharacterState character = new ParticipantCharacterState(x, y, z, rotation);
        character.setHoldingOrb(holdingOrb);
        return new ParticipantContext(new ParticipantId(id), character, local.getPartyProgression(), presented);
    }
}
