package com.doubleangels.redact;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.sentry.SentryManager;

/**
 * Reads and applies {@link CleanPreset}s. No preset is saved: {@link #current} works it out from
 * the options every time, so Reset All Settings and a removed setting need nothing special.
 */
public final class CleanPresets {

    private CleanPresets() {
    }

    /** The preset whose options all match the saved ones, or {@link CleanPreset#CUSTOM}. */
    @NonNull
    public static CleanPreset current(@NonNull Context context) {
        for (CleanPreset preset : CleanPreset.choices()) {
            if (matches(context, preset)) {
                return preset;
            }
        }
        return CleanPreset.CUSTOM;
    }

    /**
     * Whether the saved options are {@code preset}'s. Strict Clean ignores every keep option, so
     * with it on only Strict Clean is compared; a sub-option (Approximate Location, Date Only) is
     * compared only while the option it belongs to is kept.
     */
    static boolean matches(@NonNull Context context, @NonNull CleanPreset preset) {
        if (preset == CleanPreset.CUSTOM) {
            return false;
        }
        boolean strict = AppPreferences.isStrictClean(context);
        if (strict != preset.strictClean) {
            return false;
        }
        if (strict) {
            return true;
        }
        if (AppPreferences.isPreserveLocation(context) != preset.keepLocation
                || AppPreferences.isPreserveDateTaken(context) != preset.keepDateTaken
                || AppPreferences.isPreserveCameraSettings(context) != preset.keepCameraSettings
                || AppPreferences.isPreserveMotionPhoto(context) != preset.keepMotionPhotos) {
            return false;
        }
        if (preset.keepLocation && preset.approximateLocation != null
                && AppPreferences.isApproximateLocation(context) != preset.approximateLocation) {
            return false;
        }
        return !preset.keepDateTaken || preset.dateOnly == null
                || AppPreferences.isDateOnly(context) == preset.dateOnly;
    }

    /**
     * Writes {@code preset}'s options in one edit, so listeners see one change. A preset that keeps
     * a location is refused unless the user has just confirmed it, as the Keep Location switch
     * asks. A running job is unaffected: it read its options when it started.
     *
     * @return whether the options were written
     */
    public static boolean apply(@NonNull Context context, @NonNull CleanPreset preset,
                                boolean locationConfirmed) {
        if (preset == CleanPreset.CUSTOM) {
            throw new IllegalArgumentException("Custom is not a preset to apply");
        }
        if (preset.keepsLocation() && !locationConfirmed) {
            return false;
        }
        AppPreferences.setCleanOptions(context, preset.strictClean, preset.keepLocation,
                preset.approximateLocation, preset.keepDateTaken, preset.dateOnly,
                preset.keepCameraSettings, preset.keepMotionPhotos);
        // Several options change at once: say so, as the setting tags alone show only the result.
        SentryManager.logEvent("clean", "The clean preset " + preset.tagValue + " was applied.");
        return true;
    }
}
