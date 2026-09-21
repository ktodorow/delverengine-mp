package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.host.HostDisconnectOutcome;
import com.interrupt.dungeoneer.multiplayer.host.HostPersistedState;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;
import com.interrupt.dungeoneer.multiplayer.host.HostSessionOutput;
import com.interrupt.dungeoneer.multiplayer.host.HostTransitionOutcome;
import com.interrupt.dungeoneer.multiplayer.movement.MovementEntityDescriptor;
import com.interrupt.dungeoneer.multiplayer.movement.NetworkEntityId;
import com.interrupt.dungeoneer.multiplayer.movement.RectangularMovementCollisionWorld;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ParticipantMaximumHealthTest {
    private final ParticipantId alpha = new ParticipantId("campaign-slot-1");
    private final ParticipantId beta = new ParticipantId("campaign-slot-2");
    private final HostSessionOutput output = new HostSessionOutput() {
        public void event(HostSessionEvent event) { }
        public void disconnect(HostDisconnectOutcome outcome) { }
        public void transition(HostTransitionOutcome outcome) { }
        public void persist(HostPersistedState state) { }
    };

    @Test
    public void levelUpRestoresLivingParticipantAndStatChoiceRaisesMaximum() {
        AuthoritativeCombatEncounter encounter = encounter();
        encounter.applyNativeParticipantDamage(1L, "native-world", alpha, 3, 0f, 0f, 0f, output);
        assertEquals(5, health(encounter, alpha));

        encounter.setParticipantMaximumHealth(2L, alpha, 12, false, output);
        assertEquals(5, health(encounter, alpha));
        assertEquals(12, maximum(encounter, alpha));

        encounter.setParticipantMaximumHealth(3L, alpha, 12, true, output);
        assertEquals(12, health(encounter, alpha));
        assertEquals(8, maximum(encounter, beta));
    }

    @Test
    public void incapacitatedParticipantIsNotRevivedByProgressionAndHealthClampsToMaximum() {
        AuthoritativeCombatEncounter encounter = encounter();
        encounter.applyNativeParticipantDamage(1L, "native-world", alpha, 100, 0f, 0f, 0f, output);

        encounter.setParticipantMaximumHealth(2L, alpha, 20, true, output);
        assertEquals(0, health(encounter, alpha));

        encounter.setParticipantMaximumHealth(3L, beta, 5, false, output);
        assertEquals(5, health(encounter, beta));
        assertEquals(5, maximum(encounter, beta));

        encounter.setParticipantMaximumHealth(4L, beta, 0, true, output);
        assertEquals(5, maximum(encounter, beta));
    }

    private AuthoritativeCombatEncounter encounter() {
        return new AuthoritativeCombatEncounter(Arrays.asList(
                new MovementEntityDescriptor(1L, new NetworkEntityId(1), alpha, 1, "Host", "humanoid-1"),
                new MovementEntityDescriptor(1L, new NetworkEntityId(2), beta, 2, "Friend", "humanoid-2")),
                new RectangularMovementCollisionWorld(32f, 32f, 16.5f, 16.5f, 0.5f));
    }

    private static int health(AuthoritativeCombatEncounter encounter, ParticipantId participant) {
        return encounter.getSnapshot(10L).getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant)).getHealth();
    }

    private static int maximum(AuthoritativeCombatEncounter encounter, ParticipantId participant) {
        return encounter.getSnapshot(10L).getCombatant(
                AuthoritativeCombatEncounter.participantTargetId(participant)).getMaximumHealth();
    }
}
