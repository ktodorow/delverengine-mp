package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Entity.ArtType;
import com.interrupt.dungeoneer.entities.ProjectedDecal;

/** Client-only replay of Host-confirmed floor or wall impact. */
public final class CombatImpactDecal extends ProjectedDecal {
    public CombatImpactDecal(CombatPresentationEvent event) {
        super(ArtType.sprite, texture(event), size(event));
        if(event == null || event.getPhase() != CombatPresentationPhase.IMPACT) {
            throw new IllegalArgumentException("Impact decal requires an impact event.");
        }
        x = event.getImpactX();
        y = event.getImpactY();
        z = event.getImpactZ();
        direction = new Vector3(event.getImpactX() - event.getOriginX(),
                event.getImpactY() - event.getOriginY(),
                event.getImpactZ() - event.getOriginZ()).nor();
        if(direction.isZero()) direction.set(0f, 0f, -1f);
        start = 0.001f;
        end = event.getAction() == CombatAction.SPELL ? 0.8f : 0.5f;
        roll = (float)((event.getSequence() * 137L) % 360L);
        isOrtho = true;
        isSolid = false;
        isDynamic = false;
        persists = false;
        canStepUpOn = false;
    }

    private static int texture(CombatPresentationEvent event) {
        return event != null && event.getAction() == CombatAction.MELEE ? 18 : 19;
    }

    private static float size(CombatPresentationEvent event) {
        return event != null && event.getAction() == CombatAction.SPELL ? 0.75f : 0.55f;
    }
}
