package com.interrupt.dungeoneer.multiplayer.host;

/** Clock expressed in fixed Host ticks so tests never depend on wall time. */
public interface HostSessionClock {
    long getTargetTick();
}
