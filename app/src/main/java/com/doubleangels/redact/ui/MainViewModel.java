package com.doubleangels.redact.ui;



import android.app.Application;

import android.graphics.Bitmap;

import android.os.Handler;

import android.os.Looper;



import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelProvider;



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



import java.util.ArrayList;

import java.util.List;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;



import io.sentry.ISpan;

import io.sentry.ITransaction;

import io.sentry.SpanStatus;



/**

 * ViewModel that manages UI-related data for the Redact application.

 *

 * This class maintains the state of selected media items, processing progress,

 * and related information. It survives configuration changes (like screen rotations)

 * and provides a clean interface for communicating between UI components and

 * business logic through LiveData objects.

 */

public class MainViewModel extends AndroidViewModel {



    private final AppProcessingScope processingScope;

    private final MediaProcessor mediaProcessor;

    private final ExecutorService convertExecutor;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean convertInProgress;

    private final AtomicInteger convertGeneration;

    private final AtomicInteger cleanGeneration;

    private final ProgressUpdateThrottler convertProgressThrottler = new ProgressUpdateThrottler();



    private static final String KEY_CLEAN_STATE = "clean_processing_state";
    private static final String KEY_CONVERT_STATE = "convert_processing_state";
    private static final String KEY_CLEAN_URIS = "clean_selected_uris";
    private static final String KEY_CLEAN_NAMES = "clean_selected_names";
    private static final String KEY_CLEAN_VIDEOS = "clean_selected_videos";
    private static final String KEY_CONVERT_URIS = "convert_selected_uris";
    private static final String KEY_CONVERT_NAMES = "convert_selected_names";
    private static final String KEY_CONVERT_VIDEOS = "convert_selected_videos";
    private static final String KEY_CLEAN_PROCESSED = "clean_processed_count";
    private static final String KEY_CLEAN_BATCH = "clean_batch_total";
    private static final String KEY_CONVERT_PROCESSED = "convert_processed_count";
    private static final String KEY_CONVERT_BATCH = "convert_batch_total";

    private final SavedStateHandle savedStateHandle;

    public MainViewModel(Application application) {
        this(application, new SavedStateHandle());
    }

    public MainViewModel(Application application, SavedStateHandle savedStateHandle) {

        super(application);

        this.savedStateHandle = savedStateHandle;
        processingScope = AppProcessingScope.get(application);
        mediaProcessor = processingScope.mediaProcessor();
        convertExecutor = processingScope.convertExecutor();
        convertInProgress = processingScope.convertInProgress();
        convertGeneration = processingScope.convertGeneration();
        cleanGeneration = processingScope.cleanGeneration();

        restorePersistedProcessingState();

    }

    private void restorePersistedProcessingState() {
        List<MediaItem> cleanItems = restoreMediaItems(KEY_CLEAN_URIS, KEY_CLEAN_NAMES, KEY_CLEAN_VIDEOS);
        if (!cleanItems.isEmpty()) {
            selectedItems.setValue(cleanItems);
        }
        List<MediaItem> convertItems =
                restoreMediaItems(KEY_CONVERT_URIS, KEY_CONVERT_NAMES, KEY_CONVERT_VIDEOS);
        if (!convertItems.isEmpty()) {
            convertSelectedItems.setValue(convertItems);
        }

        Integer cleanProcessed = savedStateHandle.get(KEY_CLEAN_PROCESSED);
        if (cleanProcessed != null) {
            cleanProcessedItemCount.setValue(cleanProcessed);
        }
        Integer cleanBatch = savedStateHandle.get(KEY_CLEAN_BATCH);
        if (cleanBatch != null) {
            cleanBatchTotalCount.setValue(cleanBatch);
        }
        Integer convertProcessed = savedStateHandle.get(KEY_CONVERT_PROCESSED);
        if (convertProcessed != null) {
            convertProcessedItemCount.setValue(convertProcessed);
        }
        Integer convertBatch = savedStateHandle.get(KEY_CONVERT_BATCH);
        if (convertBatch != null) {
            convertBatchTotalCount.setValue(convertBatch);
        }

        ProcessingState clean = savedStateHandle.get(KEY_CLEAN_STATE);
        if (clean != null && clean != ProcessingState.PROCESSING) {
            cleanProcessingState.setValue(clean);
        } else if (processingScope.isCleanBusy()) {
            cleanProcessingState.setValue(ProcessingState.PROCESSING);
        }
        ProcessingState convert = savedStateHandle.get(KEY_CONVERT_STATE);
        if (convert != null && convert != ProcessingState.PROCESSING) {
            convertProcessingState.setValue(convert);
        } else if (processingScope.isConvertBusy()) {
            convertProcessingState.setValue(ProcessingState.PROCESSING);
        }
    }

