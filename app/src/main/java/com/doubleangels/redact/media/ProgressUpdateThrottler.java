package com.doubleangels.redact.media;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Limits how often UI progress callbacks run (e.g. during video transcode).
 */
public final class ProgressUpdateThrottler {

    public static final long DEFAULT_INTERVAL_MS = 300L;

    /** No run yet: the first call always runs, whatever the clock reads. */
    private static final long NEVER = Long.MIN_VALUE;

    private final long intervalMs;
    private final LongSupplier clockMs;
    private final AtomicLong lastPostMs = new AtomicLong(NEVER);

    public ProgressUpdateThrottler() {
        this(DEFAULT_INTERVAL_MS);
    }

    public ProgressUpdateThrottler(long intervalMs) {
        // Monotonic, so a change to the phone's time can't hold progress back or let it flood.
        this(intervalMs, () -> System.nanoTime() / 1_000_000L);
    }

    @VisibleForTesting
    ProgressUpdateThrottler(long intervalMs, @NonNull LongSupplier clockMs) {
        this.intervalMs = intervalMs;
        this.clockMs = clockMs;
    }

    /** Runs {@code action} if at least {@link #intervalMs} has elapsed since the last run. */
    public void maybeRun(Runnable action) {
        long now = clockMs.getAsLong();
        long prev = lastPostMs.get();
        if ((prev == NEVER || now - prev >= intervalMs) && lastPostMs.compareAndSet(prev, now)) {
            action.run();
        }
    }

    /** Always runs and resets the throttle timer. */
    public void forceRun(Runnable action) {
        lastPostMs.set(clockMs.getAsLong());
        action.run();
    }
}
