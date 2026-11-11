package com.doubleangels.redact.media;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.sentry.SentryManager;

import java.util.List;

/**
 * Resolves content URIs (picker, documents, etc.) to canonical MediaStore URIs for
 * reliable {@link OpenableColumns#DISPLAY_NAME} reads.
 */
public final class MediaUriResolver {

    private MediaUriResolver() {
    }

    /**
     * Returns the best URI to query for display metadata, preferring a MediaStore content URI.
     */
    @NonNull
    public static Uri resolveForDisplayNameQuery(@NonNull Context context, @NonNull Uri uri) {
        Uri resolved = resolveToMediaStoreUri(context, uri);
        return resolved != null ? resolved : uri;
    }

    @Nullable
    public static String readDisplayName(@NonNull Context context, @NonNull Uri uri) {
        Uri queryUri = resolveForDisplayNameQuery(context, uri);
        if (!"content".equals(queryUri.getScheme())) {
            return queryUri.getLastPathSegment();
        }
        try (Cursor cursor = context.getContentResolver().query(
                queryUri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (SecurityException e) {
            // The sender shared a URI without granting read access, which only the sender can fix.
            SentryManager.log("The display name query was denied: " + e.getClass().getSimpleName() + ".");
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
        String fallback = queryUri.getLastPathSegment();
        return fallback != null && !fallback.isEmpty() ? fallback : null;
    }

    @Nullable
    public static Uri resolveToMediaStoreUri(@NonNull Context context, @NonNull Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return uri;
        }
        if (!isPhotoPickerUri(uri)) {
            try {
                Uri mediaUri = MediaStore.getMediaUri(context, uri);
                if (mediaUri != null && canQueryMediaUri(context, mediaUri)) {
                    return mediaUri;
                }
            } catch (Exception e) {
                SentryManager.log("The MediaStore.getMediaUri call failed: " + e.getClass().getSimpleName() + ".");
            }
        }
        if (!isPhotoPickerUri(uri)) {
            return uri;
        }
        String mimeType = null;
        try {
            mimeType = context.getContentResolver().getType(uri);
        } catch (Exception e) {
            SentryManager.log("The picker MIME type failed to resolve: " + e.getClass().getSimpleName() + ".");
        }
        boolean isVideo = MediaSelector.isVideoFromMimeAndName(mimeType, uri.getLastPathSegment());

        String lastSegment = uri.getLastPathSegment();
        if (lastSegment != null) {
            try {
                long mediaId = Long.parseLong(lastSegment);
                Uri collection = isVideo
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                Uri candidate = ContentUris.withAppendedId(collection, mediaId);
                if (canQueryMediaUri(context, candidate)) {
                    return candidate;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to alternate image collection if video lookup failed.
            }
        }

        if (lastSegment != null) {
            try {
                long mediaId = Long.parseLong(lastSegment);
                Uri alternate = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId);
                if (canQueryMediaUri(context, alternate)) {
                    return alternate;
                }
                Uri videoAlternate = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                if (canQueryMediaUri(context, videoAlternate)) {
                    return videoAlternate;
                }
            } catch (NumberFormatException ignored) {
                // Keep picker URI.
            }
        }
        return uri;
    }

    private static boolean canQueryMediaUri(Context context, Uri mediaUri) {
        try (Cursor cursor = context.getContentResolver().query(
                mediaUri,
                new String[]{MediaStore.MediaColumns._ID},
                null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            SentryManager.log("The MediaStore URI probe failed: " + e.getClass().getSimpleName() + ".");
            return false;
        }
    }

    public static boolean isPhotoPickerUri(@Nullable Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return false;
        }
        List<String> segments = uri.getPathSegments();
        if (segments != null) {
            for (String segment : segments) {
                if (segment.contains("picker")) {
                    return true;
                }
            }
        }
        String authority = uri.getAuthority();
        return authority != null && authority.contains("picker");
    }

    /**
     * Whether a content URI names one of this app's own providers. Android drops a
     * {@code userId@} prefix from an authority before it finds the provider, so
     * {@code 0@<package>.fileprovider} still opens Redact's FileProvider: the prefix is dropped
     * here too, and the system is asked who owns what is left. A URI with no authority, or an
     * owner the lookup cannot name, counts as Redact's own so that callers refuse it.
     */
    public static boolean isOwnProvider(@NonNull Context context, @NonNull Uri uri) {
        String authority = uri.getAuthority();
        if (authority == null) {
            return true;
        }
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        if (authority.isEmpty()) {
            return true;
        }
        String packageName = context.getPackageName();
        try {
            ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, 0);
            return info != null && packageName.equals(info.packageName);
        } catch (RuntimeException e) {
            SentryManager.log("The provider owner lookup failed: " + e.getClass().getSimpleName() + ".");
            return true;
        }
    }
}
