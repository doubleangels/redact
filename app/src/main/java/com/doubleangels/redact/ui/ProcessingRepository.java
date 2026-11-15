package com.doubleangels.redact.ui;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.ShareHandlerActivity;
import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaProcessor;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.media.ProgressUpdateThrottler;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.stats.StatsStore;
import com.doubleangels.redact.ui.MainViewModel.ProcessingState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SpanStatus;
import io.sentry.protocol.SentryId;

/**
 * The Clean and Convert jobs and everything their tabs show: the selections, progress, results
 * and the error report a failed run sent. One per {@link AppProcessingScope}, so it lives as long
 * as the process does: a job outlives the activity that started it (the user backs out and opens
 * the app again), and its results reach whichever screen is showing then.
 *
 * <p>{@link MainViewModel} is a screen's view of it. The screen showing now {@link #attach}es its
 * saved state, where results are written so a screen recreated after the process was killed finds
 * them, and {@link #detach}es it when it is cleared.
 */
public final class ProcessingRepository {

    static final String KEY_CLEAN_STATE = "clean_processing_state";
    static final String KEY_CONVERT_STATE = "convert_processing_state";
    static final String KEY_CLEAN_URIS = "clean_selected_uris";
    static final String KEY_CLEAN_NAMES = "clean_selected_names";
    static final String KEY_CLEAN_VIDEOS = "clean_selected_videos";
    static final String KEY_CONVERT_URIS = "convert_selected_uris";
    static final String KEY_CONVERT_NAMES = "convert_selected_names";
    static final String KEY_CONVERT_VIDEOS = "convert_selected_videos";
    static final String KEY_CLEAN_PROCESSED = "clean_processed_count";
    static final String KEY_CLEAN_BATCH = "clean_batch_total";
    static final String KEY_CONVERT_PROCESSED = "convert_processed_count";
    static final String KEY_CONVERT_BATCH = "convert_batch_total";

    private final Application app;
    private final AppProcessingScope scope;
    private final MediaProcessor mediaProcessor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<List<MediaItem>> selectedItems = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<MediaItem>> convertSelectedItems = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<ProcessingState> cleanProcessingState = new MutableLiveData<>(ProcessingState.IDLE);
    private final MutableLiveData<ProcessingState> convertProcessingState = new MutableLiveData<>(ProcessingState.IDLE);
    private final MutableLiveData<Integer> cleanProcessedItemCount = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> convertProcessedItemCount = new MutableLiveData<>(0);
    /** Batch size when the last clean run started. */
    private final MutableLiveData<Integer> cleanBatchTotalCount = new MutableLiveData<>(0);
    /** Batch size when the last convert run started. */
    private final MutableLiveData<Integer> convertBatchTotalCount = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> cleanProgressPercent = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> convertProgressPercent = new MutableLiveData<>(0);
    private final MutableLiveData<String> cleanProgressMessage = new MutableLiveData<>("");
    private final MutableLiveData<String> convertProgressMessage = new MutableLiveData<>("");
    private final List<Uri> convertOutputs = new CopyOnWriteArrayList<>();
    private final ProgressUpdateThrottler convertProgressThrottler = new ProgressUpdateThrottler();

    /** Error report the last failed clean sent, until its tab offers feedback on it. */
    @Nullable
    private volatile SentryId cleanFeedbackEventId;
    /** Error report the last failed convert sent, until its tab offers feedback on it. */
    @Nullable
    private volatile SentryId convertFeedbackEventId;

    /**
     * False until a screen has filled this from its saved state. Only the first one does: saved
     * state matters after the process was killed, while a later screen in the same process would
     * replace live values with older saved ones.
     */
    private boolean restored;

    /** The saved state of the screen showing now; null while none is. Main thread only. */
    @Nullable
    private SavedStateHandle savingHandle;

    /** Made by {@link AppProcessingScope#repository()}, once per scope. */
    public ProcessingRepository(@NonNull Application app, @NonNull AppProcessingScope scope) {
        this.app = app;
        this.scope = scope;
        this.mediaProcessor = scope.mediaProcessor();
    }

    // ---- saved state ------------------------------------------------------------------------

