package com.interrupt.dungeoneer.multiplayer.lives;

/** One frame of a Spectator's borrowed viewpoint; renderer replaces the local camera with it. */
public final class SpectatorCamera {
    public static final float EYE_HEIGHT = 0.12f;
    public static final float THIRD_PERSON_DISTANCE = 1.6f;
    public static final float THIRD_PERSON_RISE = 0.45f;

    public final float x;
    public final float y;
    public final float z;
    /** Native yaw, same convention as Player.rot. */
    public final float yaw;
    /** Native pitch, same convention as Player.yrot. */
    public final float pitch;
    public final boolean thirdPerson;
    public final String watchedNickname;

    SpectatorCamera(float x, float y, float z, float yaw, float pitch,
            boolean thirdPerson, String watchedNickname) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.thirdPerson = thirdPerson;
        this.watchedNickname = watchedNickname;
    }

    /** Eye position of the watched body, or a point behind and above it for third person. */
    static SpectatorCamera at(float bodyX, float bodyY, float bodyZ, float yaw, float lookY,
            boolean thirdPerson, String nickname) {
        float pitch = (float)Math.asin(Math.max(-1f, Math.min(1f, lookY)));
        float eyeZ = bodyZ + EYE_HEIGHT;
        if(!thirdPerson) return new SpectatorCamera(bodyX, bodyY, eyeZ, yaw, pitch, false, nickname);
        // Native forward on the floor plane is (sin(rot), cos(rot)); step back along it.
        float backX = bodyX - (float)Math.sin(yaw) * THIRD_PERSON_DISTANCE;
        float backY = bodyY - (float)Math.cos(yaw) * THIRD_PERSON_DISTANCE;
        float downPitch = (float)Math.atan2(-THIRD_PERSON_RISE, THIRD_PERSON_DISTANCE) * 0.6f;
        return new SpectatorCamera(backX, backY, eyeZ + THIRD_PERSON_RISE, yaw,
                downPitch, true, nickname);
    }
}