    private void persistMediaItems(
            String urisKey, String namesKey, String videosKey, List<MediaItem> items) {
        if (items == null || items.isEmpty()) {
            savedStateHandle.remove(urisKey);
            savedStateHandle.remove(namesKey);
            savedStateHandle.remove(videosKey);
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
        savedStateHandle.set(urisKey, uris);
        savedStateHandle.set(namesKey, names);
        savedStateHandle.set(videosKey, videos);
    }

    private List<MediaItem> restoreMediaItems(String urisKey, String namesKey, String videosKey) {
        ArrayList<String> uris = savedStateHandle.get(urisKey);
        ArrayList<String> names = savedStateHandle.get(namesKey);
        ArrayList<Boolean> videos = savedStateHandle.get(videosKey);
        if (uris == null || names == null || videos == null || uris.isEmpty()) {
            return new ArrayList<>();
        }
        int count = Math.min(uris.size(), Math.min(names.size(), videos.size()));
        ArrayList<MediaItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(
                    new MediaItem(
                            android.net.Uri.parse(uris.get(i)),
                            Boolean.TRUE.equals(videos.get(i)),
                            names.get(i)));
        }
        return items;
    }

    private void persistBatchCounts(int processed, int batchTotal, String processedKey, String batchKey) {
        savedStateHandle.set(processedKey, processed);
        savedStateHandle.set(batchKey, batchTotal);
    }

    private void persistProcessingState(ProcessingState state, String key) {
        if (state == ProcessingState.PROCESSING) {
            savedStateHandle.remove(key);
        } else {
            savedStateHandle.set(key, state);
        }
    }

    public static boolean isAnyProcessing(FragmentActivity activity) {
        if (activity == null) {
            return false;
        }
        if (ShareHandlerActivity.isShareProcessingActive()) {
            return true;
        }
        Application app = activity.getApplication();
        AppProcessingScope scope = AppProcessingScope.get(app);
        if (scope.isCleanBusy() || scope.isConvertBusy()) {
            return true;
        }
        return new ViewModelProvider(activity).get(MainViewModel.class).isProcessingActive();
    }

    public boolean isProcessingActive() {
        ProcessingState clean = cleanProcessingState.getValue();
        ProcessingState convert = convertProcessingState.getValue();
        return clean == ProcessingState.PROCESSING
                || convert == ProcessingState.PROCESSING
                || convertInProgress.get()
                || processingScope.isCleanBusy()
                || processingScope.isConvertBusy();
    }



    @Override
    protected void onCleared() {
        super.onCleared();
        // Clean/convert work continues in AppProcessingScope; do not cancel or shut down workers here.
    }



    /**

     * Represents different states of media processing workflow.

     */

    public enum ProcessingState {

        /** No processing is active */

        IDLE,

        /** Processing is currently in progress */

        PROCESSING,

        /** Processing has finished */

        COMPLETED,

        /** Processing was cancelled before completion */

        CANCELLED

    }



    /** Currently selected media items for the Clean tab */

    private final MutableLiveData<List<MediaItem>> selectedItems = new MutableLiveData<>(new ArrayList<>());



    /** Currently selected media items for the Convert tab */

