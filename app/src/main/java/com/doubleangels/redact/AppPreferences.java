package com.doubleangels.redact;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

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
    private static final String KEY_DEFAULT_VIDEO_FORMAT_INDEX = "default_video_format_index";
    private static final String KEY_IMAGE_QUALITY_PRESET = "image_quality_preset";
    private static final String KEY_SHARE_CONFIRM_BEFORE_STRIP = "share_confirm_before_strip";
    private static final String KEY_SECURE_DELETE_PASSES = "secure_delete_passes";
    private static final String KEY_AUTO_CLEAR_TEMP_FILES = "auto_clear_temp_files";
    private static final String KEY_MAX_BITMAP_SIZE = "max_bitmap_size";
    private static final String KEY_MAX_IMAGE_FILE_SIZE_MB = "max_image_file_size_mb";
    private static final String KEY_PRESERVE_CAMERA_SETTINGS = "preserve_camera_settings";
    private static final String KEY_PRESERVE_LOCATION = "preserve_location";
    private static final String KEY_STRICT_CLEAN = "strict_clean";
    private static final String KEY_VIDEO_FALLBACK_COPY = "video_fallback_copy";

    /** High quality — matches legacy defaults. */
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
        return prefs(context).getBoolean(KEY_PROGRESS_NOTIFICATIONS_ENABLED, true);
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
        return prefs(context).getInt(KEY_SECURE_DELETE_PASSES, 3);
    }

    public static void setSecureDeletePasses(@NonNull Context context, int passes) {
        prefs(context).edit().putInt(KEY_SECURE_DELETE_PASSES, passes).apply();
    }

    public static boolean isAutoClearTempFiles(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CLEAR_TEMP_FILES, false);
    }

    public static void setAutoClearTempFiles(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_CLEAR_TEMP_FILES, enabled).apply();
    }

    public static int getMaxBitmapSize(@NonNull Context context) {
        return prefs(context).getInt(KEY_MAX_BITMAP_SIZE, 4096);
    }

    public static void setMaxBitmapSize(@NonNull Context context, int size) {
        prefs(context).edit().putInt(KEY_MAX_BITMAP_SIZE, size).apply();
    }

    public static int getMaxImageFileSizeMb(@NonNull Context context) {
        return prefs(context).getInt(KEY_MAX_IMAGE_FILE_SIZE_MB, 100);
    }

    public static void setMaxImageFileSizeMb(@NonNull Context context, int size) {
        prefs(context).edit().putInt(KEY_MAX_IMAGE_FILE_SIZE_MB, size).apply();
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
        return prefs(context).getBoolean(KEY_STRICT_CLEAN, false);
    }

    public static void setStrictClean(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_STRICT_CLEAN, enabled).apply();
    }

    public static boolean isVideoFallbackCopy(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_VIDEO_FALLBACK_COPY, true);
    }

    public static void setVideoFallbackCopy(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_VIDEO_FALLBACK_COPY, enabled).apply();
    }
}
