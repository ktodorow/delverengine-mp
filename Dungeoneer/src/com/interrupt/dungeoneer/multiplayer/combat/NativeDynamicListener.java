package com.interrupt.dungeoneer.multiplayer.combat;

import com.interrupt.dungeoneer.entities.items.FusedBomb;

/** Host render-thread hook for live presentation emitted by dynamic entities. */
public interface NativeDynamicListener {
    void onFusedBombFizzle(FusedBomb bomb);
}
