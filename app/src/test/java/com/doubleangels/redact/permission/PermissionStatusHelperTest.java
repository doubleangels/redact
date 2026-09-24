package com.doubleangels.redact.permission;

import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PermissionStatusHelperTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        PermissionStatusHelper.testSdkIntOverride = null;
    }

    private void grant(String... permissions) {
        Shadows.shadowOf(app).grantPermissions(permissions);
    }

    private void deny(String... permissions) {
        Shadows.shadowOf(app).denyPermissions(permissions);
    }

    @Test
    public void mediaAccessStatus_sdk34_bothGranted() {
        PermissionStatusHelper.testSdkIntOverride = 34;
        deny(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getMediaAccessStatus(app));
    }

    @Test
    public void mediaAccessStatus_sdk34_partialWhenUserSelected() {
        PermissionStatusHelper.testSdkIntOverride = 34;
        deny(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        assertEquals(PermissionStatusHelper.Status.PARTIAL, PermissionStatusHelper.getMediaAccessStatus(app));
    }

    @Test
    public void mediaAccessStatus_sdk34_deniedWithoutUserSelection() {
        PermissionStatusHelper.testSdkIntOverride = 34;
        grant(Manifest.permission.READ_MEDIA_IMAGES);
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(app));
    }

    @Test
    public void mediaAccessStatus_sdk33_requiresBoth() {
        PermissionStatusHelper.testSdkIntOverride = 33;
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getMediaAccessStatus(app));

        deny(Manifest.permission.READ_MEDIA_VIDEO);
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(app));
    }

    @Test
    public void mediaAccessStatus_legacySdk_usesExternalStorage() {
        PermissionStatusHelper.testSdkIntOverride = 32;
        grant(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getMediaAccessStatus(app));

        deny(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(app));
    }

    @Test
    public void locationRequiresMediaReadPrerequisite() {
        PermissionStatusHelper.testSdkIntOverride = 34;
        assertEquals(PermissionStatusHelper.Status.BLOCKED, PermissionStatusHelper.getLocationStatus(app));

        grant(Manifest.permission.READ_MEDIA_IMAGES);
        grant(Manifest.permission.ACCESS_MEDIA_LOCATION);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getLocationStatus(app));

        deny(Manifest.permission.ACCESS_MEDIA_LOCATION);
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getLocationStatus(app));
    }

    @Test
    public void hasMediaReadPrerequisite_trueWhenEitherMediaPermissionGranted() {
        PermissionStatusHelper.testSdkIntOverride = 34;
        assertEquals(false, PermissionStatusHelper.hasMediaReadPrerequisiteForLocation(app));

        grant(Manifest.permission.READ_MEDIA_IMAGES);
        assertEquals(true, PermissionStatusHelper.hasMediaReadPrerequisiteForLocation(app));

        deny(Manifest.permission.READ_MEDIA_IMAGES);
        grant(Manifest.permission.READ_MEDIA_VIDEO);
        assertEquals(true, PermissionStatusHelper.hasMediaReadPrerequisiteForLocation(app));
    }

    @Test
    public void notificationsStatus_grantedWhenEnabledAndPermitted() {
        PermissionStatusHelper.testSdkIntOverride = 34;
        ShadowNotificationManager shadowNnm = Shadows.shadowOf(
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE));
        shadowNnm.setNotificationsEnabled(true);
        grant(Manifest.permission.POST_NOTIFICATIONS);

        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getNotificationsStatus(app));
    }

    @Test
    public void notificationsStatus_deniedWhenNotificationsDisabled() {
        PermissionStatusHelper.testSdkIntOverride = 34;
        ShadowNotificationManager shadowNnm = Shadows.shadowOf(
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE));
        shadowNnm.setNotificationsEnabled(false);
        grant(Manifest.permission.POST_NOTIFICATIONS);

        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getNotificationsStatus(app));
    }

    @Test
    public void notificationsStatus_notRequiredBelowN() {
        PermissionStatusHelper.testSdkIntOverride = 23;
        assertEquals(PermissionStatusHelper.Status.NOT_REQUIRED, PermissionStatusHelper.getNotificationsStatus(app));
    }
}