    /**
     * Makes {@code handle}, the saved state of the screen now showing, the one results are saved
     * to. The first screen of the process fills this repository from it; a later one starts its
     * saved state from what is showing now.
     */
    @MainThread
    void attach(@NonNull SavedStateHandle handle) {
        savingHandle = handle;
        if (!restored) {
            restored = true;
            restore(handle);
        } else {
            persistMediaItems(KEY_CLEAN_URIS, KEY_CLEAN_NAMES, KEY_CLEAN_VIDEOS, selectedItems.getValue());
            persistMediaItems(KEY_CONVERT_URIS, KEY_CONVERT_NAMES, KEY_CONVERT_VIDEOS,
                    convertSelectedItems.getValue());
        }
    }

    /** Stops saving to {@code handle}, when its screen is cleared; a newer screen's stays. */
    @MainThread
    void detach(@NonNull SavedStateHandle handle) {
        if (savingHandle == handle) {
            savingHandle = null;
        }
    }

    private void restore(@NonNull SavedStateHandle handle) {
        List<MediaItem> cleanItems = restoreMediaItems(handle, KEY_CLEAN_URIS, KEY_CLEAN_NAMES, KEY_CLEAN_VIDEOS);
        if (!cleanItems.isEmpty()) {
            selectedItems.setValue(cleanItems);
        }
        List<MediaItem> convertItems =
                restoreMediaItems(handle, KEY_CONVERT_URIS, KEY_CONVERT_NAMES, KEY_CONVERT_VIDEOS);
        if (!convertItems.isEmpty()) {
            convertSelectedItems.setValue(convertItems);
        }
        Integer cleanProcessed = handle.get(KEY_CLEAN_PROCESSED);
        if (cleanProcessed != null) {
            cleanProcessedItemCount.setValue(cleanProcessed);
        }
        Integer cleanBatch = handle.get(KEY_CLEAN_BATCH);
        if (cleanBatch != null) {
            cleanBatchTotalCount.setValue(cleanBatch);
        }
        Integer convertProcessed = handle.get(KEY_CONVERT_PROCESSED);
        if (convertProcessed != null) {
            convertProcessedItemCount.setValue(convertProcessed);
        }
        Integer convertBatch = handle.get(KEY_CONVERT_BATCH);
        if (convertBatch != null) {
            convertBatchTotalCount.setValue(convertBatch);
        }
        ProcessingState clean = handle.get(KEY_CLEAN_STATE);
        if (clean != null && clean != ProcessingState.PROCESSING) {
            cleanProcessingState.setValue(clean);
        } else if (scope.isCleanBusy()) {
            cleanProcessingState.setValue(ProcessingState.PROCESSING);
        }
        ProcessingState convert = handle.get(KEY_CONVERT_STATE);
        if (convert != null && convert != ProcessingState.PROCESSING) {
            convertProcessingState.setValue(convert);
        } else if (scope.isConvertBusy()) {
            convertProcessingState.setValue(ProcessingState.PROCESSING);
        }
    }

