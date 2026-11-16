package com.skribbl.client.ui.canvas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrokeThrottlerTest {

    @Test
    void shouldAcceptFirstPointAndFilterPointsCloserThanThreshold() {
        StrokeThrottler throttler = new StrokeThrottler(2.0);

        assertTrue(throttler.shouldSend(100.0, 100.0));
        assertFalse(throttler.shouldSend(101.0, 101.0));
        assertTrue(throttler.shouldSend(103.0, 100.0));

        throttler.reset();
        assertTrue(throttler.shouldSend(103.5, 100.5));
    }
}