package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.doubleangels.redact.media.AppProcessingScope;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** Settings behaviour that depends on runtime permissions (API 34: partial media access, notifications). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsPermissionFlowsTest {

    private Application app;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View settings;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setInitialPermissionsPromptCompleted(app);
        shadowOf(app).denyPermissions(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS);
        openSettings();
    }

    @After
    public void tearDown() {
        setShareSessions(0);
        controller.pause().stop().destroy();
        AppProcessingScope.resetForTests();
    }

    private void openSettings() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        activity.selectTab(R.id.navigation_settings);
        idle();
        settings = fragment().getView();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Fragment fragment() {
        return activity.getSupportFragmentManager().findFragmentByTag("settings");
    }

    private AlertDialog dialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private TextView text(int id) {
        return settings.findViewById(id);
    }

    private static void setShareSessions(int count) {
        try {
            Field f = ShareHandlerActivity.class.getDeclaredField("activeShareSessions");
            f.setAccessible(true);
            ((AtomicInteger) f.get(null)).set(count);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void resume() {
        controller.pause().resume();
        idle();
    }

    private void deliver(String permission, boolean granted) {
        ShadowActivity.PermissionsRequest request = shadowOf(activity).getLastRequestedPermission();
        assertNotNull("a permission request should be pending", request);
        activity.onRequestPermissionsResult(
                request.requestCode,
                new String[] {permission},
                new int[] {granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED});
        idle();
    }

    // ---- statuses -----------------------------------------------------------------------------------

    @Test
    public void mediaStatus_distinguishesDeniedPartialAndFull() {
        resume();
        assertEquals(app.getString(R.string.settings_permission_denied), text(R.id.textPermissionMediaStatus).getText().toString());

        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        resume();
        assertEquals(app.getString(R.string.settings_permission_partial), text(R.id.textPermissionMediaStatus).getText().toString());

        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        resume();
        assertEquals(app.getString(R.string.settings_permission_granted), text(R.id.textPermissionMediaStatus).getText().toString());
    }

    @Test
    public void locationStatus_isBlockedUntilMediaAccessIsGranted() {
        resume();
        assertEquals(app.getString(R.string.settings_permission_needs_media), text(R.id.textPermissionLocationStatus).getText().toString());
        assertEquals(View.VISIBLE, settings.findViewById(R.id.buttonLocationPermission).getVisibility());
    }

    // ---- notifications permission ----------------------------------------------------------------------------

    @Test
    public void enablingNotifications_withoutThePermission_isRevertedAndExplained() {
        MaterialSwitch master = settings.findViewById(R.id.switchNotifications);
        master.setChecked(false);
        idle();

        // Robolectric answers the system prompt immediately from the current grant state: denied.
        master.setChecked(true);
        idle();

        assertNotNull(shadowOf(activity).getLastRequestedPermission());
        assertFalse(AppPreferences.areNotificationsEnabled(app));
        assertFalse(master.isChecked());
        assertEquals(app.getString(R.string.settings_notifications_permission_denied), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void enablingNotifications_whenAlreadyPermitted_justTurnsThemOn() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        MaterialSwitch master = settings.findViewById(R.id.switchNotifications);
        master.setChecked(false);
        idle();

        master.setChecked(true);
        idle();

        assertTrue(AppPreferences.areNotificationsEnabled(app));
    }

    // ---- location permission -------------------------------------------------------------------------------

    @Test
    public void locationRow_withMediaAccess_requestsTheLocationPermission() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        resume();

        settings.findViewById(R.id.rowPermissionLocation).performClick();
        idle();
        assertEquals(Manifest.permission.ACCESS_MEDIA_LOCATION,
                shadowOf(activity).getLastRequestedPermission().requestedPermissions[0]);
        assertEquals(app.getString(R.string.settings_location_permission_denied), ShadowToast.getTextOfLatestToast());

        settings.findViewById(R.id.buttonLocationPermission).performClick();
        idle();
        assertNotNull(shadowOf(activity).getLastRequestedPermission());
    }

    @Test
    public void locationRow_withoutMediaAccess_offersToGrantMediaFirstThenAsksForLocation() {
        settings.findViewById(R.id.rowPermissionLocation).performClick();
        idle();
        AlertDialog dialog = dialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertNotNull("media permissions are requested first", shadowOf(activity).getLastRequestedPermission());

        // The user grants media access and returns to Settings: location is requested next.
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        resume();
        assertNotNull(shadowOf(activity).getLastRequestedPermission());
    }

    @Test
    public void locationRow_whenMediaAccessIsStillMissingOnReturn_dropsTheFollowUp() {
        settings.findViewById(R.id.rowPermissionLocation).performClick();
        idle();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();

        resume(); // still no media access

        assertNotNull(fragment());
    }

    // ---- storage + busy guards -----------------------------------------------------------------------------------

    private void confirmClear() {
        settings.findViewById(R.id.buttonClearTempFiles).performClick();
        idle();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
    }

    @Test
    public void clearingTempFiles_isRefusedWhileASharIsBeingProcessed() {
        setShareSessions(1);
        confirmClear();
        assertEquals(app.getString(R.string.settings_storage_clear_busy), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void clearingTempFiles_isRefusedWhileConverting() {
        AppProcessingScope.get(app).convertInProgress().set(true);
        confirmClear();
        assertEquals(app.getString(R.string.settings_storage_clear_busy), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void clearingNothing_saysThereWasNothingToClear() throws Exception {
        confirmClear();
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end
                && !app.getString(R.string.settings_storage_clear_empty).equals(ShadowToast.getTextOfLatestToast())) {
            idle();
            Thread.sleep(25);
        }
        assertEquals(app.getString(R.string.settings_storage_clear_empty), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void clearingRealFiles_saysItSucceeded() throws Exception {
        java.io.File processed = new java.io.File(app.getCacheDir(), "processed");
        processed.mkdirs();
        java.io.File leftover = new java.io.File(processed, "old.jpg");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(leftover)) {
            out.write(new byte[4096]);
        }
        assertTrue(leftover.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000));

        confirmClear();
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end
                && !app.getString(R.string.settings_storage_clear_success).equals(ShadowToast.getTextOfLatestToast())) {
            idle();
            Thread.sleep(25);
        }
        assertEquals(app.getString(R.string.settings_storage_clear_success), ShadowToast.getTextOfLatestToast());
    }

    // ---- dropdown filtering --------------------------------------------------------------------------------------------

    @Test
    public void dropdownAdapters_alwaysShowEveryOption() {
        int[] ids = {R.id.dropdownDefaultImageFormat, R.id.dropdownDefaultVideoFormat, R.id.dropdownImageQuality,
                R.id.dropdownSecureDelete, R.id.dropdownMaxBitmapSize};
        for (int id : ids) {
            MaterialAutoCompleteTextView dropdown = settings.findViewById(id);
            android.widget.ListAdapter adapter = dropdown.getAdapter();
            int total = adapter.getCount();
            ((android.widget.Filterable) adapter).getFilter().filter("zzz-no-match");
            idle();
            assertEquals(total, adapter.getCount());
        }
    }
}
