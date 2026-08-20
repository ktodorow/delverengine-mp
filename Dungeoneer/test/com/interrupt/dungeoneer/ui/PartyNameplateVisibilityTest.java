package com.interrupt.dungeoneer.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartyNameplateVisibilityTest {
    @Test
    public void hidesNameplatesBeyondMaximumDistance() {
        assertTrue(PartyNameplateVisibility.isWithinRange(12f));
        assertFalse(PartyNameplateVisibility.isWithinRange(12.01f));
    }
}
