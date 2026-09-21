package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.game.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Copies the solid native objects a local Player collides with (walkways, columns, lifts, doors,
 * crates, NPCs) from the live Active Floor for the Host movement thread. Actors stay excluded:
 * Participants never collide with each other, and Monsters keep their existing Host behavior.
 */
public final class NativeMovementObstacles {
    private long signature;
    private int count = -1;

    /** Bounds for the Host movement world, or null when nothing changed since the last copy. */
    public List<MovementObstacle> changed(Level level) {
        if(level == null) return null;
        long nextSignature = 17L;
        int nextCount = 0;
        for(Array<Entity> entities : lists(level)) {
            if(entities == null) continue;
            for(Entity entity : entities) {
                if(!collides(entity)) continue;
                nextCount++;
                nextSignature = nextSignature * 31L + System.identityHashCode(entity);
                nextSignature = nextSignature * 31L + Float.floatToIntBits(entity.x);
                nextSignature = nextSignature * 31L + Float.floatToIntBits(entity.y);
                nextSignature = nextSignature * 31L + Float.floatToIntBits(entity.z);
                nextSignature = nextSignature * 31L + Float.floatToIntBits(entity.collision.x);
                nextSignature = nextSignature * 31L + Float.floatToIntBits(entity.collision.y);
                nextSignature = nextSignature * 31L + Float.floatToIntBits(entity.collision.z);
                nextSignature = nextSignature * 31L + (entity.canStepUpOn ? 1 : 0);
            }
        }
        if(nextCount == count && nextSignature == signature) return null;
        count = nextCount;
        signature = nextSignature;
        List<MovementObstacle> result = new ArrayList<MovementObstacle>(nextCount);
        for(Array<Entity> entities : lists(level)) {
            if(entities == null) continue;
            for(Entity entity : entities) if(collides(entity)) result.add(obstacle(entity));
        }
        return result;
    }

    /** Native Level.checkEntityCollision rules for a solid Player checking against this entity. */
    static boolean collides(Entity entity) {
        if(entity == null || !entity.isActive || !entity.isSolid || entity.ignorePlayerCollision) {
            return false;
        }
        if(entity instanceof Actor || entity instanceof Item) return false;
        return entity.collidesWith != Entity.CollidesWith.staticOnly
                && entity.collidesWith != Entity.CollidesWith.nonActors;
    }

    static MovementObstacle obstacle(Entity entity) {
        // Native Player bounces off Triggers instead of standing on them.
        boolean trigger = entity instanceof Trigger;
        return new MovementObstacle(entity.x, entity.y, entity.z, entity.collision.x,
                entity.collision.y, entity.collision.z, !trigger, !trigger && entity.canStepUpOn,
                entity instanceof Door || entity instanceof Breakable);
    }

    @SuppressWarnings("unchecked")
    private static Array<Entity>[] lists(Level level) {
        return new Array[] { level.entities, level.static_entities, level.non_collidable_entities };
    }
}
