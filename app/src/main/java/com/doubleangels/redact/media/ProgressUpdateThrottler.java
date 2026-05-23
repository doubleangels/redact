package com.doubleangels.redact.media;

/**
 * Limits how often UI progress callbacks run (e.g. during video transcode).
 */
public final class ProgressUpdateThrottler {

    public static final long DEFAULT_INTERVAL_MS = 300L;

    private final long intervalMs;
    private long lastPostMs;

    public ProgressUpdateThrottler() {
        this(DEFAULT_INTERVAL_MS);
    }

    public ProgressUpdateThrottler(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** Runs {@code action} if at least {@link #intervalMs} has elapsed since the last run. */
    public void maybeRun(Runnable action) {
        long now = System.currentTimeMillis();
        if (now - lastPostMs >= intervalMs) {
            lastPostMs = now;
            action.run();
        }
    }

    /** Always runs and resets the throttle timer. */
    public void forceRun(Runnable action) {
        lastPostMs = System.currentTimeMillis();
        action.run();
    }
}
