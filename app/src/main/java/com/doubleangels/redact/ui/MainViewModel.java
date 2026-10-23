package com.doubleangels.redact.ui;



import android.app.Application;

import android.graphics.Bitmap;

import android.os.Handler;

import android.os.Looper;



import androidx.fragment.app.FragmentActivity;
import androidx.annotation.NonNull;
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
import io.sentry.protocol.SentryId;



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

    private final List<android.net.Uri> convertOutputs;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean convertInProgress;

    private final AtomicInteger convertGeneration;

    private final AtomicBoolean convertTimedOut;

    private final AtomicInteger cleanGeneration;

    private final ProgressUpdateThrottler convertProgressThrottler;

    /**
     * What the Clean and Convert tabs show, kept with the {@link AppProcessingScope} that runs
     * their jobs rather than in this ViewModel. A job outlives the activity that started it (the
     * user backs out and opens the app again), and its callbacks must reach the screen that is
     * showing then, not the ViewModel that was cleared with the old activity.
     */
    private final SharedState shared;

    private static final class SharedState {
        final MutableLiveData<List<MediaItem>> selectedItems = new MutableLiveData<>(new ArrayList<>());
        final MutableLiveData<List<MediaItem>> convertSelectedItems = new MutableLiveData<>(new ArrayList<>());
        final MutableLiveData<ProcessingState> cleanProcessingState = new MutableLiveData<>(ProcessingState.IDLE);
        final MutableLiveData<ProcessingState> convertProcessingState = new MutableLiveData<>(ProcessingState.IDLE);
        final MutableLiveData<Integer> cleanProcessedItemCount = new MutableLiveData<>(0);
        final MutableLiveData<Integer> convertProcessedItemCount = new MutableLiveData<>(0);
        final MutableLiveData<Integer> cleanBatchTotalCount = new MutableLiveData<>(0);
        final MutableLiveData<Integer> convertBatchTotalCount = new MutableLiveData<>(0);
        final MutableLiveData<Integer> cleanProgressPercent = new MutableLiveData<>(0);
        final MutableLiveData<Integer> convertProgressPercent = new MutableLiveData<>(0);
        final MutableLiveData<String> cleanProgressMessage = new MutableLiveData<>("");
        final MutableLiveData<String> convertProgressMessage = new MutableLiveData<>("");
        final List<android.net.Uri> convertOutputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ProgressUpdateThrottler convertProgressThrottler = new ProgressUpdateThrottler();
        /** Error report the last failed clean sent, until its tab offers feedback on it. */
        @androidx.annotation.Nullable
        volatile SentryId cleanFeedbackEventId;
        /** Error report the last failed convert sent, until its tab offers feedback on it. */
        @androidx.annotation.Nullable
        volatile SentryId convertFeedbackEventId;
        /**
         * False until a ViewModel has filled this from its saved state. Only the first one does:
         * saved state matters after the process was killed, while a later ViewModel in the same
         * process would replace live values with older saved ones.
         */
        boolean restored;
        /**
         * The saved state of the newest ViewModel. A job reports through the ViewModel that
         * started it, so its results are saved here, where a recreated screen will look.
         */
        @androidx.annotation.Nullable
        volatile SavedStateHandle savingHandle;
    }

    @androidx.annotation.Nullable
    private static SharedState sharedState;
    @androidx.annotation.Nullable
    private static AppProcessingScope sharedStateScope;

    /** The state for {@code scope}; a new scope (a new process, or tests) starts a new one. */
    @NonNull
    private static synchronized SharedState sharedStateFor(@NonNull AppProcessingScope scope) {
        if (sharedState == null || sharedStateScope != scope) {
            sharedState = new SharedState();
            sharedStateScope = scope;
        }
        return sharedState;
    }



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

    // A fresh handle for callers without saved state; the constructor is only flagged because lint treats it as test-only.
    @android.annotation.SuppressLint("VisibleForTests")
    public MainViewModel(Application application) {
        this(application, new SavedStateHandle(new java.util.HashMap<>()));
    }

    public MainViewModel(Application application, SavedStateHandle savedStateHandle) {

        super(application);

        this.savedStateHandle = savedStateHandle;
        processingScope = AppProcessingScope.get(application);
        mediaProcessor = processingScope.mediaProcessor();
        convertExecutor = processingScope.convertExecutor();
        convertInProgress = processingScope.convertInProgress();
        convertGeneration = processingScope.convertGeneration();
        convertTimedOut = processingScope.convertTimedOut();
        cleanGeneration = processingScope.cleanGeneration();

        shared = sharedStateFor(processingScope);
        selectedItems = shared.selectedItems;
        convertSelectedItems = shared.convertSelectedItems;
        cleanProcessingState = shared.cleanProcessingState;
        convertProcessingState = shared.convertProcessingState;
        cleanProcessedItemCount = shared.cleanProcessedItemCount;
        convertProcessedItemCount = shared.convertProcessedItemCount;
        cleanBatchTotalCount = shared.cleanBatchTotalCount;
        convertBatchTotalCount = shared.convertBatchTotalCount;
        cleanProgressPercent = shared.cleanProgressPercent;
        convertProgressPercent = shared.convertProgressPercent;
        cleanProgressMessage = shared.cleanProgressMessage;
        convertProgressMessage = shared.convertProgressMessage;
        convertOutputs = shared.convertOutputs;
        convertProgressThrottler = shared.convertProgressThrottler;
        shared.savingHandle = savedStateHandle;

        if (!shared.restored) {
            shared.restored = true;
            restorePersistedProcessingState();
        } else {
            // This screen's saved state starts from what is showing now.
            persistMediaItems(KEY_CLEAN_URIS, KEY_CLEAN_NAMES, KEY_CLEAN_VIDEOS, selectedItems.getValue());
            persistMediaItems(KEY_CONVERT_URIS, KEY_CONVERT_NAMES, KEY_CONVERT_VIDEOS,
                    convertSelectedItems.getValue());
        }

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

    /** Where state is saved: the newest ViewModel's handle (see {@link SharedState#savingHandle}). */
    @NonNull
    private SavedStateHandle savingHandle() {
        SavedStateHandle handle = shared.savingHandle;
        return handle != null ? handle : savedStateHandle;
    }

    private void persistMediaItems(
            String urisKey, String namesKey, String videosKey, List<MediaItem> items) {
        if (items == null || items.isEmpty()) {
            savingHandle().remove(urisKey);
            savingHandle().remove(namesKey);
            savingHandle().remove(videosKey);
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
        savingHandle().set(urisKey, uris);
        savingHandle().set(namesKey, names);
        savingHandle().set(videosKey, videos);
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
        savingHandle().set(processedKey, processed);
        savingHandle().set(batchKey, batchTotal);
    }

    private void persistProcessingState(ProcessingState state, String key) {
        if (state == ProcessingState.PROCESSING) {
            savingHandle().remove(key);
        } else {
            savingHandle().set(key, state);
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

    private final MutableLiveData<List<MediaItem>> selectedItems;



    /** Currently selected media items for the Convert tab */

    private final MutableLiveData<List<MediaItem>> convertSelectedItems;



    private final MutableLiveData<ProcessingState> cleanProcessingState;

    private final MutableLiveData<ProcessingState> convertProcessingState;



    private final MutableLiveData<Integer> cleanProcessedItemCount;

    private final MutableLiveData<Integer> convertProcessedItemCount;



    /** Batch size when the last clean run started (survives rotation). */

    private final MutableLiveData<Integer> cleanBatchTotalCount;

    /** Batch size when the last convert run started (survives rotation). */

    private final MutableLiveData<Integer> convertBatchTotalCount;



    private final MutableLiveData<Integer> cleanProgressPercent;

    private final MutableLiveData<Integer> convertProgressPercent;



    private final MutableLiveData<String> cleanProgressMessage;

    private final MutableLiveData<String> convertProgressMessage;



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
        releaseStalePermissions(previous, items, convertSelectedItems.getValue());
        selectedItems.setValue(items != null ? new ArrayList<>(items) : new ArrayList<>());
        persistMediaItems(KEY_CLEAN_URIS, KEY_CLEAN_NAMES, KEY_CLEAN_VIDEOS, items);
    }



    public void setConvertSelectedItems(List<MediaItem> items) {
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
        java.util.Set<android.net.Uri> keepUris = new java.util.HashSet<>();
        for (List<MediaItem> kept : java.util.Arrays.asList(next, otherTab)) {
            if (kept != null) {
                for (MediaItem item : kept) {
                    keepUris.add(item.uri());
                }
            }
        }
        for (MediaItem item : previous) {
            if (!keepUris.contains(item.uri())) {
                MediaSelector.releasePersistableReadPermission(getApplication(), item.uri());
            }
        }
    }



    public void clearSelectedItems() {
        setSelectedItems(new ArrayList<>());
    }

    public void cancelCleaning() {
        final int runGeneration = cleanGeneration.get();
        cleanGeneration.incrementAndGet();
        mediaProcessor.cancel();
        VideoMedia3Converter.cancelActiveTranscode(VideoMedia3Converter.cleanOwner(runGeneration));
        LocalNotifications.cancelCleanProgress(getApplication());
        LocalNotifications.stopProcessingForeground(
                getApplication(), LocalNotifications.ForegroundJob.CLEAN);
        setCleanProcessingState(ProcessingState.CANCELLED);
        cleanProgressMessage.postValue(getApplication().getString(R.string.status_processing_cancelled));
    }

    public void cancelConversion() {
        final int runGeneration = convertGeneration.get();
        convertGeneration.incrementAndGet();
        convertInProgress.set(false);
        VideoMedia3Converter.cancelActiveTranscode(VideoMedia3Converter.convertOwner(runGeneration));
        LocalNotifications.cancelConvertProgress(getApplication());
        LocalNotifications.stopProcessingForeground(
                getApplication(), LocalNotifications.ForegroundJob.CONVERT);
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

    /**
     * The error report sent by the last clean that had failures, once: later calls return null
     * so a rotation doesn't offer it again.
     */
    @androidx.annotation.Nullable
    public SentryId takeCleanFeedbackEventId() {
        SentryId id = shared.cleanFeedbackEventId;
        shared.cleanFeedbackEventId = null;
        return id;
    }

    /** Like {@link #takeCleanFeedbackEventId()}, for the last convert. */
    @androidx.annotation.Nullable
    public SentryId takeConvertFeedbackEventId() {
        SentryId id = shared.convertFeedbackEventId;
        shared.convertFeedbackEventId = null;
        return id;
    }

    /** Saved outputs of the last clean batch, kept until the next batch starts. */
    public List<android.net.Uri> getCleanOutputs() {
        return mediaProcessor.getSucceededOutputs();
    }

    /**
     * What the last clean batch removed, phrased to follow the completion status, or null when
     * nothing was cleaned (or the process was recreated since and the batch is gone).
     */
    @androidx.annotation.Nullable
    public String getCleanSummaryText() {
        return mediaProcessor.getCleanSummary().describe(getApplication().getResources());
    }

    /** How many files of the last clean batch carried a location, for the completion notification. */
    public int getCleanLocationsRemoved() {
        return mediaProcessor.getCleanSummary().filesWithLocation;
    }

    /** Saved outputs of the last convert batch, kept until the next batch starts. */
    public List<android.net.Uri> getConvertOutputs() {
        return new ArrayList<>(convertOutputs);
    }

    public List<android.net.Uri> getCleanSucceededSources() {
        return mediaProcessor.getSucceededSources();
    }

    /** Like {@link #getCleanSucceededSources()} but consumes the list, so it is returned only once. */
    public List<android.net.Uri> takeCleanSucceededSources() {
        return mediaProcessor.takeSucceededSources();
    }

    /** Call once the user has let {@code trashed} originals go to the trash; see MediaProcessor. */
    public void restoreCleanOriginalNames(List<android.net.Uri> trashed) {
        mediaProcessor.restoreOriginalNames(trashed);
    }

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
        shared.cleanFeedbackEventId = null;
        final SentryId reportedBefore = SentryManager.lastReportedEventId();
        mediaProcessor.processMediaItems(items, VideoMedia3Converter.cleanOwner(runGeneration),
                new MediaProcessor.ProcessingCallback() {

            @Override
            public void onBatchStarted() {
                if (runGeneration != cleanGeneration.get()) {
                    return;
                }
                setCleanProcessingState(ProcessingState.PROCESSING);
                LocalNotifications.startProcessingForeground(
                        getApplication(), LocalNotifications.ForegroundJob.CLEAN,
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
                int failCount = Math.max(0, totalCount - successCount);
                // Set before COMPLETED, whose observers run right away and read it.
                if (failCount > 0) {
                    shared.cleanFeedbackEventId = SentryManager.reportedSince(reportedBefore);
                }
                setCleanProcessingState(ProcessingState.COMPLETED);
                // Counted here rather than by the Clean tab, which sees only the runs it was
                // watching when they finished.
                if (successCount > 0 && failCount == 0) {
                    ReviewPrompt.recordSuccessfulJob(getApplication());
                }
                LocalNotifications.stopProcessingForeground(
                        getApplication(), LocalNotifications.ForegroundJob.CLEAN);
                LocalNotifications.showCleanComplete(
                        getApplication(), successCount, failCount, getCleanLocationsRemoved());
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
                LocalNotifications.stopProcessingForeground(
                        getApplication(), LocalNotifications.ForegroundJob.CLEAN);
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

        convertTimedOut.set(false);
        convertOutputs.clear();

        final int total = items.size();

        convertBatchTotalCount.setValue(total);
        persistBatchCounts(0, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);

        setConvertSelectedItems(new ArrayList<>(items));

        setConvertProcessingState(ProcessingState.PROCESSING);
        updateConvertProgressPercent(0, "");
        LocalNotifications.startProcessingForeground(
                getApplication(),
                LocalNotifications.ForegroundJob.CONVERT,
                getApplication().getString(R.string.notification_convert_progress_title));

        final int runGeneration = convertGeneration.incrementAndGet();
        shared.convertFeedbackEventId = null;
        final SentryId reportedBefore = SentryManager.lastReportedEventId();
        convertExecutor.execute(() -> {

            ITransaction transaction = SentryManager.startTransaction("convert_multiple", "task");
            long batchStartMs = System.currentTimeMillis();
            SentryManager.distribution("processing.batch.size", total, "operation_type", "convert");

            int ok = 0;

            int fail = 0;

            try {

                for (int i = 0; i < total; i++) {

                    if (Thread.currentThread().isInterrupted()
                            || convertTimedOut.get()
                            || runGeneration != convertGeneration.get()) {
                        break;
                    }

                    MediaItem mediaItem = items.get(i);

                    android.net.Uri uri = mediaItem.uri();

                    final int index = i + 1;

                    final String name = mediaItem.displayName(getApplication());
                    // Empty, not the display label, so a nameless source still gets a random output name.
                    final String outputName = mediaItem.fileName() != null ? mediaItem.fileName() : "";

                    ISpan span = transaction.startChild("convert_item", "media_item");



                    int overallStart = total > 0 ? ((index - 1) * 100) / total : 0;

                    String itemLine = getApplication().getString(R.string.convert_progress_item, index, total);

                    updateConvertProgressPercent(overallStart, itemLine);

                    LocalNotifications.updateConversionProgress(getApplication(), overallStart, itemLine);



                    try {

                        if (mediaItem.isVideo()) {
                            int[] actualFormatIndex = new int[1];
                            actualFormatIndex[0] = formatIndex;
                            android.net.Uri convertedVideo = FormatConverter.convertVideoToMovies(

                                    getApplication(),

                                    uri,

                                    outputName,

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
                                    VideoMedia3Converter.convertOwner(runGeneration),
                                    () -> Thread.currentThread().isInterrupted()
                                            || convertTimedOut.get()
                                            || runGeneration != convertGeneration.get());
                            if (runGeneration == convertGeneration.get()) {
                                convertOutputs.add(convertedVideo);
                            }
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



                            android.net.Uri convertedImage = FormatConverter.convertImageToPictures(

                                    getApplication(), uri,
                                    FormatConverter.formatAtIndex(imageFormatIndex), outputName);
                            if (runGeneration == convertGeneration.get()) {
                                convertOutputs.add(convertedImage);
                            }

                        }

                        ok++;
                        com.doubleangels.redact.stats.StatsStore.recordConverted(getApplication());
                        SentryManager.count(
                                "processing.convert.success", 1, "is_video", String.valueOf(mediaItem.isVideo()));

                        span.setStatus(SpanStatus.OK);

                    } catch (Exception | OutOfMemoryError e) {
                        // Out of memory fails this file, not the app: the next file may be smaller.

                        boolean cancelled = Thread.currentThread().isInterrupted()
                                || convertTimedOut.get()
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
                                || convertTimedOut.get()
                                || capturedGeneration != convertGeneration.get();
                convertInProgress.set(false);
                mainHandler.post(() -> {
                    if (capturedGeneration != convertGeneration.get()) {
                        return;
                    }
                    LocalNotifications.stopProcessingForeground(
                            getApplication(), LocalNotifications.ForegroundJob.CONVERT);
                    if (interrupted) {
                        LocalNotifications.cancelConvertProgress(getApplication());
                        convertProcessedItemCount.setValue(finalOk);
                        persistBatchCounts(finalOk, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);
                        setConvertProcessingState(ProcessingState.CANCELLED);
                        convertProgressMessage.postValue(
                                getApplication().getString(R.string.status_processing_cancelled));
                    } else {
                        if (finalFail > 0) {
                            shared.convertFeedbackEventId = SentryManager.reportedSince(reportedBefore);
                        }
                        updateConvertProgressPercentForce(
                                100, convertCompletionMessage(finalOk, finalFail));
                        convertProcessedItemCount.setValue(finalOk);
                        persistBatchCounts(finalOk, total, KEY_CONVERT_PROCESSED, KEY_CONVERT_BATCH);
                        setConvertProcessingState(ProcessingState.COMPLETED);
                        if (finalOk > 0 && finalFail == 0) {
                            ReviewPrompt.recordSuccessfulJob(getApplication());
                        }
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


