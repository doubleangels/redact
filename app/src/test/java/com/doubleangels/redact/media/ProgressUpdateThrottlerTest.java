package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ProgressUpdateThrottlerTest {

    @Test
    public void maybeRun_throttlesWithinInterval() throws InterruptedException {
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler(50);
        AtomicInteger count = new AtomicInteger();

        throttler.maybeRun(count::incrementAndGet);
        throttler.maybeRun(count::incrementAndGet);
        assertEquals(1, count.get());

        Thread.sleep(60);
        throttler.maybeRun(count::incrementAndGet);
        assertEquals(2, count.get());
    }

    @Test
    public void forceRun_alwaysRuns() {
        ProgressUpdateThrottler throttler = new ProgressUpdateThrottler(10_000);
        AtomicInteger count = new AtomicInteger();

        throttler.maybeRun(count::incrementAndGet);
        throttler.forceRun(count::incrementAndGet);
        assertEquals(2, count.get());
    }

    @Test
    public void defaultInterval() {
        assertEquals(300L, ProgressUpdateThrottler.DEFAULT_INTERVAL_MS);
        new ProgressUpdateThrottler();
    }
}
