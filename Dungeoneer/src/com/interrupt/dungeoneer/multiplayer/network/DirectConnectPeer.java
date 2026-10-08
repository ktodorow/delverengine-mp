package com.interrupt.dungeoneer.multiplayer.network;
import com.interrupt.dungeoneer.multiplayer.items.ItemActionResult;
import com.interrupt.dungeoneer.multiplayer.combat.NativeExplosionPresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicState;
import com.interrupt.dungeoneer.multiplayer.combat.NativeDynamicCue;
import com.interrupt.dungeoneer.multiplayer.combat.NativeSpellPresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeMeleePresentation;
import com.interrupt.dungeoneer.multiplayer.combat.NativeRangedPresentation;

import com.interrupt.dungeoneer.multiplayer.items.DoorFeedback;

import com.interrupt.dungeoneer.multiplayer.combat.ActorEffectsSnapshot;
import com.interrupt.dungeoneer.multiplayer.combat.NativeStatusEffectState;

import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
import com.interrupt.dungeoneer.multiplayer.items.BreakableSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld;
import com.interrupt.dungeoneer.multiplayer.items.ItemAction;
import com.interrupt.dungeoneer.multiplayer.items.ItemRequest;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;

import java.util.List;

public interface DirectConnectPeer extends AutoCloseable {
    default com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge getPersonalKnowledge() { return com.interrupt.dungeoneer.multiplayer.knowledge.PersonalKnowledge.empty(); }
    default com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping getPotionMapping() { return com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping.empty(); }
    default void publishPotionMapping(com.interrupt.dungeoneer.multiplayer.knowledge.PotionMapping mapping) { }
    default List<ItemActionResult> drainItemActionResults() { return java.util.Collections.emptyList(); }
    DirectConnectStatus getStatus();

    String getRole();

    String getEndpoint();

    NetworkEntityId getLocalMovementEntityId();

    /** Persistent slot identity also exists while Spectator has no world body. */
    default int getLocalCampaignSlot() {
        NetworkEntityId entity = getLocalMovementEntityId();
        return entity == null ? 0 : (int)entity.getValue();
    }

    /** Render captures checkpoint before applying native state; stale completions are ignored. */
    default long getPendingAdmissionCheckpoint() { return 0L; }
    default void acknowledgeNativeWorldReadiness(long checkpoint) { }

    List<MovementEntityDescriptor> getMovementEntities();

    List<MovementSnapshot> getMovementSnapshots();

    /** Host only: every Participant's accepted position this instant; null on a client. */
    default List<MovementEntityState> getCurrentMovementStates() { return null; }

    CombatSnapshot getCombatSnapshot();

    List<CombatPresentationEvent> getCombatPresentationEvents();

    long getNextCombatRequestId();

    PartyStatusSnapshot getPartyStatus();

    PartyCommunicationState getPartyCommunicationState();

    void submitPartyChat(String text);

    void requestPauseSession();

    boolean isSessionPaused();

    boolean canControlSessionPause();

    void setSessionPaused(boolean paused);

    void submitCombatAction(long requestId, CombatAction action, String targetId);

