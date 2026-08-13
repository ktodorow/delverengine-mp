package com.interrupt.dungeoneer.multiplayer.movement;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteAvatarTest {
    @Test
    public void remainsVisualOnlyAndNeverCreatesAnotherOriginalPlayer() {
        RemoteAvatar avatar = new RemoteAvatar(new MovementEntityDescriptor(
                1L, new NetworkEntityId(2L), new ParticipantId("campaign-slot-2"),
                2, "Friend", "humanoid-2"));

        assertFalse(avatar.isSolid);
        assertFalse(avatar.persists);
        assertEquals("tech_sprites", avatar.spriteAtlas);
        assertEquals(1, avatar.tex);
        assertTrue(avatar.fullbrite);
        assertFalse(Player.class.isAssignableFrom(avatar.getClass()));
    }
}
