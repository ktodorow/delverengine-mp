package com.interrupt.dungeoneer.multiplayer.network;

/** Separate TCP and UDP probe outcomes for one explicit Direct Connect endpoint. */
public final class DirectConnectDiagnosticReport {
    private final String endpoint;
    private final boolean tcpReachable;
    private final String tcpDetail;
    private final boolean udpReachable;
    private final String udpDetail;
    private final boolean compatiblePrivateSession;

    DirectConnectDiagnosticReport(String endpoint, boolean tcpReachable, String tcpDetail,
            boolean udpReachable, String udpDetail, boolean compatiblePrivateSession) {
        this.endpoint = endpoint;
        this.tcpReachable = tcpReachable;
        this.tcpDetail = tcpDetail;
        this.udpReachable = udpReachable;
        this.udpDetail = udpDetail;
        this.compatiblePrivateSession = compatiblePrivateSession;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean isTcpReachable() {
        return tcpReachable;
    }

    public String getTcpDetail() {
        return tcpDetail;
    }

    public boolean isUdpReachable() {
        return udpReachable;
    }

    public String getUdpDetail() {
        return udpDetail;
    }

    public boolean isCompatiblePrivateSession() {
        return compatiblePrivateSession;
    }
}
