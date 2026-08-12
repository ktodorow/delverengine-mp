package com.interrupt.dungeoneer.multiplayer.network;

public enum DirectConnectPhase {
    STARTING,
    LISTENING,
    CONNECTING,
    HANDSHAKING,
    REGISTERING_UDP,
    READY,
    REJECTED,
    DISCONNECTED,
    FAILED,
    CLOSED
}
