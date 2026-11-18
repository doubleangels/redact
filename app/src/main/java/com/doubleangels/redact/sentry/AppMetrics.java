package com.doubleangels.redact.sentry;

import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * The app's Sentry metrics by area, in one place, so what is measured can be read at a glance.
 * Every value is a fixed label, a count or a duration: never a file's name, path or metadata
 * value, and never anything that identifies a phone. Each call goes through {@link SentryManager},
 * which checks consent and the attribute allowlist.
 */
public final class AppMetrics {

    public static final String CLEAN = "clean";
    public static final String SHARE = "share";
    public static final String CONVERT = "convert";
    public static final String SCAN = "scan";
    public static final String CENSOR = "censor";

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String UNSUPPORTED = "unsupported";
    public static final String OUT_OF_MEMORY = "out_of_memory";
    public static final String CANCELLED = "cancelled";

    private AppMetrics() {
    }

    @NonNull
    static String mediaType(boolean video) {
        return video ? "video" : "image";
    }

    /**
     * One file a job finished with {@code outcome}, counted as {@code processing.item} by job and
     * media type. A successful file also records how long it took, as
     * {@code processing.item_duration_ms}.
     */
    public static void item(@NonNull String job, boolean video, @NonNull String outcome, long durationMs) {
        SentryManager.count("processing.item", 1,
                "operation_type", job, "media_type", mediaType(video), "outcome", outcome);
        if (SUCCESS.equals(outcome) && durationMs >= 0) {
            SentryManager.distributionDurationMs("processing.item_duration_ms", durationMs,
                    "operation_type", job, "media_type", mediaType(video));
        }
    }

    /**
     * One file a job could not finish, counted as {@code processing.item} like {@link #item}, with
     * the kind of error as well: an exception's class name, or {@code unsupported} or
     * {@code clean_failed} when the job said so without one.
     */
    public static void itemFailed(@NonNull String job, boolean video, @NonNull String outcome,
                                  @NonNull String errorType) {
        SentryManager.count("processing.item", 1,
                "operation_type", job, "media_type", mediaType(video), "outcome", outcome,
                "error_type", errorType);
    }

    /** {@link #itemFailed} for a file that threw {@code error}. */
    public static void itemFailed(@NonNull String job, boolean video, @NonNull Throwable error,
                                  boolean unsupported) {
        itemFailed(job, video, outcomeOf(error, unsupported), error.getClass().getSimpleName());
    }

    /** {@link #itemFailed} for a file a job returned unfinished without throwing. */
    public static void itemNotCleaned(@NonNull String job, boolean video, boolean unsupported) {
        itemFailed(job, video, unsupported ? UNSUPPORTED : FAILED, unsupported ? UNSUPPORTED : "clean_failed");
    }

    /** The outcome for a file that threw: out of memory, an unsupported format or a failure. */
    @NonNull
    public static String outcomeOf(@NonNull Throwable error, boolean unsupported) {
        if (error instanceof OutOfMemoryError) {
            return OUT_OF_MEMORY;
        }
        return unsupported ? UNSUPPORTED : FAILED;
    }

    /**
     * How many files a job was given, as the {@code processing.batch_size} distribution, and how
     * hot the phone was when it started, as {@code processing.thermal_status}.
     */
    public static void batchStarted(@NonNull String job, int size) {
        SentryManager.distribution("processing.batch_size", size, "operation_type", job);
        SentryManager.count("processing.thermal_status", 1,
                "operation_type", job, "thermal_status", SentryManager.thermalStatus());
    }

    /**
     * How a job ended, counted as {@code processing.batch}: success (every file), partial (some),
     * failed (none) or cancelled.
     */
    public static void batchFinished(@NonNull String job, int succeeded, int failed, boolean cancelled) {
        SentryManager.count("processing.batch", 1, "operation_type", job, "outcome",
                batchOutcome(succeeded, failed, cancelled));
    }

    @NonNull
    static String batchOutcome(int succeeded, int failed, boolean cancelled) {
        if (cancelled) {
            return CANCELLED;
        }
        if (failed <= 0) {
            return SUCCESS;
        }
        return succeeded > 0 ? "partial" : FAILED;
    }

    /** A converted file by the format it was converted to, as {@code convert.format}. */
    public static void convertFormat(@NonNull String targetMimeType, @NonNull String outcome) {
        SentryManager.count("convert.format", 1, "mime_type", targetMimeType, "outcome", outcome);
    }

    /** The output MIME type of an image conversion, from {@code Bitmap.CompressFormat#name()}. */
    @NonNull
    public static String imageFormatMimeType(@Nullable String compressFormatName) {
        if (compressFormatName == null) {
            return "unknown";
        }
        String name = compressFormatName.toUpperCase(Locale.ROOT);
        if (name.startsWith("WEBP")) {
            return "image/webp";
        }
        return switch (name) {
            case "JPEG" -> "image/jpeg";
            case "PNG" -> "image/png";
            case "HEIC" -> "image/heic";
            default -> "unknown";
        };
    }

