package com.doubleangels.redact.media;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;

import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.MetadataStripper;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SpanStatus;

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

    /** Prevents overlapping batch processing from multiple strip invocations */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * Callback interface for reporting processing progress and completion.
     * Implementations should handle updating the UI accordingly.
     */
    public interface ProcessingCallback {
        /**
         * Called periodically to report progress during processing.
         *
         * @param overallPercent 0–100 across the whole batch
         * @param message        status line including current file and step when available
         */
        void onProgress(int overallPercent, String message);

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
        if (items == null || items.isEmpty()) {
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            ITransaction transaction = SentryManager.startTransaction("clean_multiple", "task");
            try {
                int totalItems = items.size();
                int successCount = 0;

                for (int index = 0; index < totalItems; index++) {
                    MediaItem item = items.get(index);
                    ISpan span = transaction.startChild("clean_item", "media_item");
                    final int itemIndex = index;
                    String batchLine =
                            activity.getString(
                                    R.string.clean_progress_batch,
                                    index + 1,
                                    totalItems,
                                    item.fileName());

                    try {
                        metadataStripper.setProgressCallback(
                                (percentOfCurrentItem, message) -> {
                                    int overall =
                                            totalItems > 0
                                                    ? (itemIndex * 100 + percentOfCurrentItem) / totalItems
                                                    : 0;
                                    String combined = batchLine + "\n" + message;
                                    activity.runOnUiThread(
                                            () -> callback.onProgress(overall, combined));
                                });

                        activity.runOnUiThread(
                                () -> callback.onProgress(
                                        totalItems > 0 ? (itemIndex * 100) / totalItems : 0,
                                        batchLine));

                        Uri processedUri;
                        if (item.isVideo()) {
                            processedUri = metadataStripper.stripVideoMetadata(item.uri(), item.fileName());
                        } else {
                            processedUri = metadataStripper.stripExifData(item.uri(), item.fileName());
                        }

                        metadataStripper.setProgressCallback(null);

                        if (processedUri != null) {
                            lastProcessedFileUri = processedUri;
                            successCount++;
                            span.setStatus(SpanStatus.OK);
                        } else {
                            Log.e(TAG, "Failed to process item: " + item.fileName());
                            SentryManager.log("Failed to process item: " + item.fileName());
                            span.setStatus(SpanStatus.INTERNAL_ERROR);
                        }
                    } catch (Exception e) {
                        metadataStripper.setProgressCallback(null);
                        Log.e(TAG, "Error processing item: " + item.fileName(), e);
                        SentryManager.recordException(e);
                        span.setStatus(SpanStatus.INTERNAL_ERROR);
                    } finally {
                        span.finish();
                    }
                }

                final int finalSuccessCount = successCount;
                activity.runOnUiThread(() -> callback.onComplete(finalSuccessCount));
            } finally {
                processing.set(false);
                transaction.finish();
            }
        }).start();
    }
}