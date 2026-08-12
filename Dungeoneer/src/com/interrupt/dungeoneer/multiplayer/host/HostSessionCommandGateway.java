package com.interrupt.dungeoneer.multiplayer.host;

/** Shared session-facing command boundary for local, synthetic, and future network clients. */
public interface HostSessionCommandGateway {
    void submit(HostSessionCommand command);
}
