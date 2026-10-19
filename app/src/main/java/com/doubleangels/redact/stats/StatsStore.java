package com.doubleangels.redact.stats;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.metadata.CleanStats;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Counts what Redact has done on this device, for Settings › Statistics.
 *
 * <p>Kept in its own preferences file so Reset All Settings leaves it alone and Clear Statistics
 * leaves the settings alone. Nothing here is ever sent anywhere: backups exclude shared
 * preferences, and none of it goes to crash reports.
 */
public final class StatsStore {

    /** Days of history kept for the activity chart, today included. */
    public static final int HISTORY_DAYS = 30;

    @VisibleForTesting
    static final String PREFS_NAME = "redact_stats";

    private static final String KEY_IMAGES_CLEANED = "files_cleaned_images";
    private static final String KEY_VIDEOS_CLEANED = "files_cleaned_videos";
    private static final String KEY_FIELDS_REMOVED = "fields_removed";
    private static final String KEY_LOCATIONS_REMOVED = "locations_removed";
    private static final String KEY_SHARED_IN = "files_shared_in";
    private static final String KEY_SHARES = "shares";
    private static final String KEY_CONVERTED = "files_converted";
    private static final String KEY_TRACKING_SINCE = "tracking_since";
    private static final String DAY_PREFIX = "day_";

    private static Supplier<LocalDate> today = LocalDate::now;
    private static Supplier<Long> now = System::currentTimeMillis;

    private StatsStore() {}

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Counts one file cleaned on the Clean tab ({@code viaShare} false) or shared into Redact. */
    public static synchronized void recordClean(@NonNull Context context, @NonNull CleanStats stats,
                                                boolean isVideo, boolean viaShare) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor e = p.edit();
        startTrackingIfNeeded(p, e);
        add(p, e, isVideo ? KEY_VIDEOS_CLEANED : KEY_IMAGES_CLEANED, 1);
        add(p, e, KEY_FIELDS_REMOVED, Math.max(0, stats.removedFields));
        if (stats.hadLocation) {
            add(p, e, KEY_LOCATIONS_REMOVED, 1);
        }
        if (viaShare) {
            add(p, e, KEY_SHARED_IN, 1);
        }
        long day = today.get().toEpochDay();
        add(p, e, DAY_PREFIX + day, 1);
        pruneDays(p, e, day);
        e.apply();
    }

    /** Counts one share sheet opened with cleaned or converted files. */
    public static synchronized void recordShare(@NonNull Context context) {
        increment(context, KEY_SHARES);
    }

    /** Counts one file converted on the Convert tab. */
    public static synchronized void recordConverted(@NonNull Context context) {
        increment(context, KEY_CONVERTED);
    }

    @NonNull
    public static synchronized StatsSnapshot snapshot(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        long[] daily = new long[HISTORY_DAYS];
        long todayEpoch = today.get().toEpochDay();
        for (int i = 0; i < HISTORY_DAYS; i++) {
            daily[i] = p.getLong(DAY_PREFIX + (todayEpoch - (HISTORY_DAYS - 1 - i)), 0);
        }
        return new StatsSnapshot(
                p.getLong(KEY_IMAGES_CLEANED, 0),
                p.getLong(KEY_VIDEOS_CLEANED, 0),
                p.getLong(KEY_FIELDS_REMOVED, 0),
                p.getLong(KEY_LOCATIONS_REMOVED, 0),
                p.getLong(KEY_SHARED_IN, 0),
                p.getLong(KEY_SHARES, 0),
                p.getLong(KEY_CONVERTED, 0),
                p.getLong(KEY_TRACKING_SINCE, 0),
                daily);
    }

    /** Sets every count back to zero; the next thing recorded starts tracking again. */
    public static synchronized void clear(@NonNull Context context) {
        prefs(context).edit().clear().apply();
    }

    private static void increment(@NonNull Context context, @NonNull String key) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor e = p.edit();
        startTrackingIfNeeded(p, e);
        add(p, e, key, 1);
        e.apply();
    }

    private static void startTrackingIfNeeded(@NonNull SharedPreferences p,
                                              @NonNull SharedPreferences.Editor e) {
        if (!p.contains(KEY_TRACKING_SINCE)) {
            e.putLong(KEY_TRACKING_SINCE, now.get());
        }
    }

    private static void add(@NonNull SharedPreferences p, @NonNull SharedPreferences.Editor e,
                            @NonNull String key, long amount) {
        long current = p.getLong(key, 0);
        // Saturates rather than wrapping round to a negative count.
        e.putLong(key, current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount);
    }

    /** Drops day counts that have fallen out of the chart, and any dated in the future. */
    private static void pruneDays(@NonNull SharedPreferences p, @NonNull SharedPreferences.Editor e,
                                  long todayEpoch) {
        long oldest = todayEpoch - (HISTORY_DAYS - 1);
        for (String key : p.getAll().keySet()) {
            if (!key.startsWith(DAY_PREFIX)) {
                continue;
            }
            try {
                long day = Long.parseLong(key.substring(DAY_PREFIX.length()));
                if (day < oldest || day > todayEpoch) {
                    e.remove(key);
                }
            } catch (NumberFormatException ignored) {
                e.remove(key);
            }
        }
    }

    @VisibleForTesting
    public static void setClockForTests(@NonNull Supplier<LocalDate> date, @NonNull Supplier<Long> millis) {
        today = date;
        now = millis;
    }

    @VisibleForTesting
    public static void resetClockForTests() {
        today = LocalDate::now;
        now = System::currentTimeMillis;
    }
}