    private final MutableLiveData<List<MediaItem>> convertSelectedItems = new MutableLiveData<>(new ArrayList<>());



    private final MutableLiveData<ProcessingState> cleanProcessingState =

            new MutableLiveData<>(ProcessingState.IDLE);

    private final MutableLiveData<ProcessingState> convertProcessingState =

            new MutableLiveData<>(ProcessingState.IDLE);



    private final MutableLiveData<Integer> cleanProcessedItemCount = new MutableLiveData<>(0);

    private final MutableLiveData<Integer> convertProcessedItemCount = new MutableLiveData<>(0);



    /** Batch size when the last clean run started (survives rotation). */

    private final MutableLiveData<Integer> cleanBatchTotalCount = new MutableLiveData<>(0);

    /** Batch size when the last convert run started (survives rotation). */

    private final MutableLiveData<Integer> convertBatchTotalCount = new MutableLiveData<>(0);



    private final MutableLiveData<Integer> cleanProgressPercent = new MutableLiveData<>(0);

    private final MutableLiveData<Integer> convertProgressPercent = new MutableLiveData<>(0);



    private final MutableLiveData<String> cleanProgressMessage = new MutableLiveData<>("");

    private final MutableLiveData<String> convertProgressMessage = new MutableLiveData<>("");



    public LiveData<List<MediaItem>> getSelectedItems() {

        return selectedItems;

    }



    public LiveData<List<MediaItem>> getConvertSelectedItems() {

        return convertSelectedItems;

    }



    public LiveData<ProcessingState> getCleanProcessingState() {

        return cleanProcessingState;

    }



    public LiveData<ProcessingState> getConvertProcessingState() {

        return convertProcessingState;

    }



    public LiveData<Integer> getCleanProcessedItemCount() {

        return cleanProcessedItemCount;

    }



    public LiveData<Integer> getConvertProcessedItemCount() {

        return convertProcessedItemCount;

    }



    public LiveData<Integer> getCleanBatchTotalCount() {

        return cleanBatchTotalCount;

    }



    public LiveData<Integer> getConvertBatchTotalCount() {

        return convertBatchTotalCount;

    }



    public LiveData<Integer> getCleanProgressPercent() {

        return cleanProgressPercent;

    }



    public LiveData<Integer> getConvertProgressPercent() {

        return convertProgressPercent;

    }



    public LiveData<String> getCleanProgressMessage() {

        return cleanProgressMessage;

    }



    public LiveData<String> getConvertProgressMessage() {

        return convertProgressMessage;

    }



    public void setSelectedItems(List<MediaItem> items) {
        List<MediaItem> previous = selectedItems.getValue();
        if (previous != null) {
            for (MediaItem item : previous) {
                MediaSelector.releasePersistableReadPermission(getApplication(), item.uri());
            }
        }
        selectedItems.setValue(items != null ? new ArrayList<>(items) : new ArrayList<>());
        persistMediaItems(KEY_CLEAN_URIS, KEY_CLEAN_NAMES, KEY_CLEAN_VIDEOS, items);
    }



    public void setConvertSelectedItems(List<MediaItem> items) {
        List<MediaItem> previous = convertSelectedItems.getValue();
        if (previous != null) {
            for (MediaItem item : previous) {
                MediaSelector.releasePersistableReadPermission(getApplication(), item.uri());
            }
        }
        convertSelectedItems.setValue(items != null ? new ArrayList<>(items) : new ArrayList<>());
        persistMediaItems(KEY_CONVERT_URIS, KEY_CONVERT_NAMES, KEY_CONVERT_VIDEOS, items);
    }



    public void clearSelectedItems() {
        setSelectedItems(new ArrayList<>());
    }

    public void cancelCleaning() {
        final int runGeneration = cleanGeneration.get();
        cleanGeneration.incrementAndGet();
        mediaProcessor.cancel();
        VideoMedia3Converter.cancelActiveTranscode(runGeneration);
        LocalNotifications.cancelCleanProgress(getApplication());
        LocalNotifications.stopProcessingForeground(getApplication());
        setCleanProcessingState(ProcessingState.CANCELLED);
        cleanProgressMessage.postValue(getApplication().getString(R.string.status_processing_cancelled));
    }

