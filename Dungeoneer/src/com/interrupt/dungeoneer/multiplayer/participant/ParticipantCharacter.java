package com.interrupt.dungeoneer.multiplayer.participant;

/** Character state needed by Participant-scoped authoritative actions. */
public interface ParticipantCharacter {
    float getX();

    float getY();

    float getZ();

    float getRotation();

    boolean isHoldingOrb();

    void setPosition(float x, float y, float z);

    void setRotation(float rotation);
}
