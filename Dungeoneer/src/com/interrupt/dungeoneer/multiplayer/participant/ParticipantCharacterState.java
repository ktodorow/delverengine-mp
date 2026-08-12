package com.interrupt.dungeoneer.multiplayer.participant;

/** Plain authoritative character state. This is deliberately not an original global Player. */
public final class ParticipantCharacterState implements ParticipantCharacter {
    private float x;
    private float y;
    private float z;
    private float rotation;
    private boolean holdingOrb;

    public ParticipantCharacterState(float x, float y, float z, float rotation) {
        setPosition(x, y, z);
        setRotation(rotation);
    }

    @Override
    public float getX() {
        return x;
    }

    @Override
    public float getY() {
        return y;
    }

    @Override
    public float getZ() {
        return z;
    }

    @Override
    public float getRotation() {
        return rotation;
    }

    @Override
    public boolean isHoldingOrb() {
        return holdingOrb;
    }

    @Override
    public void setPosition(float x, float y, float z) {
        requireFinite(x, "Participant x position");
        requireFinite(y, "Participant y position");
        requireFinite(z, "Participant z position");
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void setRotation(float rotation) {
        requireFinite(rotation, "Participant rotation");
        this.rotation = rotation;
    }

    public void setHoldingOrb(boolean holdingOrb) {
        this.holdingOrb = holdingOrb;
    }

    private static void requireFinite(float value, String name) {
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }
}
