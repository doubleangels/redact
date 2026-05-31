package com.doubleangels.redact.permission;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.snackbar.Snackbar;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class PermissionManagerTest {

    private TestActivity activity;
    private View rootView;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private PermissionManager.PermissionCallback callback;
    private Application application;

    @Before
    public void setup() {
        activity = Robolectric.buildActivity(TestActivity.class).setup().get();
        rootView = new FrameLayout(activity);
        activity.setContentView(rootView);
        settingsLauncher = mock(ActivityResultLauncher.class);
        callback = mock(PermissionManager.PermissionCallback.class);
        application = ApplicationProvider.getApplicationContext();
    }

    @Test
    @Config(sdk = 34)
    public void getRequestCodes_returnExpectedConstants() {
        PermissionManager manager = createManager();

        assertEquals(123, manager.getPermissionRequestCode());
        assertEquals(124, manager.getLocationPermissionRequestCode());
    }

    @Test
    public void permissionCallback_defaultLocationMethodsAreCallable() {
        PermissionManager.PermissionCallback defaultCallback =
                new PermissionManager.PermissionCallback() {
                    @Override
                    public void onPermissionsGranted() {
                    }

                    @Override
                    public void onPermissionsDenied() {
                    }

                    @Override
                    public void onPermissionsRequestStarted() {
                    }
                };

        defaultCallback.onLocationPermissionGranted();
        defaultCallback.onLocationPermissionDenied();
    }

    @Test
    @Config(sdk = 34)
    public void needsPermissions_api34Denied_returnsTrue() {
        assertTrue(createManager().needsPermissions());
    }

    @Test
    @Config(sdk = 34)
    public void needsPermissions_api34FullGrant_returnsFalse() {
        grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);

        assertFalse(createManager().needsPermissions());
    }

    @Test
    @Config(sdk = 34)
    public void needsPermissions_api34UserSelectedGrant_returnsFalse() {
        grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        assertFalse(createManager().needsPermissions());
    }

    @Test
    @Config(sdk = 33)
    public void needsPermissions_api33RequiresBothGranularPermissions() {
        PermissionManager manager = createManager();

        assertTrue(manager.needsPermissions());

        grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        assertTrue(manager.needsPermissions());

        grantPermissions(Manifest.permission.READ_MEDIA_VIDEO);
        assertFalse(manager.needsPermissions());
    }

    @Test
    @Config(sdk = 32)
    public void needsPermissions_preTiramisuUsesExternalStorage() {
        PermissionManager manager = createManager();

        assertTrue(manager.needsPermissions());

        grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertFalse(manager.needsPermissions());
    }

    @Test
    @Config(sdk = 34)
    public void needsPermissions_exceptionFallback_api34ChecksFullAccess() {
        try (MockedStatic<ContextCompat> contextCompat = mockStatic(ContextCompat.class);
             MockedStatic<SentryManager> sentry = mockStatic(SentryManager.class)) {
            contextCompat.when(() -> ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_IMAGES))
                    .thenThrow(new RuntimeException("boom"))
                    .thenReturn(PackageManager.PERMISSION_GRANTED);
            contextCompat.when(() -> ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_VIDEO))
                    .thenReturn(PackageManager.PERMISSION_GRANTED);
            contextCompat.when(() -> ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
                    .thenReturn(PackageManager.PERMISSION_DENIED);

            assertFalse(createManager().needsPermissions());
        }
    }

    @Test
    @Config(sdk = 33)
    public void needsPermissions_exceptionFallback_api33ChecksGranularPermissions() {
        try (MockedStatic<ContextCompat> contextCompat = mockStatic(ContextCompat.class);
             MockedStatic<SentryManager> sentry = mockStatic(SentryManager.class)) {
            contextCompat.when(() -> ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_IMAGES))
                    .thenThrow(new RuntimeException("boom"))
                    .thenReturn(PackageManager.PERMISSION_DENIED);
            contextCompat.when(() -> ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_VIDEO))
                    .thenReturn(PackageManager.PERMISSION_GRANTED);

            assertTrue(createManager().needsPermissions());
        }
    }

    @Test
    @Config(sdk = 32)
    public void needsPermissions_exceptionFallback_preTiramisuChecksStoragePermission() {
        try (MockedStatic<ContextCompat> contextCompat = mockStatic(ContextCompat.class);
             MockedStatic<SentryManager> sentry = mockStatic(SentryManager.class)) {
            contextCompat.when(() -> ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE))
                    .thenThrow(new RuntimeException("boom"))
                    .thenReturn(PackageManager.PERMISSION_DENIED);

            assertTrue(createManager().needsPermissions());
        }
    }

    @Test
    @Config(sdk = 34)
    public void needsLocationPermission_reflectsGrantState() {
        PermissionManager manager = createManager();

        assertTrue(manager.needsLocationPermission());

        grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        assertFalse(manager.needsLocationPermission());
    }

    @Test
    @Config(sdk = 34)
    public void needsLocationPermission_exceptionFallbackChecksPermissionAgain() {
        try (MockedStatic<ContextCompat> contextCompat = mockStatic(ContextCompat.class);
             MockedStatic<SentryManager> sentry = mockStatic(SentryManager.class)) {
            contextCompat.when(() -> ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.ACCESS_MEDIA_LOCATION))
                    .thenThrow(new RuntimeException("boom"))
                    .thenReturn(PackageManager.PERMISSION_DENIED);

            assertTrue(createManager().needsLocationPermission());
        }
    }

    @Test
    @Config(sdk = 34)
    public void checkPermissions_grantedPath_notifiesGranted() {
        grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        PermissionManager manager = createManager();

        manager.checkPermissions();

        verify(callback).onPermissionsGranted();
        verify(callback, never()).onPermissionsRequestStarted();
    }

    @Test
    @Config(sdk = 34)
    public void checkPermissions_needsRequest_startsPermissionFlow() {
        PermissionManager manager = spy(createManager());
        doNothing().when(manager).requestStoragePermission();

        manager.checkPermissions();

        verify(callback).onPermissionsRequestStarted();
        verify(manager).requestStoragePermission();
        verify(callback, never()).onPermissionsGranted();
    }

    @Test
    @Config(sdk = 34)
    public void checkPermissions_exceptionFallback_requestsPermissionsWhenStillNeeded() {
        PermissionManager manager = spy(createManager());
        doThrow(new RuntimeException("boom")).doReturn(true).when(manager).needsPermissions();
        doNothing().when(manager).requestStoragePermission();

        manager.checkPermissions();

        verify(callback).onPermissionsRequestStarted();
        verify(manager).requestStoragePermission();
    }

    @Test
    @Config(sdk = 34)
    public void checkPermissions_exceptionFallback_notifiesGrantedWhenPermissionsAvailable() {
        PermissionManager manager = spy(createManager());
        doThrow(new RuntimeException("boom")).doReturn(false).when(manager).needsPermissions();

        manager.checkPermissions();

        verify(callback).onPermissionsGranted();
        verify(callback, never()).onPermissionsRequestStarted();
    }

    @Test
    @Config(sdk = 34)
    public void requestStoragePermission_api34WithoutRationale_requestsAllMediaPermissions() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            stubApi34NoRationale(activityCompat);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            manager.requestStoragePermission();
        }

        assertArrayEquals(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }, requestedPermissions.get());
        assertEquals(manager.getPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 34)
    public void requestStoragePermission_api34WithRationale_showsSnackbarThenRequests() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);
        AtomicReference<View.OnClickListener> actionListener = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class);
             MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_IMAGES)).thenReturn(true);
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_VIDEO)).thenReturn(false);
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            Snackbar snackbar = mockSnackbar(snackbarStatic, actionListener);

            manager.requestStoragePermission();

            verify(snackbar).show();
            assertNotNull(actionListener.get());

            actionListener.get().onClick(rootView);
        }

        assertArrayEquals(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }, requestedPermissions.get());
        assertEquals(manager.getPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 33)
    public void requestStoragePermission_api33WithoutRationale_requestsImageAndVideo() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_IMAGES)).thenReturn(false);
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_VIDEO)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            manager.requestStoragePermission();
        }

        assertArrayEquals(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
        }, requestedPermissions.get());
        assertEquals(manager.getPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 33)
    public void requestStoragePermission_api33WithRationale_showsSnackbarThenRequests() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);
        AtomicReference<View.OnClickListener> actionListener = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class);
             MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_IMAGES)).thenReturn(false);
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_VIDEO)).thenReturn(true);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            Snackbar snackbar = mockSnackbar(snackbarStatic, actionListener);

            manager.requestStoragePermission();

            verify(snackbar).show();
            actionListener.get().onClick(rootView);
        }

        assertArrayEquals(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
        }, requestedPermissions.get());
        assertEquals(manager.getPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 32)
    public void requestStoragePermission_preTiramisuWithoutRationale_requestsExternalStorage() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            manager.requestStoragePermission();
        }

        assertArrayEquals(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, requestedPermissions.get());
        assertEquals(manager.getPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 32)
    public void requestStoragePermission_preTiramisuWithRationale_showsSnackbarThenRequests() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);
        AtomicReference<View.OnClickListener> actionListener = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class);
             MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE)).thenReturn(true);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            Snackbar snackbar = mockSnackbar(snackbarStatic, actionListener);

            manager.requestStoragePermission();

            verify(snackbar).show();
            actionListener.get().onClick(rootView);
        }

        assertArrayEquals(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, requestedPermissions.get());
        assertEquals(manager.getPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 34)
    public void requestStoragePermission_exceptionFallback_api34RequestsAllMediaPermissions() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_IMAGES)).thenThrow(new RuntimeException("boom"));
            capturePermissionRequest(activityCompat, requestedPermissions, new AtomicInteger(-1));

            manager.requestStoragePermission();
        }

        assertArrayEquals(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }, requestedPermissions.get());
    }

    @Test
    @Config(sdk = 33)
    public void requestStoragePermission_exceptionFallback_api33RequestsImageAndVideo() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_IMAGES)).thenThrow(new RuntimeException("boom"));
            capturePermissionRequest(activityCompat, requestedPermissions, new AtomicInteger(-1));

            manager.requestStoragePermission();
        }

        assertArrayEquals(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
        }, requestedPermissions.get());
    }

    @Test
    @Config(sdk = 32)
    public void requestStoragePermission_exceptionFallback_preTiramisuRequestsStoragePermission() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE)).thenThrow(new RuntimeException("boom"));
            capturePermissionRequest(activityCompat, requestedPermissions, new AtomicInteger(-1));

            manager.requestStoragePermission();
        }

        assertArrayEquals(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, requestedPermissions.get());
    }

    @Test
    @Config(sdk = 34)
    public void requestLocationPermission_withoutRationale_requestsAccessMediaLocation() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.ACCESS_MEDIA_LOCATION)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            manager.requestLocationPermission();
        }

        assertArrayEquals(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION}, requestedPermissions.get());
        assertEquals(createManager().getLocationPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 34)
    public void requestLocationPermission_withRationale_showsSnackbarThenRequests() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);
        AtomicReference<View.OnClickListener> actionListener = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class);
             MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.ACCESS_MEDIA_LOCATION)).thenReturn(true);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);

            Snackbar snackbar = mockSnackbar(snackbarStatic, actionListener);

            manager.requestLocationPermission();

            verify(snackbar).show();
            actionListener.get().onClick(rootView);
        }

        assertArrayEquals(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION}, requestedPermissions.get());
        assertEquals(createManager().getLocationPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 34)
    public void requestLocationPermission_exceptionFallback_requestsLocationPermission() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.ACCESS_MEDIA_LOCATION)).thenThrow(new RuntimeException("boom"));
            capturePermissionRequest(activityCompat, requestedPermissions, new AtomicInteger(-1));

            manager.requestLocationPermission();
        }

        assertArrayEquals(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION}, requestedPermissions.get());
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_storageGrantOnApi34PartialAccess_notifiesGranted() {
        PermissionManager manager = createManager();
        grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        manager.handlePermissionResult(manager.getPermissionRequestCode(),
                new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                },
                new int[]{
                        PackageManager.PERMISSION_DENIED,
                        PackageManager.PERMISSION_DENIED,
                        PackageManager.PERMISSION_GRANTED
                });

        verify(callback).onPermissionsGranted();
        verify(callback, never()).onPermissionsDenied();
    }

    @Test
    @Config(sdk = 33)
    public void handlePermissionResult_storageDeniedTemporary_showsRetryAndRequestsAgain() {
        PermissionManager manager = createManager();
        AtomicReference<View.OnClickListener> actionListener = new AtomicReference<>();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class);
             MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_IMAGES)).thenReturn(false);
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_VIDEO)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);
            mockSnackbar(snackbarStatic, actionListener);

            manager.handlePermissionResult(manager.getPermissionRequestCode(),
                    new String[]{
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO
                    },
                    new int[]{
                            PackageManager.PERMISSION_DENIED,
                            PackageManager.PERMISSION_DENIED
                    });

            verify(callback).onPermissionsDenied();
            assertNotNull(actionListener.get());

            actionListener.get().onClick(rootView);
        }

        assertArrayEquals(new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
        }, requestedPermissions.get());
        assertEquals(manager.getPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 32)
    public void handlePermissionResult_storageDeniedPermanent_gracefulDenial() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, new AtomicInteger(-1));

            manager.requestStoragePermission();
            manager.handlePermissionResult(manager.getPermissionRequestCode(),
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    new int[]{PackageManager.PERMISSION_DENIED});

            verify(callback).onPermissionsDenied();
        }
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_locationGranted_notifiesCallback() {
        PermissionManager manager = createManager();

        manager.handlePermissionResult(manager.getLocationPermissionRequestCode(),
                new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[]{PackageManager.PERMISSION_GRANTED});

        verify(callback).onLocationPermissionGranted();
        verify(callback, never()).onLocationPermissionDenied();
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_locationDeniedTemporary_showsRetryAndRequestsAgain() {
        PermissionManager manager = createManager();
        AtomicReference<View.OnClickListener> actionListener = new AtomicReference<>();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();
        AtomicInteger requestCode = new AtomicInteger(-1);

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class);
             MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.ACCESS_MEDIA_LOCATION)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, requestCode);
            mockSnackbar(snackbarStatic, actionListener);

            manager.handlePermissionResult(manager.getLocationPermissionRequestCode(),
                    new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                    new int[]{PackageManager.PERMISSION_DENIED});

            verify(callback).onLocationPermissionDenied();
            assertNotNull(actionListener.get());

            actionListener.get().onClick(rootView);
        }

        assertArrayEquals(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION}, requestedPermissions.get());
        assertEquals(manager.getLocationPermissionRequestCode(), requestCode.get());
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_locationDeniedPermanent_gracefulDenial() {
        PermissionManager manager = createManager();
        AtomicReference<String[]> requestedPermissions = new AtomicReference<>();

        try (MockedStatic<ActivityCompat> activityCompat = mockStatic(ActivityCompat.class)) {
            activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.ACCESS_MEDIA_LOCATION)).thenReturn(false);
            capturePermissionRequest(activityCompat, requestedPermissions, new AtomicInteger(-1));

            manager.requestLocationPermission();
            manager.handlePermissionResult(manager.getLocationPermissionRequestCode(),
                    new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                    new int[]{PackageManager.PERMISSION_DENIED});

            verify(callback).onLocationPermissionDenied();
        }
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_storageExceptionFallback_granted_notifiesGranted() {
        PermissionManager manager = createManager();
        grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        doThrow(new RuntimeException("boom")).doNothing().when(callback).onPermissionsGranted();

        manager.handlePermissionResult(manager.getPermissionRequestCode(),
                new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                },
                new int[]{
                        PackageManager.PERMISSION_GRANTED,
                        PackageManager.PERMISSION_GRANTED
                });

        verify(callback, times(2)).onPermissionsGranted();
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_storageExceptionFallback_denied_handlesDenialSafely() {
        PermissionManager manager = createManager();

        try (MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            snackbarStatic.when(() -> Snackbar.make(eq(rootView), any(CharSequence.class), eq(Snackbar.LENGTH_LONG)))
                    .thenThrow(new RuntimeException("boom"));

            manager.handlePermissionResult(manager.getPermissionRequestCode(),
                    new String[]{null},
                    new int[]{PackageManager.PERMISSION_DENIED});
        }

        verify(callback).onPermissionsDenied();
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_locationExceptionFallback_granted_notifiesGranted() {
        PermissionManager manager = createManager();
        doThrow(new RuntimeException("boom")).doNothing().when(callback).onLocationPermissionGranted();

        manager.handlePermissionResult(manager.getLocationPermissionRequestCode(),
                new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[]{PackageManager.PERMISSION_GRANTED});

        verify(callback, times(2)).onLocationPermissionGranted();
    }

    @Test
    @Config(sdk = 34)
    public void handlePermissionResult_locationExceptionFallback_denied_handlesDenialSafely() {
        PermissionManager manager = createManager();
        doThrow(new RuntimeException("boom")).doNothing().when(callback).onLocationPermissionDenied();

        try (MockedStatic<Snackbar> snackbarStatic = mockStatic(Snackbar.class)) {
            snackbarStatic.when(() -> Snackbar.make(eq(rootView), any(CharSequence.class), eq(Snackbar.LENGTH_LONG)))
                    .thenThrow(new RuntimeException("boom"));

            manager.handlePermissionResult(manager.getLocationPermissionRequestCode(),
                    new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                    new int[]{PackageManager.PERMISSION_DENIED});
        }

        verify(callback, times(2)).onLocationPermissionDenied();
    }

    private PermissionManager createManager() {
        return new PermissionManager(activity, rootView, settingsLauncher, callback);
    }

    private void grantPermissions(String... permissions) {
        shadowOf(application).grantPermissions(permissions);
    }

    private void stubApi34NoRationale(MockedStatic<ActivityCompat> activityCompat) {
        activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                Manifest.permission.READ_MEDIA_IMAGES)).thenReturn(false);
        activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                Manifest.permission.READ_MEDIA_VIDEO)).thenReturn(false);
        activityCompat.when(() -> ActivityCompat.shouldShowRequestPermissionRationale(activity,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)).thenReturn(false);
    }

    private void capturePermissionRequest(
            MockedStatic<ActivityCompat> activityCompat,
            AtomicReference<String[]> requestedPermissions,
            AtomicInteger requestCode
    ) {
        activityCompat.when(() -> ActivityCompat.requestPermissions(
                eq(activity), any(String[].class), anyInt()))
                .thenAnswer(invocation -> {
                    requestedPermissions.set(invocation.getArgument(1));
                    requestCode.set(invocation.getArgument(2));
                    return null;
                });
    }

    private Snackbar mockSnackbar(
            MockedStatic<Snackbar> snackbarStatic,
            AtomicReference<View.OnClickListener> actionListener
    ) {
        Snackbar snackbar = mock(Snackbar.class);
        snackbarStatic.when(() -> Snackbar.make(eq(rootView), any(CharSequence.class), eq(Snackbar.LENGTH_LONG)))
                .thenReturn(snackbar);
        when(snackbar.setAction(anyInt(), any(View.OnClickListener.class)))
                .thenAnswer(invocation -> {
                    actionListener.set(invocation.getArgument(1));
                    return snackbar;
                });
        when(snackbar.setAction(anyString(), any(View.OnClickListener.class)))
                .thenAnswer(invocation -> {
                    actionListener.set(invocation.getArgument(1));
                    return snackbar;
                });
        return snackbar;
    }

    public static class TestActivity extends AppCompatActivity {
    }
}
