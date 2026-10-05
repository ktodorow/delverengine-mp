package com.interrupt.dungeoneer.multiplayer.items;

import java.nio.charset.StandardCharsets;

/** Screen-only part of a Host trigger chain a client started by using a shared world object. */
public final class TriggerPresentation {
    public static final int MAX_VALUE_BYTES = 128;
    public final long objectId;
    public final String value;

    public TriggerPresentation(long objectId, String value) {
        if(objectId <= 0L || value == null
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Invalid trigger presentation.");
        }
        this.objectId = objectId;
        this.value = value;
    }
}
