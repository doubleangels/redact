package com.doubleangels.redact.media;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles selection and processing of media files (images and videos).
 * <p>
 * This class encapsulates the functionality for launching Android's media picker,
 * processing the results, and converting them to application-specific MediaItem objects.
 * It takes care of requesting persistent permissions for the selected media URIs and
 * extracting metadata such as file names.
 *
 * @param activity            Parent activity for context and content resolution
 * @param mediaPickerLauncher Launcher for the media picker activity
 */
public record MediaSelector(Activity activity, ActivityResultLauncher<Intent> mediaPickerLauncher) {
    private static final String TAG = "MediaSelector";

    /**
     * Creates a new MediaSelector instance.
     *
     * @param activity            The parent activity used for context
     * @param mediaPickerLauncher The ActivityResultLauncher that will handle media picker results
     */
    public MediaSelector {
    }

    /**
     * Launches the system media picker to select images and videos.
     * <p>
     * Configures the intent to allow selection of both images and videos,
     * and enables multiple item selection.
     */
    public void selectMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        // Allow both image and video selection
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});

        // Enable multiple selection
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        mediaPickerLauncher.launch(intent);
    }

    /**
     * Processes a single media URI to create a MediaItem.
     * <p>
     * Takes a persistable URI permission to ensure the app can access the media file
     * in the future. Determines if the content is a video based on the MIME type.
     *
     * @param uri The URI of the selected media item
     * @return A MediaItem object representing the selected media
     */
    public MediaItem processMediaUri(Uri uri) {
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to take permission for URI: " + uri, e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }

        String mimeType = activity.getContentResolver().getType(uri);
        boolean isVideo = mimeType != null && mimeType.startsWith("video/");

        return new MediaItem(uri, isVideo, getFileName(uri));
    }

    /**
     * Extracts the file name from a content URI.
     * <p>
     * First attempts to query the ContentResolver for the display name. If that fails,
     * falls back to using the last path segment of the URI.
     *
     * @param uri The URI to extract the file name from
     * @return The file name, or the last path segment if the file name cannot be determined
     */
    public String getFileName(Uri uri) {
        String result = null;
        if (Objects.equals(uri.getScheme(), "content")) {
            try (Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
            }
        }

        if (result == null) {
            result = uri.getLastPathSegment();
        }

        return result;
    }

    /**
     * Processes the result from the media picker activity.
     * <p>
     * Handles both single and multiple selection cases by extracting URIs
     * from the result intent and converting them to MediaItem objects.
     *
     * @param data The Intent containing the results from the media picker
     * @return A list of MediaItem objects representing the selected media
     */
    public List<MediaItem> processMediaResult(Intent data) {
        List<MediaItem> items = new ArrayList<>();

        if (data.getClipData() != null) {
            // Multiple items selected
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                items.add(processMediaUri(uri));
            }
        } else if (data.getData() != null) {
            // Single item selected
            items.add(processMediaUri(data.getData()));
        }

        return items;
    }
}
