package com.doubleangels.redact.media;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.sentry.SentryManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.SecureRandom;

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
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureDelete() {
    }

    public static boolean secureDelete(@NonNull Context context, @NonNull File file) {
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        try {
            long fileSize = file.length();
            if (fileSize == 0) {
                return file.delete();
            }

            byte[] randomData = new byte[BUFFER_SIZE];
            // "rw" plus one sync at the end: "rws" synced every 64 KB write, which made deleting a
            // large video take minutes. The overwrite still reaches storage before the unlink.
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                long position = 0;
                while (position < fileSize) {
                    RANDOM.nextBytes(randomData);
                    int bytesToWrite = (int) Math.min(randomData.length, fileSize - position);
                    raf.write(randomData, 0, bytesToWrite);
                    position += bytesToWrite;
                }
                raf.getFD().sync();
            }

            return file.delete();
        } catch (Exception e) {
            SentryManager.log("An error occurred during secure file deletion: " + e.getMessage() + ".");
            return file.delete();
        }
    }
}
