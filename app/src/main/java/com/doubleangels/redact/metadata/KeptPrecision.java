package com.doubleangels.redact.metadata;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How much of a kept location and a kept date a clean writes back: an approximate location is
 * rounded to a tenth of a degree (about 11 km north to south, enough to say which town a photo
 * was taken in but not which street), with its altitude and GPS time left out; a date only
 * keeps the day, at midnight, with its time zone and fractions of a second left out.
 *
 * <p>The cleaner rounds what it keeps ({@link #apply}), and the check after a clean, like the
 * already-clean check, refuses a file whose kept values are finer than the settings allow
 * ({@link #matchesSettings}), so a value that slipped through unrounded fails closed.
 */
public final class KeptPrecision {

    /** Tenths of a degree per degree: the approximate grid. */
    private static final int STEPS_PER_DEGREE = 10;
    /** How far from the grid a value read back may be and still count as on it. */
    private static final double GRID_TOLERANCE = 1e-6;
    /** Seconds between 1904 (MP4's clock) and 1970. */
    private static final long SECONDS_1904_TO_1970 = 2_082_844_800L;
    private static final Pattern EXIF_DATE_TIME = Pattern.compile("\\d{4}:\\d{2}:\\d{2} .*");
    private static final String MIDNIGHT = " 00:00:00";

    private KeptPrecision() {
    }

    /** Whether Keep Location keeps only an approximate location for the current settings. */
    public static boolean approximateLocation(@NonNull Context context) {
        return AppPreferences.isApproximateLocation(context);
    }

    /** Whether Keep Date Taken keeps only the day for the current settings. */
    public static boolean dateOnly(@NonNull Context context) {
        return AppPreferences.isDateOnly(context);
    }

    // ---- locations ------------------------------------------------------------------------------

    /** {@code degrees} rounded to the approximate grid. */
    public static double round(double degrees) {
        return Math.signum(degrees) * Math.round(Math.abs(degrees) * STEPS_PER_DEGREE) / (double) STEPS_PER_DEGREE;
    }

    /** Whether {@code degrees} lies on the approximate grid. */
    public static boolean onGrid(double degrees) {
        double steps = Math.abs(degrees) * STEPS_PER_DEGREE;
        return Math.abs(steps - Math.rint(steps)) < GRID_TOLERANCE * STEPS_PER_DEGREE;
    }

    /** {latitude, longitude} rounded to the approximate grid; null stays null. */
    @Nullable
    public static double[] round(@Nullable double[] coordinates) {
        if (coordinates == null || coordinates.length < 2) {
            return coordinates;
        }
        double[] rounded = coordinates.clone();
        rounded[0] = round(coordinates[0]);
        rounded[1] = round(coordinates[1]);
        return rounded;
    }

    /**
     * An EXIF GPS coordinate ("37/1,46/1,3012/100": degrees, minutes, seconds as rationals) as
     * degrees, or null when it can't be read.
     */
    @Nullable
    @VisibleForTesting
    static Double parseDms(@Nullable String rationals) {
        if (rationals == null) {
            return null;
        }
        String[] parts = rationals.split(",");
        if (parts.length != 3) {
            return null;
        }
        double degrees = 0;
        double scale = 1;
        for (String part : parts) {
            String[] fraction = part.trim().split("/");
            if (fraction.length != 2) {
                return null;
            }
            try {
                double numerator = Double.parseDouble(fraction[0]);
                double denominator = Double.parseDouble(fraction[1]);
                if (denominator == 0 || numerator < 0 || denominator < 0) {
                    return null;
                }
                degrees += numerator / denominator / scale;
            } catch (NumberFormatException e) {
                return null;
            }
            scale *= 60;
        }
        return degrees;
    }

    /** Degrees (not negative) as EXIF rationals on the approximate grid: a tenth is six minutes. */
    @NonNull
    @VisibleForTesting
    static String toDms(double degrees) {
        long tenths = Math.round(Math.abs(degrees) * STEPS_PER_DEGREE);
        long whole = tenths / STEPS_PER_DEGREE;
        long minutes = (tenths % STEPS_PER_DEGREE) * 60 / STEPS_PER_DEGREE;
        return whole + "/1," + minutes + "/1,0/1";
    }

    // ---- dates ----------------------------------------------------------------------------------

    /** An EXIF date and time ("2026:01:17 09:30:12") with its time set to midnight; null when it isn't one. */
    @Nullable
    public static String dateOnly(@Nullable String exifDateTime) {
        if (exifDateTime == null || !EXIF_DATE_TIME.matcher(exifDateTime).matches()) {
            return null;
        }
        return exifDateTime.substring(0, 10) + MIDNIGHT;
    }

    /** Whether an EXIF date and time is at midnight, as {@link #dateOnly(String)} writes it. */
    public static boolean isDateOnly(@Nullable String exifDateTime) {
        return exifDateTime != null && exifDateTime.equals(dateOnly(exifDateTime));
    }

    /**
     * MP4 times ({creation, modification}, seconds since 1904, in UTC) moved to midnight of
     * their day in {@code zone}, the phone's own, which is where most recordings were made.
     */
    @Nullable
    public static long[] dateOnly(@Nullable long[] times, @NonNull ZoneId zone) {
        if (times == null) {
            return null;
        }
        long[] result = new long[times.length];
        for (int i = 0; i < times.length; i++) {
            if (times[i] <= 0) {
                result[i] = times[i];
                continue;
            }
            LocalDate day = Instant.ofEpochSecond(times[i] - SECONDS_1904_TO_1970).atZone(zone).toLocalDate();
            result[i] = day.atStartOfDay(zone).toEpochSecond() + SECONDS_1904_TO_1970;
        }
        return result;
    }

    // ---- images ---------------------------------------------------------------------------------

    /**
     * Rounds the location and date in {@code kept} (EXIF tag to value, as a clean read them from
     * the original) to what the settings keep. Shares and Strict Clean keep neither, so there is
     * nothing to do for them.
     */
    static void apply(@NonNull Context context, @NonNull Map<String, String> kept, boolean forSharing) {
        if (forSharing || AppPreferences.isStrictClean(context)) {
            return;
        }
        if (AppPreferences.isPreserveLocation(context) && approximateLocation(context)) {
            roundCoordinate(kept, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF);
            roundCoordinate(kept, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF);
        }
        if (AppPreferences.isPreserveDateTaken(context) && dateOnly(context)) {
            for (String tag : new String[] {ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME}) {
                String value = kept.get(tag);
                if (value != null) {
                    String day = dateOnly(value);
                    if (day != null) {
                        kept.put(tag, day);
                    } else {
                        kept.remove(tag); // not a date this can cut to the day: not kept at all
                    }
                }
            }
        }
    }

    private static void roundCoordinate(@NonNull Map<String, String> kept, @NonNull String tag, @NonNull String refTag) {
        String value = kept.get(tag);
        if (value == null) {
            return;
        }
        Double degrees = parseDms(value);
        if (degrees == null) {
            // A coordinate that can't be read can't be rounded, so neither it nor its sign is kept.
            kept.remove(tag);
            kept.remove(refTag);
            return;
        }
        kept.put(tag, toDms(degrees));
    }

    /**
     * Whether the location and date {@code exif} holds are no finer than the settings keep: on
     * the approximate grid when Keep Location is approximate, and at midnight when Keep Date
     * Taken keeps the day only. Always true for shares and Strict Clean, which keep neither and
     * whose check refuses the tags themselves.
     */
    static boolean matchesSettings(@NonNull Context context, @NonNull ExifInterface exif, boolean forSharing) {
        if (forSharing || AppPreferences.isStrictClean(context)) {
            return true;
        }
        if (AppPreferences.isPreserveLocation(context) && approximateLocation(context)) {
            for (String tag : new String[] {ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE}) {
                String value = exif.getAttribute(tag);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                Double degrees = parseDms(value);
                if (degrees == null || !onGrid(degrees)) {
                    return false;
                }
            }
        }
        if (AppPreferences.isPreserveDateTaken(context) && dateOnly(context)) {
            for (String tag : new String[] {ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME}) {
                String value = exif.getAttribute(tag);
                if (value != null && !value.isEmpty() && !isDateOnly(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether an ISO 6709 location a video reports lies on the approximate grid, when that is what is kept. */
    static boolean videoLocationMatchesSettings(@NonNull Context context, @Nullable String iso6709) {
        if (iso6709 == null || AppPreferences.isStrictClean(context)
                || !AppPreferences.isPreserveLocation(context) || !approximateLocation(context)) {
            return true;
        }
        double[] coordinates = ReadableText.iso6709(iso6709);
        return coordinates != null && coordinates.length >= 2
                && onGrid(coordinates[0]) && onGrid(coordinates[1]);
    }
}
