package com.interrupt.dungeoneer.multiplayer.movement;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Ladder;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.game.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Copies the solid native objects a local Player collides with (walkways, columns, lifts, doors,
 * crates, NPCs) and ladder climb areas from the live Active Floor for the Host movement thread.
 * Actors stay excluded: Participants never collide with each other, and Monsters keep their
 * existing Host behavior.
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
            for(Entity entity : entities) {
                if(!collides(entity)) continue;
                result.add(obstacle(entity));
                // Native Ladder.init: climb area is its collision grown by 0.1 across.
                if(entity instanceof Ladder) result.add(MovementObstacle.climbArea(entity.x, entity.y,
                        entity.z, entity.collision.x + 0.1f, entity.collision.y + 0.1f, entity.collision.z));
            }
        }
        return result;
    }

    /**
     * Bounds of the floor's Monsters that native Player physics collides with; they move every
     * frame, so they are copied every frame. Native Player bounces off Actors instead of standing.
     */
    public static List<MovementObstacle> monsters(Level level) {
        List<MovementObstacle> result = new ArrayList<MovementObstacle>();
        if(level == null || level.entities == null) return result;
        for(Entity entity : level.entities) {
            if(!(entity instanceof com.interrupt.dungeoneer.entities.Monster)) continue;
            com.interrupt.dungeoneer.entities.Monster monster = (com.interrupt.dungeoneer.entities.Monster)entity;
            if(!monster.blocksNativeMovement() || monster.ignorePlayerCollision
                    || monster.collidesWith == Entity.CollidesWith.staticOnly
                    || monster.collidesWith == Entity.CollidesWith.nonActors) continue;
            if(result.size() == LevelMovementCollisionWorld.MAX_ACTOR_OBSTACLES) break;
            result.add(new MovementObstacle(monster.x, monster.y, monster.z, monster.collision.x,
                    monster.collision.y, monster.collision.z, false, monster.canStepUpOn, false));
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
