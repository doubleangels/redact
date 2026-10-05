package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.permission.PermissionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * How the Clean, Scan and Convert tabs react as storage and photo-location permissions are denied
 * and granted. Run on both sides of the Android 13 line (see the concrete subclasses), because the
 * system picker only works without storage access from API 33.
 */
@RunWith(RobolectricTestRunner.class)
public abstract class FragmentPermissionFlowsBase {

    protected Application app;
    private ActivityController<MainActivity> controller;
    protected MainActivity activity;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setInitialPermissionsPromptCompleted(app);
        denyAll();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        AppProcessingScope.resetForTests();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void denyAll() {
        shadowOf(app).denyPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
    }

    private void grantStorage() {
        shadowOf(app).grantPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
    }

    private void denyStorage() {
        shadowOf(app).denyPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
    }

    private View tab(String tag, int id) {
        activity.selectTab(id);
        idle();
        Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag(tag);
        assertNotNull(fragment);
        return fragment.getView();
    }

    private void storageResult(boolean granted) {
        activity.onRequestPermissionsResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED,
                        granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED});
        idle();
    }

    private boolean pickerNeedsStorage() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
    }

    @Test
    public void withoutStorageAccess_eachTabReflectsWhetherThePickerIsUsable() {
        View clean = tab("clean", R.id.navigation_clean);
        View scan = tab("scan", R.id.navigation_scan);
        View convert = tab("convert", R.id.navigation_convert);
        tab("clean", R.id.navigation_clean);

        boolean pickerUsable = !pickerNeedsStorage();
        assertEquals(pickerUsable, clean.findViewById(R.id.selectButton).isEnabled());
        assertEquals(pickerUsable, scan.findViewById(R.id.selectButton).isEnabled());
        assertEquals(pickerUsable, convert.findViewById(R.id.selectButton).isEnabled());
        if (!pickerUsable) {
            assertEquals(activity.getString(R.string.status_storage_permissions_required),
                    ((TextView) convert.findViewById(R.id.statusText)).getText().toString());
        }
    }

    @Test
    public void grantingStorageAccess_enablesEveryTab() {
        View clean = tab("clean", R.id.navigation_clean);
        View scan = tab("scan", R.id.navigation_scan);
        View convert = tab("convert", R.id.navigation_convert);

        grantStorage();
        for (int id : new int[] {R.id.navigation_clean, R.id.navigation_scan, R.id.navigation_convert}) {
            activity.selectTab(id);
            idle();
            storageResult(true);
        }

        assertTrue(clean.findViewById(R.id.selectButton).isEnabled());
        assertTrue(scan.findViewById(R.id.selectButton).isEnabled());
        assertTrue(convert.findViewById(R.id.selectButton).isEnabled());
        assertEquals(activity.getString(R.string.convert_status_ready),
                ((TextView) convert.findViewById(R.id.statusText)).getText().toString());
    }

    @Test
    public void denyingStorageAccess_isHandledOnEveryTab() {
        View clean = tab("clean", R.id.navigation_clean);
        View scan = tab("scan", R.id.navigation_scan);
        View convert = tab("convert", R.id.navigation_convert);

        denyStorage();
        for (int id : new int[] {R.id.navigation_clean, R.id.navigation_scan, R.id.navigation_convert}) {
            activity.selectTab(id);
            idle();
            storageResult(false);
        }

        boolean pickerUsable = !pickerNeedsStorage();
        assertEquals(pickerUsable, clean.findViewById(R.id.selectButton).isEnabled());
        assertEquals(pickerUsable, scan.findViewById(R.id.selectButton).isEnabled());
        assertEquals(pickerUsable, convert.findViewById(R.id.selectButton).isEnabled());
    }

    @Test
    public void photoLocationResults_reachTheScanTab() {
        tab("scan", R.id.navigation_scan);

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        activity.onRequestPermissionsResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[] {PackageManager.PERMISSION_GRANTED});
        idle();

        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        activity.onRequestPermissionsResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[] {PackageManager.PERMISSION_DENIED});
        idle();

        assertNotNull(activity.getSupportFragmentManager().findFragmentByTag("scan"));
    }

    @Test
    public void resumingATab_rechecksPermissions() {
        for (int id : new int[] {R.id.navigation_scan, R.id.navigation_convert, R.id.navigation_clean}) {
            activity.selectTab(id);
            idle();
            controller.pause().resume();
            idle();
        }
        assertFalse(activity.isFinishing());
    }

    @Test
    public void thePermissionFlowCompletingNotifiesTheVisibleTab() {
        tab("scan", R.id.navigation_scan);
        // Re-running the first-launch flow to completion notifies whichever tab is on screen.
        PermissionManager.prepareInitialPermissionFlow();
        grantStorage();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
        PermissionManager.startInitialPermissionFlow(activity);
        idle();

        assertFalse(PermissionManager.isInitialPermissionFlowInProgress());

        for (int id : new int[] {R.id.navigation_convert, R.id.navigation_clean}) {
            tab(id == R.id.navigation_convert ? "convert" : "clean", id);
            PermissionManager.prepareInitialPermissionFlow();
            PermissionManager.startInitialPermissionFlow(activity);
            idle();
        }
    }
}