    private void persistMediaItems(
            String urisKey, String namesKey, String videosKey, @Nullable List<MediaItem> items) {
        SavedStateHandle handle = savingHandle;
        if (handle == null) {
            return;
        }
        if (items == null || items.isEmpty()) {
            handle.remove(urisKey);
            handle.remove(namesKey);
            handle.remove(videosKey);
            return;
        }
        ArrayList<String> uris = new ArrayList<>(items.size());
        ArrayList<String> names = new ArrayList<>(items.size());
        ArrayList<Boolean> videos = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            uris.add(item.uri().toString());
            names.add(item.fileName() != null ? item.fileName() : "");
            videos.add(item.isVideo());
        }
        handle.set(urisKey, uris);
        handle.set(namesKey, names);
        handle.set(videosKey, videos);
    }

    private static List<MediaItem> restoreMediaItems(
            @NonNull SavedStateHandle handle, String urisKey, String namesKey, String videosKey) {
        ArrayList<String> uris = handle.get(urisKey);
        ArrayList<String> names = handle.get(namesKey);
        ArrayList<Boolean> videos = handle.get(videosKey);
        if (uris == null || names == null || videos == null || uris.isEmpty()) {
            return new ArrayList<>();
        }
        int count = Math.min(uris.size(), Math.min(names.size(), videos.size()));
        ArrayList<MediaItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new MediaItem(Uri.parse(uris.get(i)), Boolean.TRUE.equals(videos.get(i)), names.get(i)));
        }
        return items;
    }

    private void persistBatchCounts(int processed, int batchTotal, String processedKey, String batchKey) {
        SavedStateHandle handle = savingHandle;
        if (handle != null) {
            handle.set(processedKey, processed);
            handle.set(batchKey, batchTotal);
        }
    }

    private void persistProcessingState(ProcessingState state, String key) {
        SavedStateHandle handle = savingHandle;
        if (handle == null) {
            return;
        }
        if (state == ProcessingState.PROCESSING) {
            handle.remove(key);
        } else {
            handle.set(key, state);
        }
    }

    // ---- what the tabs show -----------------------------------------------------------------

    LiveData<List<MediaItem>> selectedItems() {
        return selectedItems;
    }

    LiveData<List<MediaItem>> convertSelectedItems() {
        return convertSelectedItems;
    }

    LiveData<ProcessingState> cleanProcessingState() {
        return cleanProcessingState;
    }

    LiveData<ProcessingState> convertProcessingState() {
        return convertProcessingState;
    }

    LiveData<Integer> cleanProcessedItemCount() {
        return cleanProcessedItemCount;
    }

    LiveData<Integer> convertProcessedItemCount() {
        return convertProcessedItemCount;
    }

    LiveData<Integer> cleanBatchTotalCount() {
        return cleanBatchTotalCount;
    }

    LiveData<Integer> convertBatchTotalCount() {
        return convertBatchTotalCount;
    }

    LiveData<Integer> cleanProgressPercent() {
        return cleanProgressPercent;
    }

    LiveData<Integer> convertProgressPercent() {
        return convertProgressPercent;
    }

    LiveData<String> cleanProgressMessage() {
        return cleanProgressMessage;
    }

    LiveData<String> convertProgressMessage() {
        return convertProgressMessage;
    }

    /** Whether a clean or convert is running, or a tab still shows one as running. */
    public boolean isProcessingActive() {
        return cleanProcessingState.getValue() == ProcessingState.PROCESSING
                || convertProcessingState.getValue() == ProcessingState.PROCESSING
                || scope.isCleanBusy()
                || scope.isConvertBusy();
    }

    void setSelectedItems(@Nullable List<MediaItem> items) {
        List<MediaItem> previous = selectedItems.getValue();
        releaseStalePermissions(previous, items, convertSelectedItems.getValue());
        selectedItems.setValue(items != null ? new ArrayList<>(items) : new ArrayList<>());
        persistMediaItems(KEY_CLEAN_URIS, KEY_CLEAN_NAMES, KEY_CLEAN_VIDEOS, items);
    }

    void setConvertSelectedItems(@Nullable List<MediaItem> items) {
        List<MediaItem> previous = convertSelectedItems.getValue();
        releaseStalePermissions(previous, items, selectedItems.getValue());
        convertSelectedItems.setValue(items != null ? new ArrayList<>(items) : new ArrayList<>());
        persistMediaItems(KEY_CONVERT_URIS, KEY_CONVERT_NAMES, KEY_CONVERT_VIDEOS, items);
    }

    /**
     * Releases the persistable read permission for every item in {@code previous} that is not
     * also present in {@code next}, so a URI re-picked into the new selection keeps its
     * just-taken permission instead of losing it to the old selection's cleanup. Items the other
     * tab ({@code otherTab}) still has selected keep theirs too, since the grant is per file.
     */
    private void releaseStalePermissions(
            List<MediaItem> previous, List<MediaItem> next, List<MediaItem> otherTab) {
        if (previous == null || previous.isEmpty()) {
            return;
        }
        Set<Uri> keepUris = new HashSet<>();
        for (List<MediaItem> kept : Arrays.asList(next, otherTab)) {
            if (kept != null) {
                for (MediaItem item : kept) {
                    keepUris.add(item.uri());
                }
            }
        }
        for (MediaItem item : previous) {
            if (!keepUris.contains(item.uri())) {
                MediaSelector.releasePersistableReadPermission(app, item.uri());
            }
        }
    }

    void setCleanProcessingState(ProcessingState state) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanProcessingState.setValue(state);
            persistProcessingState(state, KEY_CLEAN_STATE);
        } else {
            cleanProcessingState.postValue(state);
            mainHandler.post(() -> persistProcessingState(state, KEY_CLEAN_STATE));
        }
    }

    void setConvertProcessingState(ProcessingState state) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            convertProcessingState.setValue(state);
            persistProcessingState(state, KEY_CONVERT_STATE);
        } else {
            convertProcessingState.postValue(state);
            mainHandler.post(() -> persistProcessingState(state, KEY_CONVERT_STATE));
        }
    }

    void updateCleanProgressPercent(int percentComplete, String message) {
        cleanProgressPercent.postValue(Math.min(100, Math.max(0, percentComplete)));
        cleanProgressMessage.postValue(message != null ? message : "");
    }

    void updateConvertProgressPercent(int percentComplete, String message) {
        convertProgressThrottler.maybeRun(() -> postConvertProgress(percentComplete, message));
    }

    void updateConvertProgressPercentForce(int percentComplete, String message) {
        convertProgressThrottler.forceRun(() -> postConvertProgress(percentComplete, message));
    }

    private void postConvertProgress(int percentComplete, String message) {
        convertProgressPercent.postValue(Math.min(100, Math.max(0, percentComplete)));
        convertProgressMessage.postValue(message != null ? message : "");
    }

    /**
     * The error report sent by the last clean that had failures, once: later calls return null
     * so a rotation doesn't offer it again.
     */
    @Nullable
    SentryId takeCleanFeedbackEventId() {
        SentryId id = cleanFeedbackEventId;
        cleanFeedbackEventId = null;
        return id;
    }

    /** Like {@link #takeCleanFeedbackEventId()}, for the last convert. */
    @Nullable
    SentryId takeConvertFeedbackEventId() {
        SentryId id = convertFeedbackEventId;
        convertFeedbackEventId = null;
        return id;
    }

    /** Saved outputs of the last clean batch, kept until the next batch starts. */
    List<Uri> getCleanOutputs() {
        return mediaProcessor.getSucceededOutputs();
    }

    /**
     * What the last clean batch removed, phrased to follow the completion status, or null when
     * nothing was cleaned (or the process was recreated since and the batch is gone).
     */
    @Nullable
    String getCleanSummaryText() {
        return mediaProcessor.getCleanSummary().describe(app.getResources());
    }

    /** How many files of the last clean batch carried a location, for the completion notification. */
    int getCleanLocationsRemoved() {
        return mediaProcessor.getCleanSummary().filesWithLocation;
    }

    /** Saved outputs of the last convert batch, kept until the next batch starts. */
    List<Uri> getConvertOutputs() {
        return new ArrayList<>(convertOutputs);
    }

    List<Uri> getCleanSucceededSources() {
        return mediaProcessor.getSucceededSources();
    }

    List<Uri> takeCleanSucceededSources() {
        return mediaProcessor.takeSucceededSources();
    }

    void restoreCleanOriginalNames(List<Uri> trashed) {
        mediaProcessor.restoreOriginalNames(trashed);
    }

    // ---- clean ------------------------------------------------------------------------------

    void cancelCleaning() {
        final int runGeneration = scope.cleanGeneration().get();
        scope.cleanGeneration().incrementAndGet();
        mediaProcessor.cancel();
        VideoMedia3Converter.cancelActiveTranscode(VideoMedia3Converter.cleanOwner(runGeneration));
        LocalNotifications.cancelCleanProgress(app);
        LocalNotifications.stopProcessingForeground(app, LocalNotifications.ForegroundJob.CLEAN);
        setCleanProcessingState(ProcessingState.CANCELLED);
        cleanProgressMessage.postValue(app.getString(R.string.status_processing_cancelled));
    }

    void startCleaning(List<MediaItem> items) {
        if (items == null || items.isEmpty()) return;
        if (ShareHandlerActivity.isShareProcessingActive()) {
            cleanProgressMessage.postValue(app.getString(R.string.status_already_processing));
            return;
        }
        if (scope.convertInProgress().get()
                || convertProcessingState.getValue() == ProcessingState.PROCESSING) {
            cleanProgressMessage.postValue(app.getString(R.string.status_already_processing));
            return;
        }
        cleanBatchTotalCount.setValue(items.size());
        persistBatchCounts(0, items.size(), KEY_CLEAN_PROCESSED, KEY_CLEAN_BATCH);
        final int runGeneration = scope.cleanGeneration().incrementAndGet();
        cleanFeedbackEventId = null;
        final SentryId reportedBefore = SentryManager.lastReportedEventId();
        mediaProcessor.processMediaItems(items, VideoMedia3Converter.cleanOwner(runGeneration),
                new MediaProcessor.ProcessingCallback() {
            private boolean current() {
                return runGeneration == scope.cleanGeneration().get();
            }

            @Override
            public void onBatchStarted() {
                if (!current()) {
                    return;
                }
                setCleanProcessingState(ProcessingState.PROCESSING);
                LocalNotifications.startProcessingForeground(
                        app, LocalNotifications.ForegroundJob.CLEAN,
                        app.getString(R.string.notification_clean_progress_title));
            }

            @Override
            public void onProgress(int overallPercent, String message) {
                if (!current()) {
                    return;
                }
                updateCleanProgressPercent(overallPercent, message);
                LocalNotifications.updateCleanProgress(app, overallPercent, message);
            }

            @Override
            public void onComplete(int successCount, int totalCount) {
                if (!current()) {
                    return;
                }
                cleanProcessedItemCount.setValue(successCount);
                cleanBatchTotalCount.setValue(totalCount);
                persistBatchCounts(successCount, totalCount, KEY_CLEAN_PROCESSED, KEY_CLEAN_BATCH);
                int failCount = Math.max(0, totalCount - successCount);
                // Set before COMPLETED, whose observers run right away and read it.
                if (failCount > 0) {
                    cleanFeedbackEventId = SentryManager.reportedSince(reportedBefore);
                }
                setCleanProcessingState(ProcessingState.COMPLETED);
                // Counted here rather than by the Clean tab, which sees only the runs it was
                // watching when they finished.
                if (successCount > 0 && failCount == 0) {
                    ReviewPrompt.recordSuccessfulJob(app);
                }
                if (successCount > 0) {
                    AppPreferences.setSummaryHintLearned(app, AppPreferences.HINT_CLEAN);
                }
                LocalNotifications.stopProcessingForeground(app, LocalNotifications.ForegroundJob.CLEAN);
                LocalNotifications.showCleanComplete(app, successCount, failCount, getCleanLocationsRemoved());
            }

            @Override
            public void onCancelled(int successCount, int totalCount) {
                if (!current()) {
                    return;
                }
                cleanProcessedItemCount.setValue(successCount);
                cleanBatchTotalCount.setValue(totalCount);
                persistBatchCounts(successCount, totalCount, KEY_CLEAN_PROCESSED, KEY_CLEAN_BATCH);
                setCleanProcessingState(ProcessingState.CANCELLED);
                LocalNotifications.cancelCleanProgress(app);
                LocalNotifications.stopProcessingForeground(app, LocalNotifications.ForegroundJob.CLEAN);
                cleanProgressMessage.postValue(app.getString(R.string.status_processing_cancelled));
            }

            @Override
            public void onAlreadyProcessing() {
                cleanProgressMessage.postValue(app.getString(R.string.status_already_processing));
            }
        });
    }

    // ---- convert ----------------------------------------------------------------------------

    void cancelConversion() {
        final int runGeneration = scope.convertGeneration().get();
        scope.convertGeneration().incrementAndGet();
        scope.convertInProgress().set(false);
        VideoMedia3Converter.cancelActiveTranscode(VideoMedia3Converter.convertOwner(runGeneration));
        LocalNotifications.cancelConvertProgress(app);
        LocalNotifications.stopProcessingForeground(app, LocalNotifications.ForegroundJob.CONVERT);
        setConvertProcessingState(ProcessingState.CANCELLED);
    }

    void startConversion(
            List<MediaItem> items,
            int formatIndex,
            int imageFormatIndex,
            Bitmap.CompressFormat imageFormat) {
        if (items == null || items.isEmpty()) return;
        if (ShareHandlerActivity.isShareProcessingActive()) {
            convertProgressMessage.postValue(app.getString(R.string.status_already_processing));
            return;
        }
        if (cleanProcessingState.getValue() == ProcessingState.PROCESSING) {
            convertProgressMessage.postValue(app.getString(R.string.status_already_processing));
            return;
        }
        if (!scope.convertInProgress().compareAndSet(false, true)) {
            convertProgressMessage.postValue(app.getString(R.string.status_already_processing));
            return;
        }
        scope.convertTimedOut().set(false);
        convertOutputs.clear();
        final int total = items.size();
        convertBatchTotalCount.setValue(total);
        persistBatchCounts(0, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);
        setConvertSelectedItems(new ArrayList<>(items));
        setConvertProcessingState(ProcessingState.PROCESSING);
        updateConvertProgressPercent(0, "");
        LocalNotifications.startProcessingForeground(
                app,
                LocalNotifications.ForegroundJob.CONVERT,
                app.getString(R.string.notification_convert_progress_title));
        final int runGeneration = scope.convertGeneration().incrementAndGet();
        convertFeedbackEventId = null;
        final SentryId reportedBefore = SentryManager.lastReportedEventId();
        scope.convertExecutor().execute(() -> runConversion(
                items, total, formatIndex, imageFormatIndex, runGeneration, reportedBefore));
    }

    private boolean convertStopped(int runGeneration) {
        return Thread.currentThread().isInterrupted()
                || scope.convertTimedOut().get()
                || runGeneration != scope.convertGeneration().get();
    }

    private void runConversion(List<MediaItem> items, int total, int formatIndex, int imageFormatIndex,
                               int runGeneration, @Nullable SentryId reportedBefore) {
        ITransaction transaction = SentryManager.startTransaction("convert_multiple", "task");
        long batchStartMs = System.currentTimeMillis();
        int ok = 0;
        int fail = 0;
        try {
            for (int i = 0; i < total; i++) {
                if (convertStopped(runGeneration)) {
                    break;
                }
                MediaItem mediaItem = items.get(i);
                Uri uri = mediaItem.uri();
                final int index = i + 1;
                final String name = mediaItem.displayName(app);
                // Empty, not the display label, so a nameless source still gets a random output name.
                final String outputName = mediaItem.fileName() != null ? mediaItem.fileName() : "";
                ISpan span = transaction.startChild("convert_item", "media_item");
                int overallStart = total > 0 ? ((index - 1) * 100) / total : 0;
                String itemLine = app.getString(R.string.convert_progress_item, index, total);
                updateConvertProgressPercent(overallStart, itemLine);
                LocalNotifications.updateConversionProgress(app, overallStart, itemLine);
                try {
                    if (mediaItem.isVideo()) {
                        int[] actualFormatIndex = new int[1];
                        actualFormatIndex[0] = formatIndex;
                        Uri convertedVideo = FormatConverter.convertVideoToMovies(
                                app,
                                uri,
                                outputName,
                                formatIndex,
                                p -> {
                                    int overall = total > 0 ? ((index - 1) * 100 + p) / total : 0;
                                    String detail = app.getString(
                                            R.string.convert_progress_detail,
                                            index,
                                            total,
                                            name,
                                            app.getString(R.string.convert_transcoding_percent, p));
                                    updateConvertProgressPercent(overall, detail);
                                    LocalNotifications.updateConversionProgress(app, overall, detail);
                                },
                                actualFormatIndex,
                                VideoMedia3Converter.convertOwner(runGeneration),
                                () -> convertStopped(runGeneration));
                        if (runGeneration == scope.convertGeneration().get()) {
                            convertOutputs.add(convertedVideo);
                        }
                        if (actualFormatIndex[0] != formatIndex) {
                            String requested = FormatConverter.videoFormatLabel(app, formatIndex);
                            String used = FormatConverter.videoFormatLabel(app, actualFormatIndex[0]);
                            convertProgressMessage.postValue(
                                    app.getString(R.string.convert_codec_fallback, requested, used));
                        }
                    } else {
                        int overallImage = total > 0 ? ((index - 1) * 100) / total : 0;
                        String detail = app.getString(
                                R.string.convert_progress_detail,
                                index,
                                total,
                                name,
                                app.getString(R.string.convert_encoding_image));
                        updateConvertProgressPercent(overallImage, detail);
                        LocalNotifications.updateConversionProgress(app, overallImage, detail);
                        Uri convertedImage = FormatConverter.convertImageToPictures(
                                app, uri, FormatConverter.formatAtIndex(imageFormatIndex), outputName);
                        if (runGeneration == scope.convertGeneration().get()) {
                            convertOutputs.add(convertedImage);
                        }
                    }
                    ok++;
                    StatsStore.recordConverted(app);
                    span.setStatus(SpanStatus.OK);
                } catch (Exception | OutOfMemoryError e) {
                    // Out of memory fails this file, not the app: the next file may be smaller.
                    boolean cancelled = convertStopped(runGeneration) || SentryManager.isUserCancellation(e);
                    if (cancelled) {
                        span.setStatus(SpanStatus.CANCELLED);
                    } else {
                        fail++;
                        SentryManager.recordException(e);
                        SentryManager.count(
                                "processing.convert.failure",
                                1,
                                "is_video",
                                String.valueOf(mediaItem.isVideo()),
                                "error_type",
                                e.getClass().getSimpleName());
                        span.setStatus(SpanStatus.INTERNAL_ERROR);
                    }
                } finally {
                    span.finish();
                }
                int overallDone = total > 0 ? (index * 100) / total : 0;
                String doneLine = app.getString(R.string.convert_progress_item, index, total);
                updateConvertProgressPercent(overallDone, doneLine);
                LocalNotifications.updateConversionProgress(app, overallDone, doneLine);
            }
        } finally {
            final int finalOk = ok;
            final int finalFail = fail;
            final boolean interrupted = convertStopped(runGeneration);
            // A cancelled run already cleared the flag, and a newer run may have set it since;
            // only the run that is still current may clear it.
            if (runGeneration == scope.convertGeneration().get()) {
                scope.convertInProgress().set(false);
            }
            mainHandler.post(() -> {
                if (runGeneration != scope.convertGeneration().get()) {
                    return;
                }
                LocalNotifications.stopProcessingForeground(app, LocalNotifications.ForegroundJob.CONVERT);
                if (interrupted) {
                    LocalNotifications.cancelConvertProgress(app);
                    convertProcessedItemCount.setValue(finalOk);
                    persistBatchCounts(finalOk, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);
                    setConvertProcessingState(ProcessingState.CANCELLED);
                    convertProgressMessage.postValue(app.getString(R.string.status_processing_cancelled));
                } else {
                    if (finalFail > 0) {
                        convertFeedbackEventId = SentryManager.reportedSince(reportedBefore);
                    }
                    updateConvertProgressPercentForce(100, convertCompletionMessage(finalOk, finalFail));
                    convertProcessedItemCount.setValue(finalOk);
                    persistBatchCounts(finalOk, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);
                    setConvertProcessingState(ProcessingState.COMPLETED);
                    if (finalOk > 0 && finalFail == 0) {
                        ReviewPrompt.recordSuccessfulJob(app);
                    }
                    if (finalOk > 0) {
                        AppPreferences.setSummaryHintLearned(app, AppPreferences.HINT_CONVERT);
                    }
                    LocalNotifications.showConversionComplete(app, finalOk, finalFail);
                }
            });
            SentryManager.distributionDurationMs(
                    "processing.duration_ms",
                    System.currentTimeMillis() - batchStartMs,
                    "operation_type",
                    "convert");
            transaction.finish();
        }
    }

    private String convertCompletionMessage(int okCount, int failCount) {
        if (failCount == 0 && okCount > 0) {
            return app.getString(R.string.convert_done_all, okCount);
        }
        if (okCount > 0) {
            return app.getString(R.string.convert_done_partial, okCount, failCount);
        }
        return app.getString(R.string.convert_done_failed);
    }
}
