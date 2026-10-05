package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;

/**
 * Bodies another peer simulates that still block native movement: Participants for the Host's
 * Monsters, replica Monsters for a client's own Player. Consulted by native Level collision
 * queries; only a moving Player or Monster is ever blocked, so triggers, traps, projectiles,
 * explosions and weapon sweeps keep their existing behavior.
 */
public interface MovementBodies {
    /** Adds bodies whose bounds overlap a box of this size at (x, y, z) for the moving entity. */
    void addColliding(float x, float y, float z, float widthX, float widthY, float height,
            Entity checking, Array<Entity> result);
}
