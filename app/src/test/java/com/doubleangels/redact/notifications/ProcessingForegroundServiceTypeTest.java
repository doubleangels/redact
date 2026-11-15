package com.doubleangels.redact.notifications;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.media.AppProcessingScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The foreground service type must be one the running Android version knows. mediaProcessing only
 * exists from API 35; passing it on API 34 throws InvalidForegroundServiceTypeException ("type
 * unknown"), which used to be swallowed so the service silently never started.
 */
@RunWith(RobolectricTestRunner.class)
public class ProcessingForegroundServiceTypeTest {

    private Application app;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        AppPreferences.setNotificationsEnabled(app, true);
        AppPreferences.setProgressNotificationsEnabled(app, true);
    }

    @After
    public void tearDown() {
        AppProcessingScope.resetForTests();
    }

    private int startedForegroundType() {
        Intent intent = new Intent(app, ProcessingForegroundService.class);
        intent.setAction(ProcessingForegroundService.ACTION_START);
        ProcessingForegroundService service =
                Robolectric.buildService(ProcessingForegroundService.class, intent).create().get();
        service.onStartCommand(intent, 0, 1);
        return service.getForegroundServiceType();
    }

    @Test
    @Config(sdk = 34)
    public void onAndroid14_usesDataSync_becauseMediaProcessingDoesNotExistThere() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, startedForegroundType());
    }

    @Test
    @Config(sdk = 35)
    public void onAndroid15_usesMediaProcessing() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, startedForegroundType());
    }

    @Test
    @Config(sdk = 33)
    public void beforeAndroid14_passesNoType() {
        assertEquals(0, startedForegroundType());
    }
}
