package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;

/** Animated visual-only remote Participant. Never substitutes another original Player. */
public final class RemoteAvatar extends Entity {
    private static final String AVATAR_ATLAS = "tech_sprites";
    private static final float BASE_SCALE = 1.3f;

    private final MovementEntityDescriptor descriptor;
    private MovementState movementState = MovementState.IDLE;
    private float animationTime;

    public RemoteAvatar(MovementEntityDescriptor descriptor) {
        super(0f, 0f, 0, true);
        if(descriptor == null) throw new IllegalArgumentException("Remote Avatar descriptor cannot be null.");
        this.descriptor = descriptor;
        isSolid = false;
        persists = false;
        canStepUpOn = false;
        spriteAtlas = AVATAR_ATLAS;
        tex = avatarTexture(descriptor.getAvatarId());
        fullbrite = true;
        scale = BASE_SCALE;
        collision.set(0.2f, 0.2f, 0.65f);
        shadowType = ShadowType.BLOB;
        color = avatarColor(descriptor.getAvatarId());

    }

    public MovementEntityDescriptor getDescriptor() {
        return descriptor;
    }

    public void applyNetworkState(float x, float y, float z, float velocityX,
            float velocityY, float velocityZ, MovementState state) {
        setPosition(x, y, z);
        xa = velocityX;
        ya = velocityY;
        za = velocityZ;
        movementState = state == null ? MovementState.IDLE : state;
    }

    public MovementState getMovementState() {
        return movementState;
    }

    @Override
    public void tick(Level level, float delta) {
        animationTime += delta;
        if(movementState == MovementState.MOVING) {
            yOffset = 0.05f * Math.abs((float)Math.sin(animationTime * 0.35f));
            scale = BASE_SCALE + 0.04f * Math.abs((float)Math.sin(animationTime * 0.2f));
        }
        else if(movementState == MovementState.AIRBORNE) {
            yOffset = 0.08f;
            scale = BASE_SCALE;
        }
        else if(movementState == MovementState.BLOCKED) {
            yOffset = 0f;
            scale = BASE_SCALE - 0.05f;
        }
        else {
            yOffset = 0f;
            scale = BASE_SCALE;
        }
        tickAttached(level, delta);
    }

    private static int avatarTexture(String avatarId) {
        if(AvatarCatalog.HUMANOID_2.equals(avatarId)) return 1;
        if(AvatarCatalog.HUMANOID_3.equals(avatarId)) return 3;
        if(AvatarCatalog.HUMANOID_4.equals(avatarId)) return 2;
        return 0;
    }

    private static Color avatarColor(String avatarId) {
        if(AvatarCatalog.HUMANOID_2.equals(avatarId)) return new Color(0.6f, 0.85f, 1f, 1f);
        if(AvatarCatalog.HUMANOID_3.equals(avatarId)) return new Color(0.65f, 1f, 0.65f, 1f);
        if(AvatarCatalog.HUMANOID_4.equals(avatarId)) return new Color(1f, 0.7f, 0.85f, 1f);
        return new Color(1f, 0.85f, 0.55f, 1f);
    }
}
