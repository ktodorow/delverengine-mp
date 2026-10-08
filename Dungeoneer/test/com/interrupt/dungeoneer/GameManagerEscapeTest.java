package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Progression;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import com.interrupt.dungeoneer.overlays.Overlay;
import com.interrupt.dungeoneer.overlays.OverlayManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GameManagerEscapeTest {
    private GameApplication previousApplication;
    private boolean previousInEditor;
    private Game previousGame;
    private boolean previousIgnoreEscape;

    @Before public void remember() {
        previousApplication = GameApplication.instance;
        previousInEditor = Game.inEditor;
        previousGame = Game.instance;
        previousIgnoreEscape = Game.ignoreEscape;
        OverlayManager.instance.reset();
    }

    @After public void restore() {
        GameApplication.instance = previousApplication;
        Game.inEditor = previousInEditor;
        Game.instance = previousGame;
        OverlayManager.instance.reset();
        Game.ignoreEscape = previousIgnoreEscape;
    }

    @Test public void heldEscapeKeepsPauseMenuOpenWhileDirectConnectWorldTicks() {
        Game.instance = null;
        Game.ignoreEscape = false;
        StubOverlay menu = new StubOverlay(true);

        GameManager.handleEscape(() -> menu);
        // Next world tick: the same Esc press is still held down.
        GameManager.handleEscape(() -> new StubOverlay(true));

        assertSame(menu, OverlayManager.instance.current());
        assertTrue("Pause menu must stay open, not flicker shut", menu.visible);
    }

    @Test public void escapeStillClosesAnOverlayThatKeepsTheWorldRunning() {
        Game.instance = null;
        StubOverlay chat = new StubOverlay(false);
        OverlayManager.instance.push(chat);
        Game.ignoreEscape = false;

        GameManager.handleEscape(() -> new StubOverlay(true));

        assertSame("Closing an overlay must not also open the pause menu",
                chat, OverlayManager.instance.current());
        assertFalse(chat.visible);
    }

    @Test public void escapeStillEndsADelvEditPlaytest() throws Exception {
        GameApplication.instance = application(null);
        Game.inEditor = true;

        assertTrue(GameManager.escapeEndsEditorPlaytest());
    }

    @Test public void escapeNeverStopsADirectConnectFloorEvenIfLegacyStateLeaksIn() throws Exception {
        // Campaign boot clears inEditor; this guard also contains stale state from older callers.
        GameApplication.instance = application((DirectConnectPeer)Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { DirectConnectPeer.class },
                (proxy, method, arguments) -> null));
        Game.inEditor = true;

        assertFalse(GameManager.escapeEndsEditorPlaytest());
    }

    @Test public void directConnectSaveEntrypointsNeverTouchNativeSaveSlots() throws Exception {
        DirectConnectPeer client = (DirectConnectPeer)Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { DirectConnectPeer.class },
                (proxy, method, arguments) -> null);
        GameApplication.instance = application(client);
        Game game = new ObjenesisStd().newInstance(Game.class);

        game.save();
        game.save(3, null);
        Game.saveProgression(new Progression(), 3);
        assertFalse(game.levelFileExists(0, null));
        assertFalse(game.loadLevel(0, null));
    }

    @Test public void directConnectNativeTravelCannotCreateSeparateParticipantFloors() throws Exception {
        DirectConnectPeer client = (DirectConnectPeer)Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { DirectConnectPeer.class },
                (proxy, method, arguments) -> null);
        GameApplication.instance = application(client);
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.player = new com.interrupt.dungeoneer.entities.Player();
        game.level = new com.interrupt.dungeoneer.game.Level(4, 4);
        Field levelNumber = Game.class.getDeclaredField("levelNum");
        levelNumber.setAccessible(true); levelNumber.setInt(game, 1);
        com.interrupt.dungeoneer.game.Level active = game.level;
        com.interrupt.dungeoneer.entities.Stairs down = new com.interrupt.dungeoneer.entities.Stairs();
        down.direction = com.interrupt.dungeoneer.entities.Stairs.StairDirection.down;
        game.changeLevel(down);
        game.doLevelChange(down);
        game.warpToLevel("side-area", new ObjenesisStd().newInstance(com.interrupt.dungeoneer.entities.triggers.TriggeredWarp.class));
        game.doLevelExit(null);
        assertSame("Only Party activation boundary can install destination", active, game.level);
        org.junit.Assert.assertEquals(1, levelNumber.getInt(game));
        org.junit.Assert.assertNull(game.player.getCurrentTravelKey());
    }

    private static GameApplication application(DirectConnectPeer peer) throws Exception {
        GameApplication application = new ObjenesisStd().newInstance(GameApplication.class);
        Field field = GameApplication.class.getDeclaredField("directConnectPeer");
        field.setAccessible(true);
        field.set(application, peer);
        return application;
    }

    private static final class StubOverlay extends Overlay {
        StubOverlay(boolean pausesGame) { this.pausesGame = pausesGame; }
        @Override public void show(boolean setInputSettings) { visible = true; running = true; }
        @Override public void hide() { visible = false; running = false; }
        @Override public void tick(float delta) { }
        @Override public void onShow() { }
        @Override public void onHide() { }
    }
}
