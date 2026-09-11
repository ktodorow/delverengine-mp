package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.items.Bow;

/** Host hook for native Bow release feedback without creating another arrow. */
public interface NativeRangedPresentationListener {
    void onRangedPresentation(Entity owner, Bow bow, Vector3 position);
}
