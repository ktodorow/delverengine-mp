package com.interrupt.dungeoneer.screens;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPhase;
import org.junit.Test;

public class GameScreenTest {
    @Test
    public void readyPartyControlsUsePolledKeyboardWhenBaseScreenOwnsInputProcessor() {
        assertTrue(GameScreen.canHandlePartyControls(DirectConnectPhase.READY, false));
    }

    @Test
    public void partyCombatDoesNotRunWhileAnOverlayOwnsInput() {
        assertFalse(GameScreen.canHandlePartyControls(DirectConnectPhase.READY, true));
    }

    @Test
    public void failedDirectConnectCannotKeepRunningAsLocalGameplay() {
        assertTrue(GameScreen.canAdvanceDirectConnectGameplay(DirectConnectPhase.READY));
        assertFalse(GameScreen.canAdvanceDirectConnectGameplay(DirectConnectPhase.FAILED));
        assertFalse(GameScreen.canAdvanceDirectConnectGameplay(DirectConnectPhase.DISCONNECTED));
        assertFalse(GameScreen.canAdvanceDirectConnectGameplay(DirectConnectPhase.CLOSED));
    }
}
