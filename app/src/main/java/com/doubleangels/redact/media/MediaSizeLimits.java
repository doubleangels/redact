package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import com.doubleangels.redact.AppPreferences;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shared size guards for inbound media streams.
 */
public final class MediaSizeLimits {

    /** Default cap for share-in video when no app preference exists (~200 MB). */
    public static final long DEFAULT_SHARE_VIDEO_MAX_BYTES = 200L * 1024L * 1024L;

    private MediaSizeLimits() {
    }

    public static long maxImageBytes(@NonNull Context context) {
        return AppPreferences.getMaxImageFileSizeMb(context) * 1024L * 1024L;
    }

    public static long maxVideoBytes() {
        return DEFAULT_SHARE_VIDEO_MAX_BYTES;
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

    public static boolean isWithinLimit(long declaredSize, long maxBytes) {
        return declaredSize > 0 && declaredSize <= maxBytes;
    }

    /**
     * Copies from {@code in} to {@code out} while enforcing {@code maxBytes}. Returns bytes copied.
     */
    public static long copyWithLimit(
            @NonNull InputStream in, @NonNull java.io.OutputStream out, long maxBytes)
            throws IOException {
        return copyWithLimit(in, out, maxBytes, null);
    }

    public static long copyWithLimit(
            @NonNull InputStream in,
            @NonNull java.io.OutputStream out,
            long maxBytes,
            @androidx.annotation.Nullable Runnable cancelCheck)
            throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (cancelCheck != null) {
                cancelCheck.run();
            }
            total += read;
            if (total > maxBytes) {
                throw new IOException("stream_exceeds_size_limit");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }
}
