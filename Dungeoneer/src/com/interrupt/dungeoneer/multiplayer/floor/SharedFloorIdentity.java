package com.interrupt.dungeoneer.multiplayer.floor;

import com.interrupt.dungeoneer.entities.Entity;

import java.util.regex.Pattern;

/** Names one native floor entity identically on every peer that built the same Shared Floor. */
public final class SharedFloorIdentity {
    // Group.makeIdsUnique prefixes child ids with a per-process random UUID.
    private static final Pattern GENERATED_ENTITY_ID_PREFIX =
            Pattern.compile("^(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}_)+");

    private SharedFloorIdentity() { }

    /** Editor id without per-peer random Group prefixes; empty when the entity has none. */
    public static String stableEntityId(Entity entity) {
        if(entity == null || entity.id == null) return "";
        return GENERATED_ENTITY_ID_PREFIX.matcher(entity.id).replaceFirst("");
    }

    /** Class, stable id and position to 1/100 tile. */
    public static String placementKey(Entity entity) {
        return entity.getClass().getName() + ":" + stableEntityId(entity)
                + ":" + Math.round(entity.x * 100f)
                + ":" + Math.round(entity.y * 100f) + ":" + Math.round(entity.z * 100f);
    }
}
