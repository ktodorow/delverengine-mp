package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.multiplayer.items.ItemProperties;
import com.interrupt.dungeoneer.multiplayer.items.PhysicalItemState;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;
import java.nio.charset.StandardCharsets;

/** One Host-authoritative shop offer. Items are local templates plus bounded rolls. */
public final class ShopEntryState {
    public static final int MAX_ENTRY_ID = 1000000;
    public static final int MAX_ACHIEVEMENT_BYTES = 64;

    public final long shopId, generation, revision;
    public final int entryId;
    public final String templateId;
    public final ItemProperties properties;
    public final int cost;
    public final boolean useOnBuy, sold;
    /** Native achievement granted to the accepted buyer only; null when none. */
    public final String achievement;
    /** Null for shared stock; set for personal soulbound offers. */
    public final ParticipantId restrictedTo;

    public ShopEntryState(long shopId, long generation, long revision, int entryId,
            String templateId, ItemProperties properties, int cost, boolean useOnBuy,
            boolean sold, ParticipantId restrictedTo) {
        this(shopId, generation, revision, entryId, templateId, properties, cost, useOnBuy,
                sold, restrictedTo, null);
    }

    public ShopEntryState(long shopId, long generation, long revision, int entryId,
            String templateId, ItemProperties properties, int cost, boolean useOnBuy,
            boolean sold, ParticipantId restrictedTo, String achievement) {
        if(shopId < 1L || generation < 1L || revision < 1L || entryId < 1 || entryId > MAX_ENTRY_ID
                || templateId == null || templateId.isEmpty()
                || templateId.getBytes(StandardCharsets.UTF_8).length > PhysicalItemState.MAX_TEMPLATE_BYTES
                || properties == null || cost < 0 || cost > ParticipantProgress.MAX_GOLD
                || (achievement != null && achievement.getBytes(StandardCharsets.UTF_8).length
                        > MAX_ACHIEVEMENT_BYTES)) {
            throw new IllegalArgumentException("Invalid shop entry.");
        }
        this.shopId = shopId;
        this.generation = generation;
        this.revision = revision;
        this.entryId = entryId;
        this.templateId = templateId;
        this.properties = properties;
        this.cost = cost;
        this.useOnBuy = useOnBuy;
        this.sold = sold;
        this.restrictedTo = restrictedTo;
        this.achievement = achievement == null || achievement.isEmpty() ? null : achievement;
    }

    public boolean offeredTo(ParticipantId participant) {
        return restrictedTo == null || restrictedTo.equals(participant);
    }

    ShopEntryState settled(long nextRevision) {
        return new ShopEntryState(shopId, generation, nextRevision, entryId, templateId,
                properties, cost, useOnBuy, true, restrictedTo, achievement);
    }
}
