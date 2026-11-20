package com.doubleangels.redact;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.ui.ReviewPrompt;

/**
 * Central access to app-wide user preferences stored in the default SharedPreferences file.
 */
public final class AppPreferences {

    public static final String PREFS_NAME = "redact_prefs";

    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_CLEAN_NOTIFICATIONS_ENABLED = "clean_notifications_enabled";
    private static final String KEY_CONVERT_NOTIFICATIONS_ENABLED = "convert_notifications_enabled";
    private static final String KEY_PROGRESS_NOTIFICATIONS_ENABLED = "progress_notifications_enabled";
    private static final String KEY_CRASH_REPORTING_ENABLED = "crash_reporting_enabled";
    private static final String KEY_CRASH_REPORTING_SINCE = "crash_reporting_since";
    private static final String KEY_LAST_REPORTED_EXIT = "crash_reporting_last_exit";
    private static final String KEY_DEFAULT_IMAGE_FORMAT_INDEX = "default_image_format_index";
    private static final String KEY_KEEP_STATISTICS = "keep_statistics";
    private static final String KEY_DEFAULT_VIDEO_FORMAT_INDEX = "default_video_format_index";
    private static final String KEY_IMAGE_QUALITY_PRESET = "image_quality_preset";
    private static final String KEY_SHARE_CONFIRM_BEFORE_STRIP = "share_confirm_before_strip";
    private static final String KEY_AUTO_CLEAR_TEMP_FILES = "auto_clear_temp_files";
    private static final String KEY_PRESERVE_CAMERA_SETTINGS = "preserve_camera_settings";
    private static final String KEY_PRESERVE_LOCATION = "preserve_location";
    private static final String KEY_PRESERVE_DATE_TAKEN = "preserve_date_taken";
    private static final String KEY_APPROXIMATE_LOCATION = "approximate_location";
    private static final String KEY_DATE_ONLY = "date_only";
    private static final String KEY_REMOVE_AUDIO = "remove_audio";
    private static final String KEY_PRESERVE_MOTION_PHOTO = "preserve_motion_photo";
    private static final String KEY_STRICT_CLEAN = "strict_clean";
    private static final String KEY_WARN_ALREADY_CLEAN = "warn_already_clean";
    private static final String KEY_SECURE_WINDOW = "secure_window";
    private static final String KEY_CLIPBOARD_CLEAR_SECONDS = "clipboard_clear_seconds";
    private static final String KEY_DELETE_ORIGINALS = "delete_originals_after_clean";
    private static final String KEY_KEEP_ORIGINAL_NAMES = "keep_original_file_names";
    private static final String KEY_VIDEO_FALLBACK_COPY = "video_fallback_copy";
    private static final String KEY_INITIAL_PERMISSIONS_PROMPTED = "initial_permissions_prompted";
    private static final String KEY_ONBOARDING_SEEN = "onboarding_seen";
    private static final String KEY_WHATS_NEW_SEEN_VERSION = "whats_new_seen_version";
    private static final String KEY_MAPS_LOCATION_CONSENT_GIVEN = "maps_location_consent_given";
    private static final String KEY_SHARE_REPORT_CONSENT_GIVEN = "share_report_consent_given";
    private static final String KEY_OUTPUT_TREE_IMAGES = "output_tree_images";
    private static final String KEY_OUTPUT_TREE_VIDEOS = "output_tree_videos";
    private static final String KEY_CENSOR_TOOL = "censor_tool";
    private static final String KEY_CENSOR_EFFECT = "censor_effect";
    private static final String KEY_CENSOR_BRUSH_SIZE = "censor_brush_size";
    private static final String KEY_SUMMARY_HINT_LEARNED_PREFIX = "summary_hint_learned_";

    /** The tabs whose summary card shows a how-to line until a job there succeeds once. */
    public static final String HINT_CLEAN = "clean";
    public static final String HINT_CONVERT = "convert";
    public static final String HINT_CENSOR = "censor";

