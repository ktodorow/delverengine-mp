package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** Host movement walks each Participant at its native Player speed. */
public class NativeWalkSpeedTest {
    private static final ParticipantId PARTICIPANT = new ParticipantId("campaign-slot-1");

    @Test public void speedStatAndEquipmentSetNativeWalkSpeedBeforeStatusEffects() {
        Player player = new Player();
        // Native Player.tick recalculates equipment stats every tick; nothing is equipped here.
        player.calculatedStats.Recalculate(player);
        assertEquals("Base Speed 4", 0.16f, player.getUnaffectedWalkSpeed(), 0.0001f);
        player.stats.SPD = 6;
        assertEquals(0.19f, player.getUnaffectedWalkSpeed(), 0.0001f);
        // Heavy gear lowers calculated Speed; native weighs it by 0.16 against base Speed.
        player.calculatedStats.SPD = -2;
        assertEquals(0.19f * (6f - 2f * 0.16f) / 6f, player.getUnaffectedWalkSpeed(), 0.0001f);
    }

    @Test public void hostTravelFollowsTheParticipantsNativeWalkSpeed() {
        float base = travel(AuthoritativeMovementSimulation.NATIVE_WALK_SPEED);
        float faster = travel(0.19f);
        assertEquals(base * 0.19f / AuthoritativeMovementSimulation.NATIVE_WALK_SPEED, faster, 0.001f);
    }

    private static float travel(float walkSpeed) {
        AuthoritativeMovementSimulation simulation = new AuthoritativeMovementSimulation(
                new RectangularMovementCollisionWorld(40f, 40f, 20f, 5f, 0.5f),
                Collections.singletonList(new MovementEntityDescriptor(1L, new NetworkEntityId(1L),
                        PARTICIPANT, 1, "Host", "humanoid-1")));
        simulation.setNativeWalkSpeed(PARTICIPANT, walkSpeed);
        float start = simulation.getState(PARTICIPANT).getY();
        for(int tick = 1; tick <= 60; tick++) {
            simulation.applyCommand(tick, new MovementInputCommand(PARTICIPANT,
                    new MovementInputFrame(tick, 1f, 0f, 0f, false)), null);
            simulation.tick(tick, 1f / 60f, null);
        }
        return simulation.getState(PARTICIPANT).getY() - start;
    }
}
