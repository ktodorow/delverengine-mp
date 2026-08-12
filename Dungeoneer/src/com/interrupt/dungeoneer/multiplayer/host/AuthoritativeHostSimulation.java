package com.interrupt.dungeoneer.multiplayer.host;

/**
 * High-level seam around authoritative game and level simulation. Implementations own all state
 * changes; Host session only controls command ordering, fixed time, and observable outputs.
 */
public interface AuthoritativeHostSimulation {
    void applyCommand(long hostTick, HostSessionCommand command, HostSessionOutput output);

    void tick(long hostTick, float fixedDeltaSeconds, HostSessionOutput output);

    HostSessionSnapshot snapshot(long hostTick);
}
