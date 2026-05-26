package com.doubleangels.redact.permission;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;

import androidx.core.app.NotificationManagerCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PermissionStatusHelperTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void getMediaAccessStatus_deniedUntilBothGranted() {
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(context));

        shadowOf((Application) context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(context));

        shadowOf((Application) context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getMediaAccessStatus(context));
    }

    @Test
    public void getLocationStatus() {
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getLocationStatus(context));
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getLocationStatus(context));
    }

    @Test
    public void getNotificationsStatus_requiresPermissionOnApi34() {
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getNotificationsStatus(context));

        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getNotificationsStatus(context));
    }

    @Test
    @Config(sdk = 33, manifest = Config.NONE)
    public void getMediaAccessStatus_api33_requiresBothGranularPermissions() {
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(context));

        shadowOf((Application) context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(context));

        shadowOf((Application) context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getMediaAccessStatus(context));
    }

    @Test
    @Config(sdk = 32, manifest = Config.NONE)
    public void getMediaAccessStatus_api32_usesReadExternalStorage() {
        assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getMediaAccessStatus(context));

        shadowOf((Application) context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getMediaAccessStatus(context));
    }

    @Test
    @Config(sdk = 32, manifest = Config.NONE)
    public void getNotificationsStatus_api32_reflectsNotificationManagerState() {
        NotificationManagerCompat notificationManager = mock(NotificationManagerCompat.class);
        when(notificationManager.areNotificationsEnabled()).thenReturn(false, true);

        try (MockedStatic<NotificationManagerCompat> notificationManagerStatic =
                mockStatic(NotificationManagerCompat.class)) {
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context))
                    .thenReturn(notificationManager);
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context.getApplicationContext()))
                    .thenReturn(notificationManager);

            assertEquals(PermissionStatusHelper.Status.DENIED, PermissionStatusHelper.getNotificationsStatus(context));
            assertEquals(PermissionStatusHelper.Status.GRANTED, PermissionStatusHelper.getNotificationsStatus(context));
        }
    }

    @Test
    public void getNotificationsStatus_preN_isNotRequired() {
        PermissionStatusHelper.testSdkIntOverride = 23;
        try {
            assertEquals(PermissionStatusHelper.Status.NOT_REQUIRED,
                    PermissionStatusHelper.getNotificationsStatus(context));
        } finally {
            PermissionStatusHelper.testSdkIntOverride = null;
        }
    }

}