    public void cancelConversion() {
        final int runGeneration = convertGeneration.get();
        convertGeneration.incrementAndGet();
        convertInProgress.set(false);
        VideoMedia3Converter.cancelActiveTranscode(runGeneration);
        LocalNotifications.cancelConvertProgress(getApplication());
        LocalNotifications.stopProcessingForeground(getApplication());
        setConvertProcessingState(ProcessingState.CANCELLED);
    }



    public void setCleanProcessingState(ProcessingState state) {

        if (Looper.myLooper() == Looper.getMainLooper()) {

            cleanProcessingState.setValue(state);
            persistProcessingState(state, KEY_CLEAN_STATE);

        } else {

            cleanProcessingState.postValue(state);
            mainHandler.post(() -> persistProcessingState(state, KEY_CLEAN_STATE));

        }

    }



    public void setConvertProcessingState(ProcessingState state) {

        if (Looper.myLooper() == Looper.getMainLooper()) {

            convertProcessingState.setValue(state);
            persistProcessingState(state, KEY_CONVERT_STATE);

        } else {

            convertProcessingState.postValue(state);
            mainHandler.post(() -> persistProcessingState(state, KEY_CONVERT_STATE));

        }

    }



    public void updateCleanProgressPercent(int percentComplete, String message) {

        cleanProgressPercent.postValue(Math.min(100, Math.max(0, percentComplete)));

        cleanProgressMessage.postValue(message != null ? message : "");

    }



    public void updateConvertProgressPercent(int percentComplete, String message) {
        convertProgressThrottler.maybeRun(() -> postConvertProgress(percentComplete, message));
    }

    public void updateConvertProgressPercentForce(int percentComplete, String message) {
        convertProgressThrottler.forceRun(() -> postConvertProgress(percentComplete, message));
    }

    private void postConvertProgress(int percentComplete, String message) {
        convertProgressPercent.postValue(Math.min(100, Math.max(0, percentComplete)));
        convertProgressMessage.postValue(message != null ? message : "");
    }



    /**

     * Starts the cleaning process for the given items.

     */

    public void startCleaning(List<MediaItem> items) {

        if (items == null || items.isEmpty()) return;

        if (ShareHandlerActivity.isShareProcessingActive()) {
            cleanProgressMessage.postValue(
                    getApplication().getString(R.string.status_already_processing));
            return;
        }

        if (convertInProgress.get()
                || convertProcessingState.getValue() == ProcessingState.PROCESSING) {
            cleanProgressMessage.postValue(
                    getApplication().getString(R.string.status_already_processing));
            return;
        }

        cleanBatchTotalCount.setValue(items.size());
        persistBatchCounts(0, items.size(), KEY_CLEAN_PROCESSED, KEY_CLEAN_BATCH);

        final int runGeneration = cleanGeneration.incrementAndGet();
        mediaProcessor.processMediaItems(items, new MediaProcessor.ProcessingCallback() {

            @Override
            public void onBatchStarted() {
                if (runGeneration != cleanGeneration.get()) {
                    return;
                }
                mediaProcessor.setTranscodeOwnerId(runGeneration);
                setCleanProcessingState(ProcessingState.PROCESSING);
                LocalNotifications.startProcessingForeground(
                        getApplication(),
                        getApplication().getString(R.string.notification_clean_progress_title));
            }

            @Override

            public void onProgress(int overallPercent, String message) {
                if (runGeneration != cleanGeneration.get()) {
                    return;
                }
                updateCleanProgressPercent(overallPercent, message);

                LocalNotifications.updateCleanProgress(getApplication(), overallPercent, message);

            }



            @Override

            public void onComplete(int successCount, int totalCount) {
                if (runGeneration != cleanGeneration.get()) {
                    return;
                }
                cleanProcessedItemCount.setValue(successCount);
                cleanBatchTotalCount.setValue(totalCount);
                persistBatchCounts(successCount, totalCount, KEY_CLEAN_PROCESSED, KEY_CLEAN_BATCH);
                setCleanProcessingState(ProcessingState.COMPLETED);
                int failCount = Math.max(0, totalCount - successCount);
                LocalNotifications.stopProcessingForeground(getApplication());
                LocalNotifications.showCleanComplete(getApplication(), successCount, failCount);
            }

            @Override
            public void onCancelled(int successCount, int totalCount) {
                if (runGeneration != cleanGeneration.get()) {
                    return;
                }
                cleanProcessedItemCount.setValue(successCount);
                cleanBatchTotalCount.setValue(totalCount);
                persistBatchCounts(successCount, totalCount, KEY_CLEAN_PROCESSED, KEY_CLEAN_BATCH);
                setCleanProcessingState(ProcessingState.CANCELLED);
                LocalNotifications.cancelCleanProgress(getApplication());
                LocalNotifications.stopProcessingForeground(getApplication());
                cleanProgressMessage.postValue(getApplication().getString(R.string.status_processing_cancelled));
            }

            @Override
            public void onAlreadyProcessing() {
                cleanProgressMessage.postValue(getApplication().getString(R.string.status_already_processing));
            }
        });

    }



