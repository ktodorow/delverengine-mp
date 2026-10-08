package com.interrupt.dungeoneer.multiplayer.floor;

import com.interrupt.dungeoneer.multiplayer.economy.ParticipantProgress;
import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Native starter roll detached from Player/Level before an atomic travel save. */
public final class FreshCharacter {
    public final ParticipantProgress progress;
    public final List<Starter> kit;
    public FreshCharacter(ParticipantProgress progress, List<Starter> kit) {
        if(progress == null || progress.gold != 0 || progress.experience != 0 || progress.level != 1
                || progress.pendingStatChoices != 0 || kit == null || kit.size() > progress.inventorySize)
            throw new IllegalArgumentException("Fresh Return requires a new native character.");
        this.progress = progress;
        this.kit = Collections.unmodifiableList(new ArrayList<>(kit));
    }
    public static final class Starter {
        public final String templateId, equipmentSlot;
        public final ItemProperties properties;
        public Starter(String templateId, ItemProperties properties, String equipmentSlot) {
            if(templateId == null || templateId.isEmpty() || properties == null || equipmentSlot == null)
                throw new IllegalArgumentException("Native starter definition is incomplete.");
            this.templateId = templateId; this.properties = properties; this.equipmentSlot = equipmentSlot;
        }
    }
}
