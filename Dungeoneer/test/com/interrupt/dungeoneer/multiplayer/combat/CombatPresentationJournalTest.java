package com.interrupt.dungeoneer.multiplayer.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CombatPresentationJournalTest {
    @Test
    public void rejectsDuplicateAndStaleSequencesAndKeepsBoundedHistory() {
        CombatPresentationJournal journal = new CombatPresentationJournal(2);

        assertTrue(journal.add(event(1L)));
        assertTrue(journal.add(event(2L)));
        assertFalse(journal.add(event(2L)));
        assertFalse(journal.add(event(1L)));
        assertTrue(journal.add(event(4L)));

        assertEquals(2, journal.getEvents().size());
        assertEquals(2L, journal.getEvents().get(0).getSequence());
        assertEquals(4L, journal.getEvents().get(1).getSequence());
    }

    private CombatPresentationEvent event(long sequence) {
        return new CombatPresentationEvent(sequence, sequence,
                "participant:campaign-slot-1",
                AuthoritativeCombatEncounter.SHARED_MONSTER_ID, CombatAction.MELEE,
                1f, 1f, 0.5f, 2f, 1f, 0.5f, true);
    }
}
