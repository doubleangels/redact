package com.doubleangels.redact.permission;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class PermissionManagerInstanceTest {

    private Activity activity;
    private final List<String> events = new ArrayList<>();
    private PermissionManager manager;

    private final PermissionManager.PermissionCallback callback = new PermissionManager.PermissionCallback() {
        @Override
        public void onPermissionsGranted() {
            events.add("granted");
        }

        @Override
        public void onPermissionsDenied() {
            events.add("denied");
        }

        @Override
        public void onPermissionsRequestStarted() {
            events.add("started");
        }

        @Override
        public void onLocationPermissionGranted() {
            events.add("location-granted");
        }

        @Override
        public void onLocationPermissionDenied() {
            events.add("location-denied");
        }
    };

    @Before
    public void setUp() {
        PermissionManager.resetRuntimePermissionRequestStateForTests();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        AppPreferences.setInitialPermissionsPromptCompleted(activity);
        manager = new PermissionManager(activity, callback);
    }

    @After
    public void tearDown() {
        PermissionManager.resetRuntimePermissionRequestStateForTests();
    }

    private void clearPrompted() {
        activity.getApplication()
                .getSharedPreferences(AppPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().remove("initial_permissions_prompted").commit();
    }

    private ShadowApplication app() {
        return Shadows.shadowOf(activity.getApplication());
    }

    private void grant(String... permissions) {
        app().grantPermissions(permissions);
    }

    private void deny(String... permissions) {
        app().denyPermissions(permissions);
    }

    private String[] lastRequested() {
        org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                Shadows.shadowOf(activity).getLastRequestedPermission();
        return request == null ? null : request.requestedPermissions;
    }

    private void grantFullMedia34() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
    }

    // ---- needsPermissions / needsLocationPermission ---------------------------------------------

    @Test
    @Config(sdk = 34)
    public void needsPermissions_sdk34_acceptsFullOrPartialAccess() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        assertTrue(manager.needsPermissions());

        grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        assertFalse("partial access is enough", manager.needsPermissions());

        deny(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        assertFalse("full access is enough", manager.needsPermissions());

        deny(Manifest.permission.READ_MEDIA_VIDEO);
        assertTrue("images alone are not", manager.needsPermissions());
    }

    @Test
    @Config(sdk = 33)
    public void needsPermissions_sdk33_needsBothMediaPermissions() {
        grant(Manifest.permission.READ_MEDIA_IMAGES);
        deny(Manifest.permission.READ_MEDIA_VIDEO);
        assertTrue(manager.needsPermissions());
        grant(Manifest.permission.READ_MEDIA_VIDEO);
        assertFalse(manager.needsPermissions());
    }

    @Test
    @Config(sdk = 31)
    public void needsPermissions_legacy_needsExternalStorage() {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertTrue(manager.needsPermissions());
        grant(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertFalse(manager.needsPermissions());
    }

    @Test
    @Config(sdk = 33)
    public void pickerNeedsNoStoragePermissionFromAndroid13() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        assertTrue(PermissionManager.canUseSystemPhotoPickerWithoutMediaRead());
        assertFalse(manager.shouldRequestStorageBeforePicker());
        assertTrue(manager.isMediaPickerAvailable());
    }

    @Test
    @Config(sdk = 31)
    public void pickerNeedsStorageBeforeAndroid13() {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertFalse(PermissionManager.canUseSystemPhotoPickerWithoutMediaRead());
        assertTrue(manager.shouldRequestStorageBeforePicker());
        assertFalse(manager.isMediaPickerAvailable());

        grant(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertFalse(manager.shouldRequestStorageBeforePicker());
        assertTrue(manager.isMediaPickerAvailable());
    }

    @Test
    @Config(sdk = 34)
    public void needsLocationPermission_followsTheGrant() {
        deny(Manifest.permission.ACCESS_MEDIA_LOCATION);
        assertTrue(manager.needsLocationPermission());
        grant(Manifest.permission.ACCESS_MEDIA_LOCATION);
        assertFalse(manager.needsLocationPermission());
    }

    @Test
    @Config(sdk = 34)
    public void requestCodes_areExposed() {
        assertEquals(PermissionManager.STORAGE_PERMISSION_REQUEST_CODE, manager.getPermissionRequestCode());
        assertEquals(PermissionManager.LOCATION_PERMISSION_REQUEST_CODE, manager.getLocationPermissionRequestCode());
    }

    // ---- checkPermissions ------------------------------------------------------------------------

    @Test
    @Config(sdk = 34)
    public void checkPermissions_isDeferredDuringTheFirstLaunchFlow() {
        PermissionManager.prepareInitialPermissionFlow();
        manager.checkPermissions();
        assertTrue(events.isEmpty());
    }

    @Test
    @Config(sdk = 34)
    public void checkPermissions_allGranted_notifiesGrantedAndLocation() {
        grantFullMedia34();
        grant(Manifest.permission.ACCESS_MEDIA_LOCATION);

        manager.checkPermissions();

        assertEquals(Arrays.asList("granted", "location-granted"), events);
    }

    @Test
    @Config(sdk = 34)
    public void checkPermissions_allGrantedButNoLocation_onlyNotifiesGranted() {
        grantFullMedia34();
        deny(Manifest.permission.ACCESS_MEDIA_LOCATION);

        manager.checkPermissions();

        assertEquals(Arrays.asList("granted"), events);
    }

    @Test
    @Config(sdk = 34)
    public void checkPermissions_missing_startsARequestOnce() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        manager.checkPermissions();
        assertEquals(Arrays.asList("started"), events);
        assertNotNull(lastRequested());

        // A second check while the dialog is up only reports "started"; it must not stack dialogs.
        manager.checkPermissions();
        assertEquals(Arrays.asList("started", "started"), events);
    }

    // ---- request methods -------------------------------------------------------------------------

    @Test
    @Config(sdk = 34)
    public void requestStoragePermission_asksForTheMissingMediaPermissions() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        manager.requestStoragePermission();

        assertTrue(Arrays.asList(lastRequested()).contains(Manifest.permission.READ_MEDIA_IMAGES));
    }

    @Test
    @Config(sdk = 34)
    public void requestLocationPermission_withMediaAccess_asksForLocationDirectly() {
        grantFullMedia34();
        deny(Manifest.permission.ACCESS_MEDIA_LOCATION);

        manager.requestLocationPermission();

        assertEquals(Arrays.asList(Manifest.permission.ACCESS_MEDIA_LOCATION), Arrays.asList(lastRequested()));
    }

    @Test
    @Config(sdk = 34)
    public void requestLocationPermission_alreadyGranted_doesNothing() {
        grant(Manifest.permission.ACCESS_MEDIA_LOCATION);
        manager.requestLocationPermission();
        assertNull(lastRequested());
    }

    @Test
    @Config(sdk = 34)
    public void requestLocationPermission_withoutMediaAccess_asksForMediaFirstThenLocation() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION);

        manager.requestLocationPermission();
        assertTrue(Arrays.asList(lastRequested()).contains(Manifest.permission.READ_MEDIA_IMAGES));

        // The user grants media access; the location prompt follows automatically.
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        manager.handlePermissionResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {PackageManager.PERMISSION_GRANTED});

        assertTrue(events.contains("granted"));
        assertEquals(Arrays.asList(Manifest.permission.ACCESS_MEDIA_LOCATION), Arrays.asList(lastRequested()));
    }

    // ---- handlePermissionResult ------------------------------------------------------------------

    @Test
    @Config(sdk = 34)
    public void storageResult_granted_notifiesGranted() {
        grantFullMedia34();
        grant(Manifest.permission.ACCESS_MEDIA_LOCATION);

        manager.handlePermissionResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO},
                new int[] {PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED});

        assertTrue(events.contains("granted"));
        assertTrue(events.contains("location-granted"));
    }

    @Test
    @Config(sdk = 34)
    public void storageResult_denied_notifiesDenied() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        manager.requestStoragePermission();

        manager.handlePermissionResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {PackageManager.PERMISSION_DENIED});

        assertTrue(events.contains("denied"));
        assertFalse(events.contains("granted"));
    }

    @Test
    @Config(sdk = 31)
    public void storageResult_denied_legacyPath() {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE);
        manager.handlePermissionResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_EXTERNAL_STORAGE},
                new int[] {PackageManager.PERMISSION_DENIED});
        assertTrue(events.contains("denied"));
    }

    @Test
    @Config(sdk = 34)
    public void storageResult_withLocationRequestedButDenied_notifiesLocationDenied() {
        grantFullMedia34();
        deny(Manifest.permission.ACCESS_MEDIA_LOCATION);

        manager.handlePermissionResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[] {PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED});

        assertTrue(events.contains("location-denied"));
    }

    @Test
    @Config(sdk = 34)
    public void locationResult_grantedAndDenied() {
        manager.handlePermissionResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[] {PackageManager.PERMISSION_GRANTED});
        assertEquals(Arrays.asList("location-granted"), events);

        events.clear();
        manager.requestLocationPermission();
        manager.handlePermissionResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[] {PackageManager.PERMISSION_DENIED});
        assertEquals(Arrays.asList("location-denied"), events);
    }

    @Test
    @Config(sdk = 34)
    public void unknownRequestCode_isIgnored() {
        manager.handlePermissionResult(999, new String[0], new int[0]);
        assertTrue(events.isEmpty());
    }

    @Test
    @Config(sdk = 34)
    public void resultWithMismatchedArrays_isHandledWithoutCrashing() {
        manager.handlePermissionResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[0]);
        assertEquals(Arrays.asList("location-denied"), events);
    }

    // ---- pending results -------------------------------------------------------------------------

    @Test
    @Config(sdk = 34)
    public void pendingResult_isAppliedOnceToTheMatchingManager() {
        grantFullMedia34();
        PermissionManager.storeActivityPermissionResult(
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {PackageManager.PERMISSION_GRANTED});

        manager.applyPendingPermissionResultIfAny(PermissionManager.LOCATION_PERMISSION_REQUEST_CODE);
        assertTrue("other request codes leave it queued", events.isEmpty());

        manager.applyPendingPermissionResultIfAny(PermissionManager.STORAGE_PERMISSION_REQUEST_CODE);
        assertTrue(events.contains("granted"));

        events.clear();
        manager.applyPendingPermissionResultIfAny(PermissionManager.STORAGE_PERMISSION_REQUEST_CODE);
        assertTrue("consumed results are not replayed", events.isEmpty());
        manager.applyPendingPermissionResultIfAny();
        manager.applyPendingPermissionResultIfAny((int[]) null);
    }

    // ---- first-launch flow + resume helper ----------------------------------------------------------

    @Test
    @Config(sdk = 34)
    public void initialFlow_walksMediaThenLocationThenNotifications() {
        clearPrompted();
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS);
        final boolean[] completed = {false};
        PermissionManager.setInitialFlowCompletedCallback(() -> completed[0] = true);

        PermissionManager.requestAllInitialPermissions(activity);
        assertTrue(PermissionManager.isInitialPermissionFlowInProgress());
        assertTrue(Arrays.asList(lastRequested()).contains(Manifest.permission.READ_MEDIA_IMAGES));

        grantFullMedia34();
        assertTrue(PermissionManager.handleInitialPermissionFlowResult(activity,
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {PackageManager.PERMISSION_GRANTED}));
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(Manifest.permission.ACCESS_MEDIA_LOCATION), Arrays.asList(lastRequested()));

        grant(Manifest.permission.ACCESS_MEDIA_LOCATION);
        PermissionManager.handleInitialPermissionFlowResult(activity,
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[] {PackageManager.PERMISSION_GRANTED});
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(Manifest.permission.POST_NOTIFICATIONS), Arrays.asList(lastRequested()));

        grant(Manifest.permission.POST_NOTIFICATIONS);
        PermissionManager.handleInitialPermissionFlowResult(activity,
                PermissionManager.NOTIFICATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                new int[] {PackageManager.PERMISSION_GRANTED});
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertFalse(PermissionManager.isInitialPermissionFlowInProgress());
        assertTrue(completed[0]);
        assertTrue(AppPreferences.hasCompletedInitialPermissionsPrompt(activity));
    }

    @Test
    @Config(sdk = 31)
    public void initialFlow_legacyDevicesSkipTheNotificationStep() {
        clearPrompted();
        grant(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION);

        PermissionManager.requestAllInitialPermissions(activity);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertFalse(PermissionManager.isInitialPermissionFlowInProgress());
        assertTrue(AppPreferences.hasCompletedInitialPermissionsPrompt(activity));
    }

    @Test
    @Config(sdk = 34)
    public void initialFlow_skipsLocationWithoutMediaAccess() {
        clearPrompted();
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION);
        grant(Manifest.permission.POST_NOTIFICATIONS);

        PermissionManager.requestAllInitialPermissions(activity);
        PermissionManager.handleInitialPermissionFlowResult(activity,
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {PackageManager.PERMISSION_DENIED});
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertFalse(PermissionManager.isInitialPermissionFlowInProgress());
    }

    @Test
    @Config(sdk = 34)
    public void requestAllInitialPermissions_doesNothingOnceCompleted() {
        PermissionManager.requestAllInitialPermissions(activity);
        assertFalse(PermissionManager.isInitialPermissionFlowInProgress());
    }

    @Test
    @Config(sdk = 34)
    public void startInitialPermissionFlow_isANoOpWhenNotInProgress() {
        PermissionManager.startInitialPermissionFlow(activity);
        assertNull(lastRequested());
    }

    @Test
    @Config(sdk = 34)
    public void requestInitialPermissionsIfNeeded_branches() {
        // In flight: nothing happens.
        PermissionManager.prepareInitialPermissionFlow();
        PermissionManager.requestInitialPermissionsIfNeeded(activity);
        assertNull(lastRequested());
        PermissionManager.resetRuntimePermissionRequestStateForTests();

        // Not yet prompted: starts the first-launch flow.
        clearPrompted();
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        PermissionManager.requestInitialPermissionsIfNeeded(activity);
        assertNotNull(lastRequested());
        PermissionManager.resetRuntimePermissionRequestStateForTests();

        // Already prompted, but something is missing: asks again.
        AppPreferences.setInitialPermissionsPromptCompleted(activity);
        PermissionManager.requestInitialPermissionsIfNeeded(activity);
        assertNotNull(lastRequested());

        // Destroying the host clears the in-flight flag so the next check can ask again.
        PermissionManager.clearRuntimePermissionRequestOnDestroy();
        PermissionManager.requestInitialPermissionsIfNeeded(activity);
    }
}
