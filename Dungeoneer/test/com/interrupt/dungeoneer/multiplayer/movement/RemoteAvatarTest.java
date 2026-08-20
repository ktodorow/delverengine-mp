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

    @Test
    public void animationTracksAuthoritativeMovementState() {
        RemoteAvatar avatar = new RemoteAvatar(new MovementEntityDescriptor(
                1L, new NetworkEntityId(2L), new ParticipantId("campaign-slot-2"),
                2, "Friend", "humanoid-2"));

        avatar.applyNetworkState(1f, 2f, 0.5f, 1f, 0f, 0f,
                MovementState.MOVING);
        avatar.tick(null, 10f);
        assertEquals(MovementState.MOVING, avatar.getMovementState());
        assertTrue(avatar.yOffset > 0f);

        avatar.applyNetworkState(1f, 2f, 0.5f, 0f, 0f, 1f,
                MovementState.AIRBORNE);
        avatar.tick(null, 1f);
        assertEquals(0.08f, avatar.yOffset, 0f);

        avatar.applyNetworkState(1f, 2f, 0.5f, 0f, 0f, 0f,
                MovementState.IDLE);
        avatar.tick(null, 1f);
        assertEquals(0f, avatar.yOffset, 0f);
    }
}
