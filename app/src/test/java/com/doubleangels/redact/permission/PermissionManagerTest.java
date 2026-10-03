package com.doubleangels.redact.permission;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
public class PermissionManagerTest {

    private Activity activity;

    @Before
    public void setUp() {
        PermissionManager.resetRuntimePermissionRequestStateForTests();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @After
    public void tearDown() {
        PermissionManager.resetRuntimePermissionRequestStateForTests();
    }

    private void grant(String... permissions) {
        Shadows.shadowOf(activity.getApplication()).grantPermissions(permissions);
    }

    private void deny(String... permissions) {
        Shadows.shadowOf(activity.getApplication()).denyPermissions(permissions);
    }

    @Test
    @Config(sdk = 34)
    public void collectMissing_sdk34_includesAllVisualMediaPermissions() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        assertEquals(Arrays.asList(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                PermissionManager.collectMissingRuntimePermissions(activity));
    }

    @Test
    @Config(sdk = 34)
    public void collectMissing_sdk34_omitsGrantedPermissions() {
        deny(Manifest.permission.READ_MEDIA_VIDEO);
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        assertEquals(Collections.singletonList(Manifest.permission.READ_MEDIA_VIDEO),
                PermissionManager.collectMissingRuntimePermissions(activity));
    }

    @Test
    @Config(sdk = 33)
    public void collectMissing_sdk33_requestsImagesAndVideoOnly() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);

        assertEquals(Arrays.asList(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO),
                PermissionManager.collectMissingRuntimePermissions(activity));
    }

    @Test
    @Config(sdk = 31)
    public void collectMissing_legacy_requestsExternalStorage() {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE);

        assertEquals(Collections.singletonList(Manifest.permission.READ_EXTERNAL_STORAGE),
                PermissionManager.collectMissingRuntimePermissions(activity));
    }

    @Test
    @Config(sdk = 31)
    public void collectMissing_legacy_emptyWhenGranted() {
        grant(Manifest.permission.READ_EXTERNAL_STORAGE);

        assertTrue(PermissionManager.collectMissingRuntimePermissions(activity).isEmpty());
    }

    @Test
    @Config(sdk = 34)
    public void prepareInitialPermissionFlow_marksFlowInProgressAndDefersChecks() {
        AppPreferences.setInitialPermissionsPromptCompleted(activity);
        assertFalse(PermissionManager.isInitialPermissionFlowInProgress());
        assertFalse(PermissionManager.shouldDeferPermissionChecks(activity));

        PermissionManager.prepareInitialPermissionFlow();

        assertTrue(PermissionManager.isInitialPermissionFlowInProgress());
        assertTrue(PermissionManager.shouldDeferPermissionChecks(activity));
    }

    @Test
    @Config(sdk = 34)
    public void shouldDeferPermissionChecks_trueUntilInitialPromptCompleted() {
        assertTrue(PermissionManager.shouldDeferPermissionChecks(activity));
        assertTrue(PermissionManager.shouldPromptInitialPermissions(activity));

        AppPreferences.setInitialPermissionsPromptCompleted(activity);
        assertFalse(PermissionManager.shouldDeferPermissionChecks(activity));
        assertFalse(PermissionManager.shouldPromptInitialPermissions(activity));
    }

    @Test
    @Config(sdk = 34)
    public void handleInitialPermissionFlowResult_ignoredWhenFlowNotRunning() {
        assertFalse(PermissionManager.handleInitialPermissionFlowResult(activity,
                PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                new int[]{PackageManager.PERMISSION_GRANTED}));
    }

    @Test
    @Config(sdk = 34)
    public void handleInitialPermissionFlowResult_ignoresUnrelatedRequestCode() {
        PermissionManager.prepareInitialPermissionFlow();
        assertFalse(PermissionManager.handleInitialPermissionFlowResult(activity, 0xBEEF,
                new String[0], new int[0]));
        assertTrue(PermissionManager.isInitialPermissionFlowInProgress());
    }

    @Test
    @Config(sdk = 34)
    public void initialFlow_completesImmediatelyWhenEverythingGranted() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
        AtomicBoolean completed = new AtomicBoolean(false);
        PermissionManager.setInitialFlowCompletedCallback(() -> completed.set(true));

        PermissionManager.prepareInitialPermissionFlow();
        PermissionManager.startInitialPermissionFlow(activity);
        ShadowLooper.idleMainLooper();

        assertFalse(PermissionManager.isInitialPermissionFlowInProgress());
        assertTrue(AppPreferences.hasCompletedInitialPermissionsPrompt(activity));
        assertTrue(completed.get());
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
    }

    @Test
    @Config(sdk = 34)
    public void initialFlow_requestsMissingMediaPermissionsFirst() {
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        PermissionManager.prepareInitialPermissionFlow();
        PermissionManager.startInitialPermissionFlow(activity);

        List<String> requested = Arrays.asList(
                Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
        assertTrue(requested.contains(Manifest.permission.READ_MEDIA_IMAGES));
        assertTrue(PermissionManager.isInitialPermissionFlowInProgress());
    }

    @Test
    @Config(sdk = 34)
    public void requestMissingRuntimePermissions_includesMediaLocationWhenAsked() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        deny(Manifest.permission.ACCESS_MEDIA_LOCATION);

        PermissionManager.requestMissingRuntimePermissions(activity, true);

        assertEquals(Collections.singletonList(Manifest.permission.ACCESS_MEDIA_LOCATION),
                Arrays.asList(Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions));
    }

    @Test
    @Config(sdk = 34)
    public void requestMissingRuntimePermissions_noRequestWhenNothingMissing() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        PermissionManager.requestMissingRuntimePermissions(activity);

        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
    }
}
