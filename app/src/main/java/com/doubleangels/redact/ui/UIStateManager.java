package com.doubleangels.redact.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.doubleangels.redact.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * Manages UI state changes throughout the application.
 * <p>
 * This class centralizes UI state management for status messages, progress indicators,
 * and button states. It provides a clean interface for updating UI elements based on
 * application state changes without exposing UI implementation details to other components.
 *
 * @param activity          Parent activity that contains the UI elements
 * @param statusText        TextView displaying current application status
 * @param stripButton       Button to initiate processing of selected media items
 * @param progressContainer Container for progress-related UI elements
 * @param progressBar       Progress bar showing visual representation of processing progress
 * @param progressText      Text displaying detailed progress information
 */
public record UIStateManager(Activity activity, TextView statusText, MaterialButton stripButton,
                             LinearLayout progressContainer, LinearProgressIndicator progressBar,
                             TextView progressText) {
    /**
     * Creates a new UIStateManager with references to managed UI components.
     *
     * @param activity          The parent activity containing the UI elements
     * @param statusText        TextView that displays status messages
     * @param stripButton       Button that initiates metadata stripping
     * @param progressContainer Layout that contains progress indicator elements
     * @param progressBar       Progress bar that shows processing progress
     * @param progressText      TextView that shows detailed progress messages
     */
    public UIStateManager {
    }

    /**
     * Updates the progress indicators with current processing progress.
     * <p>
     * Calculates percentage complete and updates both the visual progress bar
     * and the textual progress message on the UI thread.
     *
     * @param current Number of items processed so far
     * @param total   Total number of items to process
     * @param message Text describing the current processing step
     */
    public void updateProgress(int current, int total, String message) {
        int progressPercentage = (current * 100) / total;

        activity.runOnUiThread(() -> {
            progressBar.setProgress(progressPercentage);
            progressText.setText(message);
        });
    }

    /**
     * Shows or hides the progress indicator container.
     * <p>
     * When showing the progress container, the progress bar is reset to zero.
     *
     * @param visible True to show the progress container, false to hide it
     */
    public void showProgress(boolean visible) {
        progressContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            progressBar.setProgress(0);
        }
    }

    /**
     * Sets a custom status message.
     *
     * @param status The status message to display
     */
    public void setStatus(String status) {
        statusText.setText(status);
    }

    /**
     * Sets status text to indicate permissions are being requested.
     */
    public void setPermissionRequestingStatus() {
        statusText.setText(activity.getString(R.string.status_requesting_permissions));
    }

    /**
     * Sets status text to indicate the app is ready for user interaction.
     */
    public void setReadyStatus() {
        statusText.setText(activity.getString(R.string.status_ready));
    }

    /**
     * Sets status text to indicate permissions are required but not granted.
     */
    public void setPermissionsRequiredStatus() {
        statusText.setText(activity.getString(R.string.status_permissions_required));
    }

    /**
     * Sets status text to show how many media items are currently selected.
     *
     * @param count Number of selected media items
     */
    public void setSelectedItemsStatus(int count) {
        statusText.setText(activity.getString(R.string.status_selected_items, count));
    }

    /**
     * Sets status text to indicate media processing is in progress.
     */
    public void setProcessingStatus() {
        statusText.setText(activity.getString(R.string.status_processing));
    }

    /**
     * Sets status text to show how many media items were processed successfully.
     *
     * @param count Number of successfully processed media items
     */
    public void setProcessedItemsStatus(int count) {
        statusText.setText(activity.getString(R.string.status_processed_items, count));
    }

    /**
     * Sets status text to prompt user to select media files first.
     * <p>
     * This is used when the user attempts to process files without selecting any.
     */
    public void setFirstSelectMediaFilesStatus() {
        statusText.setText(activity.getString(R.string.status_first_select_media_files));
    }

    /**
     * Enables or disables the strip button based on selection state.
     * <p>
     * The strip button should only be enabled when media items are selected.
     *
     * @param enable True to enable the button, false to disable it
     */
    public void enableStripButton(boolean enable) {
        stripButton.setEnabled(enable);
    }
}
