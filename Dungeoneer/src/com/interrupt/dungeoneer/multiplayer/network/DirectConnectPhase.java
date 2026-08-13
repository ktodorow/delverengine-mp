package com.interrupt.dungeoneer.multiplayer.network;

public enum DirectConnectPhase {
    STARTING,
    LISTENING,
    CONNECTING,
    HANDSHAKING,
    CLAIMING_SLOT,
    AWAITING_APPROVAL,
    REGISTERING_UDP,
    LOBBY,
    READY,
    REJECTED,
    DISCONNECTED,
    FAILED,
    CLOSED
}
