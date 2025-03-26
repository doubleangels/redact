package com.doubleangels.redact.media;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;

import com.doubleangels.redact.metadata.MetadataStripper;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.List;

/**
 * Handles the processing of media files (images and videos) to strip metadata.
 *
 * This class encapsulates the workflow for processing multiple media items in the background,
 * reporting progress to the UI, and handling any errors that occur during processing.
 * It uses MetadataStripper to perform the actual metadata removal operations.
 */
public class MediaProcessor {
    private static final String TAG = "MediaProcessor";

    /** Utility for stripping metadata from images and videos */
    private final MetadataStripper metadataStripper;

    /** The activity context used for UI thread operations */
    private final Activity activity;

    /** Stores the URI of the most recently processed file */
    private Uri lastProcessedFileUri;

    /**
     * Callback interface for reporting processing progress and completion.
     * Implementations should handle updating the UI accordingly.
     */
    public interface ProcessingCallback {
        /**
         * Called periodically to report progress during processing.
         *
         * @param current The index of the current item being processed
         * @param total The total number of items to process
         * @param message A human-readable progress message
         */
        void onProgress(int current, int total, String message);

        /**
         * Called when all processing has completed.
         *
         * @param processedCount The number of items successfully processed
         */
        void onComplete(int processedCount);
    }

    /**
     * Creates a new MediaProcessor instance.
     *
     * @param activity The activity context for UI thread operations
     */
    public MediaProcessor(Activity activity) {
        this.activity = activity;
        this.metadataStripper = new MetadataStripper(activity);
    }

    /**
     * Returns the URI of the most recently processed file.
     *
     * @return The URI of the last successfully processed media file, or null if none
     */
    public Uri getLastProcessedFileUri() {
        return lastProcessedFileUri;
    }

    /**
     * Processes a list of media items in a background thread, stripping metadata.
     *
     * This method launches a background thread to process each media item, stripping
     * either image EXIF data or video metadata depending on the item type. Progress
     * is reported through the callback interface, which will be called on the UI thread.
     *
     * @param items The list of media items to process
     * @param callback The callback to report progress and completion
     */
    public void processMediaItems(List<MediaItem> items, ProcessingCallback callback) {
        new Thread(() -> {
            int totalItems = items.size();
            int processedItems = 0;

            for (MediaItem item : items) {
                final int currentItem = processedItems;
                String progressMessage = "Processing " + (currentItem + 1) + " of " + totalItems + "...";

                try {
                    // Update progress on UI thread
                    activity.runOnUiThread(() -> callback.onProgress(currentItem, totalItems, progressMessage));

                    // Process the item based on its type
                    Uri processedUri;
                    if (item.isVideo()) {
                        processedUri = metadataStripper.stripVideoMetadata(item.getUri(), item.getFileName());
                    } else {
                        processedUri = metadataStripper.stripExifData(item.getUri(), item.getFileName());
                    }

                    // Track successful processing
                    if (processedUri != null) {
                        lastProcessedFileUri = processedUri;
                        processedItems++;
                    } else {
                        Log.e(TAG, "Failed to process item: " + item.getFileName());
                        FirebaseCrashlytics.getInstance().log("Failed to process item: " + item.getFileName());
                    }
                } catch (Exception e) {
                    // Log and report any errors that occur
                    Log.e(TAG, "Error processing item: " + item.getFileName(), e);
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            }

            // Report completion on UI thread
            final int finalProcessedItems = processedItems;
            activity.runOnUiThread(() -> callback.onComplete(finalProcessedItems));
        }).start();
    }
}
