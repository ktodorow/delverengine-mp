package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.items.DoorSnapshot;
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

    void submitMovementInput(MovementInputFrame input);

    @Override
    void close();
}
