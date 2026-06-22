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
 * <p>
 * Implemented as a plain class (not a record) for compatibility with Android runtimes
 * that have had issues resolving record classes at class load time.
 */
public final class UIStateManager {

    private final Activity activity;
    private final TextView statusText;
    private final MaterialButton stripButton;
    private final LinearLayout progressContainer;
    private final LinearProgressIndicator progressBar;
    private final TextView progressText;

    public UIStateManager(
            Activity activity,
            TextView statusText,
            MaterialButton stripButton,
            LinearLayout progressContainer,
            LinearProgressIndicator progressBar,
            TextView progressText) {
        this.activity = activity;
        this.statusText = statusText;
        this.stripButton = stripButton;
        this.progressContainer = progressContainer;
        this.progressBar = progressBar;
        this.progressText = progressText;
    }

    private void runOnUi(Runnable action) {
        if (activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(action);
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
        int progressPercentage = total <= 0 ? 0
                : Math.min(100, Math.max(0, (current * 100) / total));

        runOnUi(() -> {
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
        runOnUi(() -> {
            progressContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                progressBar.setProgress(0);
            }
        });
    }

    /**
     * Sets a custom status message.
     *
     * @param status The status message to display
     */
    public void setStatus(String status) {
        runOnUi(() -> statusText.setText(status));
    }

    /**
     * Sets status text to indicate permissions are being requested.
     */
    public void setPermissionRequestingStatus() {
        runOnUi(() -> statusText.setText(activity.getString(R.string.status_requesting_permissions)));
    }

    /**
     * Sets status text to indicate the app is ready for user interaction.
     */
    public void setReadyStatus() {
        runOnUi(() -> statusText.setText(R.string.clean_status_ready));
    }

    /**
     * Sets status text to indicate permissions are required but not granted.
     */
    public void setPermissionsRequiredStatus() {
        runOnUi(() -> statusText.setText(activity.getString(R.string.status_permissions_required)));
    }

    /**
     * Sets status text to show how many media items are currently selected.
     *
     * @param count Number of selected media items
     */
    public void setSelectedItemsStatus(int count) {
        runOnUi(() -> {
            if (count == 0) {
                statusText.setText(R.string.clean_status_ready);
            } else {
                statusText.setText(activity.getString(R.string.clean_selected_count, count));
            }
        });
    }

    /**
     * Sets status text to indicate media processing is in progress.
     */
    public void setProcessingStatus() {
        runOnUi(() -> statusText.setText(activity.getString(R.string.status_processing)));
    }

    /**
     * Sets status text to show how many media items were processed successfully.
     *
     * @param successCount Successfully processed items
     * @param totalCount   Items in the batch
     */
    public void setProcessedItemsStatus(int successCount, int totalCount) {
        runOnUi(() -> {
            if (totalCount > 0 && successCount < totalCount) {
                if (successCount > 0) {
                    statusText.setText(activity.getString(R.string.status_clean_done_partial, successCount,
                            totalCount - successCount));
                } else {
                    statusText.setText(R.string.status_clean_done_failed);
                }
            } else if (successCount > 0) {
                statusText.setText(activity.getString(R.string.status_clean_done_all, successCount));
            } else {
                statusText.setText(R.string.status_clean_done_failed);
            }
        });
    }

    /**
     * Sets status text to prompt user to select media files first.
     * <p>
     * This is used when the user attempts to process files without selecting any.
     */
    public void setFirstSelectMediaFilesStatus() {
        runOnUi(() -> statusText.setText(activity.getString(R.string.status_first_select_media_files)));
    }

    /**
     * Enables or disables the strip button based on selection state.
     * <p>
     * The strip button should only be enabled when media items are selected.
     *
     * @param enable True to enable the button, false to disable it
     */
    public void enableStripButton(boolean enable) {
        runOnUi(() -> stripButton.setEnabled(enable));
    }
}
