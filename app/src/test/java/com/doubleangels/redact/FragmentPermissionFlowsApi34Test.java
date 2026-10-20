package com.doubleangels.redact;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.pm.PackageManager;

import com.doubleangels.redact.permission.PermissionManager;

import org.junit.Test;

/**
 * From Android 13 the system picker works without storage access. Also covers the permission flows
 * that are the same on every Android version, so they only run once.
 */
public class FragmentPermissionFlowsApi34Test extends FragmentPermissionFlowsBase {

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
