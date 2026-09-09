package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.graphics.Color;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.game.Level;

/** Short-lived cosmetic replay. Never participates in collision or damage. */
public final class CombatPresentationEffect extends Entity {
    private static final String EFFECT_ATLAS = "tech_sprites";
    private static final float IMPACT_LIFETIME_TICKS = 20f;

    private final float originX;
    private final float originY;
    private final float originZ;
    private final float impactX;
    private final float impactY;
    private final float impactZ;
    private final float travelTicks;
    private final float baseScale;
    private float age;

    public CombatPresentationEffect(CombatPresentationEvent event) {
        super(event == null ? 0f : event.getOriginX(),
                event == null ? 0f : event.getOriginY(), texture(event), true);
        if(event == null) {
            throw new IllegalArgumentException("Combat presentation event cannot be null.");
        }
        originX = event.getOriginX();
        originY = event.getOriginY();
        originZ = event.getOriginZ();
        impactX = event.getImpactX();
        impactY = event.getImpactY();
        impactZ = event.getImpactZ();
        travelTicks = travelTicks(event);
        baseScale = event.getProjectileVisual() != null ? event.getProjectileVisual().scale
                : event.getPhase() == CombatPresentationPhase.IMPACT ? 0.5f
                : event.getAction() == CombatAction.MELEE ? 0.42f : 0.28f;

        z = travelTicks > 0f ? originZ : impactZ;
        if(travelTicks <= 0f) setPosition(impactX, impactY, impactZ);
        isSolid = false;
        persists = false;
        canStepUpOn = false;
        floating = true;
        bounces = false;
        collision.set(0f, 0f, 0f);
        spriteAtlas = EFFECT_ATLAS;
        fullbrite = true;
        blendMode = BlendMode.ADD;
        scale = baseScale;
        color = color(event.getAction(), event.isStateChanged());
        ProjectileVisual visual = event.getProjectileVisual();
        if(visual != null) {
            spriteAtlas = visual.atlas;
            tex = visual.texture;
            fullbrite = visual.fullbrite;
            blendMode = visual.additive ? BlendMode.ADD : BlendMode.OPAQUE;
            color = new Color(visual.rgba);
        }
    }

    public boolean isTravelling() {
        return isActive && age < travelTicks;
    }

    @Override
    public void tick(Level level, float delta) {
        if(!isActive) return;
        age += Math.max(0f, delta);
        if(age < travelTicks) {
            float progress = age / travelTicks;
            setPosition(lerp(originX, impactX, progress),
                    lerp(originY, impactY, progress), lerp(originZ, impactZ, progress));
            scale = baseScale + 0.07f * Math.abs((float)Math.sin(age * 0.7f));
        }
        else {
            setPosition(impactX, impactY, impactZ);
            float impactAge = age - travelTicks;
            scale = baseScale + 0.18f * Math.abs((float)Math.sin(impactAge * 0.45f));
            color.a = Math.max(0f, 1f - impactAge / IMPACT_LIFETIME_TICKS);
            if(impactAge >= IMPACT_LIFETIME_TICKS) isActive = false;
        }
        roll += delta * 0.15f;
        tickAttached(level, delta);
    }

    private static float travelTicks(CombatPresentationEvent event) {
        if(event.getPhase() != CombatPresentationPhase.ATTACK) return 0f;
        CombatAction action = event.getAction();
        if(action != CombatAction.PROJECTILE && action != CombatAction.SPELL
                && action != CombatAction.BENEFICIAL_SPELL) return 0f;
        float dx = event.getImpactX() - event.getOriginX();
        float dy = event.getImpactY() - event.getOriginY();
        float dz = event.getImpactZ() - event.getOriginZ();
        float distance = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        if(event.getProjectileVisual() != null) return distance / event.getProjectileVisual().speed;
        return Math.max(4f, Math.min(18f, distance * 2f));
    }

    private static int texture(CombatPresentationEvent event) {
        if(event != null && event.getPhase() == CombatPresentationPhase.IMPACT) return 19;
        if(event == null || event.getAction() == CombatAction.MELEE) return 3;
        if(event.getAction() == CombatAction.PROJECTILE) return 1;
        if(event.getAction() == CombatAction.BENEFICIAL_SPELL) return 0;
        return 2;
    }

    private static Color color(CombatAction action, boolean stateChanged) {
        float alpha = stateChanged ? 1f : 0.65f;
        if(action == CombatAction.PROJECTILE) return new Color(1f, 0.9f, 0.45f, alpha);
        if(action == CombatAction.SPELL) return new Color(0.55f, 0.7f, 1f, alpha);
        if(action == CombatAction.BENEFICIAL_SPELL) return new Color(0.4f, 1f, 0.55f, alpha);
        if(action == CombatAction.ENVIRONMENTAL_HAZARD) {
            return new Color(1f, 0.25f, 0.1f, alpha);
        }
        return new Color(1f, 0.55f, 0.2f, alpha);
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }
}
