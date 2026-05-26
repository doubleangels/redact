package com.doubleangels.redact.notifications;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.core.app.NotificationManagerCompat;
import androidx.test.core.app.ApplicationProvider;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class LocalNotificationsTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        resetThrottleTimers();
    }

    @After
    public void tearDown() {
        resetThrottleTimers();
    }

    private static void resetThrottleTimers() {
        try {
            setAtomicLong("lastConvertProgressNotifyMs", 0);
            setAtomicLong("lastCleanProgressNotifyMs", 0);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setAtomicLong(String fieldName, long value) throws ReflectiveOperationException {
        Field f = LocalNotifications.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        ((AtomicLong) f.get(null)).set(value);
    }

    @Test
    public void ensureChannels_createsChannel() {
        LocalNotifications.ensureChannels(context);
    }

    @Test
    public void ensureChannels_preO_isNoop() {
        LocalNotifications.testSdkIntOverride = 25;
        try {
            LocalNotifications.ensureChannels(context);
        } finally {
            LocalNotifications.testSdkIntOverride = null;
        }
    }

    @Test
    public void canPostNotifications_requiresPermissionAndPrefs() {
        assertFalse(LocalNotifications.canPostNotifications(context));

        AppPreferences.setNotificationsEnabled(context, true);
        assertFalse(LocalNotifications.canPostNotifications(context));

        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(LocalNotifications.canPostNotifications(context));
    }

    @Test
    @Config(sdk = 32)
    public void canPostNotifications_api32_respectsNotificationManagerState() {
        AppPreferences.setNotificationsEnabled(context, true);
        NotificationManagerCompat notificationManager = mock(NotificationManagerCompat.class);
        when(notificationManager.areNotificationsEnabled()).thenReturn(false, true);

        try (MockedStatic<NotificationManagerCompat> notificationManagerStatic =
                mockStatic(NotificationManagerCompat.class)) {
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context))
                    .thenReturn(notificationManager);
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context.getApplicationContext()))
                    .thenReturn(notificationManager);

            assertFalse(LocalNotifications.canPostNotifications(context));
            assertTrue(LocalNotifications.canPostNotifications(context));
        }
    }

    @Test
    public void updateConversionProgress_respectsPrefsAndThrottle() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        LocalNotifications.updateConversionProgress(context, 50, "Half");
        LocalNotifications.updateConversionProgress(context, 60, "Sixty");
        LocalNotifications.updateConversionProgress(context, -5, "Low");
        LocalNotifications.updateConversionProgress(context, 150, "High");

        AppPreferences.setConvertNotificationsEnabled(context, false);
        LocalNotifications.updateConversionProgress(context, 10, "Convert disabled");

        // Ensure we hit the "progress notifications disabled" early return.
        AppPreferences.setConvertNotificationsEnabled(context, true);
        AppPreferences.setProgressNotificationsEnabled(context, false);
        resetThrottleTimers();
        LocalNotifications.updateConversionProgress(context, 10, "Progress disabled");
    }

    @Test
    public void updateConversionProgress_throttleShortCircuits() throws Exception {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        // Force throttle return path deterministically.
        setAtomicLong("lastConvertProgressNotifyMs", android.os.SystemClock.elapsedRealtime());
        LocalNotifications.updateConversionProgress(context, 10, "Throttled");
    }

    @Test
    public void updateConversionProgress_swallowsSecurityException() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        NotificationManagerCompat notificationManager = mock(NotificationManagerCompat.class);
        when(notificationManager.areNotificationsEnabled()).thenReturn(true);
        doThrow(new SecurityException("blocked")).when(notificationManager).notify(anyInt(), any());

        try (MockedStatic<NotificationManagerCompat> notificationManagerStatic =
                mockStatic(NotificationManagerCompat.class)) {
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context))
                    .thenReturn(notificationManager);
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context.getApplicationContext()))
                    .thenReturn(notificationManager);

            LocalNotifications.updateConversionProgress(context, 50, "Half");
        }
    }

    @Test
    public void updateCleanProgress_andCancel() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        LocalNotifications.updateCleanProgress(context, 25, "Cleaning");
        LocalNotifications.cancelCleanProgress(context);
        LocalNotifications.updateCleanProgress(context, 30, "Again");

        AppPreferences.setProgressNotificationsEnabled(context, false);
        LocalNotifications.updateCleanProgress(context, 40, "Progress disabled");
    }

    @Test
    public void updateCleanProgress_throttlesRapidUpdates() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        // First call sets the throttle timestamp; the immediate follow-up should be throttled.
        LocalNotifications.updateCleanProgress(context, 1, "first");
        LocalNotifications.updateCleanProgress(context, 2, "second");
    }

    @Test
    public void updateCleanProgress_swallowsSecurityExceptionAndHonorsMasterPref() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        NotificationManagerCompat notificationManager = mock(NotificationManagerCompat.class);
        when(notificationManager.areNotificationsEnabled()).thenReturn(true);
        doThrow(new SecurityException("blocked")).when(notificationManager).notify(anyInt(), any());

        try (MockedStatic<NotificationManagerCompat> notificationManagerStatic =
                mockStatic(NotificationManagerCompat.class)) {
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context))
                    .thenReturn(notificationManager);
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context.getApplicationContext()))
                    .thenReturn(notificationManager);

            AppPreferences.setNotificationsEnabled(context, false);
            LocalNotifications.updateCleanProgress(context, 25, "No master permission");
            verify(notificationManager, never()).notify(anyInt(), any());

            AppPreferences.setNotificationsEnabled(context, true);
            LocalNotifications.updateCleanProgress(context, 25, "Cleaning");
        }
    }

    @Test
    public void showConversionComplete_allBranches() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        LocalNotifications.showConversionComplete(context, 3, 0);
        LocalNotifications.showConversionComplete(context, 2, 1);
        LocalNotifications.showConversionComplete(context, 0, 2);

        AppPreferences.setConvertNotificationsEnabled(context, false);
        LocalNotifications.showConversionComplete(context, 1, 0);
    }

    @Test
    public void showConversionComplete_swallowsSecurityException() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        NotificationManagerCompat notificationManager = mock(NotificationManagerCompat.class);
        when(notificationManager.areNotificationsEnabled()).thenReturn(true);
        doThrow(new SecurityException("blocked")).when(notificationManager).notify(anyInt(), any());

        try (MockedStatic<NotificationManagerCompat> notificationManagerStatic =
                mockStatic(NotificationManagerCompat.class)) {
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context))
                    .thenReturn(notificationManager);
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context.getApplicationContext()))
                    .thenReturn(notificationManager);

            LocalNotifications.showConversionComplete(context, 1, 0);
        }
    }

    @Test
    public void showCleanComplete_successAndFailure() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        LocalNotifications.showCleanComplete(context, 2);
        LocalNotifications.showCleanComplete(context, 0);

        AppPreferences.setCleanNotificationsEnabled(context, false);
        LocalNotifications.showCleanComplete(context, 1);
    }

    @Test
    public void showCleanComplete_swallowsSecurityException() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        NotificationManagerCompat notificationManager = mock(NotificationManagerCompat.class);
        when(notificationManager.areNotificationsEnabled()).thenReturn(true);
        doThrow(new SecurityException("blocked")).when(notificationManager).notify(anyInt(), any());

        try (MockedStatic<NotificationManagerCompat> notificationManagerStatic =
                mockStatic(NotificationManagerCompat.class)) {
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context))
                    .thenReturn(notificationManager);
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context.getApplicationContext()))
                    .thenReturn(notificationManager);

            LocalNotifications.showCleanComplete(context, 2);
        }
    }

    @Test
    public void showCleanComplete_cancelsProgressEvenWhenDisabled() {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        NotificationManagerCompat notificationManager = mock(NotificationManagerCompat.class);
        when(notificationManager.areNotificationsEnabled()).thenReturn(true);

        try (MockedStatic<NotificationManagerCompat> notificationManagerStatic =
                mockStatic(NotificationManagerCompat.class)) {
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context))
                    .thenReturn(notificationManager);
            notificationManagerStatic.when(() -> NotificationManagerCompat.from(context.getApplicationContext()))
                    .thenReturn(notificationManager);

            AppPreferences.setCleanNotificationsEnabled(context, false);
            LocalNotifications.showCleanComplete(context, 1);
            verify(notificationManager).cancel(7102);
            verify(notificationManager, never()).notify(anyInt(), any());
        }
    }

    @Test
    public void privateHelpers_coverMasterFlagsAndPercentClamping() throws Exception {
        enableAllNotificationPrefs();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        assertTrue(invokeBooleanHelper("canPostCleanNotifications"));
        assertTrue(invokeBooleanHelper("canPostConvertNotifications"));

        AppPreferences.setNotificationsEnabled(context, false);
        assertFalse(invokeBooleanHelper("canPostCleanNotifications"));
        assertFalse(invokeBooleanHelper("canPostConvertNotifications"));

        assertEquals(0, invokeClampPercent(-1));
        assertEquals(100, invokeClampPercent(101));
        assertEquals(55, invokeClampPercent(55));
    }

    private void enableAllNotificationPrefs() {
        AppPreferences.setNotificationsEnabled(context, true);
        AppPreferences.setCleanNotificationsEnabled(context, true);
        AppPreferences.setConvertNotificationsEnabled(context, true);
        AppPreferences.setProgressNotificationsEnabled(context, true);
    }

    private boolean invokeBooleanHelper(String methodName) throws Exception {
        Method method = LocalNotifications.class.getDeclaredMethod(methodName, Context.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, context);
    }

    private int invokeClampPercent(int percent) throws Exception {
        Method method = LocalNotifications.class.getDeclaredMethod("clampPercent", int.class);
        method.setAccessible(true);
        return (int) method.invoke(null, percent);
    }
}
