package com.interrupt.dungeoneer.multiplayer.launcher;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Reserve actual TCP and UDP pair; UDP-only ephemeral ports may still have busy TCP on Windows. */
final class SocketTestPorts {
    private SocketTestPorts() { }

    static DatagramSocket occupyUdpWithAvailableTcp() throws IOException {
        BindException lastFailure = null;
        for(int attempt = 0; attempt < 16; attempt++) {
            try(ServerSocket tcp = new ServerSocket()) {
                tcp.setReuseAddress(false);
                tcp.bind(new InetSocketAddress(0));
                try { return new DatagramSocket(tcp.getLocalPort()); }
                catch(BindException busyUdp) { lastFailure = busyUdp; }
            }
        }
        throw new IOException("No available TCP/UDP pair for listener test.", lastFailure);
    }

    static int availableTcpAndUdp() throws IOException {
        try(DatagramSocket reservation = occupyUdpWithAvailableTcp()) { return reservation.getLocalPort(); }
    }
}
