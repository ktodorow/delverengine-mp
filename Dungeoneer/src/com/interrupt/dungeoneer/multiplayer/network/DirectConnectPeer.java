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
    default List<ItemActionResult> drainItemActionResults() { return java.util.Collections.emptyList(); }
    DirectConnectStatus getStatus();

    String getRole();

    String getEndpoint();

    NetworkEntityId getLocalMovementEntityId();

    List<MovementEntityDescriptor> getMovementEntities();

    List<MovementSnapshot> getMovementSnapshots();

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

    default List<BreakableSnapshot> getBreakableSnapshots() {
        return java.util.Collections.emptyList();
    }

    default List<com.interrupt.dungeoneer.multiplayer.combat.NativeStatusCue> drainNativeStatusCues() {
        return java.util.Collections.emptyList();
    }

    default long getNativeWorldGeneration() { return 1; }

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

    void submitMovementInput(MovementInputFrame input);

    @Override
    void close();
}
