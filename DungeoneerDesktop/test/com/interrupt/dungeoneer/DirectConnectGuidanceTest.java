package com.interrupt.dungeoneer;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DirectConnectGuidanceTest {
    @Test
    public void guidanceRequiresExplicitPrivateFirewallAndRouterActions() {
        String guidance = DirectConnectGuidance.forPort(41234);

        assertTrue(guidance.contains("Private network"));
        assertTrue(guidance.contains("TCP 41234 and UDP 41234"));
        assertTrue(guidance.contains("CGNAT"));
        assertTrue(guidance.contains("100.64.0.0/10"));
        assertTrue(guidance.contains("private-network overlay/VPN"));
        assertTrue(guidance.contains("never requests administrator elevation"));
        assertTrue(guidance.contains("UPnP or NAT-PMP"));
        assertTrue(guidance.contains("do not disable the firewall"));
    }
}
