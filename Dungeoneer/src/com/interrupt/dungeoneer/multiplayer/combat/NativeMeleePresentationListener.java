package com.interrupt.dungeoneer.multiplayer.combat;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.items.Sword;

/** Host hook for native Sword feedback without replaying damage or collision. */
public interface NativeMeleePresentationListener {
    void onMeleePresentation(Entity owner, Sword sword, NativeMeleePresentation.Kind kind,
            Entity target, Vector3 position, Vector3 direction);
}
