package com.interrupt.dungeoneer.multiplayer.items;

import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.multiplayer.participant.ParticipantId;

/** Host: hands the screen-only part of a trigger a client used to that client's own screen. */
public interface TriggerPresentationListener {
    void deliver(Trigger trigger, ParticipantId activator, String value);
}
