package com.doubleangels.redact.media;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.SecureRandom;

/**
 * Overwrites a file's contents with random data (per the user's configured pass count) before
 * unlinking it, so a plain filesystem-level recovery cannot recover deleted media bytes.
 *
 * <p>Use this instead of {@link File#delete()} for anything that may still hold original,
 * unredacted media (source snapshots, transcode intermediates, processed outputs) — a bare
 * {@code delete()} only removes the directory entry and leaves the data blocks recoverable.
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

            int passes = AppPreferences.getSecureDeletePasses(context);
            byte[] randomData = new byte[BUFFER_SIZE];
            for (int pass = 0; pass < passes; pass++) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
                    long position = 0;
                    while (position < fileSize) {
                        RANDOM.nextBytes(randomData);
                        int bytesToWrite = (int) Math.min(randomData.length, fileSize - position);
                        raf.write(randomData, 0, bytesToWrite);
                        position += bytesToWrite;
                    }
                    raf.getFD().sync();
                }
            }

            return file.delete();
        } catch (Exception e) {
            SentryManager.log("Error during secure file deletion: " + e.getMessage() + ".");
            return file.delete();
        }
    }
}
