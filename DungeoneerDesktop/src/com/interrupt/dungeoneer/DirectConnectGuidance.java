package com.interrupt.dungeoneer;

/** Participant-visible manual network setup guidance. */
final class DirectConnectGuidance {
    private DirectConnectGuidance() { }

    static String forPort(int port) {
        return "Windows and router guidance (manual actions only)\n"
                + "- Host PC: use a trusted Private network profile. If Windows Firewall blocks the game, open Windows Security > Firewall & network protection > Allow an app through firewall, then allow Delver Multiplayer (or its Java runtime during development) on Private networks. Prefer an app exception over a permanently open port; do not disable the firewall.\n"
                + "- Internet Direct Connect: in the router's own administration page, manually forward TCP "
                + port + " and UDP " + port
                + " to the Host PC. Both protocols use the same number. Avoid DMZ mode and remove forwarding when no longer needed.\n"
                + "- CGNAT: a router WAN address in shared range 100.64.0.0/10, or a WAN address that differs from the public address, can indicate CGNAT or double NAT. Double NAT needs manual forwarding at both routers. With ISP-controlled CGNAT, ordinary forwarding cannot reach the Host; ask ISP for a public address or use trusted private-network overlay/VPN tooling with friends.\n"
                + "- Safety: launcher never requests administrator elevation, edits firewall rules, changes router settings, or invokes UPnP or NAT-PMP. It only reports probe results.";
    }
}
