package com.doubleangels.redact.ui;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import android.graphics.Bitmap;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaProcessor;
import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final MediaProcessor mediaProcessor;
    private final ExecutorService convertExecutor;

    public MainViewModel(Application application) {
        super(application);
        mediaProcessor = new MediaProcessor(application);
        convertExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mediaProcessor.cancel();
        if (convertExecutor != null) {
            convertExecutor.shutdownNow();
        }
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
        COMPLETED
    }

    /** Currently selected media items for processing */
    private final MutableLiveData<List<MediaItem>> selectedItems = new MutableLiveData<>(new ArrayList<>());

    /** Current state of the processing workflow */
    private final MutableLiveData<ProcessingState> processingState = new MutableLiveData<>(ProcessingState.IDLE);

    /** Count of successfully processed items */
    private final MutableLiveData<Integer> processedItemCount = new MutableLiveData<>(0);

    /** Current progress percentage (0-100) */
    private final MutableLiveData<Integer> progressPercent = new MutableLiveData<>(0);

    /** Detailed message about current processing step */
    private final MutableLiveData<String> progressMessage = new MutableLiveData<>("");

    /**
     * Returns the list of selected media items as LiveData.
     *
     * @return LiveData containing the list of selected MediaItem objects
     */
    public LiveData<List<MediaItem>> getSelectedItems() {
        return selectedItems;
    }

    /**
     * Returns the current processing state as LiveData.
     *
     * @return LiveData containing the current ProcessingState
     */
    public LiveData<ProcessingState> getProcessingState() {
        return processingState;
    }

    /**
     * Returns the count of successfully processed items as LiveData.
     *
     * @return LiveData containing the count of processed items
     */
    public LiveData<Integer> getProcessedItemCount() {
        return processedItemCount;
    }

    /**
     * Returns the current progress percentage as LiveData.
     *
     * @return LiveData containing the progress percentage (0-100)
     */
    public LiveData<Integer> getProgressPercent() {
        return progressPercent;
    }

    /**
     * Returns the current progress message as LiveData.
     *
     * @return LiveData containing a textual description of the current progress
     */
    public LiveData<String> getProgressMessage() {
        return progressMessage;
    }

    /**
     * Updates the list of selected media items.
     *
     * @param items New list of selected MediaItem objects
     */
    public void setSelectedItems(List<MediaItem> items) {
        selectedItems.setValue(items);
    }

    /**
     * Clears all selected media items.
     */
    public void clearSelectedItems() {
        selectedItems.setValue(new ArrayList<>());
    }

    /**
     * Updates the current processing state.
     *
     * @param state New ProcessingState value
     */
    public void setProcessingState(ProcessingState state) {
        processingState.setValue(state);
    }

    /**
     * Updates the count of successfully processed items.
     *
     * @param count Number of successfully processed items
     */
    public void setProcessedItemCount(int count) {
        processedItemCount.setValue(count);
    }

    /**
     * Updates progress from a batch index (legacy coarse steps).
     *
     * @param current Zero-based index of the item currently being processed
     * @param total   total number of items
     */
    public void updateProgress(int current, int total, String message) {
        if (total <= 0) {
            progressPercent.postValue(0);
            progressMessage.postValue(message != null ? message : "");
            return;
        }
        int percent = Math.min(100, Math.max(0, ((current + 1) * 100) / total));
        progressPercent.postValue(percent);
        progressMessage.postValue(message != null ? message : "");
    }

    /**
     * Sets overall progress (0–100) and status message.
     */
    public void updateProgressPercent(int percentComplete, String message) {
        progressPercent.postValue(Math.min(100, Math.max(0, percentComplete)));
        progressMessage.postValue(message != null ? message : "");
    }

    /**
     * Starts the cleaning process for the given items.
     */
    public void startCleaning(List<MediaItem> items) {
        if (items == null || items.isEmpty()) return;
        setProcessingState(ProcessingState.PROCESSING);
        mediaProcessor.processMediaItems(items, new MediaProcessor.ProcessingCallback() {
            @Override
            public void onProgress(int overallPercent, String message) {
                updateProgressPercent(overallPercent, message);
                LocalNotifications.updateCleanProgress(getApplication(), overallPercent, message);
            }
            @Override
            public void onComplete(int processedCount) {
                setProcessedItemCount(processedCount);
                setProcessingState(ProcessingState.COMPLETED);
                LocalNotifications.showCleanComplete(getApplication(), processedCount);
            }
        });
    }

    /**
     * Starts the conversion process for the given items.
     */
    public void startConversion(List<MediaItem> items, int formatIndex, int imageFormatIndex, Bitmap.CompressFormat imageFormat) {
        if (items == null || items.isEmpty()) return;
        setProcessingState(ProcessingState.PROCESSING);
        updateProgressPercent(0, "");

        final int total = items.size();
        convertExecutor.execute(() -> {
            ITransaction transaction = SentryManager.startTransaction("convert_multiple", "task");
            int ok = 0;
            int fail = 0;
            try {
                for (int i = 0; i < total; i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    MediaItem mediaItem = items.get(i);
                    android.net.Uri uri = mediaItem.uri();
                    final int index = i + 1;
                    final String name = mediaItem.fileName() != null ? mediaItem.fileName() : "unknown";
                    ISpan span = transaction.startChild("convert_item", name);
                    
                    int overallStart = total > 0 ? ((index - 1) * 100) / total : 0;
                    String itemLine = getApplication().getString(R.string.convert_progress_item, index, total);
                    updateProgressPercent(overallStart, itemLine);
                    LocalNotifications.updateConversionProgress(getApplication(), overallStart, itemLine);

                    try {
                        if (mediaItem.isVideo()) {
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
                                        updateProgressPercent(overall, detail);
                                        LocalNotifications.updateConversionProgress(getApplication(), overall, detail);
                                    });
                        } else {
                            int overallImage = total > 0 ? ((index - 1) * 100) / total : 0;
                            String detail = getApplication().getString(
                                    R.string.convert_progress_detail,
                                    index,
                                    total,
                                    name,
                                    getApplication().getString(R.string.convert_encoding_image));
                            updateProgressPercent(overallImage, detail);
                            LocalNotifications.updateConversionProgress(getApplication(), overallImage, detail);
                            
                            FormatConverter.convertImageToPictures(getApplication(), uri, imageFormat, name);
                        }
                        ok++;
                        span.setStatus(SpanStatus.OK);
                    } catch (Exception e) {
                        fail++;
                        SentryManager.recordException(e);
                        span.setStatus(SpanStatus.INTERNAL_ERROR);
                    } finally {
                        span.finish();
                    }
                    int overallDone = total > 0 ? (index * 100) / total : 0;
                    String doneLine = getApplication().getString(R.string.convert_progress_item, index, total);
                    updateProgressPercent(overallDone, doneLine);
                    LocalNotifications.updateConversionProgress(getApplication(), overallDone, doneLine);
                }
            } finally {
                final int finalOk = ok;
                final int finalFail = fail;
                updateProgressPercent(100, getApplication().getString(R.string.convert_done_all, finalOk));
                setProcessedItemCount(finalOk);
                setProcessingState(ProcessingState.COMPLETED);
                LocalNotifications.showConversionComplete(getApplication(), finalOk, finalFail);
                transaction.finish();
            }
        });
    }
}
