package com.doubleangels.redact;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.Locale;

/**
 * Utilities for measuring and clearing temporary processing files in app cache.
 */
public final class CacheCleanup {

    private static final String PROCESSED_SUBDIR = "processed";
    private static final String TEMP_PREFIX = "temp_";

    private CacheCleanup() {
    }

    public static long getTempCacheSizeBytes(@NonNull Context context) {
        long total = 0;
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) {
            File processedDir = new File(cacheDir, PROCESSED_SUBDIR);
            total += directorySizeBytes(processedDir);
        }
        File externalCacheDir = context.getExternalCacheDir();
        if (externalCacheDir != null && externalCacheDir.isDirectory()) {
            total += tempPrefixSizeBytes(externalCacheDir);
        }
        return total;
    }

    public static int clearAllTempFiles(@NonNull Context context) {
        int deleted = 0;
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) {
            deleted += deleteAllFilesInDirectory(new File(cacheDir, PROCESSED_SUBDIR));
        }
        File externalCacheDir = context.getExternalCacheDir();
        if (externalCacheDir != null && externalCacheDir.isDirectory()) {
            deleted += deleteTempPrefixFiles(externalCacheDir);
        }
        return deleted;
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
            if (file.isFile() && file.getName().startsWith(TEMP_PREFIX)) {
                total += file.length();
            }
        }
        return total;
    }

    private static int deleteAllFilesInDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return 0;
        }
        int deleted = 0;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.isFile() && file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    private static int deleteTempPrefixFiles(File directory) {
        int deleted = 0;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(TEMP_PREFIX) && file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }
}
