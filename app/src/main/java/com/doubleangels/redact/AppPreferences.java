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
    private static final String KEY_DEFAULT_IMAGE_FORMAT_INDEX = "default_image_format_index";
    private static final String KEY_KEEP_STATISTICS = "keep_statistics";
    private static final String KEY_DEFAULT_VIDEO_FORMAT_INDEX = "default_video_format_index";
    private static final String KEY_IMAGE_QUALITY_PRESET = "image_quality_preset";
    private static final String KEY_SHARE_CONFIRM_BEFORE_STRIP = "share_confirm_before_strip";
    private static final String KEY_SECURE_DELETE_PASSES = "secure_delete_passes";
    private static final String KEY_AUTO_CLEAR_TEMP_FILES = "auto_clear_temp_files";
    private static final String KEY_MAX_BITMAP_SIZE = "max_bitmap_size";
    private static final String KEY_PRESERVE_CAMERA_SETTINGS = "preserve_camera_settings";
    private static final String KEY_PRESERVE_LOCATION = "preserve_location";
    private static final String KEY_STRICT_CLEAN = "strict_clean";
    private static final String KEY_WARN_ALREADY_CLEAN = "warn_already_clean";
    private static final String KEY_SECURE_WINDOW = "secure_window";
    private static final String KEY_CLIPBOARD_CLEAR_SECONDS = "clipboard_clear_seconds";
    private static final String KEY_DELETE_ORIGINALS = "delete_originals_after_clean";
    private static final String KEY_KEEP_ORIGINAL_NAMES = "keep_original_file_names";
    private static final String KEY_VIDEO_FALLBACK_COPY = "video_fallback_copy";
    private static final String KEY_INITIAL_PERMISSIONS_PROMPTED = "initial_permissions_prompted";
    private static final String KEY_ONBOARDING_SEEN = "onboarding_seen";
    private static final String KEY_MAPS_LOCATION_CONSENT_GIVEN = "maps_location_consent_given";
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

    /** {@link #getMaxBitmapSize} for full resolution, limited only by free memory. */
    public static final int MAX_BITMAP_SIZE_FULL = 0;

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
        prefs(context).edit().putBoolean(KEY_CRASH_REPORTING_ENABLED, enabled).apply();
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

    public static int getSecureDeletePasses(@NonNull Context context) {
        int passes = prefs(context).getInt(KEY_SECURE_DELETE_PASSES, 3);
        return switch (passes) {
            case 1 -> 1;
            case 7 -> 7;
            default -> 3;
        };
    }

    public static void setSecureDeletePasses(@NonNull Context context, int passes) {
        int clamped = switch (passes) {
            case 1 -> 1;
            case 7 -> 7;
            default -> 3;
        };
        prefs(context).edit().putInt(KEY_SECURE_DELETE_PASSES, clamped).apply();
    }

    public static boolean isAutoClearTempFiles(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CLEAR_TEMP_FILES, true);
    }

    public static void setAutoClearTempFiles(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_CLEAR_TEMP_FILES, enabled).apply();
    }

    /**
     * The Max Processing Resolution setting: the size, in pixels per side, an image is scaled
     * down toward before it is re-encoded, or {@link #MAX_BITMAP_SIZE_FULL} (the default) to keep
     * every pixel the device has the memory for. The default used to follow the app's Java heap, but pixels
     * have lived in native memory since Android 8, so it shrank photos for no reason.
     */
    public static int getMaxBitmapSize(@NonNull Context context) {
        return prefs(context).getInt(KEY_MAX_BITMAP_SIZE, MAX_BITMAP_SIZE_FULL);
    }

    public static void setMaxBitmapSize(@NonNull Context context, int size) {
        int clamped = switch (size) {
            case MAX_BITMAP_SIZE_FULL -> MAX_BITMAP_SIZE_FULL;
            case 2048 -> 2048;
            case 8192 -> 8192;
            default -> 4096;
        };
        prefs(context).edit().putInt(KEY_MAX_BITMAP_SIZE, clamped).apply();
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

    public static boolean isPreserveLocation(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_PRESERVE_LOCATION, false);
    }

    public static void setPreserveLocation(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PRESERVE_LOCATION, enabled).apply();
    }

    public static boolean isStrictClean(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_STRICT_CLEAN, true);
    }

    public static void setStrictClean(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_STRICT_CLEAN, enabled).apply();
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
     * Whether the user has already agreed, once, that opening a Scan coordinate in the device's
     * maps app is okay. That action hands the GPS coordinates to a third-party app, which may
     * in turn send them off-device (tile/geocoding requests), unlike every other Scan action,
     * which stays entirely on-device. False until the user explicitly consents.
     */
    public static boolean hasConsentedToOpenLocationInMaps(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_MAPS_LOCATION_CONSENT_GIVEN, false);
    }

    public static void setConsentedToOpenLocationInMaps(@NonNull Context context, boolean consented) {
        prefs(context).edit().putBoolean(KEY_MAPS_LOCATION_CONSENT_GIVEN, consented).apply();
    }

    /**
     * Puts every setting back to its default. The first-launch permission prompt is not shown
     * again, since resetting settings does not change what Android has already granted, nor is the
     * introduction, and the rating prompt's progress is kept, so it is never asked again.
     */
    public static void resetAll(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        boolean prompted = hasCompletedInitialPermissionsPrompt(context);
        int reviewCount = p.getInt(ReviewPrompt.KEY_SUCCESS_COUNT, 0);
        int reviewState = p.getInt(ReviewPrompt.KEY_STATE, 0);
        boolean onboardingSeen = hasSeenOnboarding(context);
        SharedPreferences.Editor e = p.edit().clear()
                .putBoolean(KEY_INITIAL_PERMISSIONS_PROMPTED, prompted);
        if (onboardingSeen) {
            e.putBoolean(KEY_ONBOARDING_SEEN, true);
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
