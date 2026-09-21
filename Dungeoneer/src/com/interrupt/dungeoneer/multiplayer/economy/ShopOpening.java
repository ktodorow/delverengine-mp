package com.interrupt.dungeoneer.multiplayer.economy;

import java.nio.charset.StandardCharsets;

/** Targeted live cue: only the activating Participant sees the native dialogue and shop. */
public final class ShopOpening {
    public static final int MAX_DIALOGUE_FILE_BYTES = 128;

    public final long shopId, generation;
    public final String dialogueFile;

    public ShopOpening(long shopId, long generation, String dialogueFile) {
        if(shopId < 1L || generation < 1L || dialogueFile == null
                || dialogueFile.getBytes(StandardCharsets.UTF_8).length > MAX_DIALOGUE_FILE_BYTES) {
            throw new IllegalArgumentException("Invalid shop opening.");
        }
        this.shopId = shopId;
        this.generation = generation;
        this.dialogueFile = dialogueFile;
    }
}
