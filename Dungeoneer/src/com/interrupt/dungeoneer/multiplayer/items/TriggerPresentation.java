package com.interrupt.dungeoneer.multiplayer.items;

import java.nio.charset.StandardCharsets;

/** One Host trigger cue: activator UI or shared positional sound. Never executes gameplay. */
public final class TriggerPresentation {
    public static final int MAX_VALUE_BYTES = 128;
    public final long objectId;
    public final String value;
    public final long sequence;
    public final long generation;
    public final boolean sharedSound;

    public TriggerPresentation(long objectId, String value) {
        this(objectId, value, 1L, 1L, false);
    }

    public TriggerPresentation(long objectId, String value, long sequence, long generation,
            boolean sharedSound) {
        if(sequence <= 0 || generation <= 0) throw new IllegalArgumentException("Invalid trigger cue identity.");
        this.sequence = sequence; this.generation = generation; this.sharedSound = sharedSound;
        if(objectId <= 0L || value == null
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Invalid trigger presentation.");
        }
        this.objectId = objectId;
        this.value = value;
    }
}
