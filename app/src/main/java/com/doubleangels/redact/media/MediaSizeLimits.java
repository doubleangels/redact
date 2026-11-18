package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared utilities for inbound media size lookup and stream copying.
 */
public final class MediaSizeLimits {

    /** Matches the copy buffer size used elsewhere in the codebase (e.g. MetadataStripper). */
    private static final int COPY_BUFFER_SIZE = 65536;

    /** Left free by a copy into internal storage, for the clean's output and for the phone. */
    static final long FREE_SPACE_RESERVE_BYTES = 256L * 1024 * 1024;

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
        return copyStream(in, out, cancelCheck, Long.MAX_VALUE);
    }

    /**
     * {@link #copyStream(InputStream, OutputStream, Runnable)}, refusing to write more than
     * {@code maxBytes}: a source that never ends, or one larger than the room left, stops with a
     * {@link TooLargeException} before it fills the storage.
     */
    public static long copyStream(
            @NonNull InputStream in,
            @NonNull OutputStream out,
            @Nullable Runnable cancelCheck,
            long maxBytes)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (cancelCheck != null) {
                cancelCheck.run();
            }
            if (read > maxBytes - total) {
                throw new TooLargeException();
            }
            total += read;
            out.write(buffer, 0, read);
        }
        return total;
    }

    /**
     * What a copy into internal storage may take: the free space there, less a reserve kept for
     * the clean's own output and for the rest of the phone. Never less than zero.
     */
    public static long roomFor(@NonNull File dir) {
        return Math.max(0, dir.getUsableSpace() - FREE_SPACE_RESERVE_BYTES);
    }

    /** A copy that stopped because its source was larger than the room it was given. */
    public static final class TooLargeException extends IOException {
        public TooLargeException() {
            super("The source is larger than the free space allows");
        }
    }
}
