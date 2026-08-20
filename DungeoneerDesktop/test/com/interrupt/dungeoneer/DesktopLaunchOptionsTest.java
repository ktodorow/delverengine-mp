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
                "--direct-host=41234",
                "--campaign-capacity=4",
                "--campaign-id=friends",
                "--nickname=Hosty",
                "--avatar=humanoid-1",
                "--profile-root=C:\\DelverProfiles\\Host"
        });
        assertTrue(host.directHost);
        assertEquals(41234, host.sessionPort);
        assertEquals(4, host.campaignCapacity);
        assertEquals("friends", host.campaignId);
        assertEquals("Hosty", host.nickname);
        assertEquals("humanoid-1", host.avatarId);
        assertEquals(new File("C:\\DelverProfiles\\Host"), host.profileRoot);

        DesktopLaunchOptions client = DesktopLaunchOptions.parse(new String[] {
                "--direct-connect", "192.168.1.50",
                "--session-port=41234",
                "--nickname=Friend",
                "--avatar=humanoid-2",
                "--campaign-slot=3"
        });
        assertEquals("192.168.1.50", client.directConnectAddress);
        assertEquals(41234, client.sessionPort);
        assertEquals("Friend", client.nickname);
        assertEquals("humanoid-2", client.avatarId);
        assertEquals(3, client.requestedSlot);
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
        assertParseFailure(new String[] { "--direct-host", "--campaign-capacity=5" },
                "2, 3, or 4");
        assertParseFailure(new String[] { "--direct-connect=127.0.0.1", "--campaign-capacity=3" },
                "Only Host");
        assertParseFailure(new String[] { "--direct-connect=127.0.0.1", "--participant-id=ip-name" },
                "generated automatically");
    }

    @Test
    public void parsesLanDiscoveryDiagnosticsAndManualNetworkHelp() {
        DesktopLaunchOptions discovery = DesktopLaunchOptions.parse(new String[] {
                "--discover-private-sessions", "--session-port=41234"
        });
        assertTrue(discovery.discoverPrivateSessions);
        assertTrue(discovery.hasNetworkUtility());
        assertEquals(41234, discovery.sessionPort);

        DesktopLaunchOptions diagnostics = DesktopLaunchOptions.parse(new String[] {
                "--diagnose-direct-connect", "friends.example", "--session-port", "41235"
        });
        assertEquals("friends.example", diagnostics.diagnoseDirectConnectAddress);
        assertEquals(41235, diagnostics.sessionPort);

        DesktopLaunchOptions help = DesktopLaunchOptions.parse(
                new String[] { "--network-help" });
        assertTrue(help.networkHelp);
        assertTrue(help.hasNetworkUtility());
    }

    @Test
    public void parsesIdentityRecoveryUtilitiesWithoutLaunchingGame() {
        DesktopLaunchOptions export = DesktopLaunchOptions.parse(new String[] {
                "--export-identity-recovery", "C:\\Backups\\identity.properties",
                "--profile-root=C:\\DelverProfiles\\Source"
        });
        assertTrue(export.hasIdentityRecoveryUtility());
        assertEquals(new File("C:\\Backups\\identity.properties"), export.exportIdentityRecovery);

        DesktopLaunchOptions imported = DesktopLaunchOptions.parse(new String[] {
                "--import-identity-recovery=C:\\Backups\\identity.properties"
        });
        assertTrue(imported.hasIdentityRecoveryUtility());
        assertEquals(new File("C:\\Backups\\identity.properties"), imported.importIdentityRecovery);
    }

    @Test
    public void rejectsNetworkUtilityCombinedWithGameLaunch() {
        assertParseFailure(new String[] {
                "--discover-private-sessions", "--direct-host"
        }, "cannot be combined");
        assertParseFailure(new String[] {
                "--diagnose-direct-connect=127.0.0.1", "--test-level"
        }, "cannot be combined");
        assertParseFailure(new String[] { "--diagnose-direct-connect" },
                "requires a Host address");
        assertParseFailure(new String[] {
                "--export-identity-recovery=backup.properties",
                "--import-identity-recovery=backup.properties"
        }, "either --export-identity-recovery or --import-identity-recovery");
        assertParseFailure(new String[] {
                "--export-identity-recovery=backup.properties", "--direct-host"
        }, "cannot be combined");
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
