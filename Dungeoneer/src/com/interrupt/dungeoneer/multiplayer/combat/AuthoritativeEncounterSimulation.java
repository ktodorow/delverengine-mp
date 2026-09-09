package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.host.AuthoritativeHostSimulation;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionCommand;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionSnapshot;
import com.interrupt.dungeoneer.multiplayer.movement.AuthoritativeMovementSimulation;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityState;

import java.util.ArrayList;
import java.util.List;

/** Combines existing movement authority with encounter authority in one Host tick. */
public final class AuthoritativeEncounterSimulation implements AuthoritativeHostSimulation {
    private final AuthoritativeMovementSimulation movement;
    private final AuthoritativeCombatEncounter combat;
    private final List<MovementEntityDescriptor> descriptors;

    public AuthoritativeEncounterSimulation(AuthoritativeMovementSimulation movement,
            AuthoritativeCombatEncounter combat, List<MovementEntityDescriptor> descriptors) {
        if(movement == null || combat == null || descriptors == null) {
            throw new IllegalArgumentException("Encounter simulation dependencies cannot be null.");
        }
        this.movement = movement;
        this.combat = combat;
        this.descriptors = new ArrayList<MovementEntityDescriptor>(descriptors);
    }

    @Override
    public void applyCommand(long hostTick, HostSessionCommand command, HostSessionOutput output) {
        if(command instanceof CombatRequest) {
            combat.apply(hostTick, (CombatRequest)command, output);
            return;
        }
        movement.applyCommand(hostTick, command, output);
    }

    @Override
    public void tick(long hostTick, float fixedDeltaSeconds, HostSessionOutput output) {
        movement.tick(hostTick, fixedDeltaSeconds, output);
        for(MovementEntityDescriptor descriptor : descriptors) {
            MovementEntityState state = movement.getState(descriptor.getParticipantId());
            if(state != null) {
                combat.updateParticipantPosition(descriptor.getParticipantId(),
                        state.getX(), state.getY(), state.getZ());
            }
        }
        combat.tick(hostTick, fixedDeltaSeconds, output);
    }

    @Override
    public HostSessionSnapshot snapshot(long hostTick) {
        return movement.snapshot(hostTick);
    }
}
