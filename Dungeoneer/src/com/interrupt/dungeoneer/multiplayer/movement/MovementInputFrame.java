package com.interrupt.dungeoneer.multiplayer.movement;

/** One tick-numbered local movement sample safe to place on the wire. */
public final class MovementInputFrame {
    private static final float MAX_ROTATION_MAGNITUDE = (float)(Math.PI * 4.0);

    private final long inputTick;
    private final float forward;
    private final float strafe;
    private final float rotation;
    private final boolean jump;

    public MovementInputFrame(long inputTick, float forward, float strafe, float rotation,
            boolean jump) {
        if(inputTick <= 0L) throw new IllegalArgumentException("Movement input tick must be positive.");
        requireAxis(forward, "forward");
        requireAxis(strafe, "strafe");
        requireFinite(rotation, "Movement rotation");
        if(Math.abs(rotation) > MAX_ROTATION_MAGNITUDE) {
            throw new IllegalArgumentException("Movement rotation is outside protocol bounds.");
        }
        this.inputTick = inputTick;
        this.forward = forward;
        this.strafe = strafe;
        this.rotation = rotation;
        this.jump = jump;
    }

    public long getInputTick() {
        return inputTick;
    }

    public float getForward() {
        return forward;
    }

    public float getStrafe() {
        return strafe;
    }

    public float getRotation() {
        return rotation;
    }

    public boolean isJump() {
        return jump;
    }

    private static void requireAxis(float value, String label) {
        requireFinite(value, "Movement " + label);
        if(value < -1f || value > 1f) {
            throw new IllegalArgumentException("Movement " + label + " is outside -1..1.");
        }
    }

    private static void requireFinite(float value, String label) {
        if(Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(label + " must be finite.");
        }
    }

    @Override
    public boolean equals(Object other) {
        if(this == other) return true;
        if(!(other instanceof MovementInputFrame)) return false;
        MovementInputFrame that = (MovementInputFrame)other;
        return inputTick == that.inputTick
                && Float.compare(forward, that.forward) == 0
                && Float.compare(strafe, that.strafe) == 0
                && Float.compare(rotation, that.rotation) == 0
                && jump == that.jump;
    }

    @Override
    public int hashCode() {
        int result = (int)(inputTick ^ (inputTick >>> 32));
        result = 31 * result + Float.floatToIntBits(forward);
        result = 31 * result + Float.floatToIntBits(strafe);
        result = 31 * result + Float.floatToIntBits(rotation);
        return 31 * result + (jump ? 1 : 0);
    }
}