    /**

     * Starts the conversion process for the given items.

     */

    public void startConversion(

            List<MediaItem> items,

            int formatIndex,

            int imageFormatIndex,

            Bitmap.CompressFormat imageFormat) {

        if (items == null || items.isEmpty()) return;

        if (ShareHandlerActivity.isShareProcessingActive()) {
            convertProgressMessage.postValue(
                    getApplication().getString(R.string.status_already_processing));
            return;
        }

        if (cleanProcessingState.getValue() == ProcessingState.PROCESSING) {
            convertProgressMessage.postValue(
                    getApplication().getString(R.string.status_already_processing));
            return;
        }

        if (!convertInProgress.compareAndSet(false, true)) {

            convertProgressMessage.postValue(getApplication().getString(R.string.status_already_processing));

            return;

        }

        final int total = items.size();

        convertBatchTotalCount.setValue(total);
        persistBatchCounts(0, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);

        setConvertSelectedItems(new ArrayList<>(items));

        setConvertProcessingState(ProcessingState.PROCESSING);
        updateConvertProgressPercent(0, "");
        LocalNotifications.startProcessingForeground(
                getApplication(), getApplication().getString(R.string.notification_convert_progress_title));

        final int runGeneration = convertGeneration.incrementAndGet();
        convertExecutor.execute(() -> {

            ITransaction transaction = SentryManager.startTransaction("convert_multiple", "task");
            long batchStartMs = System.currentTimeMillis();
            SentryManager.distribution("processing.batch.size", total, "operation_type", "convert");

            int ok = 0;

            int fail = 0;

            try {

                for (int i = 0; i < total; i++) {

                    if (Thread.currentThread().isInterrupted()
                            || runGeneration != convertGeneration.get()) {
                        break;
                    }

                    MediaItem mediaItem = items.get(i);

                    android.net.Uri uri = mediaItem.uri();

                    final int index = i + 1;

                    final String name = mediaItem.fileName() != null ? mediaItem.fileName() : "unknown";

                    ISpan span = transaction.startChild("convert_item", "media_item");



                    int overallStart = total > 0 ? ((index - 1) * 100) / total : 0;

                    String itemLine = getApplication().getString(R.string.convert_progress_item, index, total);

                    updateConvertProgressPercent(overallStart, itemLine);

                    LocalNotifications.updateConversionProgress(getApplication(), overallStart, itemLine);



                    try {

                        if (mediaItem.isVideo()) {
                            int[] actualFormatIndex = new int[1];
                            actualFormatIndex[0] = formatIndex;
                            FormatConverter.convertVideoToMovies(

                                    getApplication(),

                                    uri,

                                    name,

                                    formatIndex,

                                    p -> {

                                        int overall = total > 0 ? ((index - 1) * 100 + p) / total : 0;

                                        String detail = getApplication().getString(

                                                R.string.convert_progress_detail,

                                                index,

                                                total,

                                                name,

                                                getApplication().getString(R.string.convert_transcoding_percent, p));

                                        updateConvertProgressPercent(overall, detail);

                                        LocalNotifications.updateConversionProgress(

                                                getApplication(), overall, detail);

                                    },
                                    actualFormatIndex,
                                    runGeneration);
                            if (actualFormatIndex[0] != formatIndex) {
                                String requested = FormatConverter.videoFormatLabel(
                                        getApplication(), formatIndex);
                                String used = FormatConverter.videoFormatLabel(
                                        getApplication(), actualFormatIndex[0]);
                                convertProgressMessage.postValue(
                                        getApplication().getString(
                                                R.string.convert_codec_fallback, requested, used));
                            }

                        } else {

                            int overallImage = total > 0 ? ((index - 1) * 100) / total : 0;

                            String detail = getApplication().getString(

                                    R.string.convert_progress_detail,

                                    index,

                                    total,

                                    name,

                                    getApplication().getString(R.string.convert_encoding_image));

                            updateConvertProgressPercent(overallImage, detail);

                            LocalNotifications.updateConversionProgress(

                                    getApplication(), overallImage, detail);



                            FormatConverter.convertImageToPictures(

                                    getApplication(), uri,
                                    FormatConverter.formatAtIndex(imageFormatIndex), name);

                        }

                        ok++;
                        SentryManager.count(
                                "processing.convert.success", 1, "is_video", String.valueOf(mediaItem.isVideo()));

                        span.setStatus(SpanStatus.OK);

                    } catch (Exception e) {

                        boolean cancelled = Thread.currentThread().isInterrupted()
                                || runGeneration != convertGeneration.get()
                                || SentryManager.isUserCancellation(e);
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

                    String doneLine = getApplication().getString(R.string.convert_progress_item, index, total);

                    updateConvertProgressPercent(overallDone, doneLine);

                    LocalNotifications.updateConversionProgress(getApplication(), overallDone, doneLine);

                }

            } finally {
                final int finalOk = ok;
                final int finalFail = fail;
                final int capturedGeneration = runGeneration;
                final boolean interrupted =
                        Thread.currentThread().isInterrupted()
                                || capturedGeneration != convertGeneration.get();
                convertInProgress.set(false);
                mainHandler.post(() -> {
                    if (capturedGeneration != convertGeneration.get()) {
                        return;
                    }
                    LocalNotifications.stopProcessingForeground(getApplication());
                    if (interrupted) {
                        LocalNotifications.cancelConvertProgress(getApplication());
                        convertProcessedItemCount.setValue(finalOk);
                        persistBatchCounts(finalOk, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);
                        setConvertProcessingState(ProcessingState.CANCELLED);
                        convertProgressMessage.postValue(
                                getApplication().getString(R.string.status_processing_cancelled));
                    } else {
                        updateConvertProgressPercentForce(
                                100, convertCompletionMessage(finalOk, finalFail));
                        convertProcessedItemCount.setValue(finalOk);
                        persistBatchCounts(finalOk, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);
                        setConvertProcessingState(ProcessingState.COMPLETED);
                        LocalNotifications.showConversionComplete(getApplication(), finalOk, finalFail);
                    }
                });
                SentryManager.distributionDurationMs(
                        "processing.duration_ms",
                        System.currentTimeMillis() - batchStartMs,
                        "operation_type",
                        "convert");
                transaction.finish();
            }

        });

    }



    private String convertCompletionMessage(int okCount, int failCount) {

        if (failCount == 0 && okCount > 0) {

            return getApplication().getString(R.string.convert_done_all, okCount);

        }

        if (okCount > 0) {

            return getApplication().getString(R.string.convert_done_partial, okCount, failCount);

        }

        return getApplication().getString(R.string.convert_done_failed);

    }

}