    /** High quality, which matches the legacy defaults. */
    public static final int QUALITY_PRESET_HIGH = 0;
    public static final int QUALITY_PRESET_BALANCED = 1;
    public static final int QUALITY_PRESET_SMALLER = 2;

    public static final int FORMAT_INDEX_JPEG_H264 = 0;
    public static final int FORMAT_INDEX_PNG_H265 = 1;
    public static final int FORMAT_INDEX_WEBP_VP9 = 2;
    public static final int FORMAT_INDEX_HEIC_AV1 = 3;

    private AppPreferences() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean areNotificationsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, false);
    }

    public static void setNotificationsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    public static boolean areCleanNotificationsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_CLEAN_NOTIFICATIONS_ENABLED, false);
    }

    public static void setCleanNotificationsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CLEAN_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    public static boolean areConvertNotificationsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_CONVERT_NOTIFICATIONS_ENABLED, false);
    }

    public static void setConvertNotificationsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CONVERT_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    public static boolean areProgressNotificationsEnabled(@NonNull Context context) {
        if (!areNotificationsEnabled(context)) {
            return false;
        }
        return prefs(context).getBoolean(KEY_PROGRESS_NOTIFICATIONS_ENABLED, false);
    }

    public static void setProgressNotificationsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PROGRESS_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    public static boolean isCrashReportingEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_CRASH_REPORTING_ENABLED, false);
    }

    public static void setCrashReportingEnabled(@NonNull Context context, boolean enabled) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(KEY_CRASH_REPORTING_ENABLED, enabled);
        if (!enabled) {
            // Turned back on later, reporting starts from then, never from this earlier window.
            editor.remove(KEY_CRASH_REPORTING_SINCE).remove(KEY_LAST_REPORTED_EXIT);
        } else if (!prefs(context).contains(KEY_CRASH_REPORTING_SINCE)) {
            editor.putLong(KEY_CRASH_REPORTING_SINCE, System.currentTimeMillis());
        }
        editor.apply();
    }

    /**
     * When crash reporting was last turned on, in epoch milliseconds: anything that happened
     * before, such as an app freeze Android recorded while reporting was off, is not sent. Someone
     * who turned it on before this was stored starts from the first time it is asked for.
     */
    public static long getCrashReportingSince(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_CRASH_REPORTING_SINCE)) {
            long now = System.currentTimeMillis();
            p.edit().putLong(KEY_CRASH_REPORTING_SINCE, now).apply();
            return now;
        }
        return p.getLong(KEY_CRASH_REPORTING_SINCE, 0L);
    }

    /** The time of the newest process exit already counted, or 0 when none has been. */
    public static long getLastReportedExit(@NonNull Context context) {
        return prefs(context).getLong(KEY_LAST_REPORTED_EXIT, 0L);
    }

    public static void setLastReportedExit(@NonNull Context context, long timestamp) {
        prefs(context).edit().putLong(KEY_LAST_REPORTED_EXIT, timestamp).apply();
    }

    public static int getDefaultImageFormatIndex(@NonNull Context context) {
        return clampFormatIndex(prefs(context).getInt(KEY_DEFAULT_IMAGE_FORMAT_INDEX, FORMAT_INDEX_JPEG_H264));
    }

    public static void setDefaultImageFormatIndex(@NonNull Context context, int index) {
        prefs(context).edit().putInt(KEY_DEFAULT_IMAGE_FORMAT_INDEX, clampFormatIndex(index)).apply();
    }

    public static int getDefaultVideoFormatIndex(@NonNull Context context) {
        return clampFormatIndex(prefs(context).getInt(KEY_DEFAULT_VIDEO_FORMAT_INDEX, FORMAT_INDEX_JPEG_H264));
    }

    public static void setDefaultVideoFormatIndex(@NonNull Context context, int index) {
        prefs(context).edit().putInt(KEY_DEFAULT_VIDEO_FORMAT_INDEX, clampFormatIndex(index)).apply();
    }

    public static int getImageQualityPreset(@NonNull Context context) {
        int preset = prefs(context).getInt(KEY_IMAGE_QUALITY_PRESET, QUALITY_PRESET_HIGH);
        if (preset < QUALITY_PRESET_HIGH || preset > QUALITY_PRESET_SMALLER) {
            return QUALITY_PRESET_HIGH;
        }
        return preset;
    }

    public static void setImageQualityPreset(@NonNull Context context, int preset) {
        int clamped = preset;
        if (clamped < QUALITY_PRESET_HIGH) {
            clamped = QUALITY_PRESET_HIGH;
        } else if (clamped > QUALITY_PRESET_SMALLER) {
            clamped = QUALITY_PRESET_SMALLER;
        }
        prefs(context).edit().putInt(KEY_IMAGE_QUALITY_PRESET, clamped).apply();
    }

    public static boolean isShareConfirmBeforeStrip(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHARE_CONFIRM_BEFORE_STRIP, false);
    }

    public static void setShareConfirmBeforeStrip(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHARE_CONFIRM_BEFORE_STRIP, enabled).apply();
    }

    /**
     * JPEG/WebP/HEIC quality for the given preset. PNG is always lossless (100).
     */
    public static int qualityForLossyFormat(int preset, boolean isWebpOrHeic) {
        return switch (preset) {
            case QUALITY_PRESET_BALANCED -> isWebpOrHeic ? 80 : 85;
            case QUALITY_PRESET_SMALLER -> isWebpOrHeic ? 70 : 75;
            default -> isWebpOrHeic ? 90 : 92;
        };
    }

    public static int clampFormatIndex(int index) {
        if (index < FORMAT_INDEX_JPEG_H264) {
            return FORMAT_INDEX_JPEG_H264;
        }
        if (index > FORMAT_INDEX_HEIC_AV1) {
            return FORMAT_INDEX_HEIC_AV1;
        }
        return index;
    }

    public static boolean isAutoClearTempFiles(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CLEAR_TEMP_FILES, true);
    }

    public static void setAutoClearTempFiles(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_CLEAR_TEMP_FILES, enabled).apply();
    }

    /**
     * The folder (a Storage Access Framework tree URI) the user picked for cleaned and converted
     * images or videos, or null to use the default {@code Pictures/Redact} / {@code Movies/Redact}.
     */
    @Nullable
    public static Uri getOutputTree(@NonNull Context context, boolean video) {
        String stored = prefs(context).getString(outputTreeKey(video), null);
        return stored == null ? null : Uri.parse(stored);
    }

    public static void setOutputTree(@NonNull Context context, boolean video, @NonNull Uri tree) {
        prefs(context).edit().putString(outputTreeKey(video), tree.toString()).apply();
    }

    public static void resetOutputTree(@NonNull Context context, boolean video) {
        prefs(context).edit().remove(outputTreeKey(video)).apply();
    }

    private static String outputTreeKey(boolean video) {
        return video ? KEY_OUTPUT_TREE_VIDEOS : KEY_OUTPUT_TREE_IMAGES;
    }

    public static boolean isPreserveCameraSettings(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_PRESERVE_CAMERA_SETTINGS, false);
    }

    public static void setPreserveCameraSettings(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PRESERVE_CAMERA_SETTINGS, enabled).apply();
    }

    /** Whether a clean keeps when a photo was taken, so galleries still sort it by that date. */
    public static boolean isPreserveDateTaken(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_PRESERVE_DATE_TAKEN, false);
    }

    public static void setPreserveDateTaken(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PRESERVE_DATE_TAKEN, enabled).apply();
    }

    /** Whether a clean keeps a Motion Photo's video, cleaned, instead of making it a still. */
    public static boolean isPreserveMotionPhoto(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_PRESERVE_MOTION_PHOTO, false);
    }

    public static void setPreserveMotionPhoto(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PRESERVE_MOTION_PHOTO, enabled).apply();
    }

    public static boolean isPreserveLocation(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_PRESERVE_LOCATION, false);
    }

    public static void setPreserveLocation(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PRESERVE_LOCATION, enabled).apply();
    }

    /**
     * Whether Keep Location keeps only an approximate location, rounded to a tenth of a degree
     * (about 10 km), rather than the exact one. On by default: the town, not the street.
     */
    public static boolean isApproximateLocation(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_APPROXIMATE_LOCATION, true);
    }

    public static void setApproximateLocation(@NonNull Context context, boolean approximate) {
        prefs(context).edit().putBoolean(KEY_APPROXIMATE_LOCATION, approximate).apply();
    }

    /** Whether Keep Date Taken keeps only the day, not the time of day or time zone. */
    public static boolean isDateOnly(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_DATE_ONLY, false);
    }

    public static void setDateOnly(@NonNull Context context, boolean dateOnly) {
        prefs(context).edit().putBoolean(KEY_DATE_ONLY, dateOnly).apply();
    }

    /**
     * Whether videos lose their sound when they are cleaned, shared or converted: voices and
     * background noise can say who was there and where.
     */
    public static boolean isRemoveAudio(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_REMOVE_AUDIO, false);
    }

    public static void setRemoveAudio(@NonNull Context context, boolean remove) {
        prefs(context).edit().putBoolean(KEY_REMOVE_AUDIO, remove).apply();
    }

    public static boolean isStrictClean(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_STRICT_CLEAN, true);
    }

    public static void setStrictClean(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_STRICT_CLEAN, enabled).apply();
    }

    /**
     * Writes Clean's keep options in one edit, for a {@link CleanPreset}. A null sub-option is
     * left as it is. With Strict Clean on the keep options are written off, as its switch does.
     */
    static void setCleanOptions(@NonNull Context context, boolean strictClean, boolean keepLocation,
                                @Nullable Boolean approximateLocation, boolean keepDateTaken,
                                @Nullable Boolean dateOnly, boolean keepCameraSettings,
                                boolean keepMotionPhotos) {
        SharedPreferences.Editor e = prefs(context).edit()
                .putBoolean(KEY_STRICT_CLEAN, strictClean)
                .putBoolean(KEY_PRESERVE_LOCATION, !strictClean && keepLocation)
                .putBoolean(KEY_PRESERVE_DATE_TAKEN, !strictClean && keepDateTaken)
                .putBoolean(KEY_PRESERVE_CAMERA_SETTINGS, !strictClean && keepCameraSettings)
                .putBoolean(KEY_PRESERVE_MOTION_PHOTO, !strictClean && keepMotionPhotos);
        if (approximateLocation != null) {
            e.putBoolean(KEY_APPROXIMATE_LOCATION, approximateLocation);
        }
        if (dateOnly != null) {
            e.putBoolean(KEY_DATE_ONLY, dateOnly);
        }
        e.apply();
    }

    /** Whether Clean should warn before processing files that look clean already (on by default). */
    public static boolean isWarnAlreadyClean(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_WARN_ALREADY_CLEAN, true);
    }

    public static void setWarnAlreadyClean(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WARN_ALREADY_CLEAN, enabled).apply();
    }

    /** Whether the app's windows are hidden from screenshots, screen recording and recents (off by default). */
    public static boolean isSecureWindow(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SECURE_WINDOW, false);
    }

    public static void setSecureWindow(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SECURE_WINDOW, enabled).apply();
    }

    /** Delay choices, in seconds, for clearing copied metadata from the clipboard; 0 is off. */
    public static final int[] CLIPBOARD_CLEAR_SECONDS_OPTIONS = {0, 15, 30, 60, 300};
    /**
     * Copied metadata (a photo's location, say) is cleared after a minute unless the user chose
     * otherwise; left on the clipboard it is readable by whatever app is in front later.
     */
    public static final int DEFAULT_CLIPBOARD_CLEAR_SECONDS = 60;

    /** Seconds until copied metadata is cleared from the clipboard, 0 when turned off. */
    public static int getClipboardClearSeconds(@NonNull Context context) {
        int seconds = prefs(context).getInt(KEY_CLIPBOARD_CLEAR_SECONDS, DEFAULT_CLIPBOARD_CLEAR_SECONDS);
        for (int option : CLIPBOARD_CLEAR_SECONDS_OPTIONS) {
            if (option == seconds) {
                return seconds;
            }
        }
        return DEFAULT_CLIPBOARD_CLEAR_SECONDS;
    }

    public static void setClipboardClearSeconds(@NonNull Context context, int seconds) {
        prefs(context).edit().putInt(KEY_CLIPBOARD_CLEAR_SECONDS, seconds).apply();
    }

    /**
     * Whether Settings › Statistics counts what Redact does. On by default; when off nothing is
     * counted, so no record of when the user cleaned files is kept on the device.
     */
    public static boolean isKeepStatistics(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_KEEP_STATISTICS, true);
    }

    public static void setKeepStatistics(@NonNull Context context, boolean keep) {
        prefs(context).edit().putBoolean(KEY_KEEP_STATISTICS, keep).apply();
    }

    public static boolean isDeleteOriginalsAfterClean(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_DELETE_ORIGINALS, false);
    }

    public static void setDeleteOriginalsAfterClean(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DELETE_ORIGINALS, enabled).apply();
    }

    /** Off by default: names such as IMG_20260117_093012 reveal when a photo was taken. */
    public static boolean isKeepOriginalFileNames(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_KEEP_ORIGINAL_NAMES, false);
    }

    public static void setKeepOriginalFileNames(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEEP_ORIGINAL_NAMES, enabled).apply();
    }

    /** The Censor tool last used (a {@code CensorCanvasView.Tool} name), or null. */
    @Nullable
    public static String getCensorTool(@NonNull Context context) {
        return prefs(context).getString(KEY_CENSOR_TOOL, null);
    }

    public static void setCensorTool(@NonNull Context context, @NonNull String tool) {
        prefs(context).edit().putString(KEY_CENSOR_TOOL, tool).apply();
    }

    /** The Censor effect last used (a {@code CensorEffects.Effect} name), or null. */
    @Nullable
    public static String getCensorEffect(@NonNull Context context) {
        return prefs(context).getString(KEY_CENSOR_EFFECT, null);
    }

    public static void setCensorEffect(@NonNull Context context, @NonNull String effect) {
        prefs(context).edit().putString(KEY_CENSOR_EFFECT, effect).apply();
    }

    /** The Censor brush size last used, on the slider's 1 to 10 scale, or {@code fallback}. */
    public static float getCensorBrushSize(@NonNull Context context, float fallback) {
        return prefs(context).getFloat(KEY_CENSOR_BRUSH_SIZE, fallback);
    }

    public static void setCensorBrushSize(@NonNull Context context, float size) {
        prefs(context).edit().putFloat(KEY_CENSOR_BRUSH_SIZE, size).apply();
    }

    /**
     * Whether a job has succeeded on {@code tab} (one of the {@code HINT_} names), so its summary
     * card no longer needs the how-to line and gives that space back to the content below.
     */
    public static boolean isSummaryHintLearned(@NonNull Context context, @NonNull String tab) {
        return prefs(context).getBoolean(KEY_SUMMARY_HINT_LEARNED_PREFIX + tab, false);
    }

    public static void setSummaryHintLearned(@NonNull Context context, @NonNull String tab) {
        prefs(context).edit().putBoolean(KEY_SUMMARY_HINT_LEARNED_PREFIX + tab, true).apply();
    }

    public static boolean isVideoFallbackCopy(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_VIDEO_FALLBACK_COPY, false);
    }

    public static void setVideoFallbackCopy(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_VIDEO_FALLBACK_COPY, enabled).apply();
    }

    /** Whether the first-launch permission sequence has already been shown. */
    public static boolean hasCompletedInitialPermissionsPrompt(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_INITIAL_PERMISSIONS_PROMPTED, false);
    }

    public static void setInitialPermissionsPromptCompleted(@NonNull Context context) {
        prefs(context).edit().putBoolean(KEY_INITIAL_PERMISSIONS_PROMPTED, true).apply();
    }

    /** Whether the first-start introduction has been shown. */
    public static boolean hasSeenOnboarding(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDING_SEEN, false);
    }

    public static void setOnboardingSeen(@NonNull Context context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply();
    }

    /**
     * The versionCode whose What's New notes were last opened, or 0 when none were (a new install,
     * or an update from a version before the screen existed).
     */
    public static int getWhatsNewSeenVersion(@NonNull Context context) {
        return prefs(context).getInt(KEY_WHATS_NEW_SEEN_VERSION, 0);
    }

    public static void setWhatsNewSeenVersion(@NonNull Context context, int versionCode) {
        prefs(context).edit().putInt(KEY_WHATS_NEW_SEEN_VERSION, versionCode).apply();
    }

    /**
     * Whether the user has already agreed, once, that opening a Scan coordinate in the device's
     * maps app is okay. That action hands the GPS coordinates to a third-party app, which may
     * in turn send them off-device (tile/geocoding requests), unlike every other Scan action,
     * which stays entirely on-device. False until the user explicitly consents.
     */
    /**
     * Whether the person has agreed, once, that Share as Text hands a Scan report (which can hold
     * a location, dates and names) to an app they pick.
     */
    public static boolean hasConsentedToShareReport(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHARE_REPORT_CONSENT_GIVEN, false);
    }

    public static void setConsentedToShareReport(@NonNull Context context, boolean consented) {
        prefs(context).edit().putBoolean(KEY_SHARE_REPORT_CONSENT_GIVEN, consented).apply();
    }

    public static boolean hasConsentedToOpenLocationInMaps(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_MAPS_LOCATION_CONSENT_GIVEN, false);
    }

    public static void setConsentedToOpenLocationInMaps(@NonNull Context context, boolean consented) {
        prefs(context).edit().putBoolean(KEY_MAPS_LOCATION_CONSENT_GIVEN, consented).apply();
    }

    /**
     * Puts every setting back to its default. The first-launch permission prompt is not shown
     * again, since resetting settings does not change what Android has already granted, nor is the
     * introduction or the last What's New, and the rating prompt's progress is kept, so it is never
     * asked again.
     */
    public static void resetAll(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        boolean prompted = hasCompletedInitialPermissionsPrompt(context);
        int reviewCount = p.getInt(ReviewPrompt.KEY_SUCCESS_COUNT, 0);
        int reviewState = p.getInt(ReviewPrompt.KEY_STATE, 0);
        boolean onboardingSeen = hasSeenOnboarding(context);
        int whatsNewSeen = getWhatsNewSeenVersion(context);
        SharedPreferences.Editor e = p.edit().clear()
                .putBoolean(KEY_INITIAL_PERMISSIONS_PROMPTED, prompted);
        if (onboardingSeen) {
            e.putBoolean(KEY_ONBOARDING_SEEN, true);
        }
        if (whatsNewSeen > 0) {
            e.putInt(KEY_WHATS_NEW_SEEN_VERSION, whatsNewSeen);
        }
        if (reviewCount > 0) {
            e.putInt(ReviewPrompt.KEY_SUCCESS_COUNT, reviewCount);
        }
        if (reviewState != 0) {
            e.putInt(ReviewPrompt.KEY_STATE, reviewState);
        }
        e.apply();
    }
}
