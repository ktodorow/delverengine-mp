package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

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

    void submitMovementInput(MovementInputFrame input);

    @Override
    void close();
}
