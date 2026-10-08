package com.interrupt.dungeoneer.multiplayer.knowledge;

import com.badlogic.gdx.utils.Array;
import com.interrupt.dungeoneer.entities.items.Potion;
import com.interrupt.dungeoneer.multiplayer.items.DirectConnectItemController;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared physical appearance/effect facts. Contains no identification knowledge. */
public final class PotionMapping {
    public static final int MAX_APPEARANCES = 64;
    public static final int MAX_BYTES = 4 + MAX_APPEARANCES * 70;
    public final Map<String, Integer> effects;

    public PotionMapping(Map<String, Integer> effects) {
        if(effects == null || effects.size() > MAX_APPEARANCES) throw new IllegalArgumentException("Too many potion appearances.");
        for(Map.Entry<String, Integer> entry : effects.entrySet()) {
            if(entry.getKey() == null || !entry.getKey().matches("[a-f0-9]{64}") || entry.getValue() == null
                    || entry.getValue() < 0 || entry.getValue() >= Potion.PotionType.values().length)
                throw new IllegalArgumentException("Invalid potion appearance/effect.");
        }
        this.effects = Collections.unmodifiableMap(new LinkedHashMap<>(effects));
    }

    public static PotionMapping empty() { return new PotionMapping(Collections.<String, Integer>emptyMap()); }

    /** Legacy saves can prove saved physical facts, never absent personal discoveries. */
    public static PotionMapping fromItems(List<PhysicalItemState> items) {
        Map<String, Integer> effects = new LinkedHashMap<>();
        for(PhysicalItemState item : items) {
            if(item.properties.potionType < 0 || !item.templateId.matches("[a-f0-9]{64}")) continue;
            Integer old = effects.put(item.templateId, item.properties.potionType);
            if(old != null && old != item.properties.potionType)
                throw new IllegalArgumentException("Saved potion appearance has conflicting physical effects.");
        }
        return new PotionMapping(effects);
    }

    /** Native shuffle already consumed its original RNG draws. Keep known effects and draw order. */
    public PotionMapping restore(Array<Potion> shuffled) {
        boolean[] used = new boolean[Potion.PotionType.values().length];
        for(Potion potion : shuffled) {
            Integer type = effects.get(DirectConnectItemController.templateKey(potion));
            if(type != null) { potion.potionType = Potion.PotionType.values()[type]; used[type] = true; }
        }
        for(Potion potion : shuffled) {
            if(effects.containsKey(DirectConnectItemController.templateKey(potion))) continue;
            int type = potion.potionType.ordinal();
            if(used[type]) for(int candidate = 0; candidate < used.length; candidate++) if(!used[candidate]) { type = candidate; break; }
            potion.potionType = Potion.PotionType.values()[type]; used[type] = true;
        }
        shuffled.sort((left, right) -> Integer.compare(left.potionType.ordinal(), right.potionType.ordinal()));
        Map<String, Integer> complete = new LinkedHashMap<>(effects);
        for(Potion potion : shuffled) complete.put(DirectConnectItemController.templateKey(potion), potion.potionType.ordinal());
        return new PotionMapping(complete);
    }

    public void writeTo(DataOutputStream output) throws IOException {
        output.writeInt(effects.size());
        for(Map.Entry<String, Integer> entry : effects.entrySet()) { output.writeUTF(entry.getKey()); output.writeInt(entry.getValue()); }
    }

    public static PotionMapping readFrom(DataInputStream input) throws IOException {
        int count = input.readInt();
        if(count < 0 || count > MAX_APPEARANCES) throw new IllegalArgumentException("Too many potion appearances.");
        Map<String, Integer> effects = new LinkedHashMap<>();
        for(int index = 0; index < count; index++) {
            if(effects.put(input.readUTF(), input.readInt()) != null) throw new IllegalArgumentException("Duplicate potion appearance.");
        }
        return new PotionMapping(effects);
    }
}
