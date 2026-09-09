package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.items.Weapon.DamageType;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;

/** Animated visual-only remote Participant. Never substitutes another original Player. */
public final class RemoteAvatar extends Actor {
    public interface DamageAuthorityListener {
        void onDamageIntent(RemoteAvatar avatar, int damage, DamageType damageType,
                Entity instigator);
    }

    private static final String AVATAR_ATLAS = "tech_sprites";
    private static final float BASE_SCALE = 1.3f;

    private final MovementEntityDescriptor descriptor;
    private final Color baseColor;
    private MovementState movementState = MovementState.IDLE;
    private float animationTime;
    private float attackAnimationTime;
    private float damageFlashTime;
    private CombatAction presentedAction = CombatAction.MELEE;
    private transient DamageAuthorityListener damageAuthorityListener;

    public RemoteAvatar(MovementEntityDescriptor descriptor) {
        super(0f, 0f, 0);
        if(descriptor == null) throw new IllegalArgumentException("Remote Avatar descriptor cannot be null.");
        this.descriptor = descriptor;
        isSolid = false;
        ignorePlayerCollision = true;
        persists = false;
        canStepUpOn = false;
        spriteAtlas = AVATAR_ATLAS;
        tex = avatarTexture(descriptor.getAvatarId());
        fullbrite = true;
        scale = BASE_SCALE;
        collision.set(0.2f, 0.2f, 0.65f);
        shadowType = ShadowType.BLOB;
        baseColor = avatarColor(descriptor.getAvatarId());
        color = new Color(baseColor);
        maxHp = 8;
        hp = maxHp;
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

    public void playCombatAction(CombatAction action) {
        presentedAction = action == null ? CombatAction.MELEE : action;
        attackAnimationTime = 10f;
    }

    public void playDamageReaction() {
        damageFlashTime = 8f;
    }

    public void setDamageAuthorityListener(DamageAuthorityListener listener) {
        damageAuthorityListener = listener;
    }

    public void clearDamageAuthorityListener(DamageAuthorityListener listener) {
        if(damageAuthorityListener == listener) damageAuthorityListener = null;
    }

    public void applyAuthoritativeHealth(int health, int maximumHealth) {
        maxHp = Math.max(1, maximumHealth);
        hp = Math.max(0, Math.min(health, maxHp));
    }

    @Override
    public int takeDamage(int damage, DamageType damageType, Entity instigator) {
        if(damageAuthorityListener != null && damage > 0) {
            damageAuthorityListener.onDamageIntent(this, damage, damageType, instigator);
        }
        return Math.max(0, damage);
    }

    @Override
    public void hit(float projx, float projy, int damage, float knockback,
            DamageType damageType, Entity instigator) {
        takeDamage(damage, damageType, instigator);
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
        roll = 0f;
        if(attackAnimationTime > 0f) {
            attackAnimationTime = Math.max(0f, attackAnimationTime - delta);
            float pulse = Math.abs((float)Math.sin(attackAnimationTime * 0.45f));
            scale += 0.18f * pulse;
            roll = presentedAction == CombatAction.MELEE ? 0.12f * pulse : -0.08f * pulse;
        }
        if(damageFlashTime > 0f) {
            damageFlashTime = Math.max(0f, damageFlashTime - delta);
            color.set(1f, 0.25f, 0.2f, 1f);
        }
        else {
            color.set(baseColor);
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
