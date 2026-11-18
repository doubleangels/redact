package com.doubleangels.redact.media;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.doubleangels.redact.sentry.AppMetrics;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Overwrites a file's contents once with random data before unlinking it, so its old bytes are not
 * left behind as they were.
 *
 * <p>This is a best effort, not a guarantee. On flash storage the overwrite is usually written to
 * new blocks, leaving the old ones to be erased whenever the storage gets to them, so someone with
 * low-level access to the device may still recover the data. More passes would not change that,
 * which is why there is only one. What matters most is that every copy of the original is deleted
 * at all, and soon (see {@link com.doubleangels.redact.CacheCleanup}).
 *
 * <p>Use this instead of {@link File#delete()} for anything that may still hold original,
 * unredacted media (source snapshots, transcode intermediates). A file that never held any, such as
 * a cleaned copy being shared, is only unlinked: overwriting one that another app is still reading
 * would hand that app random bytes.
 */
public final class SecureDelete {

    private static final int BUFFER_SIZE = 65536;

    private SecureDelete() {
    }

    public static boolean secureDelete(@NonNull File file) {
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        try {
            long fileSize = file.length();
            if (fileSize == 0) {
                return file.delete();
            }

            long startedAt = SystemClock.elapsedRealtime();
            byte[] randomData = new byte[BUFFER_SIZE];
            ThreadLocalRandom random = ThreadLocalRandom.current();
            // "rw" plus one sync at the end: "rws" synced every 64 KB write, which made deleting a
            // large video take minutes. The overwrite still reaches storage before the unlink.
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                long position = 0;
                while (position < fileSize) {
                    // Fresh bytes for every chunk, so storage that compresses or deduplicates
                    // can't write the overwrite smaller. They need not be unpredictable, only not
                    // the original, so the per-thread generator does: no shared lock and far less
                    // CPU than SecureRandom when several large deletes run at once.
                    random.nextBytes(randomData);
                    int bytesToWrite = (int) Math.min(randomData.length, fileSize - position);
                    raf.write(randomData, 0, bytesToWrite);
                    position += bytesToWrite;
                }
                raf.getFD().sync();
            }
            AppMetrics.secureDeleteOverwrite(SystemClock.elapsedRealtime() - startedAt);

            return unlink(file);
        } catch (Exception e) {
            // The class only: a FileNotFoundException's message holds the file's path.
            SentryManager.logEvent("storage", "An error occurred during secure file deletion: "
                    + e.getClass().getSimpleName() + ".");
            AppMetrics.secureDeleteOverwriteFailed(e);
            return unlink(file);
        }
    }

    /**
     * Deletes the file and counts it when that fails: a working copy of original media may then
     * stay behind until the next sweep.
     */
    private static boolean unlink(@NonNull File file) {
        boolean deleted = file.delete();
        if (!deleted) {
            AppMetrics.secureDeleteUnlinkFailed();
        }
        return deleted;
    }
}
