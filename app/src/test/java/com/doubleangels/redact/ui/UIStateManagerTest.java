package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.doubleangels.redact.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class UIStateManagerTest {

    private Activity activity;
    private TextView statusText;
    private MaterialButton stripButton;
    private LinearLayout progressContainer;
    private LinearProgressIndicator progressBar;
    private TextView progressText;
    private UIStateManager uiStateManager;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        statusText = new TextView(activity);
        stripButton = new MaterialButton(activity);
        progressContainer = new LinearLayout(activity);
        progressBar = new LinearProgressIndicator(activity);
        progressText = new TextView(activity);
        uiStateManager = new UIStateManager(
                activity, statusText, stripButton, progressContainer, progressBar, progressText);
    }

    private void idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void updateProgress_setsBarAndMessage() {
        uiStateManager.updateProgress(5, 10, "Halfway");
        idleMainLooper();
        assertEquals(50, progressBar.getProgress());
        assertEquals("Halfway", progressText.getText().toString());
    }

    @Test
    public void updateProgress_clampsPercentToZeroAndHundred() {
        uiStateManager.updateProgress(120, 100, "Overflow");
        idleMainLooper();
        assertEquals(100, progressBar.getProgress());

        uiStateManager.updateProgress(-5, 100, "Negative");
        idleMainLooper();
        assertEquals(0, progressBar.getProgress());
    }

    @Test
    public void updateProgress_zeroTotalYieldsZero() {
        uiStateManager.updateProgress(3, 0, "No total");
        idleMainLooper();
        assertEquals(0, progressBar.getProgress());
    }

    @Test
    public void showProgress_togglesContainerAndResetsBarWhenVisible() {
        uiStateManager.showProgress(true);
        idleMainLooper();
        assertEquals(View.VISIBLE, progressContainer.getVisibility());
        assertEquals(0, progressBar.getProgress());

        uiStateManager.showProgress(false);
        idleMainLooper();
        assertEquals(View.GONE, progressContainer.getVisibility());
    }

    @Test
    public void setStatus_updatesStatusText() {
        uiStateManager.setStatus("custom message");
        idleMainLooper();
        assertEquals("custom message", statusText.getText().toString());
    }

    @Test
    public void readyStatus_usesReadyResource() {
        uiStateManager.setReadyStatus();
        idleMainLooper();
        assertEquals(activity.getString(R.string.clean_status_ready), statusText.getText().toString());
    }

    @Test
    public void setSelectedItemsStatus_differsForZeroAndNonZero() {
        uiStateManager.setSelectedItemsStatus(0);
        idleMainLooper();
        assertEquals(activity.getString(R.string.clean_status_ready), statusText.getText().toString());

        uiStateManager.setSelectedItemsStatus(3);
        idleMainLooper();
        assertEquals(activity.getString(R.string.clean_selected_count, 3), statusText.getText().toString());
    }

    @Test
    public void setProcessedItemsStatus_allSucceeded() {
        uiStateManager.setProcessedItemsStatus(5, 5);
        idleMainLooper();
        assertEquals(activity.getString(R.string.status_clean_done_all, 5), statusText.getText().toString());
    }

    @Test
    public void setProcessedItemsStatus_partialFailure() {
        uiStateManager.setProcessedItemsStatus(4, 5);
        idleMainLooper();
        assertEquals(activity.getString(R.string.status_clean_done_partial, 4, 1),
                statusText.getText().toString());
    }

    @Test
    public void setProcessedItemsStatus_totalFailure() {
        uiStateManager.setProcessedItemsStatus(0, 5);
        idleMainLooper();
        assertEquals(activity.getString(R.string.status_clean_done_failed), statusText.getText().toString());
    }

    @Test
    public void setProcessingStatus_andPermissionStatuses() {
        uiStateManager.setProcessingStatus();
        uiStateManager.setPermissionRequestingStatus();
        uiStateManager.setPermissionsRequiredStatus();
        idleMainLooper();
        assertEquals(activity.getString(R.string.status_permissions_required), statusText.getText().toString());
    }

    @Test
    public void enableStripButton_togglesEnabledState() {
        uiStateManager.enableStripButton(true);
        idleMainLooper();
        assertTrue(stripButton.isEnabled());

        uiStateManager.enableStripButton(false);
        idleMainLooper();
        assertFalse(stripButton.isEnabled());
    }

    @Test
    public void stateChangesAreIgnoredWhenActivityFinishing() {
        activity.finish();
        uiStateManager.setStatus("should not appear");
        uiStateManager.updateProgress(10, 10, "no-op");
        uiStateManager.enableStripButton(false);
        idleMainLooper();
        assertEquals("", statusText.getText().toString());
        assertTrue(stripButton.isEnabled());
        assertEquals(0, progressBar.getProgress());
    }
}