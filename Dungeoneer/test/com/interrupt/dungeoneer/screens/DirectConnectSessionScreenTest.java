package com.interrupt.dungeoneer.screens;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectConnectSessionScreenTest {
    @Test
    public void textBoundsStayInsideBottomLeftOriginViewport() {
        assertInsideViewport(320f, 0.9f);
        assertInsideViewport(640f, 0.8f);
        assertInsideViewport(1920f, 0.9f);
    }

    @Test
    public void hostApprovalControlsStayAboveSmallWindowsViewportBottom() {
        assertTrue(DirectConnectSessionScreen.lowestHostTextBaseline(320f) >= 16f);
    }

    private static void assertInsideViewport(float viewportWidth, float textWidthFraction) {
        float left = DirectConnectSessionScreen.textLeft(viewportWidth, textWidthFraction);
        float right = left + viewportWidth * textWidthFraction;

        assertTrue("Text starts outside viewport.", left >= 0f);
        assertTrue("Text ends outside viewport.", right <= viewportWidth);
    }
}
