package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.interrupt.dungeoneer.GameInput;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.gfx.GlRenderer;
import com.interrupt.dungeoneer.overlays.OverlayManager;
import com.interrupt.dungeoneer.multiplayer.combat.CombatAction;
import com.interrupt.dungeoneer.multiplayer.combat.CombatPresentationEvent;
import com.interrupt.dungeoneer.multiplayer.combat.CombatSnapshot;
import com.interrupt.dungeoneer.multiplayer.communication.PartyCommunicationState;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectStatus;
import com.interrupt.dungeoneer.multiplayer.participant.PartyStatusSnapshot;

import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DirectConnectMovementControllerTest {
    @Test
    public void appliesHostApprovedSpawnBeforeFirstGameplayTick() {
        NetworkEntityId localEntityId = new NetworkEntityId(2L);
        MovementEntityState state = new MovementEntityState(localEntityId, 1L, 0L,
                8f, 9f, 0.75f, 0.1f, 0.2f, 0.3f, 1.25f, MovementState.MOVING);
        DirectConnectMovementController controller = new DirectConnectMovementController(
                new StubPeer(localEntityId,
                        Arrays.asList(new MovementSnapshot(1L, 3L, Arrays.asList(state)))));
        Player player = new Player();

        assertTrue(controller.applyInitialAuthoritativeState(player));
        assertEquals(8f, player.x, 0f);
        assertEquals(9f, player.y, 0f);
        assertEquals(0.75f, player.z, 0f);
        // Host velocity is per second; the native Player moves per 60 Hz tick.
        assertEquals(0.1f / 60f, player.xa, 0.000001f);
        assertEquals(0.2f / 60f, player.ya, 0.000001f);
        assertEquals(0.3f / 60f, player.za, 0.000001f);
        assertEquals(1.25f, player.rot, 0f);
    }

    @Test
    public void jumpPressedOnAFrameWithoutInputSampleReachesTheNextSample() {
        GlRenderer previousRenderer = GameManager.renderer;
        OverlayManager previousOverlays = OverlayManager.instance;
        GameManager.renderer = new ObjenesisStd().newInstance(GlRenderer.class);
        GameManager.renderer.camera = new PerspectiveCamera();
        if(OverlayManager.instance == null) OverlayManager.instance = new OverlayManager();
        try {
            StubPeer peer = new StubPeer(new NetworkEntityId(2L),
                    Collections.<MovementSnapshot>emptyList());
            DirectConnectMovementController controller = new DirectConnectMovementController(peer);
            Game game = new ObjenesisStd().newInstance(Game.class);
            game.player = new Player();
            game.level = new ObjenesisStd().newInstance(Level.class);
            JumpInput input = new JumpInput();

            // Faster than 60 Hz: the native Player jumps on this frame, which has no input sample.
            input.jump = true;
            controller.update(game, input, 0.005f);
            assertTrue(peer.inputs.isEmpty());
            input.jump = false;
            controller.update(game, input, 0.013f);
            controller.update(game, input, 1f / 60f);

            assertEquals(2, peer.inputs.size());
            assertTrue("Host must jump with the native Player", peer.inputs.get(0).isJump());
            assertFalse(peer.inputs.get(1).isJump());
        }
        finally {
            GameManager.renderer = previousRenderer;
            OverlayManager.instance = previousOverlays;
        }
    }

    @Test
    public void hostCorrectionNeverPushesThePlayerIntoAWallNativePhysicsWouldLiftItOnto() {
        // Level construction draws through GameManager.renderer when one is installed.
        Level level = blockNorthOfY2();
        GlRenderer previousRenderer = GameManager.renderer;
        OverlayManager previousOverlays = OverlayManager.instance;
        Game previousGame = Game.instance;
        GameManager.renderer = new ObjenesisStd().newInstance(GlRenderer.class);
        GameManager.renderer.camera = new PerspectiveCamera();
        if(OverlayManager.instance == null) OverlayManager.instance = new OverlayManager();
        try {
            NetworkEntityId local = new NetworkEntityId(2L);
            Game game = new ObjenesisStd().newInstance(Game.class);
            Game.instance = game;
            game.player = new Player();
            game.level = level;
            // Touching the block face at y = 2 from the south.
            game.player.x = 3f; game.player.y = 2.2f; game.player.z = 0f;
            float intoWall = correctedY(game, local, 2.15f);
            assertEquals("Correction held at the wall face", 2.2f, intoWall, 0.00001f);

            game.player.x = 3f; game.player.y = 2.2f; game.player.z = 0f;
            float away = correctedY(game, local, 2.4f);
            assertTrue("Correction away from the wall still applies: " + away, away > 2.2f);
        }
        finally {
            GameManager.renderer = previousRenderer;
            OverlayManager.instance = previousOverlays;
            Game.instance = previousGame;
        }
    }

    /** Player y after one frame corrected toward a Host position at hostY. */
    private static float correctedY(Game game, NetworkEntityId local, float hostY) {
        MovementEntityState host = new MovementEntityState(local, 1L, 0L, 3f, hostY, 0f,
                0f, 0f, 0f, 0f, MovementState.IDLE);
        DirectConnectMovementController controller = new DirectConnectMovementController(
                new StubPeer(local, Arrays.asList(new MovementSnapshot(1L, 1L, Arrays.asList(host)))));
        controller.update(game, new JumpInput(), 1f / 60f);
        return game.player.y;
    }

    /** 6x6 floor with feet at height 0; rows y 0-1 are a block one unit higher. */
    private static Level blockNorthOfY2() {
        Level level = new Level(6, 6);
        for(int index = 0; index < level.tiles.length; index++) {
            com.interrupt.dungeoneer.tiles.Tile tile = new com.interrupt.dungeoneer.tiles.Tile();
            tile.ceilHeight = 3f;
            if(index / level.width < 2) tile.floorHeight = 0.5f;
            level.tiles[index] = tile;
        }
        return level;
    }

    /** Only the jump key; native jump is a newly pressed action, true for one frame. */
    private static final class JumpInput extends GameInput {
        boolean jump;

        @Override public boolean isJumpPressed() { return jump; }
        @Override public boolean isMoveForwardPressed() { return false; }
        @Override public boolean isMoveBackwardsPressed() { return false; }
        @Override public boolean isStrafeLeftPressed() { return false; }
        @Override public boolean isStrafeRightPressed() { return false; }
    }

    private static final class StubPeer implements DirectConnectPeer {
        private final NetworkEntityId localEntityId;
        private final List<MovementSnapshot> snapshots;
        private final List<MovementInputFrame> inputs = new ArrayList<MovementInputFrame>();

        private StubPeer(NetworkEntityId localEntityId, List<MovementSnapshot> snapshots) {
            this.localEntityId = localEntityId;
            this.snapshots = snapshots;
        }

        @Override public DirectConnectStatus getStatus() { return null; }
        @Override public String getRole() { return "Test"; }
        @Override public String getEndpoint() { return "memory"; }
        @Override public NetworkEntityId getLocalMovementEntityId() { return localEntityId; }
        @Override public List<MovementEntityDescriptor> getMovementEntities() {
            return Collections.emptyList();
        }
        @Override public List<MovementSnapshot> getMovementSnapshots() { return snapshots; }
        @Override public CombatSnapshot getCombatSnapshot() { return null; }
        @Override public List<CombatPresentationEvent> getCombatPresentationEvents() {
            return Collections.emptyList();
        }
        @Override public long getNextCombatRequestId() { return 1L; }
        @Override public PartyStatusSnapshot getPartyStatus() { return null; }
        @Override public PartyCommunicationState getPartyCommunicationState() {
            return PartyCommunicationState.initial();
        }
        @Override public void submitPartyChat(String text) { }
        @Override public void requestPauseSession() { }
        @Override public boolean isSessionPaused() { return false; }
        @Override public boolean canControlSessionPause() { return false; }
        @Override public void setSessionPaused(boolean paused) { }
        @Override public void submitCombatAction(long requestId, CombatAction action, String targetId) { }
        @Override public void submitCombatAction(long requestId, CombatAction action,
                float aimX, float aimY, float aimZ) { }
        @Override public void submitMovementInput(MovementInputFrame input) { inputs.add(input); }
        @Override public void close() { }
    }
}
