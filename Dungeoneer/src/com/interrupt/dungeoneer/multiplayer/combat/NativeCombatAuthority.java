package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

import java.util.List;

/** Render-thread bridge from original Delver combat into Host encounter authority. */
public interface NativeCombatAuthority {
    default boolean canApplyNativeParticipantEffect(ParticipantId participant) { return false; }

    /** Positive amount damages; negative amount heals, after original Actor rules ran once. */
    default void applyNativeParticipantDamage(String sourceId, ParticipantId participant,
            int amount, float x, float y, float z) { }

    default void applyNativeParticipantImpulse(ParticipantId participant,
            float x, float y, float z) { }

    default void setNativeParticipantPosition(ParticipantId participant,
            float x, float y, float z) { }

    default void failNativePresentation(String reason) { }

    default void beginNativeWorld() { }

    default void publishNativeAnimationCue(NativeAnimationCue cue) { }

    default void publishNativeExplosion(NativeExplosionPresentation presentation) { }

    default void synchronizeNativeDynamicState(NativeDynamicState state) { }

    default void publishNativeDynamicCue(NativeDynamicCue cue) { }

    default void publishNativeSpellPresentation(NativeSpellPresentation presentation) { }

    default void publishNativeMeleePresentation(NativeMeleePresentation presentation) { }

    default void publishNativeRangedPresentation(NativeRangedPresentation presentation) { }

    default void synchronizeNativeActorEffects(ActorEffectsSnapshot state) { }

    void bindNativeMonster(int health, int maximumHealth, float x, float y, float z);

    default void bindNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z) {
        bindNativeMonster(health, maximumHealth, x, y, z);
    }

    default void bindNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z, boolean gibbed) {
        bindNativeMonster(monsterId, health, maximumHealth, x, y, z);
    }

    void synchronizeNativeMonster(int health, int maximumHealth, float x, float y, float z);

    default void synchronizeNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z) {
        synchronizeNativeMonster(health, maximumHealth, x, y, z);
    }

    default void synchronizeNativeMonster(String monsterId, int health, int maximumHealth,
            float x, float y, float z, boolean gibbed) {
        synchronizeNativeMonster(monsterId, health, maximumHealth, x, y, z);
    }

    List<CombatRequest> drainNativeCombatRequests();

    void applyNativeMonsterDamage(ParticipantId targetId, int damage, CombatAction action,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ);

    default void applyNativeMonsterDamage(String monsterId, ParticipantId targetId,
            int damage, CombatAction action, float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ) {
        applyNativeMonsterDamage(targetId, damage, action,
                originX, originY, originZ, impactX, impactY, impactZ);
    }

    default void recordNativeMonsterAttacker(String monsterId, ParticipantId attackerId) { }

    default void applyNativeEnvironmentalDamage(String sourceId, ParticipantId targetId,
            int damage, float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ) { }

    void publishNativePresentation(String sourceId, String targetId, CombatAction action,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged);

    default void publishNativePresentation(String sourceId, String targetId,
            CombatAction action, CombatPresentationPhase phase,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged) {
        publishNativePresentation(sourceId, targetId, action,
                originX, originY, originZ, impactX, impactY, impactZ, stateChanged);
    }
    default void publishNativePresentation(String sourceId, String targetId,
            CombatAction action, CombatPresentationPhase phase,
            float originX, float originY, float originZ,
            float impactX, float impactY, float impactZ, boolean stateChanged,
            ProjectileVisual visual) {
        publishNativePresentation(sourceId, targetId, action, phase, originX, originY, originZ,
                impactX, impactY, impactZ, stateChanged);
    }
}
