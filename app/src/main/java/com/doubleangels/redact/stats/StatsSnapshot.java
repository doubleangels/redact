package com.doubleangels.redact.stats;

import androidx.annotation.NonNull;

/** The counts in {@link StatsStore} at one moment. */
public final class StatsSnapshot {

    public final long imagesCleaned;
    public final long videosCleaned;
    public final long fieldsRemoved;
    public final long locationsRemoved;
    /** Files cleaned because another app shared them into Redact. */
    public final long sharedIn;
    /** Share sheets opened with cleaned or converted files. */
    public final long shares;
    public final long converted;
    /** When counting started (epoch ms), or 0 when nothing has been counted. */
    public final long trackingSince;
    /** Files cleaned on each of the last {@link StatsStore#HISTORY_DAYS} days, oldest first. */
    @NonNull
    private final long[] dailyCleans;

    StatsSnapshot(long imagesCleaned, long videosCleaned, long fieldsRemoved, long locationsRemoved,
                  long sharedIn, long shares, long converted, long trackingSince,
                  @NonNull long[] dailyCleans) {
        this.imagesCleaned = imagesCleaned;
        this.videosCleaned = videosCleaned;
        this.fieldsRemoved = fieldsRemoved;
        this.locationsRemoved = locationsRemoved;
        this.sharedIn = sharedIn;
        this.shares = shares;
        this.converted = converted;
        this.trackingSince = trackingSince;
        this.dailyCleans = dailyCleans.clone();
    }

    public long filesCleaned() {
        return imagesCleaned + videosCleaned;
    }

    @NonNull
    public long[] dailyCleans() {
        return dailyCleans.clone();
    }

    public long cleanedInHistory() {
        long sum = 0;
        for (long d : dailyCleans) {
            sum += d;
        }
        return sum;
    }

    public boolean isEmpty() {
        return trackingSince == 0;
    }
}
