package com.doubleangels.redact.stats;

import androidx.annotation.NonNull;

/** The counts in {@link StatsStore} at one moment. */
public final class StatsSnapshot {

    public final long imagesCleaned;
    public final long videosCleaned;
    public final long fieldsRemoved;
    /** Files that carried a location. */
    public final long locationsRemoved;
    /** Files that carried when they were taken or made. */
    public final long datesRemoved;
    /** Files that carried the camera, phone or lens that made them. */
    public final long devicesRemoved;
    /** Files that carried a person's name. */
    public final long namesRemoved;
    /** Files cleaned because another app shared them into Redact. */
    public final long sharedIn;
    /** Share sheets opened with cleaned or converted files. */
    public final long shares;
    public final long converted;
    /** When counting started (epoch ms), or 0 when nothing has been counted. */
    public final long trackingSince;
    /** The day the chart starts on (epoch day): {@link StatsStore#HISTORY_DAYS} - 1 days ago. */
    public final long firstDay;
    /** Files cleaned on each of the last {@link StatsStore#HISTORY_DAYS} days, oldest first. */
    @NonNull
    private final long[] dailyCleans;

    StatsSnapshot(long imagesCleaned, long videosCleaned, long fieldsRemoved, long locationsRemoved,
                  long datesRemoved, long devicesRemoved, long namesRemoved, long sharedIn,
                  long shares, long converted, long trackingSince, long firstDay,
                  @NonNull long[] dailyCleans) {
        this.imagesCleaned = imagesCleaned;
        this.videosCleaned = videosCleaned;
        this.fieldsRemoved = fieldsRemoved;
        this.locationsRemoved = locationsRemoved;
        this.datesRemoved = datesRemoved;
        this.devicesRemoved = devicesRemoved;
        this.namesRemoved = namesRemoved;
        this.sharedIn = sharedIn;
        this.shares = shares;
        this.converted = converted;
        this.trackingSince = trackingSince;
        this.firstDay = firstDay;
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

    /**
     * The share of cleaned files that {@code count} of them is, from 0 to 1, or 0 when nothing has
     * been cleaned.
     */
    public double shareOfFiles(long count) {
        long files = filesCleaned();
        return files <= 0 ? 0 : Math.min(1, (double) Math.max(0, count) / files);
    }

    public boolean isEmpty() {
        return trackingSince == 0;
    }
}
