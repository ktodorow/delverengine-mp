package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.multiplayer.items.AuthoritativeItemWorld;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Host-only economy operations available to the render-thread native bridge. */
public interface EconomyHost {
    AuthoritativeEconomy getEconomy();

    AuthoritativeItemWorld getItemWorld();

    void publishEconomy();

    void publishShopOpening(ParticipantId participant, ShopOpening opening);
}
