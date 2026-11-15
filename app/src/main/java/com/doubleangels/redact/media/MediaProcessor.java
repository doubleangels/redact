package com.doubleangels.redact.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.CleanResult;
import com.doubleangels.redact.metadata.CleanStats;
import com.doubleangels.redact.metadata.MetadataStripper;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.stats.StatsStore;
import com.doubleangels.redact.ui.CleanSummary;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SpanStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * One item of the current/last batch that was cleaned: its source, the source's file name (""
     * when unknown), the clean copy, and what was removed (null when not counted).
     */
    private record Succeeded(@NonNull Uri source, @NonNull String name, @NonNull Uri output,
                             @Nullable CleanStats stats) {
    }

    /** The batch's cleaned items, kept until the next batch. Guarded by {@code this}. */
    private final List<Succeeded> succeeded = new ArrayList<>();

    /**
     * How many of {@link #succeeded} {@link #takeSucceededSources} has handed out already, so each
     * source is offered for trashing once. Guarded by {@code this}.
     */
    private int sourcesTaken;

    /**
     * The clean copy and original file name of each source handed out by
     * {@link #takeSucceededSources}, for {@link #restoreOriginalNames} once those are trashed.
     */
    private final Map<Uri, TrashedOriginal> awaitingTrash = new ConcurrentHashMap<>();

    private record TrashedOriginal(@NonNull Uri output, @NonNull String fileName) {
    }

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

        /**
         * Called on the main thread when this batch was accepted and will run.
         */
        default void onBatchStarted() {}
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
        VideoMedia3Converter.cancelActiveTranscode(metadataStripper.getTranscodeOwnerId());
    }

    /** Associates subsequent video transcodes with {@code ownerId} for scoped cancellation. */
    public void setTranscodeOwnerId(long ownerId) {
        metadataStripper.setTranscodeOwnerId(ownerId);
    }

    /** Stops the background worker; safe to call when the owner is destroyed. */
    public void shutdown() {
        cancel();
        processingExecutor.shutdownNow();
    }

    public synchronized List<Uri> getSucceededOutputs() {
        List<Uri> outputs = new ArrayList<>(succeeded.size());
        for (Succeeded item : succeeded) {
            outputs.add(item.output());
        }
        return outputs;
    }

    /** What the last batch removed, over the items that were cleaned successfully. */
    public synchronized CleanSummary getCleanSummary() {
        List<CleanStats> stats = new ArrayList<>(succeeded.size());
        for (Succeeded item : succeeded) {
            if (item.stats() != null) {
                stats.add(item.stats());
            }
        }
        return CleanSummary.of(stats);
    }

    /** The last batch's cleaned sources that {@link #takeSucceededSources} has not handed out. */
    public synchronized List<Uri> getSucceededSources() {
        List<Uri> sources = new ArrayList<>();
        for (Succeeded item : succeeded.subList(sourcesTaken, succeeded.size())) {
            sources.add(item.source());
        }
        return sources;
    }

    /**
     * Returns the sources of the last batch's successful items and forgets them, so each batch's
     * originals can be offered for trashing exactly once (the completion state is re-delivered
     * to every newly created view, e.g. after a rotation).
     */
    public synchronized List<Uri> takeSucceededSources() {
        List<Uri> taken = new ArrayList<>();
        awaitingTrash.clear();
        for (Succeeded item : succeeded.subList(sourcesTaken, succeeded.size())) {
            taken.add(item.source());
            awaitingTrash.put(item.source(), new TrashedOriginal(item.output(), item.name()));
        }
        sourcesTaken = succeeded.size();
        return taken;
    }

    /**
     * Once {@code trashed} originals are in the trash, gives their clean copies the original names
     * that the originals still held when the copies were saved (the system then named them
     * "IMG_1 (1).jpg"). Only matters with Keep Original File Names on and the copy saved in the
     * original's folder. Runs in the background; renamed copies replace their old URIs in
     * {@link #getSucceededOutputs()}.
     */
    public void restoreOriginalNames(@NonNull List<Uri> trashed) {
        List<TrashedOriginal> renames = new ArrayList<>();
        for (Uri source : trashed) {
            TrashedOriginal original = awaitingTrash.remove(source);
            if (original != null) {
                renames.add(original);
            }
        }
        if (renames.isEmpty() || !AppPreferences.isKeepOriginalFileNames(context)) {
            return;
        }
        if (processingExecutor.isShutdown()) {
            return;
        }
        processingExecutor.execute(() -> {
            for (TrashedOriginal original : renames) {
                String current = OutputDestination.displayName(context.getContentResolver(), original.output());
                String wanted = current == null ? null : MediaFileNames.nameBeforeClash(current, original.fileName());
                if (wanted == null) {
                    continue;
                }
                Uri renamed = OutputDestination.rename(context.getContentResolver(), original.output(), wanted);
                if (renamed != null) {
                    replaceOutput(original.output(), renamed);
                }
            }
        });
    }

    private synchronized void replaceOutput(@NonNull Uri output, @NonNull Uri renamed) {
        for (int i = 0; i < succeeded.size(); i++) {
            Succeeded item = succeeded.get(i);
            if (item.output().equals(output)) {
                succeeded.set(i, new Succeeded(item.source(), item.name(), renamed, item.stats()));
                return;
            }
        }
    }

    private synchronized void clearSucceeded() {
        succeeded.clear();
        sourcesTaken = 0;
    }

    private synchronized void addSucceeded(@NonNull Succeeded item) {
        succeeded.add(item);
    }

    /** Whether a clean batch is currently running on the shared worker. */
    public boolean isBusy() {
        return processing.get();
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
        processMediaItems(items, metadataStripper.getTranscodeOwnerId(), callback);
    }

    /**
     * Like {@link #processMediaItems(List, ProcessingCallback)}, with the batch's video transcodes
     * owned by {@code transcodeOwnerId} from its first item on. Set here, once the batch is
     * accepted, rather than by the caller afterwards: a batch that starts with a video would
     * otherwise begin it under the previous batch's owner, which cancelling this one misses.
     */
    public void processMediaItems(List<MediaItem> items, long transcodeOwnerId, ProcessingCallback callback) {
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
        metadataStripper.setTranscodeOwnerId(transcodeOwnerId);
        clearSucceeded();
        awaitingTrash.clear();
        metadataStripper.resetCancellation();
        mainHandler.post(callback::onBatchStarted);
        processingExecutor.execute(() -> {
            final int totalItems = items.size();
            ITransaction transaction = SentryManager.startTransaction("clean_multiple", "task");
            long batchStartMs = System.currentTimeMillis();
            int successCount = 0;
            try {
                for (int index = 0; index < totalItems; index++) {
                    if (cancelled.get()) {
                        Log.i(TAG, "Processing cancelled by user or lifecycle");
                        VideoMedia3Converter.cancelActiveTranscode(metadataStripper.getTranscodeOwnerId());
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
                                    item.displayName(context));
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

                        // "" when the source has no name: the output then gets a random one.
                        String sourceName = item.fileName() != null ? item.fileName() : "";
                        CleanResult result = item.isVideo()
                                ? metadataStripper.cleanVideo(item.uri(), sourceName)
                                : metadataStripper.cleanImage(item.uri(), sourceName);
                        Uri processedUri = result.uri();

                        metadataStripper.setProgressCallback(null);

                        if (processedUri != null) {
                            CleanStats stats = result.stats();
                            addSucceeded(new Succeeded(item.uri(), sourceName, processedUri, stats));
                            StatsStore.recordClean(context,
                                    stats != null ? stats : new CleanStats(0, false),
                                    item.isVideo(), false);
                            successCount++;
                            span.setStatus(SpanStatus.OK);
                        } else if (cancelled.get()) {
                            VideoMedia3Converter.cancelActiveTranscode(metadataStripper.getTranscodeOwnerId());
                            span.setStatus(SpanStatus.CANCELLED);
                            break;
                        } else {
                            Log.e(TAG, "Failed to process item at index " + index);
                            SentryManager.count(
                                    "processing.clean.failure", 1, "is_video", String.valueOf(item.isVideo()));
                            span.setStatus(SpanStatus.INTERNAL_ERROR);
                            final String failLine =
                                    context.getString(R.string.clean_item_failed, item.displayName(context));
                            progressThrottler.forceRun(
                                    () ->
                                            mainHandler.post(
                                                    () ->
                                                            callback.onProgress(
                                                                    (itemIndex * 100) / totalItems,
                                                                    batchLine + "\n" + failLine)));
                        }
                    } catch (Exception | OutOfMemoryError e) {
                        // Out of memory fails this file, not the app: the next file may be smaller.
                        metadataStripper.setProgressCallback(null);
                        if (cancelled.get() || SentryManager.isUserCancellation(e)) {
                            VideoMedia3Converter.cancelActiveTranscode(metadataStripper.getTranscodeOwnerId());
                            span.setStatus(SpanStatus.CANCELLED);
                            break;
                        }
                        Log.e(TAG, "Error processing item at index " + index, e);
                        SentryManager.recordException(e);
                        SentryManager.count(
                                "processing.clean.failure",
                                1,
                                "is_video",
                                String.valueOf(item.isVideo()),
                                "error_type",
                                e.getClass().getSimpleName());
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
                // Free before the callback is posted, so a batch it starts is not refused as
                // already running.
                processing.set(false);
                cancelled.set(false);
                mainHandler.post(() -> {
                    if (wasCancelled) {
                        callback.onCancelled(finalSuccessCount, finalTotal);
                    } else {
                        callback.onComplete(finalSuccessCount, finalTotal);
                    }
                });
                SentryManager.distributionDurationMs(
                        "processing.duration_ms",
                        System.currentTimeMillis() - batchStartMs,
                        "operation_type",
                        "clean");
                transaction.finish();
            }
        });
    }
}