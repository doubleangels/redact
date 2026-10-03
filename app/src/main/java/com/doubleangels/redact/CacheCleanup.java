package com.doubleangels.redact;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.media.SecureDelete;

import java.io.File;
import java.util.Locale;

/**
 * Utilities for measuring and clearing temporary processing files in app cache.
 */
public final class CacheCleanup {

    /** Default age for startup stale-temp cleanup (24 hours). */
    public static final long DEFAULT_STALE_TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private static final String PROCESSED_SUBDIR = "processed";
    private static final String[] TEMP_PREFIXES = {
            "temp_", "verify_", "vid_transform_", "vid_transmux_", "inbound_"
    };
    /**
     * Grace period below which a temp file is left alone even for a "clear all" request, so a
     * file created by an operation that just started (after the caller's busy check passed but
     * before this runs on a background executor) is not deleted out from under it.
     */
    private static final long MIN_DELETE_AGE_MS = 10L * 1000L;

    private CacheCleanup() {
    }

    public static long getTempCacheSizeBytes(@NonNull Context context) {
        long total = 0;
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) {
            File processedDir = new File(cacheDir, PROCESSED_SUBDIR);
            total += directorySizeBytes(processedDir);
            total += tempPrefixSizeBytes(cacheDir);
        }
        File externalCacheDir = context.getExternalCacheDir();
        if (externalCacheDir != null && externalCacheDir.isDirectory()) {
            total += tempPrefixSizeBytes(externalCacheDir);
        }
        return total;
    }

    /**
     * Deletes temp files older than {@code maxAgeMs} in processed/ and known temp prefixes.
     */
    public static int clearStaleTempFiles(@NonNull Context context, long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        int deleted = 0;
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) {
            deleted += deleteStaleFilesInDirectory(context, new File(cacheDir, PROCESSED_SUBDIR), cutoff);
            deleted += deleteStaleTempPrefixFiles(context, cacheDir, cutoff);
        }
        File externalCacheDir = context.getExternalCacheDir();
        if (externalCacheDir != null && externalCacheDir.isDirectory()) {
            deleted += deleteStaleTempPrefixFiles(context, externalCacheDir, cutoff);
        }
        return deleted;
    }

    public static int clearAllTempFiles(@NonNull Context context) {
        return clearStaleTempFiles(context, MIN_DELETE_AGE_MS);
    }

    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static long directorySizeBytes(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return 0;
        }
        long total = 0;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    private static long tempPrefixSizeBytes(File directory) {
        long total = 0;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.isFile()) {
                String name = file.getName();
                for (String prefix : TEMP_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        total += file.length();
                        break;
                    }
                }
            }
        }
        return total;
    }

    private static int deleteStaleFilesInDirectory(
            @NonNull Context context, File directory, long cutoffTime) {
        if (directory == null || !directory.isDirectory()) {
            return 0;
        }
        int deleted = 0;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.isFile()
                    && file.lastModified() < cutoffTime
                    && SecureDelete.secureDelete(context, file)) {
                deleted++;
            }
        }
        return deleted;
    }

    private static int deleteStaleTempPrefixFiles(
            @NonNull Context context, File directory, long cutoffTime) {
        int deleted = 0;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (!file.isFile() || file.lastModified() >= cutoffTime) {
                continue;
            }
            String name = file.getName();
            for (String prefix : TEMP_PREFIXES) {
                if (name.startsWith(prefix)) {
                    if (SecureDelete.secureDelete(context, file)) {
                        deleted++;
                    }
                    break;
                }
            }
        }
        return deleted;
    }
}
