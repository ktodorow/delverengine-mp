package com.interrupt.dungeoneer.multiplayer.economy;

import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Host combat proxies read accepted Campaign Slot stats instead of default Player values. */
public interface ParticipantProgressResolver {
    void synchronizeProgress(ParticipantId participant, Player player);
}
