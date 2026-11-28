package com.doubleangels.redact;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/**
 * A purpose that sets all of Clean's keep options at once. It is not a mode: it writes the same
 * preferences the switches do, and {@link CleanPresets#current} works the preset out from them
 * each time, so a preset can never disagree with the switches. {@link #CUSTOM} is what shows when
 * the options match none of the others.
 *
 * <p>Remove Audio and Keep Original File Names are left as they are by every preset: they depend
 * on the content more than on who will see it.
 */
public enum CleanPreset {

    /** Today's defaults: Strict Clean, which keeps nothing a photo doesn't need to display. */
    STANDARD("standard", R.string.clean_preset_standard, R.string.clean_preset_standard_description,
            true, false, null, false, null, false, false),
    /** Keeps only the day each photo was taken, so it sorts right in someone else's gallery. */
    SENDING_TO_PEOPLE("sending_to_people", R.string.clean_preset_sending,
            R.string.clean_preset_sending_description,
            false, false, null, true, true, false, false),
    /** Keeps the date, camera details and Motion Photos, and the location rounded to the town. */
    KEEPING_FOR_MYSELF("keeping_for_myself", R.string.clean_preset_keeping,
            R.string.clean_preset_keeping_description,
            false, true, true, true, false, true, true),
    /** The options match no preset. Never applied. */
    CUSTOM("custom", R.string.clean_preset_custom, R.string.clean_preset_custom_description,
            false, false, null, false, null, false, false);

    /** The value of the {@code setting.clean_preset} Sentry tag. */
    @NonNull
    public final String tagValue;
    @StringRes
    public final int nameRes;
    @StringRes
    public final int descriptionRes;

    final boolean strictClean;
    final boolean keepLocation;
    /** Approximate Location, or null where it doesn't matter (location isn't kept). */
    @Nullable
    final Boolean approximateLocation;
    final boolean keepDateTaken;
    /** Date Only, or null where it doesn't matter (the date isn't kept). */
    @Nullable
    final Boolean dateOnly;
    final boolean keepCameraSettings;
    final boolean keepMotionPhotos;

    CleanPreset(@NonNull String tagValue, @StringRes int nameRes, @StringRes int descriptionRes,
                boolean strictClean, boolean keepLocation, @Nullable Boolean approximateLocation,
                boolean keepDateTaken, @Nullable Boolean dateOnly, boolean keepCameraSettings,
                boolean keepMotionPhotos) {
        this.tagValue = tagValue;
        this.nameRes = nameRes;
        this.descriptionRes = descriptionRes;
        this.strictClean = strictClean;
        this.keepLocation = keepLocation;
        this.approximateLocation = approximateLocation;
        this.keepDateTaken = keepDateTaken;
        this.dateOnly = dateOnly;
        this.keepCameraSettings = keepCameraSettings;
        this.keepMotionPhotos = keepMotionPhotos;
    }

    /** Whether applying it keeps a location, which needs the user's yes first. */
    public boolean keepsLocation() {
        return keepLocation;
    }

    /** The presets someone can pick, in the order they are shown: every one but {@link #CUSTOM}. */
    @NonNull
    public static CleanPreset[] choices() {
        return new CleanPreset[] {STANDARD, SENDING_TO_PEOPLE, KEEPING_FOR_MYSELF};
    }
}
