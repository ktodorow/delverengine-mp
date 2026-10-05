package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.serializers.KryoSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Host Active Floor checkpoint in Delver's own level format, as the single-player save writes a
 * level: every native object keeps its state without per-family save code. Host-local only; a
 * serialized engine graph never crosses the wire, clients still build from their own copy.
 */
public final class NativeFloorSave {
    /** Compressed checkpoint bound; a Campaign Save file is capped at 16 MiB. */
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final int MAX_RAW_BYTES = 96 * 1024 * 1024;

    private NativeFloorSave() { }

    /**
     * Serializes the live floor without single-player {@code preSaveCleanup}, which disposes
     * entities and would break the running session. The same entities that cleanup drops
     * (non-persisting, inactive, replicas, players) are left out of the checkpoint instead.
     */
    public static byte[] capture(Level level) {
        if(level == null) throw new IllegalArgumentException("Native floor is required.");
        Array<Entity> entities = level.entities;
        Array<Entity> nonCollidable = level.non_collidable_entities;
        Array<Entity> statics = level.static_entities;
        Array<Entity> savedEntities = persisted(entities);
        Array<Entity> savedNonCollidable = persisted(nonCollidable);
        Array<Entity> savedStatics = persisted(statics);
        byte[] raw;
        try {
            level.entities = savedEntities;
            level.non_collidable_entities = savedNonCollidable;
            level.static_entities = savedStatics;
            persistTileMaterials(level);
            raw = KryoSerializer.toBytes(level);
        }
        finally {
            level.entities = entities;
            level.non_collidable_entities = nonCollidable;
            level.static_entities = statics;
        }
        if(raw == null) throw new IllegalStateException("Native floor could not be serialized.");
        // A stray reference (an effect's owner, say) can pull an object into the graph that does
        // not load back, and the level reader drops the rest of an entity list silently. Never
        // store a checkpoint that would not resume complete.
        Level check;
        try { check = KryoSerializer.loadLevel(raw); }
        catch(RuntimeException unreadable) {
            throw new IllegalStateException("Native floor checkpoint does not load back.", unreadable);
        }
        if(check == null || !sameSize(check.entities, savedEntities)
                || !sameSize(check.non_collidable_entities, savedNonCollidable)
                || !sameSize(check.static_entities, savedStatics)) {
            throw new IllegalStateException("Native floor checkpoint does not load back complete.");
        }
        byte[] compressed = deflate(raw);
        if(compressed.length > MAX_BYTES) {
            throw new IllegalStateException("Native floor checkpoint exceeds " + MAX_BYTES + " bytes.");
        }
        return compressed;
    }

    /** Loads a checkpoint; caller marks it restored so the Campaign starts it like a saved level. */
    public static Level restore(byte[] checkpoint) {
        if(checkpoint == null || checkpoint.length == 0 || checkpoint.length > MAX_BYTES) {
            throw new IllegalArgumentException("Native floor checkpoint is missing or outside bounds.");
        }
        Level level = KryoSerializer.loadLevel(inflate(checkpoint));
        if(level == null || level.tiles == null || level.entities == null) {
            throw new IllegalStateException("Native floor checkpoint could not be loaded.");
        }
        level.restoredCampaignFloor = true;
        return level;
    }

    private static Array<Entity> persisted(Array<Entity> live) {
        Array<Entity> kept = new Array<Entity>(live == null ? 0 : live.size);
        if(live == null) return kept;
        for(int index = 0; index < live.size; index++) {
            Entity entity = live.get(index);
            if(entity == null || !entity.persists || !entity.isActive
                    || entity.nativePresentationReplica || entity instanceof Player) continue;
            kept.add(entity);
        }
        return kept;
    }

    private static boolean sameSize(Array<Entity> loaded, Array<Entity> saved) {
        return loaded != null && loaded.size == saved.size;
    }

    private static void persistTileMaterials(Level level) {
        if(level.tiles == null) return;
        if(level.tileMaterials == null || level.tileMaterials.length != level.tiles.length) {
            level.tileMaterials = new com.interrupt.dungeoneer.tiles.TileMaterials[level.tiles.length];
        }
        for(int index = 0; index < level.tiles.length; index++) {
            level.tileMaterials[index] = level.tiles[index] == null
                    ? null : level.tiles[index].materials;
        }
    }

    private static byte[] deflate(byte[] raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length / 4 + 64);
        try(DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(raw);
        }
        catch(IOException ex) {
            throw new IllegalStateException("Native floor checkpoint could not be compressed.", ex);
        }
        return bytes.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(compressed.length * 4);
        byte[] buffer = new byte[64 * 1024];
        try(InputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            for(int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if(raw.size() + read > MAX_RAW_BYTES) {
                    throw new IllegalStateException("Native floor checkpoint expands beyond bounds.");
                }
                raw.write(buffer, 0, read);
            }
        }
        catch(IOException ex) {
            throw new IllegalStateException("Native floor checkpoint is corrupt.", ex);
        }
        return raw.toByteArray();
    }
}
