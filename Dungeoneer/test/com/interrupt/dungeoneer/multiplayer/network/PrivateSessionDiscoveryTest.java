package com.interrupt.dungeoneer.multiplayer.network;

import com.interrupt.dungeoneer.multiplayer.lobby.AvatarCatalog;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRoster;
import com.interrupt.dungeoneer.multiplayer.lobby.CampaignRosterStore;
import com.interrupt.dungeoneer.multiplayer.lobby.LauncherIdentity;
import com.interrupt.dungeoneer.multiplayer.lobby.SlotPresentation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PrivateSessionDiscoveryTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void compatibleLanSessionIsListedAndBothChannelsAreReachable()
            throws Exception {
        DirectConnectCompatibility compatibility = compatibility("floor");
        DirectConnectHost host = host(compatibility);
        try {
            List<LanPrivateSession> sessions = PrivateSessionDiscovery.discover(
                    host.getBoundPort(), compatibility, 750);

            assertEquals(1, sessions.size());
            assertEquals("friends", sessions.get(0).getCampaignId());
            assertEquals(host.getBoundPort(), sessions.get(0).getPort());
            assertEquals(1, sessions.get(0).getClaimedSlots());
            assertEquals(2, sessions.get(0).getCapacity());
            assertTrue(sessions.get(0).isLobbyOpen());

            DirectConnectDiagnosticReport diagnostics =
                    DirectConnectDiagnostics.diagnose("127.0.0.1",
                            host.getBoundPort(), compatibility, 750);
            assertTrue(diagnostics.getTcpDetail(), diagnostics.isTcpReachable());
            assertTrue(diagnostics.getUdpDetail(), diagnostics.isUdpReachable());
            assertTrue(diagnostics.isCompatiblePrivateSession());
        }
        finally {
            host.close();
        }
    }

    @Test
    public void incompatibleSessionIsHiddenButUdpProbeStillReportsReachability()
            throws Exception {
        DirectConnectCompatibility hostCompatibility = compatibility("host-floor");
        DirectConnectCompatibility clientCompatibility = compatibility("client-floor");
        DirectConnectHost host = host(hostCompatibility);
        try {
            assertTrue(PrivateSessionDiscovery.discover(host.getBoundPort(),
                    clientCompatibility, 500).isEmpty());

            DirectConnectDiagnosticReport diagnostics =
                    DirectConnectDiagnostics.diagnose("127.0.0.1",
                            host.getBoundPort(), clientCompatibility, 500);
            assertTrue(diagnostics.isTcpReachable());
            assertTrue(diagnostics.isUdpReachable());
            assertFalse(diagnostics.isCompatiblePrivateSession());
            assertTrue(diagnostics.getUdpDetail().contains("does not match"));
        }
        finally {
            host.close();
        }
    }

    @Test
    public void tcpListenerWithoutUdpReplyIsReportedSeparately() throws Exception {
        ServerSocket tcpOnly = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"));
        try {
            DirectConnectDiagnosticReport diagnostics =
                    DirectConnectDiagnostics.diagnose("127.0.0.1",
                            tcpOnly.getLocalPort(), compatibility("floor"), 250);

            assertTrue(diagnostics.getTcpDetail(), diagnostics.isTcpReachable());
            assertFalse(diagnostics.isUdpReachable());
            assertTrue(diagnostics.getUdpDetail().contains("No Private Session reply")
                    || diagnostics.getUdpDetail().contains("Probe failed"));
        }
        finally {
            tcpOnly.close();
        }
    }

    @Test
    public void diagnosticsTryEveryResolvedAddress() throws Exception {
        DirectConnectCompatibility compatibility = compatibility("floor");
        DirectConnectHost host = host(compatibility);
        try {
            InetAddress unreachable = InetAddress.getByAddress(new byte[] {
                    (byte)192, 0, 2, 1
            });
            DirectConnectDiagnosticReport diagnostics =
                    DirectConnectDiagnostics.diagnoseResolved("multi-address.test",
                            new InetAddress[] { unreachable,
                                    InetAddress.getByName("127.0.0.1") },
                            host.getBoundPort(), compatibility, 750);

            assertTrue(diagnostics.getTcpDetail(), diagnostics.isTcpReachable());
            assertTrue(diagnostics.getUdpDetail(), diagnostics.isUdpReachable());
            assertTrue(diagnostics.isCompatiblePrivateSession());
        }
        finally {
            host.close();
        }
    }

    @Test
    public void discoveredSessionRejectsUnsafeDisplayText() throws Exception {
        assertAnnouncementRejected("not-a-host-session", "friends");
        assertAnnouncementRejected("0123456789abcdef", "friends\nINJECTED");
    }

    private DirectConnectHost host(DirectConnectCompatibility compatibility)
            throws Exception {
        CampaignRosterStore store = new CampaignRosterStore(
                temporaryFolder.newFolder(), new SecureRandom());
        CampaignRoster roster = store.loadOrCreate("friends", 2,
                AvatarCatalog.ownedV108Humanoids(), identity('1'),
                new SlotPresentation("Host", AvatarCatalog.HUMANOID_1));
        return DirectConnectHost.start(0, compatibility, roster, store);
    }

    private void assertAnnouncementRejected(String sessionId, String campaignId)
            throws Exception {
        try {
            new LanPrivateSession(InetAddress.getByName("127.0.0.1"), 37777,
                    sessionId, campaignId, 2, 1, true);
            fail("Unsafe LAN announcement text was accepted");
        }
        catch(IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("identity"));
        }
    }

    private DirectConnectCompatibility compatibility(String floor) {
        return DirectConnectCompatibility.forOpenSourceTestFloor(
                floor.getBytes(StandardCharsets.UTF_8));
    }

    private LauncherIdentity identity(char value) {
        StringBuilder result = new StringBuilder(LauncherIdentity.ENCODED_LENGTH);
        while(result.length() < LauncherIdentity.ENCODED_LENGTH) result.append(value);
        return new LauncherIdentity(result.toString());
    }
}
