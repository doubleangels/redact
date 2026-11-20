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
     * One file a job finished with {@code outcome}, counted as {@code job.file_outcome} by job and
     * media type. A successful file also records how long it took, as
     * {@code job.file.duration}.
     */
    public static void item(@NonNull String job, boolean video, @NonNull String outcome, long durationMs) {
        SentryManager.count("job.file_outcome", 1,
                "job.type", job, "file.media_type", mediaType(video), "outcome", outcome);
        if (SUCCESS.equals(outcome) && durationMs >= 0) {
            SentryManager.distributionDurationMs("job.file.duration", durationMs,
                    "job.type", job, "file.media_type", mediaType(video));
        }
    }

    /**
     * One file a job could not finish, counted as {@code job.file_outcome} like {@link #item}, with
     * the kind of error as well: an exception's class name, or {@code unsupported} or
     * {@code clean_failed} when the job said so without one.
     */
    public static void itemFailed(@NonNull String job, boolean video, @NonNull String outcome,
                                  @NonNull String errorType) {
        SentryManager.count("job.file_outcome", 1,
                "job.type", job, "file.media_type", mediaType(video), "outcome", outcome,
                "error_type", errorType);
    }

    /** {@link #itemFailed} for a file that threw {@code error}. */
    public static void itemFailed(@NonNull String job, boolean video, @NonNull Throwable error,
                                  boolean unsupported) {
        itemFailed(job, video, outcomeOf(error, unsupported), SentryManager.errorType(error));
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
     * How many files a job was given, as the {@code job.batch.file_count} distribution, and how
     * hot the phone was when it started, as {@code job.batch_start}.
     */
    public static void batchStarted(@NonNull String job, int size) {
        SentryManager.distribution("job.batch.file_count", size, "job.type", job);
        SentryManager.count("job.batch_start", 1,
                "job.type", job, "device.thermal_status", SentryManager.thermalStatus());
    }

    /**
     * How a job ended, counted as {@code job.batch_outcome}: success (every file), partial (some),
     * failed (none) or cancelled.
     */
    public static void batchFinished(@NonNull String job, int succeeded, int failed, boolean cancelled) {
        SentryManager.count("job.batch_outcome", 1, "job.type", job, "outcome",
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

    /** A converted file by the format it was converted to, as {@code convert.output_format}. */
    public static void convertFormat(@NonNull String targetMimeType, @NonNull String outcome) {
        SentryManager.count("convert.output_format", 1, "file.output_mime_type", targetMimeType, "outcome", outcome);
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

    /** A video conversion that fell back to another codec, by the codec used, as {@code convert.video_codec_fallback}. */
    public static void codecFallback(int usedFormatIndex) {
        SentryManager.count("convert.video_codec_fallback", 1, "video.output_codec", videoFormatMimeType(usedFormatIndex));
    }

    /**
     * Whether a photo opened in Censor could be decoded (success, unsupported or failed), as
     * {@code censor.photo_open}.
     */
    public static void censorLoad(@NonNull String outcome) {
        SentryManager.count("censor.photo_open", 1, "outcome", outcome);
    }

    /** How many regions a saved censored copy covers, as the {@code censor.copy_save.region_count} distribution. */
    public static void censorRegions(int regions) {
        SentryManager.distribution("censor.copy_save.region_count", regions);
    }

    /** A clean that passed every check in {@code CleanVerifier}, as {@code verify.clean_check_outcome{passed}}. */
    public static void verificationPassed(boolean video) {
        SentryManager.count("verify.clean_check_outcome", 1, "file.media_type", mediaType(video), "outcome", "passed");
    }

    /**
     * What the already-clean check found for one file (already_clean, needs_cleaning, unknown and
     * so on, from its {@code Status} names), as {@code already_clean.file_status}.
     */
    public static void alreadyCleanResult(@NonNull String status) {
        SentryManager.count("already_clean.file_status", 1, "outcome", status.toLowerCase(Locale.ROOT));
    }

    /** How long the already-clean check took for a whole selection, as {@code already_clean.selection_check.duration}. */
    public static void alreadyCleanDuration(long durationMs) {
        SentryManager.distributionDurationMs("already_clean.selection_check.duration", durationMs, "job.type", CLEAN);
    }

    /**
     * An update check's result (newer, up_to_date or failed) and duration, as
     * {@code update.release_check} and {@code update.release_check.duration}.
     */
    public static void updateCheck(@NonNull String outcome, long durationMs) {
        SentryManager.count("update.release_check", 1, "outcome", outcome);
        if (durationMs >= 0) {
            SentryManager.distributionDurationMs("update.release_check.duration", durationMs, "outcome", outcome);
        }
    }

    /**
     * The answer to a runtime permission request, by permission (images, videos, location,
     * notifications, storage or other), as {@code permission.request_answer}.
     */
    public static void permissionResult(@Nullable String permission, boolean granted) {
        SentryManager.count("permission.request_answer", 1,
                "permission.type", permissionType(permission),
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
    public static String permissionType(@Nullable String permission) {
        if (permission == null) {
            return "other";
        }
        return switch (permission) {
            case "android.permission.READ_MEDIA_IMAGES" -> "images";
            case "android.permission.READ_MEDIA_VIDEO" -> "videos";
            case "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" -> "selected_media";
            case "android.permission.ACCESS_MEDIA_LOCATION" -> "media_gps";
            case "android.permission.POST_NOTIFICATIONS" -> "notifications";
            case "android.permission.READ_EXTERNAL_STORAGE",
                 "android.permission.WRITE_EXTERNAL_STORAGE" -> "storage";
            default -> "other";
        };
    }

    /**
     * The processing foreground service failing, by stage (start_service when Android refuses to
     * start it, start_foreground when it can't enter the foreground, no_notification_permission)
     * and exception class, as {@code service.foreground_start_failure}.
     */
    public static void foregroundServiceFailure(@NonNull String stage, @NonNull Throwable error) {
        SentryManager.count("service.foreground_start_failure", 1,
                "outcome", FAILED, "reason", stage, "error_type", SentryManager.errorType(error));
    }

    /**
     * A working copy overwritten before deletion, and how long the overwrite took, as
     * {@code secure_delete.overwrite.duration}. With {@code secure_delete.failure} it gives the failure rate.
     */
    public static void secureDeleteOverwrite(long durationMs) {
        SentryManager.distributionDurationMs("secure_delete.overwrite.duration", durationMs, "outcome", SUCCESS);
    }

    /** A notification Android refused to post, by exception class, as {@code notification.post_failure}. */
    public static void notificationFailure(@NonNull Throwable error) {
        SentryManager.count("notification.post_failure", 1, "error_type", SentryManager.errorType(error));
    }

    /**
     * How the introduction ended (finished, or left with Back), as {@code onboarding.intro_exit}.
     * Only sent once crash reports are on, so it counts the people who turned them on.
     */
    public static void onboardingFinished(boolean completed) {
        SentryManager.count("onboarding.intro_exit", 1, "outcome", completed ? "finished" : "left");
    }

    // ---- every other metric the app sends, one method each --------------------------------------

    /** How long a whole job took, as {@code job.batch.duration}. */
    public static void jobDuration(@NonNull String job, long durationMs) {
        SentryManager.distributionDurationMs("job.batch.duration", durationMs, "job.type", job);
    }

    /** A file the already-clean check could not read, as {@code already_clean.file_status{error}}. */
    public static void alreadyCleanError(@NonNull Throwable error) {
        SentryManager.count("already_clean.file_status", 1, "outcome", "error",
                "error_type", SentryManager.errorType(error));
    }

    /** Files the already-clean check had no time left for, as {@code already_clean.file_status{timed_out}}. */
    public static void alreadyCleanTimedOut(int files) {
        SentryManager.count("already_clean.file_status", files, "outcome", "timed_out");
    }

    /** Working files an earlier run left behind, found at start-up, as {@code cache.leftover_file}. */
    public static void orphansFound(int files) {
        SentryManager.count("cache.leftover_file", files);
    }

    /** A picture that had to be decoded smaller, or could not be, as {@code memory.decode_shortage}. */
    public static void lowMemory(@NonNull String outcome) {
        SentryManager.count("memory.decode_shortage", 1, "outcome", outcome);
    }

    /** A shared item that could not be copied in, as {@code share.inbound_copy_failure}. */
    public static void snapshotFailure(@NonNull String errorType) {
        SentryManager.count("share.inbound_copy_failure", 1, "error_type", errorType);
    }

    /** The chosen output folder failed and the default was used, as {@code output.custom_folder_fallback}. */
    public static void outputFolderFallback(boolean video, @NonNull String errorType) {
        SentryManager.count("output.custom_folder_fallback", 1, "file.media_type", mediaType(video), "error_type", errorType);
    }

    /** A cleaned file that failed its check, as {@code verify.clean_check_outcome{failed}}, by why. */
    public static void verificationFailed(@NonNull String mediaType, @NonNull String reason) {
        SentryManager.count("verify.clean_check_outcome", 1, "file.media_type", mediaType, "outcome", FAILED, "reason", reason);
    }

    /** A large-job or low-storage warning shown before a job, as {@code job.resource_warning}. */
    public static void resourceWarning(@NonNull String outcome) {
        SentryManager.count("job.resource_warning", 1, "reason", outcome);
    }

    /** A secure delete whose overwrite failed, so the file was only unlinked. */
    public static void secureDeleteOverwriteFailed(@NonNull Throwable error) {
        SentryManager.count("secure_delete.failure", 1, "reason", "overwrite_failed",
                "error_type", SentryManager.errorType(error));
    }

    /** A secure delete that could not even unlink the file. */
    public static void secureDeleteUnlinkFailed() {
        SentryManager.count("secure_delete.failure", 1, "reason", "unlink_failed");
    }

    /** A video encoded at a smaller size than its source, as {@code video.output_downscale}. */
    public static void videoDownscaled(int shortSide) {
        SentryManager.count("video.output_downscale", 1, "outcome", shortSide + "p");
    }

    /** A transcode attempt that failed, as {@code video.transcode_attempt_failure}, by codec and reason. */
    public static void transcodeFailure(@NonNull String codec, @NonNull String reason) {
        SentryManager.count("video.transcode_attempt_failure", 1, "video.output_codec", codec, "reason", reason);
    }

    /** A transcode tried again at a smaller size, as {@code video.smaller_size_retry}. */
    public static void retrySmaller(@NonNull String outcome) {
        SentryManager.count("video.smaller_size_retry", 1, "file.media_type", "video", "outcome", outcome);
    }

    /** A part of a file a clean had to leave out (a Motion Photo's video, a gain map...). */
    public static void partDropped(boolean video, @NonNull String part) {
        SentryManager.count("clean.discarded_part", 1, "file.media_type", mediaType(video), "reason", part);
    }

    /** Which way a file was cleaned (rebuilt, fast copy, transcoded...), as {@code clean.method_used}. */
    public static void cleanPath(boolean video, @NonNull String path) {
        SentryManager.count("clean.method_used", 1, "file.media_type", mediaType(video), "outcome", path);
    }

    /** A shared item in a type the app does not take, by its MIME type, as {@code media.unsupported_shared_type}. */
    public static void unsupportedMedia(@NonNull String mimeTypeTag) {
        SentryManager.count("media.unsupported_shared_type", 1, "file.mime_type", mimeTypeTag);
    }

    /** A censored photo saved smaller than the original, as {@code censor.downscaled_save{downscaled}}. */
    public static void censorSavedSmaller() {
        SentryManager.count("censor.downscaled_save", 1, "outcome", SUCCESS, "reason", "downscaled");
    }

    /** Another app the target is handed to. */
    public static final String EXTERNAL_MAPS = "maps";
    public static final String EXTERNAL_BROWSER = "browser";
    public static final String EXTERNAL_STORE = "store";
    public static final String EXTERNAL_SHARE_SHEET = "share_sheet";
    public static final String EXTERNAL_FOLDER_PICKER = "folder_picker";
    public static final String EXTERNAL_FILE_MANAGER = "file_manager";
    public static final String EXTERNAL_FILE_PICKER = "file_picker";
    public static final String EXTERNAL_SYSTEM_SETTINGS = "system_settings";

    /**
     * A button that hands off to another app found none to take it (no maps app, browser, Play
     * Store, share target, file picker, file manager or system settings), by which kind, as
     * {@code ui.external_app_missing}.
     */
    public static void externalAppMissing(@NonNull String target) {
        SentryManager.count("ui.external_app_missing", 1, "reason", target);
    }

    /** Android refused to keep access to a folder chosen for output, as {@code output.folder_grant_failure}. */
    public static void outputFolderGrantFailure(boolean video) {
        SentryManager.count("output.folder_grant_failure", 1, "file.media_type", mediaType(video));
    }

    /**
     * Facts about a video source or the phone's encoders that could not be read, so a transcode
     * fell back to a safe default, by what (tracks, retriever, audio_tracks, video_encoder,
     * audio_encoder), as {@code video.source_probe_failure}.
     */
    public static void videoProbeFailure(@NonNull String what, @NonNull Throwable error) {
        SentryManager.count("video.source_probe_failure", 1,
                "reason", what, "error_type", SentryManager.errorType(error));
    }

    /** Working files older than a day that the automatic cleanup removed, as {@code cache.stale_file_removal}. */
    public static void staleFilesRemoved(int files) {
        if (files > 0) {
            SentryManager.count("cache.stale_file_removal", files);
        }
    }

    /**
     * An unfinished copy an earlier run left in a chosen output folder, removed (success) or not
     * (failed, with the kind of error), as {@code output.unfinished_file_removal}.
     */
    public static void unfinishedOutputRemoval(@Nullable Throwable error) {
        if (error == null) {
            SentryManager.count("output.unfinished_file_removal", 1, "outcome", SUCCESS);
        } else {
            SentryManager.count("output.unfinished_file_removal", 1,
                    "outcome", FAILED, "error_type", SentryManager.errorType(error));
        }
    }

    /** Why Android ended the app's process and in what state, as {@code app.process_exit}. */
    public static void appExit(@NonNull String reason, @NonNull String importance, long rssKb) {
        SentryManager.count("app.process_exit", 1, "reason", reason, "app.process_importance", importance);
        if (rssKb > 0) {
            SentryManager.distributionMegabytes("app.process_exit.memory_used", rssKb / 1024.0, "reason", reason);
        }
    }
}
