package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ProgressUpdateThrottlerTest {

    @Test
    public void forceRun_alwaysExecutesAndForcesThroughThrottle() {
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler(60_000L);
        AtomicInteger runs = new AtomicInteger();
        throttler.forceRun(runs::incrementAndGet);
        throttler.forceRun(runs::incrementAndGet);
        assertEquals(2, runs.get());
    }

    @Test
    public void maybeRun_skipsCallWithinInterval() {
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler(60_000L);
        AtomicInteger runs = new AtomicInteger();
        throttler.maybeRun(runs::incrementAndGet);
        throttler.maybeRun(runs::incrementAndGet);
        throttler.maybeRun(runs::incrementAndGet);
        assertEquals(1, runs.get());
    }

    @Test
    public void maybeRun_executesAgainAfterIntervalElapses() {
        long[] now = {1_000};
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler(50L, () -> now[0]);
        AtomicInteger runs = new AtomicInteger();
        throttler.maybeRun(runs::incrementAndGet);
        now[0] += 49;
        throttler.maybeRun(runs::incrementAndGet);
        assertEquals("not yet", 1, runs.get());
        now[0] += 1;
        throttler.maybeRun(runs::incrementAndGet);
        assertEquals(2, runs.get());
    }

    /** The first call runs whatever the clock reads, even a negative monotonic time. */
    @Test
    public void theFirstCall_alwaysRuns() {
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler(50L, () -> -5_000L);
        AtomicInteger runs = new AtomicInteger();
        throttler.maybeRun(runs::incrementAndGet);
        assertEquals(1, runs.get());
    }

    @Test
    public void defaultConstructor_usesDefaultInterval() {
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler();
        AtomicInteger runs = new AtomicInteger();
        throttler.maybeRun(runs::incrementAndGet);
        throttler.maybeRun(runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertEquals(300L, ProgressUpdateThrottler.DEFAULT_INTERVAL_MS);
    }
}