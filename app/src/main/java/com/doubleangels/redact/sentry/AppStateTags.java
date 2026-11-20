package com.doubleangels.redact.sentry;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.CleanPresets;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tags added to every error report: the settings that change how files are cleaned or converted,
 * and what the phone offers for media work. They are read when the report is made, so they are
 * never stale.
 *
 * <p>Each value is one of a few fixed words or small numbers. Nothing here names a file, a folder
 * (only whether one was chosen) or the person, and the cosmetic settings (Censor's tool and
 * effect, the clipboard timer, statistics and the like) stay out: they explain no bug, and every
 * extra setting would make one phone's reports easier to tell apart.
 */
public final class AppStateTags {

    private AppStateTags() {
    }

    /** The tags for a report made now. Empty when {@code context} is null. */
    @NonNull
    public static Map<String, String> collect(@Nullable Context context) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (context == null) {
            return tags;
        }
        putSettings(context, tags);
        putDevice(context, tags);
        return tags;
    }

    private static void putSettings(@NonNull Context context, @NonNull Map<String, String> tags) {
        try {
            tags.put("setting.strict_clean", bool(AppPreferences.isStrictClean(context)));
            tags.put("setting.clean_preset", CleanPresets.current(context).tagValue);
            tags.put("setting.keep_camera_settings", bool(AppPreferences.isPreserveCameraSettings(context)));
            tags.put("setting.keep_date_taken", dateTaken(context));
            tags.put("setting.keep_motion_photos", bool(AppPreferences.isPreserveMotionPhoto(context)));
            // "gps", not "location": the scrubber blanks any tag whose key mentions a location.
            tags.put("setting.keep_gps", gps(context));
            tags.put("setting.remove_audio", bool(AppPreferences.isRemoveAudio(context)));
            tags.put("setting.faster_video_cleaning", bool(AppPreferences.isVideoFallbackCopy(context)));
            tags.put("setting.delete_originals", bool(AppPreferences.isDeleteOriginalsAfterClean(context)));
            tags.put("setting.keep_original_names", bool(AppPreferences.isKeepOriginalFileNames(context)));
            tags.put("setting.auto_clear_temp", bool(AppPreferences.isAutoClearTempFiles(context)));
            tags.put("setting.image_folder", folder(context, false));
            tags.put("setting.video_folder", folder(context, true));
            tags.put("setting.default_image_format", String.valueOf(AppPreferences.getDefaultImageFormatIndex(context)));
            tags.put("setting.default_video_codec", String.valueOf(AppPreferences.getDefaultVideoFormatIndex(context)));
            tags.put("setting.image_quality", String.valueOf(AppPreferences.getImageQualityPreset(context)));
        } catch (RuntimeException e) {
            // A report must still go out when the settings can't be read.
        }
    }

    private static void putDevice(@NonNull Context context, @NonNull Map<String, String> tags) {
        // The phone's media performance class (0, or the Android version whose codec and memory
        // requirements it meets): why one phone's encoder fails where another's doesn't.
        tags.put("device.media_performance_class", String.valueOf(Build.VERSION.MEDIA_PERFORMANCE_CLASS));
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am != null) {
            tags.put("device.low_ram", bool(am.isLowRamDevice()));
            tags.put("device.heap_mb", String.valueOf(am.getMemoryClass()));
            tags.put("device.large_heap_mb", String.valueOf(am.getLargeMemoryClass()));
            // Background limits explain foreground-service and notification failures.
            tags.put("app.background_restricted", bool(am.isBackgroundRestricted()));
        }
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        if (usage != null) {
            tags.put("app.standby_bucket", standbyBucket(usage.getAppStandbyBucket()));
        }
        tags.put("app.installer", installer(context));
    }

    private static String bool(boolean value) {
        return value ? "true" : "false";
    }

    private static String dateTaken(@NonNull Context context) {
        if (!AppPreferences.isPreserveDateTaken(context)) {
            return "off";
        }
        return AppPreferences.isDateOnly(context) ? "date_only" : "full";
    }

    private static String gps(@NonNull Context context) {
        if (!AppPreferences.isPreserveLocation(context)) {
            return "off";
        }
        return AppPreferences.isApproximateLocation(context) ? "approximate" : "exact";
    }

    /** Whether a folder was chosen, never which one. */
    private static String folder(@NonNull Context context, boolean video) {
        return AppPreferences.getOutputTree(context, video) != null ? "chosen" : "default";
    }

    static String standbyBucket(int bucket) {
        return switch (bucket) {
            case UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active";
            case UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set";
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent";
            case UsageStatsManager.STANDBY_BUCKET_RARE -> "rare";
            case UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted";
            default -> "other";
        };
    }

    /** Where the app was installed from, as one of a few words: never another app's name. */
    static String installer(@NonNull Context context) {
        try {
            InstallSourceInfo info = context.getPackageManager().getInstallSourceInfo(context.getPackageName());
            return installerClass(info.getInstallingPackageName());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "unknown";
        }
    }

    static String installerClass(@Nullable String installingPackage) {
        if (installingPackage == null) {
            return "none";
        }
        return switch (installingPackage) {
            case "com.android.vending" -> "play";
            case "org.fdroid.fdroid", "com.aurora.store" -> "store_other";
            case "com.android.packageinstaller", "com.google.android.packageinstaller" -> "sideloaded";
            case "com.android.shell" -> "adb";
            default -> "other";
        };
    }
}
