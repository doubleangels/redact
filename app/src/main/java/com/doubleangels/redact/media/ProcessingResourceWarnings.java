package com.doubleangels.redact.media;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.doubleangels.redact.CacheCleanup;
import com.doubleangels.redact.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pre-flight checks for storage and memory pressure before starting heavy media work.
 */
public final class ProcessingResourceWarnings {

    /** Advisory when free cache space falls below this (~1 GB). */
    private static final long LOW_FREE_STORAGE_BYTES = 1024L * 1024L * 1024L;

    /** Hard floor — always critical below this (~256 MB). */
    private static final long CRITICAL_FREE_STORAGE_BYTES = 256L * 1024L * 1024L;

    /** Advisory when the system reports less than this available RAM (~512 MB). */
    private static final long LOW_AVAILABLE_RAM_BYTES = 512L * 1024L * 1024L;

    /** Extra advisory for very large single videos (~2 GB). */
    private static final long LARGE_VIDEO_BYTES = 2L * 1024L * 1024L * 1024L;

    private static final double VIDEO_TEMP_MULTIPLIER = 2.5;
    private static final double IMAGE_TEMP_MULTIPLIER = 1.5;

    public enum Severity {
        ADVISORY,
        CRITICAL
    }

    public static final class Warning {
        public final Severity severity;
        @StringRes public final int messageResId;
        @Nullable public final Object[] formatArgs;

        Warning(Severity severity, @StringRes int messageResId, @Nullable Object... formatArgs) {
            this.severity = severity;
            this.messageResId = messageResId;
            this.formatArgs = formatArgs != null && formatArgs.length > 0 ? formatArgs : null;
        }
    }

    private ProcessingResourceWarnings() {
    }

    @NonNull
    public static List<Warning> assess(@NonNull Context context, @NonNull List<MediaItem> items) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        long estimatedTemp = CacheCleanup.getTempCacheSizeBytes(context);
        boolean hasLargeVideo = false;
        ContentResolver resolver = context.getContentResolver();
        for (MediaItem item : items) {
            long declared = MediaSizeLimits.declaredSizeBytes(resolver, item.uri());
            estimatedTemp += estimateTempBytesForItem(declared, item.isVideo());
            if (item.isVideo() && declared >= LARGE_VIDEO_BYTES) {
                hasLargeVideo = true;
            }
        }
        return buildWarnings(context, estimatedTemp, hasLargeVideo);
    }

    @NonNull
    public static List<Warning> assessShareUris(
            @NonNull Context context,
            @NonNull ContentResolver resolver,
            @NonNull List<Uri> uris,
            @NonNull MediaSelector mediaSelector) {
        if (uris.isEmpty()) {
            return Collections.emptyList();
        }
        long estimatedTemp = CacheCleanup.getTempCacheSizeBytes(context);
        boolean hasLargeVideo = false;
        for (Uri uri : uris) {
            if (uri == null) {
                continue;
            }
            String fileName = mediaSelector.getFileName(uri);
            String mimeType = resolver.getType(uri);
            boolean isVideo = MediaSelector.isVideoFromMimeAndName(mimeType, fileName);
            long declared = MediaSizeLimits.declaredSizeBytes(resolver, uri);
            estimatedTemp += estimateTempBytesForItem(declared, isVideo);
            if (isVideo && declared >= LARGE_VIDEO_BYTES) {
                hasLargeVideo = true;
            }
        }
        return buildWarnings(context, estimatedTemp, hasLargeVideo);
    }

    /**
     * Shows advisory toasts, then runs {@code onProceed} immediately or after a critical-storage dialog.
     */
    public static void runWithWarnings(
            @NonNull FragmentActivity activity,
            @NonNull List<Warning> warnings,
            @NonNull Runnable onProceed) {
        for (Warning warning : warnings) {
            if (warning.severity == Severity.ADVISORY) {
                showToast(activity, warning);
            }
        }
        Warning criticalStorage = findCriticalStorageWarning(warnings);
        if (criticalStorage != null) {
            String message =
                    criticalStorage.formatArgs != null
                            ? activity.getString(
                                    criticalStorage.messageResId, criticalStorage.formatArgs)
                            : activity.getString(criticalStorage.messageResId);
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.processing_warning_critical_storage_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.processing_warning_continue, (d, w) -> onProceed.run())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        onProceed.run();
    }

    @VisibleForTesting
    static long estimateTempBytesForItem(long declaredSize, boolean isVideo) {
        if (declaredSize <= 0) {
            return isVideo ? 1024L * 1024L * 1024L : 128L * 1024L * 1024L;
        }
        double multiplier = isVideo ? VIDEO_TEMP_MULTIPLIER : IMAGE_TEMP_MULTIPLIER;
        return (long) Math.ceil(declaredSize * multiplier);
    }

    @NonNull
    private static List<Warning> buildWarnings(
            @NonNull Context context, long estimatedTempBytes, boolean hasLargeVideo) {
        List<Warning> warnings = new ArrayList<>();
        long available = availableCacheBytes(context);

        if (isMemoryPressured(context)) {
            warnings.add(new Warning(Severity.ADVISORY, R.string.processing_warning_low_memory));
        }
        if (hasLargeVideo) {
            warnings.add(new Warning(Severity.ADVISORY, R.string.processing_warning_large_video));
        }

        if (available > 0) {
            if (available < CRITICAL_FREE_STORAGE_BYTES
                    || estimatedTempBytes > available) {
                warnings.add(
                        new Warning(
                                Severity.CRITICAL,
                                R.string.processing_warning_critical_storage_message,
                                CacheCleanup.formatSize(estimatedTempBytes),
                                CacheCleanup.formatSize(available)));
            } else if (available < LOW_FREE_STORAGE_BYTES
                    || estimatedTempBytes > available / 2) {
                warnings.add(
                        new Warning(
                                Severity.ADVISORY,
                                R.string.processing_warning_low_storage,
                                CacheCleanup.formatSize(available)));
            }
        }
        return warnings;
    }

    private static boolean isMemoryPressured(@NonNull Context context) {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.lowMemory || memoryInfo.availMem < LOW_AVAILABLE_RAM_BYTES;
    }

    private static long availableCacheBytes(@NonNull Context context) {
        long available = Long.MAX_VALUE;
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) {
            available = Math.min(available, availableBytesForPath(cacheDir));
        }
        File externalCacheDir = context.getExternalCacheDir();
        if (externalCacheDir != null) {
            available = Math.min(available, availableBytesForPath(externalCacheDir));
        }
        return available == Long.MAX_VALUE ? 0L : available;
    }

    private static long availableBytesForPath(@NonNull File path) {
        try {
            StatFs statFs = new StatFs(path.getAbsolutePath());
            return statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    @Nullable
    private static Warning findCriticalStorageWarning(@NonNull List<Warning> warnings) {
        for (Warning warning : warnings) {
            if (warning.severity == Severity.CRITICAL) {
                return warning;
            }
        }
        return null;
    }

    private static void showToast(@NonNull Context context, @NonNull Warning warning) {
        String text =
                warning.formatArgs != null
                        ? context.getString(warning.messageResId, warning.formatArgs)
                        : context.getString(warning.messageResId);
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
