package com.doubleangels.redact.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.MetadataStripper;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** The application context used for background operations */
    private final Context context;

    /** Stores the URI of the most recently processed file */
    private volatile Uri lastProcessedFileUri;

    /** Prevents overlapping batch processing from multiple strip invocations */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /** Flag to allow cancellation of ongoing processing */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();

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
         * @param successCount Items successfully processed
         * @param totalCount   Items in the batch (including failures)
         */
        void onComplete(int successCount, int totalCount);

        /**
         * Called when the user or lifecycle cancelled the batch before it finished.
         */
        default void onCancelled(int successCount, int totalCount) {}

        /**
         * Called when a batch is already running and a second start was ignored.
         */
        default void onAlreadyProcessing() {}
    }

    /**
     * Creates a new MediaProcessor instance.
     *
     * @param context The context for operations
     */
    public MediaProcessor(Context context) {
        this.context = context.getApplicationContext();
        this.metadataStripper = new MetadataStripper(this.context);
    }

    /**
     * Cancels any ongoing processing.
     */
    public void cancel() {
        cancelled.set(true);
        metadataStripper.requestCancellation();
        VideoMedia3Converter.cancelActiveTranscode();
    }

    /** Stops the background worker; safe to call when the owner is destroyed. */
    public void shutdown() {
        cancel();
        processingExecutor.shutdownNow();
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
        Handler mainHandler = new Handler(Looper.getMainLooper());
        if (items == null || items.isEmpty()) {
            mainHandler.post(() -> callback.onComplete(0, 0));
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            mainHandler.post(callback::onAlreadyProcessing);
            return;
        }
        cancelled.set(false);
        metadataStripper.resetCancellation();
        processingExecutor.execute(() -> {
            ITransaction transaction = SentryManager.startTransaction("clean_multiple", "task");
            int successCount = 0;
            final int totalItems = items.size();
            try {
                for (int index = 0; index < totalItems; index++) {
                    if (cancelled.get()) {
                        Log.i(TAG, "Processing cancelled by user or lifecycle");
                        VideoMedia3Converter.cancelActiveTranscode();
                        break;
                    }
                    MediaItem item = items.get(index);
                    ISpan span = transaction.startChild("clean_item", "media_item");
                    final int itemIndex = index;
                    String batchLine =
                            context.getString(
                                    R.string.clean_progress_batch,
                                    index + 1,
                                    totalItems,
                                    item.fileName());
                    ProgressUpdateThrottler progressThrottler = new ProgressUpdateThrottler();

                    try {
                        metadataStripper.setProgressCallback(
                                (percentOfCurrentItem, message) -> {
                                    if (cancelled.get()) return;
                                    int overall = (itemIndex * 100 + percentOfCurrentItem) / totalItems;
                                    String combined = batchLine + "\n" + message;
                                    progressThrottler.maybeRun(
                                            () ->
                                                    mainHandler.post(
                                                            () ->
                                                                    callback.onProgress(
                                                                            overall, combined)));
                                });

                        progressThrottler.forceRun(
                                () ->
                                        mainHandler.post(
                                                () ->
                                                        callback.onProgress(
                                                                (itemIndex * 100) / totalItems,
                                                                batchLine)));

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
                        } else if (cancelled.get()) {
                            VideoMedia3Converter.cancelActiveTranscode();
                            span.setStatus(SpanStatus.CANCELLED);
                            break;
                        } else {
                            Log.e(TAG, "Failed to process item at index " + index);
                            span.setStatus(SpanStatus.INTERNAL_ERROR);
                            final String failLine =
                                    context.getString(R.string.clean_item_failed, item.fileName());
                            progressThrottler.forceRun(
                                    () ->
                                            mainHandler.post(
                                                    () ->
                                                            callback.onProgress(
                                                                    (itemIndex * 100) / totalItems,
                                                                    batchLine + "\n" + failLine)));
                        }
                    } catch (Exception e) {
                        metadataStripper.setProgressCallback(null);
                        if (cancelled.get() || SentryManager.isUserCancellation(e)) {
                            VideoMedia3Converter.cancelActiveTranscode();
                            span.setStatus(SpanStatus.CANCELLED);
                            break;
                        }
                        Log.e(TAG, "Error processing item at index " + index, e);
                        SentryManager.recordException(e);
                        span.setStatus(SpanStatus.INTERNAL_ERROR);
                    } finally {
                        span.finish();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in processMediaItems thread", e);
                SentryManager.recordException(e);
            } finally {
                final int finalSuccessCount = successCount;
                final int finalTotal = totalItems;
                final boolean wasCancelled = cancelled.get();
                mainHandler.post(() -> {
                    if (wasCancelled) {
                        callback.onCancelled(finalSuccessCount, finalTotal);
                    } else {
                        callback.onComplete(finalSuccessCount, finalTotal);
                    }
                });
                processing.set(false);
                cancelled.set(false);
                transaction.finish();
            }
        });
    }
}