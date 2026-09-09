package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import java.nio.charset.StandardCharsets;

/** Immutable ownership/position state. Null owner means one physical world item. */
public final class PhysicalItemState {
    public static final int MAX_TEMPLATE_BYTES = 128;
    public final long entityId;
    public final long revision;
    public final String templateId;
    public final ParticipantId owner;
    public final ItemProperties properties;
    public final boolean consumed;
    public final String equipmentSlot;
    public final float x, y, z;

    public PhysicalItemState(long entityId, long revision, String templateId,
            ParticipantId owner, float x, float y, float z) {
        this(entityId, revision, templateId, owner, x, y, z, ItemProperties.DEFAULT);
    }

    public PhysicalItemState(long entityId, long revision, String templateId,
            ParticipantId owner, float x, float y, float z, ItemProperties properties) {
        this(entityId, revision, templateId, owner, x, y, z, properties, false);
    }

    public PhysicalItemState(long entityId, long revision, String templateId,
            ParticipantId owner, float x, float y, float z, ItemProperties properties,
            boolean consumed) {
        this(entityId, revision, templateId, owner, x, y, z, properties, consumed, "");
    }

    public PhysicalItemState(long entityId, long revision, String templateId,
            ParticipantId owner, float x, float y, float z, ItemProperties properties,
            boolean consumed, String equipmentSlot) {
        if(equipmentSlot == null || equipmentSlot.getBytes(StandardCharsets.UTF_8).length > 32
                || (!equipmentSlot.isEmpty() && (owner == null || consumed))) {
            throw new IllegalArgumentException("Invalid equipment location.");
        }
        this.equipmentSlot = equipmentSlot;
        if(consumed && owner != null) throw new IllegalArgumentException("Consumed item cannot have owner.");
        this.consumed = consumed;
        if(properties == null) throw new IllegalArgumentException("Item properties are required.");
        this.properties = properties;
        if(entityId < 1L || revision < 1L || templateId == null || templateId.isEmpty()
                || templateId.getBytes(StandardCharsets.UTF_8).length > MAX_TEMPLATE_BYTES
                || !finite(x) || !finite(y) || !finite(z)) {
            throw new IllegalArgumentException("Invalid physical item state.");
        }
        this.entityId = entityId;
        this.revision = revision;
        this.templateId = templateId;
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
