package com.interrupt.dungeoneer.game;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameStartModeTest {
    @Test
    public void ownedTutorialSkipsSavedRunAndForcesTutorial() {
        GameData gameData = new GameData();
        gameData.tutorialLevel = new Level();
        Progression progression = new Progression();
        progression.sawTutorial = true;

        assertFalse(Game.StartMode.OWNED_TUTORIAL.loadsSavedGame());
        assertTrue(Game.shouldStartTutorial(Game.StartMode.OWNED_TUTORIAL, progression, gameData));
    }

    @Test
    public void normalStartHonorsCompletedTutorial() {
        GameData gameData = new GameData();
        gameData.tutorialLevel = new Level();
        Progression progression = new Progression();
        progression.sawTutorial = true;

        assertTrue(Game.StartMode.NORMAL.loadsSavedGame());
        assertFalse(Game.shouldStartTutorial(Game.StartMode.NORMAL, progression, gameData));
    }
}
