package com.doubleangels.redact.media;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import android.util.Log;

import androidx.annotation.Nullable;

import com.doubleangels.redact.sentry.SentryManager;

import java.util.Locale;

/**
 * Handles selection and processing of media files (images and videos).
 */
public final class MediaSelector {

    private static final String TAG = "MediaSelector";

    private final Activity activity;

    public MediaSelector(Activity activity) {
        this.activity = activity;
    }

    public MediaItem processMediaUri(Uri uri) {
        boolean persistable = tryTakePersistableReadPermission(uri);
        if (!persistable && "content".equals(uri.getScheme())) {
            SentryManager.setCustomKey("persistable_uri_permission", false);
        }
        String fileName = getFileName(uri);
        boolean isVideo = isVideoContent(uri, fileName);
        return new MediaItem(uri, isVideo, fileName);
    }

    public static void releasePersistableReadPermission(@NonNull Context context, @Nullable Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return;
        }
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.d(TAG, "Persistable release not available for URI");
        }
    }

    private boolean tryTakePersistableReadPermission(Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return true;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (SecurityException e) {
            Log.d(TAG, "Persistable permission not available for picker URI");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to take persistable permission for media URI", e);
            SentryManager.recordException(e);
            return false;
        }
    }

    boolean isVideoContent(Uri uri, String fileName) {
        String mimeType = null;
        try {
            mimeType = activity.getContentResolver().getType(uri);
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
        return isVideoFromMimeAndName(mimeType, fileName);
    }

    public static boolean isVideoFromMimeAndName(@Nullable String mimeType, @Nullable String fileName) {
        if (mimeType != null) {
            if (mimeType.startsWith("video/")) {
                return true;
            }
            if (mimeType.startsWith("image/")) {
                return false;
            }
        }
        String lower = fileName != null ? fileName.toLowerCase(Locale.US) : "";
        if (lower.endsWith(".mp4")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".mov")
                || lower.endsWith(".m4v")
                || lower.endsWith(".3gp")
                || lower.endsWith(".avi")) {
            return true;
        }
        if (lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".heic")
                || lower.endsWith(".heif")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")) {
            return false;
        }
        return false;
    }

    public static boolean isAnimatedImageFile(@Nullable String fileName, @Nullable String mimeType) {
        if (mimeType != null && "image/gif".equalsIgnoreCase(mimeType)) {
            return true;
        }
        if (fileName != null) {
            return fileName.toLowerCase(java.util.Locale.US).endsWith(".gif");
        }
        return false;
    }

    /**
     * Reads up to {@code maxBytes} from {@code uri} and detects animated WebP/APNG payloads.
     */
    public static boolean isAnimatedImageUri(@NonNull Context context, @NonNull Uri uri, int maxBytes) {
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return false;
            }
            byte[] buffer = new byte[Math.min(maxBytes, 65536)];
            int read = 0;
            int n;
            while (read < buffer.length && (n = in.read(buffer, read, buffer.length - read)) > 0) {
                read += n;
            }
            return containsAnimatedImagePayload(buffer, read);
        } catch (Exception e) {
            SentryManager.log("Animated image probe failed: " + e.getMessage());
            return false;
        }
    }

    static boolean containsAnimatedImagePayload(@NonNull byte[] data, int length) {
        if (length < 12) {
            return false;
        }
        if (data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return indexOf(data, length, "acTL".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) >= 0;
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && length >= 12
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            byte[] anim = "ANIM".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] anmf = "ANMF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            return indexOf(data, length, anim) >= 0 || indexOf(data, length, anmf) >= 0;
        }
        return false;
    }

    private static int indexOf(@NonNull byte[] haystack, int length, @NonNull byte[] needle) {
        if (needle.length == 0 || length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public boolean isAnimatedImage(@NonNull MediaItem item) {
        if (item.isVideo()) {
            return false;
        }
        String mime = null;
        try {
            mime = activity.getContentResolver().getType(item.uri());
        } catch (Exception ignored) {
            // Fall back to filename only.
        }
        if (mime != null && "image/gif".equalsIgnoreCase(mime)) {
            return true;
        }
        if (item.fileName() != null
                && item.fileName().toLowerCase(Locale.US).endsWith(".gif")) {
            return true;
        }
        if (mime != null || (item.fileName() != null && (
                item.fileName().toLowerCase(Locale.US).endsWith(".webp")
                        || item.fileName().toLowerCase(Locale.US).endsWith(".png")
                        || item.fileName().toLowerCase(Locale.US).endsWith(".apng")))) {
            return isAnimatedImageUri(activity, item.uri(), 65536);
        }
        return false;
    }

    public String getFileName(Uri uri) {
        String result = MediaUriResolver.readDisplayName(activity, uri);
        return result != null ? result : "media";
    }
}
