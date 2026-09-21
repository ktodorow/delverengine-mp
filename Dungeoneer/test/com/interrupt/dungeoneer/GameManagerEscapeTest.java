package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectPeer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameManagerEscapeTest {
    private GameApplication previousApplication;
    private boolean previousInEditor;

    @Before public void remember() {
        previousApplication = GameApplication.instance;
        previousInEditor = Game.inEditor;
    }

    @After public void restore() {
        GameApplication.instance = previousApplication;
        Game.inEditor = previousInEditor;
    }

    @Test public void escapeStillEndsADelvEditPlaytest() throws Exception {
        GameApplication.instance = application(null);
        Game.inEditor = true;

        assertTrue(GameManager.escapeEndsEditorPlaytest());
    }

    @Test public void escapeNeverStopsADirectConnectFloorStartedThroughThePlaytestPath() throws Exception {
        // Direct Connect floors use Game(Level), which sets inEditor; Esc used to freeze native play.
        GameApplication.instance = application((DirectConnectPeer)Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { DirectConnectPeer.class },
                (proxy, method, arguments) -> null));
        Game.inEditor = true;

        assertFalse(GameManager.escapeEndsEditorPlaytest());
    }

    private static GameApplication application(DirectConnectPeer peer) throws Exception {
        GameApplication application = new ObjenesisStd().newInstance(GameApplication.class);
        Field field = GameApplication.class.getDeclaredField("directConnectPeer");
        field.setAccessible(true);
        field.set(application, peer);
        return application;
    }
}
