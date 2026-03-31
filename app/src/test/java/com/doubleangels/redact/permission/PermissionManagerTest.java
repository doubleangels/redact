package com.doubleangels.redact.permission;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public class PermissionManagerTest {

    private PermissionManager manager;
    private PermissionManager.PermissionCallback callback;

    @Before
    public void setup() {
        Activity mockActivity = mock(Activity.class);
        View mockView = mock(View.class);
        ActivityResultLauncher<Intent> launcher = mock(ActivityResultLauncher.class);
        callback = mock(PermissionManager.PermissionCallback.class);

        manager = new PermissionManager(mockActivity, mockView, launcher, callback);
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.S)
    public void testNeedsPermissionsPreAndroid13SafeExecution() {
        // Verify no NullPointerExceptions occur during ContextCompat checks with Mock Application
        boolean needs = manager.needsPermissions();
        assertNotNull(needs);
    }
    
    @Test
    public void testNeedsLocationPermissionSafeExecution() {
        boolean needs = manager.needsLocationPermission();
        assertNotNull(needs);
    }
}
