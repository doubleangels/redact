package com.doubleangels.redact.ui;

import android.annotation.SuppressLint;
import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.SavedStateHandleSupport;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;

import com.doubleangels.redact.ShareHandlerActivity;
import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.MediaItem;

import java.util.HashMap;
import java.util.List;

import io.sentry.protocol.SentryId;

/**
 * A screen's view of the Clean and Convert jobs. The jobs and their state belong to the
 * {@link ProcessingRepository}, which outlives this ViewModel; this one hands the screen its
 * LiveData, passes its requests on, and lends the repository its saved state while the screen is
 * showing. Window state (how a foldable is folded) is the screen's own and lives here.
 */
public class MainViewModel extends AndroidViewModel {

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

    private final ProcessingRepository repository;
    private final SavedStateHandle savedStateHandle;

    /** Window state, not job state, so it lives here and not in the repository. */
    private final MutableLiveData<FoldPosture> foldPosture = new MutableLiveData<>(FoldPosture.NONE);

    /**
     * Makes MainViewModel with the application's {@link ProcessingRepository}, and anything else
     * with {@code fallback}. MainActivity uses it as its default factory.
     */
    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull ViewModelProvider.Factory fallback) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass, @NonNull CreationExtras extras) {
                if (modelClass != MainViewModel.class) {
                    return fallback.create(modelClass, extras);
                }
                Application app = extras.get(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY);
                if (app == null) {
                    throw new IllegalStateException("No application in the creation extras");
                }
                SavedStateHandle handle = SavedStateHandleSupport.createSavedStateHandle(extras);
                return modelClass.cast(new MainViewModel(app, handle, AppProcessingScope.get(app).repository()));
            }
        };
    }

    /** For a screen without saved state, and tests. */
    // A fresh handle for callers without saved state; lint flags the constructor as test-only.
    @SuppressLint("VisibleForTests")
    public MainViewModel(@NonNull Application application) {
        this(application, new SavedStateHandle(new HashMap<>()));
    }

    /** What the default factory calls, from an activity that does not use {@link #factory}. */
    public MainViewModel(@NonNull Application application, @NonNull SavedStateHandle savedStateHandle) {
        this(application, savedStateHandle, AppProcessingScope.get(application).repository());
    }

    public MainViewModel(@NonNull Application application, @NonNull SavedStateHandle savedStateHandle,
                         @NonNull ProcessingRepository repository) {
        super(application);
        this.savedStateHandle = savedStateHandle;
        this.repository = repository;
        repository.attach(savedStateHandle);
    }

    @Override
    protected void onCleared() {
        // The jobs carry on in the repository; only this screen's saved state stops being written.
        repository.detach(savedStateHandle);
    }

    /** Whether a share, clean or convert is running. */
    public static boolean isAnyProcessing(FragmentActivity activity) {
        if (activity == null) {
            return false;
        }
        if (ShareHandlerActivity.isShareProcessingActive()) {
            return true;
        }
        return AppProcessingScope.get(activity.getApplication()).repository().isProcessingActive();
    }

    public boolean isProcessingActive() {
        return repository.isProcessingActive();
    }

    // ---- window -----------------------------------------------------------------------------

    /** How the device is folded; {@link FoldPosture#NONE} when it is flat or does not fold. */
    public LiveData<FoldPosture> getFoldPosture() {
        return foldPosture;
    }

    public void setFoldPosture(@NonNull FoldPosture posture) {
        if (!posture.equals(foldPosture.getValue())) {
            foldPosture.setValue(posture);
        }
    }

    // ---- what the tabs show -----------------------------------------------------------------

    public LiveData<List<MediaItem>> getSelectedItems() {
        return repository.selectedItems();
    }

    public LiveData<List<MediaItem>> getConvertSelectedItems() {
        return repository.convertSelectedItems();
    }

    public LiveData<ProcessingState> getCleanProcessingState() {
        return repository.cleanProcessingState();
    }

    public LiveData<ProcessingState> getConvertProcessingState() {
        return repository.convertProcessingState();
    }

    public LiveData<Integer> getCleanProcessedItemCount() {
        return repository.cleanProcessedItemCount();
    }

    public LiveData<Integer> getConvertProcessedItemCount() {
        return repository.convertProcessedItemCount();
    }

    public LiveData<Integer> getCleanBatchTotalCount() {
        return repository.cleanBatchTotalCount();
    }

    public LiveData<Integer> getConvertBatchTotalCount() {
        return repository.convertBatchTotalCount();
    }

    public LiveData<Integer> getCleanProgressPercent() {
        return repository.cleanProgressPercent();
    }

    public LiveData<Integer> getConvertProgressPercent() {
        return repository.convertProgressPercent();
    }

    public LiveData<String> getCleanProgressMessage() {
        return repository.cleanProgressMessage();
    }

    public LiveData<String> getConvertProgressMessage() {
        return repository.convertProgressMessage();
    }

    public void setSelectedItems(List<MediaItem> items) {
        repository.setSelectedItems(items);
    }

    public void setConvertSelectedItems(List<MediaItem> items) {
        repository.setConvertSelectedItems(items);
    }

    public void setCleanProcessingState(ProcessingState state) {
        repository.setCleanProcessingState(state);
    }

    public void setConvertProcessingState(ProcessingState state) {
        repository.setConvertProcessingState(state);
    }

    public void updateCleanProgressPercent(int percentComplete, String message) {
        repository.updateCleanProgressPercent(percentComplete, message);
    }

    public void updateConvertProgressPercent(int percentComplete, String message) {
        repository.updateConvertProgressPercent(percentComplete, message);
    }

    public void updateConvertProgressPercentForce(int percentComplete, String message) {
        repository.updateConvertProgressPercentForce(percentComplete, message);
    }

    /**
     * The error report sent by the last clean that had failures, once: later calls return null
     * so a rotation doesn't offer it again.
     */
    @Nullable
    public SentryId takeCleanFeedbackEventId() {
        return repository.takeCleanFeedbackEventId();
    }

    /** Like {@link #takeCleanFeedbackEventId()}, for the last convert. */
    @Nullable
    public SentryId takeConvertFeedbackEventId() {
        return repository.takeConvertFeedbackEventId();
    }

    /** Saved outputs of the last clean batch, kept until the next batch starts. */
    public List<Uri> getCleanOutputs() {
        return repository.getCleanOutputs();
    }

    /**
     * What the last clean batch removed, phrased to follow the completion status, or null when
     * nothing was cleaned (or the process was recreated since and the batch is gone).
     */
    @Nullable
    public String getCleanSummaryText() {
        return repository.getCleanSummaryText();
    }

    /** How many files of the last clean batch carried a location, for the completion notification. */
    public int getCleanLocationsRemoved() {
        return repository.getCleanLocationsRemoved();
    }

    /** Saved outputs of the last convert batch, kept until the next batch starts. */
    public List<Uri> getConvertOutputs() {
        return repository.getConvertOutputs();
    }

    public List<Uri> getCleanSucceededSources() {
        return repository.getCleanSucceededSources();
    }

    /** Like {@link #getCleanSucceededSources()} but consumes the list, so it is returned only once. */
    public List<Uri> takeCleanSucceededSources() {
        return repository.takeCleanSucceededSources();
    }

    /** Call once the user has let {@code trashed} originals go to the trash; see MediaProcessor. */
    public void restoreCleanOriginalNames(List<Uri> trashed) {
        repository.restoreCleanOriginalNames(trashed);
    }

    // ---- jobs -------------------------------------------------------------------------------

    public void startCleaning(List<MediaItem> items) {
        repository.startCleaning(items);
    }

    public void cancelCleaning() {
        repository.cancelCleaning();
    }

    /**
     * Starts the conversion process for the given items.
     */
    public void startConversion(
            List<MediaItem> items,
            int formatIndex,
            int imageFormatIndex,
            Bitmap.CompressFormat imageFormat) {
        repository.startConversion(items, formatIndex, imageFormatIndex, imageFormat);
    }

    public void cancelConversion() {
        repository.cancelConversion();
    }
}
