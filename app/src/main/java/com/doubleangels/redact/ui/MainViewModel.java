package com.doubleangels.redact.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.doubleangels.redact.media.MediaItem;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewModel that manages UI-related data for the Redact application.
 *
 * This class maintains the state of selected media items, processing progress,
 * and related information. It survives configuration changes (like screen rotations)
 * and provides a clean interface for communicating between UI components and
 * business logic through LiveData objects.
 */
public class MainViewModel extends ViewModel {
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
     * Updates progress information.
     *
     * Calculates progress percentage based on current and total values,
     * and updates both the percentage and message LiveData objects.
     * Uses postValue instead of setValue to safely update values from
     * background threads.
     *
     * @param current Number of items processed so far
     * @param total Total number of items to process
     * @param message Descriptive text about current processing step
     */
    public void updateProgress(int current, int total, String message) {
        int percent = (current * 100) / total;
        progressPercent.postValue(percent);
        progressMessage.postValue(message);
    }
}
