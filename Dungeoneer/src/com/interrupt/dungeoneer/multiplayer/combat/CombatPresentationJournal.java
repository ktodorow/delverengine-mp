package com.interrupt.dungeoneer.multiplayer.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded ordered view of reliable combat presentations. */
public final class CombatPresentationJournal {
    private final int capacity;
    private final List<CombatPresentationEvent> events =
            new ArrayList<CombatPresentationEvent>();
    private long lastSequence;

    public CombatPresentationJournal(int capacity) {
        if(capacity < 1) throw new IllegalArgumentException("Journal capacity must be positive.");
        this.capacity = capacity;
    }

    public synchronized boolean add(CombatPresentationEvent event) {
        if(event == null) throw new IllegalArgumentException("Combat presentation cannot be null.");
        if(event.getSequence() <= lastSequence) return false;
        events.add(event);
        lastSequence = event.getSequence();
        while(events.size() > capacity) events.remove(0);
        return true;
    }

    public synchronized List<CombatPresentationEvent> getEvents() {
        return Collections.unmodifiableList(
                new ArrayList<CombatPresentationEvent>(events));
    }
}