    /** The output MIME type of a video conversion, by its format index (H.264, HEVC, VP9, AV1). */
    @NonNull
    public static String videoFormatMimeType(int formatIndex) {
        return switch (formatIndex) {
            case 1 -> "video/hevc";
            case 2 -> "video/vp9";
            case 3 -> "video/av01";
            default -> "video/avc";
        };
    }

    /** A video conversion that fell back to another codec, by the codec used, as {@code convert.codec_fallback}. */
    public static void codecFallback(int usedFormatIndex) {
        SentryManager.count("convert.codec_fallback", 1, "mime_type", videoFormatMimeType(usedFormatIndex));
    }

    /**
     * Whether a photo opened in Censor could be decoded (success, unsupported or failed), as
     * {@code censor.load}.
     */
    public static void censorLoad(@NonNull String outcome) {
        SentryManager.count("censor.load", 1, "outcome", outcome);
    }

    /** How many regions a saved censored copy covers, as the {@code censor.regions} distribution. */
    public static void censorRegions(int regions) {
        SentryManager.distribution("censor.regions", regions);
    }

    /** A clean that passed every check in {@code CleanVerifier}, as {@code verification.passed}. */
    public static void verificationPassed(boolean video) {
        SentryManager.count("verification.passed", 1, "media_type", mediaType(video));
    }

    /**
     * What the already-clean check found for one file (already_clean, needs_cleaning, unknown and
     * so on, from its {@code Status} names), as {@code already_clean.check}.
     */
    public static void alreadyCleanResult(@NonNull String status) {
        SentryManager.count("already_clean.check", 1, "outcome", status.toLowerCase(Locale.ROOT));
    }

    /** How long the already-clean check took for a whole selection, as {@code already_clean.duration_ms}. */
    public static void alreadyCleanDuration(long durationMs) {
        SentryManager.distributionDurationMs("already_clean.duration_ms", durationMs, "operation_type", CLEAN);
    }

    /**
     * An update check's result (newer, up_to_date or failed) and duration, as
     * {@code update.check} and {@code update.check_duration_ms}.
     */
    public static void updateCheck(@NonNull String outcome, long durationMs) {
        SentryManager.count("update.check", 1, "outcome", outcome);
        if (durationMs >= 0) {
            SentryManager.distributionDurationMs("update.check_duration_ms", durationMs, "outcome", outcome);
        }
    }

    /**
     * The answer to a runtime permission request, by permission (images, videos, location,
     * notifications, storage or other), as {@code permission.result}.
     */
    public static void permissionResult(@Nullable String permission, boolean granted) {
        SentryManager.count("permission.result", 1,
                "permission_type", permissionType(permission),
                "outcome", granted ? "granted" : "denied");
    }

