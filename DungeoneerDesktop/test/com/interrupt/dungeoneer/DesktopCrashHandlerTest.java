package com.interrupt.dungeoneer;

import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DesktopCrashHandlerTest {
    @Test
    public void normalWindowCloseDoesNotForceFailureExit() {
        LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();

        DesktopStarter.configureProcessExit(config);

        assertFalse(config.forceExit);
    }

    @Test
    public void uncaughtLwjglFailureExitsWithFailureStatus() throws Exception {
        final int[] exitStatus = { 0 };
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        DesktopCrashHandler handler = new DesktopCrashHandler(
                new PrintStream(errorBytes, true, "UTF-8"),
                new DesktopCrashHandler.Exit() {
                    @Override
                    public void exit(int status) {
                        exitStatus[0] = status;
                    }
                });

        handler.uncaughtException(new Thread("LWJGL Application"), new NullPointerException("boom"));

        assertEquals(1, exitStatus[0]);
        String error = errorBytes.toString("UTF-8");
        assertTrue(error.contains("LWJGL Application"));
        assertTrue(error.contains("NullPointerException: boom"));
    }
}
