package com.interrupt.dungeoneer.multiplayer.participant;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Progression;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class LocalPlayerCompatibilityAdapterTest {
    @Test
    public void delegatesParticipantActionsToOriginalLocalPlayerAndProgression() {
        Player player = new Player();
        Progression progression = new Progression();
        ParticipantContext local = LocalPlayerCompatibilityAdapter.adapt(player, progression);

        local.getCharacter().setPosition(4f, 5f, 0.75f);
        local.getCharacter().setRotation(1.25f);
        local.getPartyProgression().putPersistent("tutorial", "complete");

        assertSame(LocalPlayerCompatibilityAdapter.LOCAL_PARTICIPANT_ID,
                local.getParticipantId());
        assertEquals(4f, player.x, 0f);
        assertEquals(5f, player.y, 0f);
        assertEquals(0.75f, player.z, 0f);
        assertEquals(1.25f, player.rot, 0f);
        assertEquals("complete", progression.progressionTriggers.get("tutorial"));
    }
}