    void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ);

    default void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ, float attackPower) {
        submitCombatAction(requestId, action, aimX, aimY, aimZ);
    }

    default void submitCombatAction(long requestId, CombatAction action,
            float aimX, float aimY, float aimZ, float attackPower, long weaponEntityId) {
        submitCombatAction(requestId, action, aimX, aimY, aimZ, attackPower);
    }

    default List<DoorSnapshot> getDoorSnapshots() {
        return java.util.Collections.emptyList();
    }

    /** Host transforms of the floor's native Movers. */
    default List<com.interrupt.dungeoneer.multiplayer.items.MoverSnapshot> getMoverSnapshots() {
        return java.util.Collections.emptyList();
    }

    default List<BreakableSnapshot> getBreakableSnapshots() {
        return java.util.Collections.emptyList();
    }

    /** Monsters Host bound after the floor's initial attach; clients materialize replicas once. */
    /** Client: Host floor marks made before this peer joined, to rebuild on its own floor. */
    default List<com.interrupt.dungeoneer.multiplayer.combat.NativeDecalState> drainNativeDecals() {
        return java.util.Collections.emptyList();
    }

    /** Client: screen-only effects of Host trigger chains this peer's Participant started. */
    default List<com.interrupt.dungeoneer.multiplayer.items.TriggerPresentation> drainTriggerPresentations() {
        return java.util.Collections.emptyList();
    }

    default List<com.interrupt.dungeoneer.multiplayer.combat.NativeMonsterSpawn> drainNativeMonsterSpawns() {
        return java.util.Collections.emptyList();
    }

    default List<com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue> drainNativeStatusCues() {
        return java.util.Collections.emptyList();
    }

    default long getNativeWorldGeneration() { return 1; }

    /** Host-chosen seed every peer builds the Shared Floor from; 0 before Host announces it. */
    default long getSharedFloorSeed() { return 0L; }

    /** Reports this peer's finished floor build. Host keeps it; a client sends it for comparison. */
    default void recordSharedFloorFingerprint(
            com.interrupt.dungeoneer.multiplayer.floor.SharedFloorFingerprint fingerprint) { }

    default List<com.interrupt.dungeoneer.multiplayer.combat.NativeAnimationCue> drainNativeAnimationCues() { return java.util.Collections.emptyList(); }

    default List<NativeExplosionPresentation> drainNativeExplosions() { return java.util.Collections.emptyList(); }

    default List<NativeDynamicState> getNativeDynamicStates() { return java.util.Collections.emptyList(); }

    default List<NativeDynamicCue> drainNativeDynamicCues() { return java.util.Collections.emptyList(); }

    default List<NativeSpellPresentation> drainNativeSpellPresentations() {
        return java.util.Collections.emptyList();
    }

    default List<NativeMeleePresentation> drainNativeMeleePresentations() {
        return java.util.Collections.emptyList();
    }

    default List<NativeRangedPresentation> drainNativeRangedPresentations() {
        return java.util.Collections.emptyList();
    }

    default void failNativePresentation(String reason) { }

    default List<DoorFeedback> drainDoorFeedback() { return java.util.Collections.emptyList(); }

    default List<ActorEffectsSnapshot> getActorEffects() {
        return java.util.Collections.emptyList();
    }

    default int getPartyKeys() { return 0; }

    default com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot getPartyProgression() {
        return com.interrupt.dungeoneer.multiplayer.participant.PartyProgressionSnapshot.empty();
    }

    default List<com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress> getParticipantProgress() {
        return java.util.Collections.emptyList();
    }

    default List<com.interrupt.dungeoneer.multiplayer.economy.ShopEntryState> getShopEntries() {
        return java.util.Collections.emptyList();
    }

    /** Targeted live shop openings for this Participant; never replayed after reconnect. */
    default List<com.interrupt.dungeoneer.multiplayer.economy.ShopOpening> drainShopOpenings() {
        return java.util.Collections.emptyList();
    }

    default List<PhysicalItemState> getPhysicalItems() {
        return java.util.Collections.emptyList();
    }

    default long getNextItemRequestId() { return 1L; }

    default void submitItemAction(long requestId, ItemAction action, long entityId) { }

    default void submitItemAction(long requestId, ItemAction action, long entityId,
            int condition, int quantity) {
        submitItemAction(requestId, action, entityId);
    }

    default void submitItemAction(long requestId, ItemAction action, long entityId,
            int condition, int quantity, boolean hasAim, float aimX, float aimY, float aimZ) {
        submitItemAction(requestId, action, entityId, condition, quantity);
    }

    /** True once Host declared the campaign defeated; gameplay never resumes on this session. */
    default boolean isPartyWiped() { return false; }

    /** Held Use toward one Downed Campaign Slot; inactive releases this Participant's Revival. */
    default void submitReviveIntent(int targetSlot, boolean active) { }

    void submitMovementInput(MovementInputFrame input);

    @Override
    void close();
}
