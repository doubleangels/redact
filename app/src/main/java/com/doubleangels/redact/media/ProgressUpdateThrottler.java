package com.doubleangels.redact.media;

/**
 * Limits how often UI progress callbacks run (e.g. during video transcode).
 */
public final class ProgressUpdateThrottler {

    public static final long DEFAULT_INTERVAL_MS = 300L;

    private final long intervalMs;
    private final java.util.concurrent.atomic.AtomicLong lastPostMs = new java.util.concurrent.atomic.AtomicLong(0);

    public ProgressUpdateThrottler() {
        this(DEFAULT_INTERVAL_MS);
    }

    public ProgressUpdateThrottler(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** Runs {@code action} if at least {@link #intervalMs} has elapsed since the last run. */
    public void maybeRun(Runnable action) {
        long now = System.currentTimeMillis();
        long prev = lastPostMs.get();
        if (now - prev >= intervalMs) {
            lastPostMs.set(now);
            action.run();
        }
    }

    /** Always runs and resets the throttle timer. */
    public void forceRun(Runnable action) {
        lastPostMs.set(System.currentTimeMillis());
        action.run();
    }
}
