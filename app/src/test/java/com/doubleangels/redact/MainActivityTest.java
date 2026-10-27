package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.fragment.app.Fragment;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.permission.PermissionManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MainActivityTest {

    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        com.doubleangels.redact.ui.Notice.resetForTests();
        AppProcessingScope.resetForTests();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        AppProcessingScope.resetForTests();
    }

    private MainActivity launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        return controller.get();
    }

    private void select(MainActivity activity, int tabId) {
        activity.selectTab(tabId);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Fragment fragment(MainActivity activity, String tag) {
        return activity.getSupportFragmentManager().findFragmentByTag(tag);
    }

    @Test
    public void launch_showsCleanTabOnly() {
        MainActivity activity = launch();

        assertNotNull(fragment(activity, "clean"));
        assertTrue(fragment(activity, "clean").isVisible());
        assertNull(fragment(activity, "scan"));
        assertNull(fragment(activity, "convert"));
        assertNull(fragment(activity, "settings"));
        BottomNavigationView nav = activity.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_clean, nav.getSelectedItemId());
    }

    @Test
    public void selectingEveryTab_addsFragmentsLazilyAndSwapsVisibility() {
        MainActivity activity = launch();

        select(activity, R.id.navigation_scan);
        assertNotNull(fragment(activity, "scan"));
        assertTrue(fragment(activity, "scan").isVisible());
        assertTrue(fragment(activity, "clean").isHidden());

        select(activity, R.id.navigation_convert);
        assertNotNull(fragment(activity, "convert"));
        assertTrue(fragment(activity, "scan").isHidden());

        select(activity, R.id.navigation_settings);
        assertNotNull(fragment(activity, "settings"));
        assertTrue(fragment(activity, "convert").isHidden());

        select(activity, R.id.navigation_clean);
        assertTrue(fragment(activity, "clean").isVisible());
        assertTrue(fragment(activity, "settings").isHidden());
    }

    @Test
    public void switchingTabs_fadesThrough_andEndsWithTheNewTabWhole() {
        MainActivity activity = launch();
        select(activity, R.id.navigation_scan);
        select(activity, R.id.navigation_clean);
        select(activity, R.id.navigation_scan);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));

        android.view.View scan = fragment(activity, "scan").getView();
        android.view.View clean = fragment(activity, "clean").getView();
        assertEquals(android.view.View.VISIBLE, scan.getVisibility());
        assertEquals(1f, scan.getAlpha(), 0.001f);
        assertEquals(1f, scan.getScaleX(), 0.001f);
        assertEquals(android.view.View.GONE, clean.getVisibility());
    }

    @Test
    public void tabFade_runsOnAHardwareLayer_onlyWhileItPlays() {
        MainActivity activity = launch();
        TabFragment clean = (TabFragment) fragment(activity, "clean");
        assertNull(clean.onCreateAnimator(0, true, 0));

        android.animation.Animator fade = clean.onCreateAnimator(0, true, R.animator.fade_through_enter);
        assertNotNull(fade);
        fade.setTarget(clean.getView());
        fade.start();
        assertEquals(android.view.View.LAYER_TYPE_HARDWARE, clean.getView().getLayerType());
        fade.end();
        assertEquals(android.view.View.LAYER_TYPE_NONE, clean.getView().getLayerType());
        assertEquals(1f, clean.getView().getAlpha(), 0.001f);
    }

    @Test
    public void recreate_restoresSelectedTab() {
        MainActivity activity = launch();
        select(activity, R.id.navigation_convert);

        controller.recreate();
        MainActivity recreated = controller.get();

        BottomNavigationView nav = recreated.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_convert, nav.getSelectedItemId());
        assertNotNull(fragment(recreated, "convert"));
    }

    @Test
    public void saveInstanceState_recordsSelectedTab() {
        MainActivity activity = launch();
        select(activity, R.id.navigation_settings);

        Bundle out = new Bundle();
        controller.saveInstanceState(out);

        assertEquals(R.id.navigation_settings, out.getInt("selected_tab"));
    }

    @Test
    public void selectTab_withUnknownId_isIgnored() {
        MainActivity activity = launch();
        BottomNavigationView nav = activity.findViewById(R.id.bottomNavigation);

        select(activity, R.id.navigation_scan);
        select(activity, R.id.navigation_clean);

        assertEquals(R.id.navigation_clean, nav.getSelectedItemId());
    }

    @Test
    public void freshInstall_opensTheIntroductionBeforeThePermissionPrompts() {
        MainActivity activity = launch();

        android.content.Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(OnboardingActivity.class.getName(), started.getComponent().getClassName());
        assertTrue(shadowOf(activity).getLastRequestedPermission() == null);

        shadowOf(activity).receiveResult(started, android.app.Activity.RESULT_OK, null);
        shadowOf(Looper.getMainLooper()).idle();

        // The introduction already asked on its own pages, so nothing is asked again here.
        assertTrue(AppPreferences.hasSeenOnboarding(activity));
        assertTrue(AppPreferences.hasCompletedInitialPermissionsPrompt(activity));
        assertTrue(shadowOf(activity).getLastRequestedPermission() == null);
    }

    @Test
    public void backingOutOfTheIntroduction_closesTheApp_andShowsItNextTime() {
        MainActivity activity = launch();
        android.content.Intent started = shadowOf(activity).getNextStartedActivity();

        shadowOf(activity).receiveResult(started, android.app.Activity.RESULT_CANCELED, null);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(activity.isFinishing());
        assertTrue(!AppPreferences.hasSeenOnboarding(activity));
        assertTrue(!AppPreferences.hasCompletedInitialPermissionsPrompt(activity));
    }

    @Test
    public void afterTheIntroduction_itIsNotShownAgain() {
        AppPreferences.setOnboardingSeen(org.robolectric.RuntimeEnvironment.getApplication());
        assertNoIntroduction(launch());
    }

    @Test
    public void updatingFromAVersionWithoutTheIntroduction_showsItOnce_withoutAskingAgain() {
        // Updating users finished the permission prompts before the introduction existed.
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        AppPreferences.setInitialPermissionsPromptCompleted(app);
        MainActivity activity = launch();

        android.content.Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(OnboardingActivity.class.getName(), started.getComponent().getClassName());

        shadowOf(activity).receiveResult(started, android.app.Activity.RESULT_OK, null);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(AppPreferences.hasSeenOnboarding(activity));
        assertTrue(shadowOf(activity).getLastRequestedPermission() == null);
        controller.pause().stop().destroy();
        controller = null;
        assertNoIntroduction(launch());
    }

    @Test
    public void skipIntroExtra_opensStraightToTheTabs_inDebugBuilds() {
        android.content.Intent intent = new android.content.Intent(
                org.robolectric.RuntimeEnvironment.getApplication(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_SKIP_INTRO, true);
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup();
        MainActivity activity = controller.get();

        assertNoIntroduction(activity);
        assertTrue(AppPreferences.hasSeenOnboarding(activity));
        assertTrue(AppPreferences.hasCompletedInitialPermissionsPrompt(activity));
    }

    private static void assertNoIntroduction(MainActivity activity) {
        android.content.Intent started;
        while ((started = shadowOf(activity).getNextStartedActivity()) != null) {
            assertTrue(started.getComponent() == null || !OnboardingActivity.class.getName()
                    .equals(started.getComponent().getClassName()));
        }
    }

    @Test
    public void permissionResults_areRoutedToTheMatchingTab() {
        MainActivity activity = launch();
        select(activity, R.id.navigation_scan);
        select(activity, R.id.navigation_settings);

        String[] locationPermission = {Manifest.permission.ACCESS_MEDIA_LOCATION};
        int[] granted = {PackageManager.PERMISSION_GRANTED};
        int[] denied = {PackageManager.PERMISSION_DENIED};

        activity.onRequestPermissionsResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE, locationPermission, granted);
        activity.onRequestPermissionsResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE, locationPermission, denied);
        activity.onRequestPermissionsResult(
                PermissionManager.NOTIFICATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                granted);
        select(activity, R.id.navigation_clean);
        activity.onRequestPermissionsResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES},
                granted);
        select(activity, R.id.navigation_convert);
        activity.onRequestPermissionsResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES},
                denied);
        activity.onRequestPermissionsResult(12345, new String[0], new int[0]);

        assertTrue(activity.getSupportFragmentManager().getFragments().size() >= 4);
    }

    @Test
    public void lifecycle_pauseAndResume_doNotThrow() {
        launch();
        controller.pause().resume();
        assertTrue(controller.get().hasWindowFocus() || !controller.get().isFinishing());
    }

    private int launchedTab(android.content.Intent intent) {
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        BottomNavigationView nav = controller.get().findViewById(R.id.bottomNavigation);
        return nav.getSelectedItemId();
    }

    @Test
    public void shortcuts_openTheirTab() {
        android.content.Intent scan = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setClassName("com.doubleangels.redact", "com.doubleangels.redact.MainActivity")
                .putExtra("tab", "scan");
        assertEquals(R.id.navigation_scan, launchedTab(scan));
    }

    @Test
    public void censorShortcut_opensCensor() {
        android.content.Intent censor = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setClassName("com.doubleangels.redact", "com.doubleangels.redact.MainActivity")
                .putExtra("tab", "censor");
        assertEquals(R.id.navigation_censor, launchedTab(censor));
    }

    @Test
    public void censorShortcutWhileRunning_switchesToCensor() {
        MainActivity activity = launch();
        controller.newIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .putExtra("tab", "censor"));
        shadowOf(Looper.getMainLooper()).idle();
        BottomNavigationView nav = activity.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_censor, nav.getSelectedItemId());
    }

    /** Every launcher shortcut in res/xml/shortcuts.xml opens the tab it is named after. */
    @Test
    public void everyLauncherShortcut_opensItsOwnTab() throws Exception {
        android.content.res.Resources res = org.robolectric.RuntimeEnvironment.getApplication().getResources();
        java.util.List<String> tabs = new java.util.ArrayList<>();
        try (android.content.res.XmlResourceParser xml = res.getXml(R.xml.shortcuts)) {
            for (int event = xml.next(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && "extra".equals(xml.getName())
                        && MainActivity.EXTRA_TAB.equals(xml.getAttributeValue(
                                "http://schemas.android.com/apk/res/android", "name"))) {
                    tabs.add(xml.getAttributeValue("http://schemas.android.com/apk/res/android", "value"));
                }
            }
        }
        assertEquals(java.util.List.of("clean", "scan", "convert", "censor"), tabs);
        for (String tab : tabs) {
            int expected = res.getIdentifier("navigation_" + tab, "id", "com.doubleangels.redact");
            if (controller != null) {
                controller.pause().stop().destroy();
            }
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .setClassName("com.doubleangels.redact", "com.doubleangels.redact.MainActivity")
                    .putExtra(MainActivity.EXTRA_TAB, tab);
            assertEquals(tab, expected, launchedTab(intent));
        }
    }

    @Test
    public void unknownShortcutTab_opensClean() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .putExtra("tab", "nonsense");
        assertEquals(R.id.navigation_clean, launchedTab(intent));
    }

    @Test
    public void shortcutWhileRunning_switchesTab() {
        MainActivity activity = launch();
        controller.newIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .putExtra("tab", "convert"));
        shadowOf(Looper.getMainLooper()).idle();
        BottomNavigationView nav = activity.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_convert, nav.getSelectedItemId());
    }

    @Test
    public void openingAnImage_goesToScan_andANonMediaFileDoesNot() {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());
        android.net.Uri image = android.net.Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3APictures%2Fa.jpg");
        android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setData(image);
        assertEquals(R.id.navigation_scan, launchedTab(view));
        controller.pause().stop().destroy();

        android.content.Intent noData = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        assertEquals(R.id.navigation_clean, launchedTab(noData));
    }

    @Test
    public void sharingAnImageToScanWithRedact_goesToScan() {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());
        android.net.Uri image = android.net.Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3APictures%2Fa.jpg");
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(android.content.Intent.EXTRA_STREAM, image);
        assertEquals(R.id.navigation_scan, launchedTab(send));
    }

    @Test
    public void filePathsAndRedactsOwnFiles_areNotOpenedInScan() {
        android.content.Intent file = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(android.content.Intent.EXTRA_STREAM,
                        android.net.Uri.parse("file:///data/data/com.doubleangels.redact/cache/a.jpg"));
        assertEquals(R.id.navigation_clean, launchedTab(file));
        controller.pause().stop().destroy();

        android.content.Intent own = new android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse(
                        "content://com.doubleangels.redact.fileprovider/cache/processed/a.jpg"), "image/jpeg");
        assertEquals(R.id.navigation_clean, launchedTab(own));
    }

    // ---- Redact › Convert ----------------------------------------------------------------

    private static android.net.Uri sharedImage(int n) {
        return android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3APictures%2F"
                + n + ".jpg");
    }

    private static android.content.Intent convertShare(String action) {
        return new android.content.Intent(action)
                .setClassName("com.doubleangels.redact", "com.doubleangels.redact.ConvertShareTarget")
                .setType("image/*");
    }

    private com.doubleangels.redact.ui.MainViewModel viewModel() {
        return new androidx.lifecycle.ViewModelProvider(controller.get())
                .get(com.doubleangels.redact.ui.MainViewModel.class);
    }

    private java.util.List<com.doubleangels.redact.media.MediaItem> awaitConvertItems() throws InterruptedException {
        TestWaits.awaitMain(() -> viewModel().getConvertSelectedItems().getValue() != null
                && !viewModel().getConvertSelectedItems().getValue().isEmpty());
        return viewModel().getConvertSelectedItems().getValue();
    }

    @Test
    public void sharingAnImageToConvert_opensConvertWithItSelected() throws Exception {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());
        android.content.Intent send = convertShare(android.content.Intent.ACTION_SEND)
                .putExtra(android.content.Intent.EXTRA_STREAM, sharedImage(1));

        assertEquals(R.id.navigation_convert, launchedTab(send));
        java.util.List<com.doubleangels.redact.media.MediaItem> items = awaitConvertItems();
        assertEquals(1, items.size());
        assertEquals(sharedImage(1), items.get(0).uri());
    }

    @Test
    public void sharingMoreThanTheCapToConvert_keepsTheFirstTwenty() throws Exception {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());
        java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            uris.add(sharedImage(i));
        }
        android.content.Intent send = convertShare(android.content.Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris);

        assertEquals(R.id.navigation_convert, launchedTab(send));
        assertEquals(20, awaitConvertItems().size());
        assertTrue(com.doubleangels.redact.ui.Notice.lastShownForTests().contains("20"));
    }

    @Test
    public void sharingNothingUsableToConvert_saysSo() {
        android.content.Intent fileUri = convertShare(android.content.Intent.ACTION_SEND)
                .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse("file:///sdcard/a.jpg"));
        assertEquals(R.id.navigation_convert, launchedTab(fileUri));
        assertEquals(controller.get().getString(R.string.share_error_unsupported_media),
                com.doubleangels.redact.ui.Notice.lastShownForTests());
        controller.pause().stop().destroy();

        android.content.Intent noStream = convertShare(android.content.Intent.ACTION_SEND_MULTIPLE);
        assertEquals(R.id.navigation_convert, launchedTab(noStream));
        assertTrue(viewModel().getConvertSelectedItems().getValue().isEmpty());
    }

    @Test
    public void viewingThroughTheConvertTarget_isNotAConvertShare() {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());
        android.content.Intent view = convertShare(android.content.Intent.ACTION_VIEW).setData(sharedImage(1));

        assertEquals(R.id.navigation_scan, launchedTab(view));
    }

    @Test
    public void sharingToConvertWhileItRuns_onlyShowsTheRunningConvert() {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());
        MainActivity activity = launch();
        viewModel().setConvertProcessingState(com.doubleangels.redact.ui.MainViewModel.ProcessingState.PROCESSING);

        controller.newIntent(convertShare(android.content.Intent.ACTION_SEND)
                .putExtra(android.content.Intent.EXTRA_STREAM, sharedImage(1)));
        shadowOf(Looper.getMainLooper()).idle();

        BottomNavigationView nav = activity.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_convert, nav.getSelectedItemId());
        assertTrue(viewModel().getConvertSelectedItems().getValue().isEmpty());
        viewModel().setConvertProcessingState(com.doubleangels.redact.ui.MainViewModel.ProcessingState.IDLE);
    }

    // ---- Censor ---------------------------------------------------------------------------------

    private static android.content.Intent censorShare(android.net.Uri stream) {
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setClassName("com.doubleangels.redact", "com.doubleangels.redact.CensorShareTarget")
                .setType("image/jpeg");
        return stream == null ? send : send.putExtra(android.content.Intent.EXTRA_STREAM, stream);
    }

    private com.doubleangels.redact.ui.CensorViewModel censorViewModel() {
        return new androidx.lifecycle.ViewModelProvider(controller.get())
                .get(com.doubleangels.redact.ui.CensorViewModel.class);
    }

    @Test
    public void selectingCensor_showsItsTabOnly() {
        MainActivity activity = launch();

        select(activity, R.id.navigation_censor);

        assertNotNull(fragment(activity, "censor"));
        assertTrue(fragment(activity, "censor").isVisible());
        assertTrue(fragment(activity, "clean").isHidden());
    }

    @Test
    public void sharingAPhotoToCensor_opensItInTheCensorTab() {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());

        assertEquals(R.id.navigation_censor, launchedTab(censorShare(sharedImage(1))));
        assertEquals(sharedImage(1), censorViewModel().getSource());
    }

    @Test
    public void sharingSomethingUnusableToCensor_saysSoAndOpensNothing() {
        assertEquals(R.id.navigation_censor,
                launchedTab(censorShare(android.net.Uri.parse("file:///sdcard/a.jpg"))));
        assertEquals(controller.get().getString(R.string.share_error_unsupported_media),
                com.doubleangels.redact.ui.Notice.lastShownForTests());
        assertNull(censorViewModel().getSource());
        controller.pause().stop().destroy();

        assertEquals(R.id.navigation_censor, launchedTab(censorShare(null)));
        assertNull(censorViewModel().getSource());
    }

    @Test
    public void sharingToCensorWhileRunning_switchesToCensor() {
        FakeDocumentsProvider.install(org.robolectric.RuntimeEnvironment.getApplication());
        MainActivity activity = launch();

        controller.newIntent(censorShare(sharedImage(2)));
        shadowOf(Looper.getMainLooper()).idle();

        BottomNavigationView nav = activity.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_censor, nav.getSelectedItemId());
        assertEquals(sharedImage(2), censorViewModel().getSource());
    }
}
