package com.interrupt.dungeoneer;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void parsesDirectHostAndClientSettings() {
        DesktopLaunchOptions host = DesktopLaunchOptions.parse(new String[] {
                "--direct-host=41234"
        });
        assertTrue(host.directHost);
        assertEquals(41234, host.sessionPort);

        DesktopLaunchOptions client = DesktopLaunchOptions.parse(new String[] {
                "--direct-connect", "192.168.1.50",
                "--session-port=41234",
                "--participant-id=friend-2"
        });
        assertEquals("192.168.1.50", client.directConnectAddress);
        assertEquals(41234, client.sessionPort);
        assertEquals("friend-2", client.participantId);
        assertFalse(client.directHost);
    }

    @Test
    public void rejectsConflictingDirectConnectModeAndInvalidPort() {
        assertParseFailure(new String[] { "--direct-host", "--direct-connect=127.0.0.1" },
                "either --direct-host or --direct-connect");
        assertParseFailure(new String[] { "--direct-host", "--owned-tutorial" },
                "cannot be combined");
        assertParseFailure(new String[] { "--direct-host", "--session-port=70000" },
                "1 to 65535");
    }

    private void assertParseFailure(String[] arguments, String expected) {
        try {
            DesktopLaunchOptions.parse(arguments);
            fail("Invalid launch options were accepted");
        }
        catch(IllegalArgumentException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains(expected));
        }
    }
}
