package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Host: hands the screen-only part of a trigger a client used to that client's own screen. */
public interface TriggerPresentationListener {
    void deliver(Entity trigger, ParticipantId activator, String value);
}
