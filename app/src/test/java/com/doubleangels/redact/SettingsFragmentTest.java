package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class SettingsFragmentTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private View settings;

    /**
     * The debug build carries the project's real Sentry DSN. Tests must never initialise the SDK
     * against it (consent flows would otherwise send a telemetry metric and log to production), so
     * an empty DSN keeps SentryInitializer from configuring anything.
     */
    private static void overrideSentryDsn(String dsn) {
        try {
            java.lang.reflect.Field f = Class.forName("com.doubleangels.redact.sentry.SentryInitializer")
                    .getDeclaredField("testDsnOverride");
            f.setAccessible(true);
            f.set(null, dsn);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Before
    public void setUp() {
        overrideSentryDsn("");
        AppProcessingScope.resetForTests();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        activity.selectTab(R.id.navigation_settings);
        idle();
        settings = fragment().getView();
    }

    @After
    public void tearDown() {
        com.doubleangels.redact.sentry.SentryInitializer.shutdown();
        overrideSentryDsn(null);
        controller.pause().stop().destroy();
        AppProcessingScope.resetForTests();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Fragment fragment() {
        return activity.getSupportFragmentManager().findFragmentByTag("settings");
    }

    private MaterialSwitch sw(int id) {
        return settings.findViewById(id);
    }

    private static void pickDropdownItem(MaterialAutoCompleteTextView dropdown, int position) {
        AdapterView.OnItemClickListener listener = dropdown.getOnItemClickListener();
        assertNotNull(listener);
        listener.onItemClick(null, null, position, position);
    }

    /** Skips intents queued earlier (e.g. the initial runtime-permission request). */
    private Intent nextIntentWithAction(String action) {
        Intent intent;
        while ((intent = shadowOf(activity).getNextStartedActivity()) != null) {
            if (action.equals(intent.getAction())) {
                return intent;
            }
        }
        return null;
    }

    private AlertDialog latestDialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    // ---- notifications -------------------------------------------------------------------

    @Test
    public void masterNotificationSwitch_dimsAndDisablesTheSubSwitches() {
        MaterialSwitch master = sw(R.id.switchNotifications);
        master.setChecked(true);
        idle();
        assertTrue(AppPreferences.areNotificationsEnabled(activity));
        assertTrue(sw(R.id.switchCleanNotifications).isEnabled());

        master.setChecked(false);
        idle();
        assertFalse(AppPreferences.areNotificationsEnabled(activity));
        assertFalse(sw(R.id.switchCleanNotifications).isEnabled());
        assertFalse(sw(R.id.switchConvertNotifications).isEnabled());
        assertFalse(sw(R.id.switchProgressNotifications).isEnabled());
        assertEquals(0.38f, settings.findViewById(R.id.rowCleanNotifications).getAlpha(), 0.001f);
    }

    @Test
    public void subNotificationSwitches_persist() {
        sw(R.id.switchNotifications).setChecked(true);
        sw(R.id.switchCleanNotifications).setChecked(false);
        sw(R.id.switchConvertNotifications).setChecked(false);
        sw(R.id.switchProgressNotifications).setChecked(false);

        assertFalse(AppPreferences.areCleanNotificationsEnabled(activity));
        assertFalse(AppPreferences.areConvertNotificationsEnabled(activity));
        assertFalse(AppPreferences.areProgressNotificationsEnabled(activity));

        sw(R.id.switchCleanNotifications).setChecked(true);
        sw(R.id.switchConvertNotifications).setChecked(true);
        sw(R.id.switchProgressNotifications).setChecked(true);
        assertTrue(AppPreferences.areCleanNotificationsEnabled(activity));
        assertTrue(AppPreferences.areConvertNotificationsEnabled(activity));
        assertTrue(AppPreferences.areProgressNotificationsEnabled(activity));
    }

    // ---- cleaning options ----------------------------------------------------------------

    @Test
    public void strictClean_turnsOffAndLocksThePreservationToggles() {
        MaterialSwitch strict = sw(R.id.switchStrictClean);
        strict.setChecked(false);
        idle();
        assertFalse(AppPreferences.isStrictClean(activity));
        assertTrue(sw(R.id.switchPreserveCamera).isEnabled());
        assertTrue(sw(R.id.switchPreserveLocation).isEnabled());

        sw(R.id.switchPreserveCamera).setChecked(true);
        assertTrue(AppPreferences.isPreserveCameraSettings(activity));

        strict.setChecked(true);
        idle();
        assertTrue(AppPreferences.isStrictClean(activity));
        assertFalse(AppPreferences.isPreserveCameraSettings(activity));
        assertFalse(AppPreferences.isPreserveLocation(activity));
        assertFalse(sw(R.id.switchPreserveCamera).isEnabled());
        assertFalse(sw(R.id.switchPreserveLocation).isEnabled());
        assertEquals(0.38f, settings.findViewById(R.id.rowPreserveCamera).getAlpha(), 0.001f);
    }

    @Test
    public void preserveLocation_requiresConfirmation() {
        sw(R.id.switchStrictClean).setChecked(false);
        idle();
        MaterialSwitch location = sw(R.id.switchPreserveLocation);

        location.setChecked(true);
        idle();
        AlertDialog dialog = latestDialog();
        assertNotNull(dialog);
        assertFalse(location.isChecked());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertFalse(AppPreferences.isPreserveLocation(activity));

        location.setChecked(true);
        idle();
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(AppPreferences.isPreserveLocation(activity));
        assertTrue(location.isChecked());

        location.setChecked(false);
        assertFalse(AppPreferences.isPreserveLocation(activity));
    }

    @Test
    public void otherSwitches_persist() {
        sw(R.id.switchDeleteOriginals).setChecked(true);
        sw(R.id.switchAutoClearTemp).setChecked(true);
        sw(R.id.switchVideoFallback).setChecked(true);
        sw(R.id.switchShareConfirm).setChecked(true);
        assertTrue(AppPreferences.isDeleteOriginalsAfterClean(activity));
        assertTrue(AppPreferences.isAutoClearTempFiles(activity));
        assertTrue(AppPreferences.isVideoFallbackCopy(activity));
        assertTrue(AppPreferences.isShareConfirmBeforeStrip(activity));

        sw(R.id.switchDeleteOriginals).setChecked(false);
        sw(R.id.switchAutoClearTemp).setChecked(false);
        sw(R.id.switchVideoFallback).setChecked(false);
        sw(R.id.switchShareConfirm).setChecked(false);
        assertFalse(AppPreferences.isDeleteOriginalsAfterClean(activity));
        assertFalse(AppPreferences.isAutoClearTempFiles(activity));
        assertFalse(AppPreferences.isVideoFallbackCopy(activity));
        assertFalse(AppPreferences.isShareConfirmBeforeStrip(activity));
    }

    @Test
    public void warnAlreadyClean_isOnByDefault_andPersists() {
        assertTrue(sw(R.id.switchWarnAlreadyClean).isChecked());
        assertTrue(AppPreferences.isWarnAlreadyClean(activity));

        sw(R.id.switchWarnAlreadyClean).setChecked(false);
        assertFalse(AppPreferences.isWarnAlreadyClean(activity));

        sw(R.id.switchWarnAlreadyClean).setChecked(true);
        assertTrue(AppPreferences.isWarnAlreadyClean(activity));
    }

    // ---- dropdowns ---------------------------------------------------------------------------

    @Test
    public void formatAndQualityDropdowns_storeTheChosenIndex() {
        MaterialAutoCompleteTextView image = settings.findViewById(R.id.dropdownDefaultImageFormat);
        MaterialAutoCompleteTextView video = settings.findViewById(R.id.dropdownDefaultVideoFormat);
        MaterialAutoCompleteTextView quality = settings.findViewById(R.id.dropdownImageQuality);

        pickDropdownItem(image, 1);
        pickDropdownItem(video, 2);
        pickDropdownItem(quality, 2);

        assertEquals(1, AppPreferences.getDefaultImageFormatIndex(activity));
        assertEquals(2, AppPreferences.getDefaultVideoFormatIndex(activity));
        assertEquals(2, AppPreferences.getImageQualityPreset(activity));
        assertTrue(image.getText().length() > 0);
    }

    @Test
    public void secureDeleteAndBitmapSizeDropdowns_mapIndexesToValues() {
        MaterialAutoCompleteTextView secure = settings.findViewById(R.id.dropdownSecureDelete);
        MaterialAutoCompleteTextView bitmap = settings.findViewById(R.id.dropdownMaxBitmapSize);

        pickDropdownItem(secure, 0);
        assertEquals(1, AppPreferences.getSecureDeletePasses(activity));
        pickDropdownItem(secure, 1);
        assertEquals(3, AppPreferences.getSecureDeletePasses(activity));
        pickDropdownItem(secure, 2);
        assertEquals(7, AppPreferences.getSecureDeletePasses(activity));

        pickDropdownItem(bitmap, 0);
        assertEquals(2048, AppPreferences.getMaxBitmapSize(activity));
        pickDropdownItem(bitmap, 1);
        assertEquals(4096, AppPreferences.getMaxBitmapSize(activity));
        pickDropdownItem(bitmap, 2);
        assertEquals(8192, AppPreferences.getMaxBitmapSize(activity));
    }

    @Test
    public void savedDropdownValues_areShownWhenTheTabIsCreated() {
        controller.pause().stop().destroy();
        AppPreferences.setSecureDeletePasses(activity, 7);
        AppPreferences.setMaxBitmapSize(activity, 2048);
        AppPreferences.setDefaultImageFormatIndex(activity, 2);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        activity.selectTab(R.id.navigation_settings);
        idle();
        settings = fragment().getView();

        MaterialAutoCompleteTextView secure = settings.findViewById(R.id.dropdownSecureDelete);
        String[] labels = activity.getResources().getStringArray(R.array.settings_secure_delete_labels);
        assertEquals(labels[2], secure.getText().toString());
        MaterialAutoCompleteTextView bitmap = settings.findViewById(R.id.dropdownMaxBitmapSize);
        String[] sizes = activity.getResources().getStringArray(R.array.settings_max_bitmap_size_labels);
        assertEquals(sizes[0], bitmap.getText().toString());
    }

    // ---- crash reporting ---------------------------------------------------------------------

    @Test
    public void crashReporting_enablingAsksForConsent() {
        AppPreferences.setCrashReportingEnabled(activity, false);
        MaterialSwitch crash = sw(R.id.switchCrashReporting);
        crash.setChecked(false);

        crash.setChecked(true);
        idle();
        AlertDialog dialog = latestDialog();
        assertNotNull(dialog);
        assertFalse(crash.isChecked());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertFalse(AppPreferences.isCrashReportingEnabled(activity));

        crash.setChecked(true);
        idle();
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(AppPreferences.isCrashReportingEnabled(activity));
        assertTrue(crash.isChecked());

        crash.setChecked(false);
        assertFalse(AppPreferences.isCrashReportingEnabled(activity));
    }

    @Test
    public void crashReportingLearnMore_showsDetailsAndCanOpenThePrivacyPolicy() {
        settings.findViewById(R.id.buttonCrashReportingLearnMore).performClick();
        idle();
        AlertDialog dialog = latestDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();

        Intent opened = nextIntentWithAction(Intent.ACTION_VIEW);
        assertNotNull(opened);
    }

    // ---- storage ---------------------------------------------------------------------------------

    @Test
    public void clearTempFiles_confirmsThenCleansAndRefreshesTheSize() throws Exception {
        java.io.File cache = new java.io.File(activity.getCacheDir(), "processed");
        cache.mkdirs();
        java.io.File junk = new java.io.File(cache, "leftover.jpg");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(junk)) {
            out.write(new byte[2048]);
        }
        assertTrue(junk.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000));

        settings.findViewById(R.id.buttonClearTempFiles).performClick();
        idle();
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end && junk.exists()) {
            idle();
            Thread.sleep(25);
        }
        idle();

        assertFalse(junk.exists());
    }

    @Test
    public void clearTempFiles_cancelLeavesFilesAlone() throws Exception {
        java.io.File cache = new java.io.File(activity.getCacheDir(), "processed");
        cache.mkdirs();
        java.io.File junk = new java.io.File(cache, "keep.jpg");
        assertTrue(junk.createNewFile());

        settings.findViewById(R.id.buttonClearTempFiles).performClick();
        idle();
        latestDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();

        assertTrue(junk.exists());
    }

    @Test
    public void storageSize_isShownAfterResume() throws Exception {
        controller.pause().resume();
        long end = System.currentTimeMillis() + 3000;
        TextView size = settings.findViewById(R.id.textStorageSize);
        while (System.currentTimeMillis() < end && size.getText().length() == 0) {
            idle();
            Thread.sleep(25);
        }
        assertTrue(size.getText().length() > 0);
    }

    // ---- permissions + about ------------------------------------------------------------------------

    @Test
    public void openSystemSettings_launchesAppDetails() {
        settings.findViewById(R.id.buttonOpenSystemSettings).performClick();
        Intent intent = nextIntentWithAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        assertNotNull(intent);
        assertEquals("package:" + activity.getPackageName(), intent.getData().toString());
    }

    @Test
    public void permissionStatuses_reflectGrantedState() {
        TextView media = settings.findViewById(R.id.textPermissionMediaStatus);
        assertTrue(media.getText().length() > 0);
        TextView location = settings.findViewById(R.id.textPermissionLocationStatus);
        assertTrue(location.getText().length() > 0);

        shadowOf(activity.getApplication()).grantPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION);
        controller.pause().resume();
        idle();

        assertEquals(View.GONE, settings.findViewById(R.id.buttonLocationPermission).getVisibility());
        assertEquals(activity.getString(R.string.settings_permission_granted), location.getText().toString());
    }

    @Test
    public void locationRow_withoutMediaAccess_explainsTheDependency() {
        shadowOf(activity.getApplication()).denyPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION);

        settings.findViewById(R.id.rowPermissionLocation).performClick();
        idle();

        AlertDialog dialog = latestDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    }

    @Test
    public void locationButton_withMediaAccess_launchesThePermissionRequest() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);
        shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);

        settings.findViewById(R.id.buttonLocationPermission).performClick();
        idle();

        ShadowActivity.PermissionsRequest request = shadowOf(activity).getLastRequestedPermission();
        assertNotNull(request);
    }

    @Test
    public void locationRow_whenAlreadyGranted_doesNothing() {
        shadowOf(activity.getApplication()).grantPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION);
        settings.findViewById(R.id.rowPermissionLocation).performClick();
        idle();
        assertNotNull(fragment());
    }

    @Test
    public void aboutRows_openTheirLinks() {
        int[] rows = {R.id.rowAboutGithub, R.id.rowAboutPrivacy, R.id.rowAboutReportIssue, R.id.rowAboutDonate};
        for (int row : rows) {
            settings.findViewById(row).performClick();
            assertNotNull(nextIntentWithAction(Intent.ACTION_VIEW));
        }
        TextView version = settings.findViewById(R.id.textAboutVersion);
        assertTrue(version.getText().toString().contains(BuildConfig.VERSION_NAME));
    }

    @Test
    public void defaultFormatHelpers_followTheSavedDefaults() {
        AppPreferences.setDefaultImageFormatIndex(activity, 1);
        AppPreferences.setDefaultVideoFormatIndex(activity, 2);

        assertEquals(1, SettingsFragment.defaultFormatIndexForSelection(activity, 3, 0));
        assertEquals(2, SettingsFragment.defaultFormatIndexForSelection(activity, 0, 2));
        assertEquals(1, SettingsFragment.defaultFormatIndexForSelection(activity, 1, 1));
        assertEquals(R.id.chipFormatJpeg, SettingsFragment.chipIdForFormatIndex(0));
        assertEquals(R.id.chipFormatPng, SettingsFragment.chipIdForFormatIndex(1));
        assertEquals(R.id.chipFormatWebp, SettingsFragment.chipIdForFormatIndex(2));
        assertEquals(R.id.chipFormatHeif, SettingsFragment.chipIdForFormatIndex(3));
        assertEquals(R.id.chipFormatJpeg, SettingsFragment.chipIdForFormatIndex(99));
    }

    @SuppressWarnings("unused")
    private static void unused(Activity a) {
    }
}