    /** Each answer in an Activity's permission result, as {@link #permissionResult}. */
    public static void permissionResults(@Nullable String[] permissions, @Nullable int[] grantResults) {
        if (permissions == null || grantResults == null) {
            return;
        }
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            permissionResult(permissions[i], grantResults[i] == PackageManager.PERMISSION_GRANTED);
        }
    }

    /** Each answer in an Activity Result API permission result, as {@link #permissionResult}. */
    public static void permissionResults(@Nullable Map<String, Boolean> results) {
        if (results == null) {
            return;
        }
        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            permissionResult(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
        }
    }

    @NonNull
    static String permissionType(@Nullable String permission) {
        if (permission == null) {
            return "other";
        }
        return switch (permission) {
            case "android.permission.READ_MEDIA_IMAGES" -> "images";
            case "android.permission.READ_MEDIA_VIDEO" -> "videos";
            case "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" -> "selected_media";
            case "android.permission.ACCESS_MEDIA_LOCATION" -> "media_location";
            case "android.permission.POST_NOTIFICATIONS" -> "notifications";
            case "android.permission.READ_EXTERNAL_STORAGE",
                 "android.permission.WRITE_EXTERNAL_STORAGE" -> "storage";
            default -> "other";
        };
    }

    /**
     * The processing foreground service failing, by stage (start_service when Android refuses to
     * start it, start_foreground when it can't enter the foreground, no_notification_permission)
     * and exception class, as {@code foreground_service.failure}.
     */
    public static void foregroundServiceFailure(@NonNull String stage, @NonNull Throwable error) {
        SentryManager.count("foreground_service.failure", 1,
                "outcome", stage, "error_type", error.getClass().getSimpleName());
    }

    /**
     * A working copy overwritten before deletion, and how long the overwrite took, as
     * {@code secure_delete.overwrite}. With {@code secure_delete.fallback} it gives the failure rate.
     */
    public static void secureDeleteOverwrite(long durationMs) {
        SentryManager.distributionDurationMs("secure_delete.overwrite", durationMs, "outcome", SUCCESS);
    }

    /** A notification Android refused to post, by exception class, as {@code notification.failure}. */
    public static void notificationFailure(@NonNull Throwable error) {
        SentryManager.count("notification.failure", 1, "error_type", error.getClass().getSimpleName());
    }

    /**
     * How the introduction ended (finished, or left with Back), as {@code onboarding.finished}.
     * Only sent once crash reports are on, so it counts the people who turned them on.
     */
    public static void onboardingFinished(boolean completed) {
        SentryManager.count("onboarding.finished", 1, "outcome", completed ? "finished" : "left");
    }

    // ---- every other metric the app sends, one method each --------------------------------------

    /** How long a whole job took, as {@code processing.duration_ms}. */
    public static void jobDuration(@NonNull String job, long durationMs) {
        SentryManager.distributionDurationMs("processing.duration_ms", durationMs, "operation_type", job);
    }

    /** A file the already-clean check could not read, as {@code already_clean.check{error}}. */
    public static void alreadyCleanError(@NonNull Throwable error) {
        SentryManager.count("already_clean.check", 1, "outcome", "error",
                "error_type", error.getClass().getSimpleName());
    }

    /** Files the already-clean check had no time left for, as {@code already_clean.check{timed_out}}. */
    public static void alreadyCleanTimedOut(int files) {
        SentryManager.count("already_clean.check", files, "outcome", "timed_out");
    }

    /** Working files an earlier run left behind, found at start-up, as {@code cache.orphans_found}. */
    public static void orphansFound(int files) {
        SentryManager.count("cache.orphans_found", files);
    }

    /** A picture that had to be decoded smaller, or could not be, as {@code memory.low}. */
    public static void lowMemory(@NonNull String outcome) {
        SentryManager.count("memory.low", 1, "outcome", outcome);
    }

    /** A shared item that could not be copied in, as {@code share.snapshot.failure}. */
    public static void snapshotFailure(@NonNull String errorType) {
        SentryManager.count("share.snapshot.failure", 1, "error_type", errorType);
    }

    /** The chosen output folder failed and the default was used, as {@code output.folder_fallback}. */
    public static void outputFolderFallback(boolean video, @NonNull String errorType) {
        SentryManager.count("output.folder_fallback", 1, "media_type", mediaType(video), "error_type", errorType);
    }

    /** A cleaned file that failed its check, as {@code verification.failed}, by why. */
    public static void verificationFailed(@NonNull String mediaType, @NonNull String reason) {
        SentryManager.count("verification.failed", 1, "media_type", mediaType, "outcome", reason);
    }

    /** A large-job or low-storage warning shown before a job, as {@code resource.warning}. */
    public static void resourceWarning(@NonNull String outcome) {
        SentryManager.count("resource.warning", 1, "outcome", outcome);
    }

    /** A secure delete whose overwrite failed, so the file was only unlinked. */
    public static void secureDeleteOverwriteFailed(@NonNull Throwable error) {
        SentryManager.count("secure_delete.fallback", 1, "outcome", "overwrite_failed",
                "error_type", error.getClass().getSimpleName());
    }

    /** A secure delete that could not even unlink the file. */
    public static void secureDeleteUnlinkFailed() {
        SentryManager.count("secure_delete.fallback", 1, "outcome", "unlink_failed");
    }

    /** A video encoded at a smaller size than its source, as {@code video.downscaled}. */
    public static void videoDownscaled(int shortSide) {
        SentryManager.count("video.downscaled", 1, "outcome", shortSide + "p");
    }

    /** A transcode attempt that failed, as {@code video.transcode.failure}, by codec and reason. */
    public static void transcodeFailure(@NonNull String codec, @NonNull String reason) {
        SentryManager.count("video.transcode.failure", 1, "mime_type", codec, "error_type", reason);
    }

    /** A transcode tried again at a smaller size, as {@code video.retry_smaller}. */
    public static void retrySmaller(@NonNull String outcome) {
        SentryManager.count("video.retry_smaller", 1, "media_type", "video", "outcome", outcome);
    }

    /** A part of a file a clean had to leave out (a Motion Photo's video, a gain map...). */
    public static void partDropped(boolean video, @NonNull String part) {
        SentryManager.count("clean.part_dropped", 1, "media_type", mediaType(video), "outcome", part);
    }

    /** Which way a file was cleaned (rebuilt, fast copy, transcoded...), as {@code clean.path}. */
    public static void cleanPath(boolean video, @NonNull String path) {
        SentryManager.count("clean.path", 1, "media_type", mediaType(video), "outcome", path);
    }

    /** A shared item in a type the app does not take, by its MIME type, as {@code media.unsupported}. */
    public static void unsupportedMedia(@NonNull String mimeTypeTag) {
        SentryManager.count("media.unsupported", 1, "mime_type", mimeTypeTag);
    }

    /** A censored photo saved smaller than the original, as {@code censor.save{downscaled}}. */
    public static void censorSavedSmaller() {
        SentryManager.count("censor.save", 1, "outcome", "downscaled");
    }

    /** Why Android ended the app's process and in what state, as {@code app.exit}. */
    public static void appExit(@NonNull String reason, @NonNull String importance, long rssKb) {
        SentryManager.count("app.exit", 1, "outcome", reason, "processing_state", importance);
        if (rssKb > 0) {
            SentryManager.distribution("app.exit.memory_mb", rssKb / 1024.0, "outcome", reason);
        }
    }
}
