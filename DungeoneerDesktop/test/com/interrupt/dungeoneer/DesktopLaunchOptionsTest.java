package com.interrupt.dungeoneer;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DesktopLaunchOptionsTest {
    @Test
    public void explicitOwnedCopyEnablesOwnedTutorial() {
        DesktopLaunchOptions options = DesktopLaunchOptions.parse(new String[] {
                "--owned-copy=C:\\Games\\Delver\\delver.jar"
        });

        assertTrue(options.ownedTutorial);
        assertEquals(new File("C:\\Games\\Delver\\delver.jar"), options.ownedCopy);
        assertFalse(options.inspectOwnedCopy);
    }

    @Test
    public void inspectionDoesNotLaunchGame() {
        DesktopLaunchOptions options = DesktopLaunchOptions.parse(new String[] {
                "--inspect-owned-copy", "C:\\Games\\Delver\\delver.jar"
        });

        assertTrue(options.inspectOwnedCopy);
        assertFalse(options.ownedTutorial);
    }
}
