package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementInputFrame;
import com.interrupt.dungeoneer.multiplayer.movement.MovementSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;

import java.util.List;

public interface DirectConnectPeer extends AutoCloseable {
    DirectConnectStatus getStatus();

    String getRole();

    String getEndpoint();

    NetworkEntityId getLocalMovementEntityId();

    List<MovementEntityDescriptor> getMovementEntities();

    List<MovementSnapshot> getMovementSnapshots();

    void submitMovementInput(MovementInputFrame input);

    @Override
    void close();
}
