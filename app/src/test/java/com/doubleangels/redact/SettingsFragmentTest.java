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
        com.doubleangels.redact.ui.Notice.resetForTests();
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

    // ---- switch rows ---------------------------------------------------------------------

    @Test
    public void tappingAnywhereOnASwitchRow_togglesItsSwitch() {
        MaterialSwitch keepNames = sw(R.id.switchKeepOriginalNames);
        View row = (View) keepNames.getParent();
        boolean before = keepNames.isChecked();

        row.performClick();
        idle();
        assertEquals(!before, keepNames.isChecked());
        assertEquals(!before, AppPreferences.isKeepOriginalFileNames(activity));

        row.performClick();
        idle();
        assertEquals(before, keepNames.isChecked());
    }

    @Test
    public void aDisabledSwitchRow_ignoresTaps() {
        sw(R.id.switchNotifications).setChecked(false);
        idle();
        MaterialSwitch clean = sw(R.id.switchCleanNotifications);
        boolean before = clean.isChecked();

        settings.findViewById(R.id.rowCleanNotifications).performClick();
        idle();
        assertEquals(before, clean.isChecked());
    }

    @Test
    public void aSwitchRow_readsAsOneSwitchWithItsState() {
        MaterialSwitch keepNames = sw(R.id.switchKeepOriginalNames);
        View row = (View) keepNames.getParent();
        android.view.accessibility.AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
        assertEquals(android.widget.Switch.class.getName(), info.getClassName().toString());
        assertTrue(info.isCheckable());
        assertEquals(keepNames.isChecked(), info.isChecked());
        assertTrue(info.isClickable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, keepNames.getImportantForAccessibility());
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

    @Test
    public void secureWindow_isOffByDefault_andTheSwitchTogglesTheLiveWindow() {
        int secure = android.view.WindowManager.LayoutParams.FLAG_SECURE;
        assertFalse(sw(R.id.switchSecureWindow).isChecked());
        assertEquals(0, activity.getWindow().getAttributes().flags & secure);

        sw(R.id.switchSecureWindow).setChecked(true);
        assertTrue(AppPreferences.isSecureWindow(activity));
        assertEquals(secure, activity.getWindow().getAttributes().flags & secure);

        sw(R.id.switchSecureWindow).setChecked(false);
        assertFalse(AppPreferences.isSecureWindow(activity));
        assertEquals(0, activity.getWindow().getAttributes().flags & secure);
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
    public void clipboardClearDropdown_isOneMinuteByDefault_andMapsIndexesToSeconds() {
        MaterialAutoCompleteTextView clear = settings.findViewById(R.id.dropdownClipboardClear);
        assertEquals(activity.getString(R.string.settings_clipboard_clear_1m), clear.getText().toString());

        int[] expected = {0, 15, 30, 60, 300};
        for (int i = 0; i < expected.length; i++) {
            pickDropdownItem(clear, i);
            assertEquals(expected[i], AppPreferences.getClipboardClearSeconds(activity));
        }
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
    public void locationRow_whenAlreadyGranted_doesNothing() {
        shadowOf(activity.getApplication()).grantPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION);
        settings.findViewById(R.id.rowPermissionLocation).performClick();
        idle();
        assertNotNull(fragment());
    }

    @Test
    public void resetAllSettings_isNotUndoneByTheRecreatedScreen() {
        sw(R.id.switchDeleteOriginals).setChecked(true);
        sw(R.id.switchKeepOriginalNames).setChecked(true);
        idle();
        assertTrue(AppPreferences.isDeleteOriginalsAfterClean(activity));

        settings.findViewById(R.id.rowResetSettings).performClick();
        idle();
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();

        // The recreated switches must not write their old checked state back.
        assertFalse(AppPreferences.isDeleteOriginalsAfterClean(activity));
        assertFalse(AppPreferences.isKeepOriginalFileNames(activity));
        activity = controller.get();
        settings = fragment().getView();
        assertFalse(sw(R.id.switchDeleteOriginals).isChecked());
        assertFalse(sw(R.id.switchKeepOriginalNames).isChecked());
    }

    @Test
    public void aboutRows_openTheirLinks() {
        int[] rows = {R.id.rowAboutGithub, R.id.rowAboutPrivacy, R.id.rowAboutReportIssue,
                R.id.rowAboutRate, R.id.rowAboutDonate};
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

    /** Leaves Settings and comes back, which re-reads the output folders. */
    private void reshowSettings() {
        activity.selectTab(R.id.navigation_clean);
        idle();
        activity.selectTab(R.id.navigation_settings);
        idle();
    }

    /** Waits for an activity-for-result launched after background work, as the folder picker is. */
    private org.robolectric.shadows.ShadowActivity.IntentForResult awaitStartedForResult() throws Exception {
        long end = System.currentTimeMillis() + 3000;
        org.robolectric.shadows.ShadowActivity.IntentForResult launched = null;
        while (launched == null && System.currentTimeMillis() < end) {
            idle();
            launched = shadowOf(activity).getNextStartedActivityForResult();
            // Skip the initial runtime-permission request.
            if (launched != null && !Intent.ACTION_OPEN_DOCUMENT_TREE.equals(launched.intent.getAction())) {
                launched = null;
                continue;
            }
            if (launched == null) {
                Thread.sleep(25);
            }
        }
        assertNotNull(launched);
        return launched;
    }

    private Intent awaitStarted(String action) throws Exception {
        Intent intent = null;
        long end = System.currentTimeMillis() + 3000;
        while (intent == null && System.currentTimeMillis() < end) {
            idle();
            intent = nextIntentWithAction(action);
            if (intent == null) {
                Thread.sleep(25);
            }
        }
        assertNotNull(intent);
        return intent;
    }

    @Test
    public void chooseFolder_offersTheSameFolderForTheOtherMediaType() throws Exception {
        FakeDocumentsProvider.install(activity);
        android.net.Uri tree = FakeDocumentsProvider.folder("primary:DCIM/Camera");
        settings.findViewById(R.id.buttonChooseOutputImages).performClick();
        org.robolectric.shadows.ShadowActivity.IntentForResult launched = awaitStartedForResult();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launched.intent.getAction());

        shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_OK,
                new Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
        idle();
        assertEquals(tree, AppPreferences.getOutputTree(activity, false));

        AlertDialog offer = latestDialog();
        assertNotNull(offer);
        offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(tree, AppPreferences.getOutputTree(activity, true));

        // A chosen folder is opened with the read grant Redact holds for it.
        settings.findViewById(R.id.buttonOpenOutputImages).performClick();
        Intent view = awaitStarted(Intent.ACTION_VIEW);
        assertTrue((view.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void chooseFolder_cancelled_changesNothing() throws Exception {
        settings.findViewById(R.id.buttonChooseOutputVideos).performClick();
        org.robolectric.shadows.ShadowActivity.IntentForResult launched = awaitStartedForResult();
        shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_CANCELED, null);
        idle();
        assertEquals(null, AppPreferences.getOutputTree(activity, true));
    }

    @Test
    public void resetAllSettings_whileAJobRuns_isRefused() {
        AppPreferences.setDeleteOriginalsAfterClean(activity, true);
        new androidx.lifecycle.ViewModelProvider(activity).get(com.doubleangels.redact.ui.MainViewModel.class)
                .setCleanProcessingState(com.doubleangels.redact.ui.MainViewModel.ProcessingState.PROCESSING);
        settings.findViewById(R.id.rowResetSettings).performClick();
        idle();
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();

        assertEquals(activity.getString(R.string.settings_reset_all_busy),
                com.doubleangels.redact.ui.Notice.lastShownForTests());
        assertTrue(AppPreferences.isDeleteOriginalsAfterClean(activity));
    }

    @Test
    public void dropdowns_showTheLeastAndMostThoroughChoices() {
        AppPreferences.setSecureDeletePasses(activity, 1);
        AppPreferences.setMaxBitmapSize(activity, 8192);
        // Settings reads its values when its view is made, so start over with a new activity.
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        activity.selectTab(R.id.navigation_settings);
        idle();
        settings = fragment().getView();

        String[] passes = activity.getResources().getStringArray(R.array.settings_secure_delete_labels);
        assertEquals(passes[0],
                ((MaterialAutoCompleteTextView) settings.findViewById(R.id.dropdownSecureDelete)).getText().toString());
        String[] sizes = activity.getResources().getStringArray(R.array.settings_max_bitmap_size_labels);
        assertEquals(sizes[2],
                ((MaterialAutoCompleteTextView) settings.findViewById(R.id.dropdownMaxBitmapSize)).getText().toString());
    }

    @Test
    public void openFolder_showsTheOutputFolderInTheFileManager() throws Exception {
        assertTrue(new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                com.doubleangels.redact.media.OutputDestination.defaultPath(true)).mkdirs());
        settings.findViewById(R.id.buttonOpenOutputVideos).performClick();

        // The folder is worked out in the background, then opened on the main thread.
        Intent intent = null;
        long end = System.currentTimeMillis() + 3000;
        while (intent == null && System.currentTimeMillis() < end) {
            idle();
            intent = nextIntentWithAction(Intent.ACTION_VIEW);
            if (intent == null) {
                Thread.sleep(25);
            }
        }
        assertNotNull(intent);
        assertEquals(android.provider.DocumentsContract.Document.MIME_TYPE_DIR, intent.getType());
        assertEquals("primary:Movies/Redact",
                android.provider.DocumentsContract.getDocumentId(intent.getData()));
        // Redact holds no grant on the default folder, so asking to pass one on would throw.
        assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Test
    public void outputFolders_showTheDefault_aChosenFolder_andAWarningOnceItIsGone() {
        TextView label = settings.findViewById(R.id.textOutputImages);
        TextView warning = settings.findViewById(R.id.textOutputImagesWarning);
        View reset = settings.findViewById(R.id.buttonResetOutputImages);
        assertEquals("Pictures/Redact", label.getText().toString());
        assertEquals("Movies/Redact",
                ((TextView) settings.findViewById(R.id.textOutputVideos)).getText().toString());
        assertEquals(View.GONE, reset.getVisibility());
        assertEquals(View.GONE, warning.getVisibility());

        FakeDocumentsProvider.install(activity);
        SettingsFragment.onOutputFolderPicked(activity, false,
                FakeDocumentsProvider.folder("primary:Pictures/Private"));
        reshowSettings();
        assertEquals("Pictures/Private", label.getText().toString());
        assertEquals(View.VISIBLE, reset.getVisibility());
        assertEquals(View.GONE, warning.getVisibility());

        //noinspection ResultOfMethodCallIgnored
        FakeDocumentsProvider.fileFor("primary:Pictures/Private").delete();
        reshowSettings();
        // The folder is checked in the background; wait for the answer to reach the screen.
        long end = System.currentTimeMillis() + 3000;
        while (warning.getVisibility() != View.VISIBLE && System.currentTimeMillis() < end) {
            idle();
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals("Pictures/Private", label.getText().toString());
        assertEquals(View.VISIBLE, warning.getVisibility());
        assertEquals(activity.getString(R.string.settings_output_unavailable, "Pictures/Redact"),
                warning.getText().toString());

        reset.performClick();
        idle();
        assertEquals("Pictures/Redact", label.getText().toString());
        assertEquals(View.GONE, reset.getVisibility());
        assertEquals(View.GONE, warning.getVisibility());
        assertEquals(null, AppPreferences.getOutputTree(activity, false));
    }

    @Test
    public void statsCard_withNothingCounted_invitesAClean() {
        assertEquals(View.GONE, settings.findViewById(R.id.rowStatsSummary).getVisibility());
        assertEquals(activity.getString(R.string.stats_settings_empty),
                ((TextView) settings.findViewById(R.id.textStatsSummarySubtitle)).getText().toString());
    }

    @Test
    public void statsCard_showsTheTotalsWhenSettingsIsShownAgain() {
        com.doubleangels.redact.stats.StatsStore.recordClean(activity,
                new com.doubleangels.redact.metadata.CleanStats(1284, true), false, false);
        com.doubleangels.redact.stats.StatsStore.recordClean(activity,
                new com.doubleangels.redact.metadata.CleanStats(0, false), true, false);

        reshowSettings();

        assertEquals(View.VISIBLE, settings.findViewById(R.id.rowStatsSummary).getVisibility());
        assertEquals("1,284", ((TextView) settings.findViewById(R.id.textStatsSummaryFields)).getText().toString());
        assertEquals("1", ((TextView) settings.findViewById(R.id.textStatsSummaryLocations)).getText().toString());
        assertEquals("2", ((TextView) settings.findViewById(R.id.textStatsSummaryFiles)).getText().toString());
        assertTrue(((TextView) settings.findViewById(R.id.textStatsSummarySubtitle)).getText().toString()
                .startsWith("Counted on this device since "));
    }

    @Test
    public void statsCard_opensTheStatisticsScreen() {
        settings.findViewById(R.id.cardStatsSummary).performClick();
        idle();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(StatisticsActivity.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void statsCard_isTheFirstThingInSettings() {
        View card = settings.findViewById(R.id.cardStatsSummary);
        android.view.ViewGroup list = (android.view.ViewGroup) card.getParent();
        int firstCard = -1;
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i) instanceof com.google.android.material.card.MaterialCardView) {
                firstCard = i;
                break;
            }
        }
        assertEquals(card, list.getChildAt(firstCard));
        assertTrue(card.isClickable());
    }

    @Test
    public void statsCard_shortensCountsTooLongForTheCard() {
        activity.getSharedPreferences("redact_stats", android.content.Context.MODE_PRIVATE).edit()
                .putLong("fields_removed", 1_284_000L)
                .putLong("locations_removed", 999_999L)
                .putLong("files_cleaned_images", 2_500_000_000L)
                .putLong("tracking_since", System.currentTimeMillis())
                .commit();

        reshowSettings();

        assertEquals("1.3M", ((TextView) settings.findViewById(R.id.textStatsSummaryFields)).getText().toString());
        assertEquals("999,999", ((TextView) settings.findViewById(R.id.textStatsSummaryLocations)).getText().toString());
        assertEquals("2.5B", ((TextView) settings.findViewById(R.id.textStatsSummaryFiles)).getText().toString());
    }

    @Test
    public void statsCard_goesBackToTheInvitationAfterAClear() {
        com.doubleangels.redact.stats.StatsStore.recordClean(activity,
                new com.doubleangels.redact.metadata.CleanStats(12, true), false, false);
        reshowSettings();
        assertEquals(View.VISIBLE, settings.findViewById(R.id.rowStatsSummary).getVisibility());

        // As when Statistics is opened from here, cleared, and closed again.
        controller.pause();
        com.doubleangels.redact.stats.StatsStore.clear(activity);
        controller.resume();
        idle();

        assertEquals(View.GONE, settings.findViewById(R.id.rowStatsSummary).getVisibility());
        assertEquals(activity.getString(R.string.stats_settings_empty),
                ((TextView) settings.findViewById(R.id.textStatsSummarySubtitle)).getText().toString());
    }

    @Test
    public void statsCard_updatesWhenTheAppComesBack() {
        controller.pause();
        com.doubleangels.redact.stats.StatsStore.recordClean(activity,
                new com.doubleangels.redact.metadata.CleanStats(3, false), true, true);
        controller.resume();
        idle();

        assertEquals(View.VISIBLE, settings.findViewById(R.id.rowStatsSummary).getVisibility());
        assertEquals("3", ((TextView) settings.findViewById(R.id.textStatsSummaryFields)).getText().toString());
        assertEquals("0", ((TextView) settings.findViewById(R.id.textStatsSummaryLocations)).getText().toString());
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "de")
    public void statsCard_isTranslated() {
        com.doubleangels.redact.stats.StatsStore.recordClean(activity,
                new com.doubleangels.redact.metadata.CleanStats(1284, true), false, false);

        reshowSettings();

        assertEquals("1.284", ((TextView) settings.findViewById(R.id.textStatsSummaryFields)).getText().toString());
        assertFalse(((TextView) settings.findViewById(R.id.textStatsSummarySubtitle)).getText().toString()
                .startsWith("Counted"));
    }

    @SuppressWarnings("unused")
    private static void unused(Activity a) {
    }
}
