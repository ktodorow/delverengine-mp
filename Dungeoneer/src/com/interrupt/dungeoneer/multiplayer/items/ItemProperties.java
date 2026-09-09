package com.interrupt.dungeoneer.multiplayer.items;

import java.nio.charset.StandardCharsets;

/** Mutable item rolls, separate from read-only local content templates. */
public final class ItemProperties {
    public static final ItemProperties DEFAULT = new ItemProperties(2, 1, "", "", 1);
    /** -1 denotes a non-potion; 0..6 retain native v1.08 PotionType order. */
    public final int potionType;
    public final int condition, level, quantity;
    public final String suffix, prefix;

    public ItemProperties(int condition, int level, String suffix, String prefix, int quantity) {
        this(condition, level, suffix, prefix, quantity, -1);
    }

    public ItemProperties(int condition, int level, String suffix, String prefix, int quantity,
            int potionType) {
        if(potionType < -1 || potionType > 6 || condition < 0 || condition > 4 || level < 0 || level > 10000
                || quantity < 0 || quantity > 1000000 || !valid(suffix) || !valid(prefix)) {
            throw new IllegalArgumentException("Invalid item properties.");
        }
        this.potionType = potionType;
        this.condition = condition;
        this.level = level;
        this.suffix = suffix;
        this.prefix = prefix;
        this.quantity = quantity;
    }

    private static boolean valid(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= 128;
    }
}
