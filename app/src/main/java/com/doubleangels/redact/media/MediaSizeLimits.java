package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared utilities for inbound media size lookup and stream copying.
 */
public final class MediaSizeLimits {

    private MediaSizeLimits() {
    }

    /**
     * Returns declared size from content resolver, or -1 if unknown.
     */
    public static long declaredSizeBytes(@NonNull ContentResolver resolver, @NonNull Uri uri) {
        try (Cursor cursor = resolver.query(
                uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    long size = cursor.getLong(idx);
                    if (size > 0) {
                        return size;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to PFD stat.
        }
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long length = pfd.getStatSize();
                if (length > 0) {
                    return length;
                }
            }
        } catch (Exception ignored) {
            // Unknown size.
        }
        return -1L;
    }

    /** Copies from {@code in} to {@code out}. Returns bytes copied. */
    public static long copyStream(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        return copyStream(in, out, null);
    }

    public static long copyStream(
            @NonNull InputStream in,
            @NonNull OutputStream out,
            @Nullable Runnable cancelCheck)
            throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (cancelCheck != null) {
                cancelCheck.run();
            }
            total += read;
            out.write(buffer, 0, read);
        }
        return total;
    }
}
