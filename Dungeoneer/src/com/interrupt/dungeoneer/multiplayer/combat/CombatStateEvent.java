package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.multiplayer.host.HostSessionEvent;

/** Reliable Host result after combat state changes. */
public final class CombatStateEvent implements HostSessionEvent {
    private final CombatSnapshot snapshot;

    public CombatStateEvent(CombatSnapshot snapshot) {
        if(snapshot == null) throw new IllegalArgumentException("Combat snapshot cannot be null.");
        this.snapshot = snapshot;
    }

    public CombatSnapshot getSnapshot() { return snapshot; }
}
