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
    public void maybeRun_executesAgainAfterIntervalElapses() throws InterruptedException {
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler(50L);
        AtomicInteger runs = new AtomicInteger();
        throttler.maybeRun(runs::incrementAndGet);
        Thread.sleep(80L);
        throttler.maybeRun(runs::incrementAndGet);
        assertEquals(2, runs.get());
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