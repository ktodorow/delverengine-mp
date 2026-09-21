package com.interrupt.dungeoneer.multiplayer.floor;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.Breakable;
import com.interrupt.dungeoneer.entities.Door;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Spikes;
import com.interrupt.dungeoneer.entities.triggers.BasicTrigger;
import com.interrupt.dungeoneer.entities.triggers.ButtonModel;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.game.Level;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Bounded summary of one built Shared Floor. Peers that built the same world produce equal
 * fingerprints; Host compares a joining client's fingerprint with its own before play.
 */
public final class SharedFloorFingerprint {
    /** Items are excluded: Host physical item state replaces every client's local floor items. */
    public enum Category {
        WORLD_OBJECTS("world objects"),
        MONSTERS("monsters"),
        TRAPS("traps"),
        SOLID_SCENERY("solid scenery"),
        SCENERY("scenery");

        private final String label;

        Category(String label) { this.label = label; }

        public String getLabel() { return label; }
    }

    public static final int MAX_COUNT = 1000000;
    private static final Category[] CATEGORIES = Category.values();

    private final int[] counts;
    private final long[] digests;

    public SharedFloorFingerprint(int[] counts, long[] digests) {
        if(counts == null || digests == null || counts.length != CATEGORIES.length
                || digests.length != CATEGORIES.length) {
            throw new IllegalArgumentException("Shared floor fingerprint is incomplete.");
        }
        for(int count : counts) {
            if(count < 0 || count > MAX_COUNT) {
                throw new IllegalArgumentException("Shared floor object count is outside bounds.");
            }
        }
        this.counts = counts.clone();
        this.digests = digests.clone();
    }

    public static SharedFloorFingerprint capture(Level level) {
        if(level == null) throw new IllegalArgumentException("Shared floor level is required.");
        List<List<String>> keys = new ArrayList<List<String>>();
        for(int index = 0; index < CATEGORIES.length; index++) keys.add(new ArrayList<String>());
        IdentityHashMap<Entity, Boolean> seen = new IdentityHashMap<Entity, Boolean>();
        collect(level.entities, keys, seen);
        collect(level.non_collidable_entities, keys, seen);
        collect(level.static_entities, keys, seen);
        int[] counts = new int[CATEGORIES.length];
        long[] digests = new long[CATEGORIES.length];
        for(int index = 0; index < CATEGORIES.length; index++) {
            List<String> category = keys.get(index);
            // List placement may legitimately differ; the set of placed objects may not.
            Collections.sort(category);
            counts[index] = Math.min(category.size(), MAX_COUNT);
            digests[index] = digest(category);
        }
        return new SharedFloorFingerprint(counts, digests);
    }

    public int getCount(Category category) { return counts[category.ordinal()]; }

    public long getDigest(Category category) { return digests[category.ordinal()]; }

    /** Differing categories as "label host/local", Host fingerprint as receiver. */
    public String describeDifferences(SharedFloorFingerprint local) {
        StringBuilder result = new StringBuilder();
        for(Category category : CATEGORIES) {
            int index = category.ordinal();
            if(counts[index] == local.counts[index] && digests[index] == local.digests[index]) continue;
            if(result.length() > 0) result.append(", ");
            result.append(category.label).append(' ').append(counts[index]).append('/')
                    .append(local.counts[index]);
            if(counts[index] == local.counts[index]) result.append(" placed differently");
        }
        return result.toString();
    }

    @Override
    public boolean equals(Object other) {
        if(this == other) return true;
        if(!(other instanceof SharedFloorFingerprint)) return false;
        SharedFloorFingerprint fingerprint = (SharedFloorFingerprint)other;
        return Arrays.equals(counts, fingerprint.counts) && Arrays.equals(digests, fingerprint.digests);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(counts) + Arrays.hashCode(digests);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("SharedFloorFingerprint[");
        for(Category category : CATEGORIES) {
            if(category.ordinal() > 0) result.append(", ");
            result.append(category.label).append('=').append(counts[category.ordinal()]);
        }
        return result.append(']').toString();
    }

    private static void collect(Array<Entity> entities, List<List<String>> keys,
            IdentityHashMap<Entity, Boolean> seen) {
        if(entities == null) return;
        for(Entity entity : entities) {
            if(entity == null || entity instanceof Item || seen.put(entity, Boolean.TRUE) != null) continue;
            keys.get(categorize(entity).ordinal()).add(key(entity));
        }
    }

    static Category categorize(Entity entity) {
        // Same families that DirectConnectItemController names as shared world objects.
        if(entity instanceof Door || entity instanceof Breakable || entity instanceof Trigger
                || entity instanceof BasicTrigger || entity instanceof ButtonModel) {
            return Category.WORLD_OBJECTS;
        }
        if(entity instanceof Monster) return Category.MONSTERS;
        if(entity instanceof Spikes) return Category.TRAPS;
        return entity.isSolid ? Category.SOLID_SCENERY : Category.SCENERY;
    }

    private static String key(Entity entity) {
        String key = SharedFloorIdentity.placementKey(entity) + ":" + entity.tex + ":"
                + (entity.spriteAtlas == null ? "" : entity.spriteAtlas);
        if(entity instanceof Monster) key += ":" + ((Monster)entity).name;
        return key;
    }

    private static long digest(List<String> keys) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable.", missing);
        }
        for(String key : keys) {
            sha256.update(key.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte)'\n');
        }
        byte[] hash = sha256.digest();
        long value = 0L;
        for(int index = 0; index < 8; index++) value = (value << 8) | (hash[index] & 0xFFL);
        return value;
    }
}
