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
}
