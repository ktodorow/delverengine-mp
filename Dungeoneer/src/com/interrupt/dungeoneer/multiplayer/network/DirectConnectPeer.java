package com.interrupt.dungeoneer.multiplayer.network;

public interface DirectConnectPeer extends AutoCloseable {
    DirectConnectStatus getStatus();

    String getRole();

    String getEndpoint();

    @Override
    void close();
}
