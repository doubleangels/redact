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
        tryTakePersistableReadPermission(uri);
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

    private void tryTakePersistableReadPermission(Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.d(TAG, "Persistable permission not available for picker URI");
        } catch (Exception e) {
            Log.e(TAG, "Failed to take persistable permission for media URI", e);
            SentryManager.recordException(e);
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

    public String getFileName(Uri uri) {
        String result = MediaUriResolver.readDisplayName(activity, uri);
        return result != null ? result : "media";
    }
}
