package com.doubleangels.redact.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalNotificationsTest {

    private Application app;
    private NotificationManager nm;
    private ShadowNotificationManager shadowNm;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        shadowNm = Shadows.shadowOf(nm);
        shadowNm.setNotificationsEnabled(true);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        AppPreferences.setNotificationsEnabled(app, true);
        AppPreferences.setCleanNotificationsEnabled(app, true);
        AppPreferences.setConvertNotificationsEnabled(app, true);
    }

    @After
    public void tearDown() {
        LocalNotifications.testSdkIntOverride = null;
    }

    private String onlyNotificationText() {
        List<Notification> posted = shadowNm.getAllNotifications();
        assertEquals(1, posted.size());
        return String.valueOf(posted.get(0).extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    @Test
    public void ensureChannels_createsTasksChannel() {
        nm.deleteNotificationChannel(LocalNotifications.CHANNEL_ID_TASKS);
        LocalNotifications.ensureChannels(app);
        assertNotNull(nm.getNotificationChannel(LocalNotifications.CHANNEL_ID_TASKS));
    }

    @Test
    public void ensureChannels_skipsBeforeOreo() {
        nm.deleteNotificationChannel(LocalNotifications.CHANNEL_ID_TASKS);
        LocalNotifications.testSdkIntOverride = 25;
        LocalNotifications.ensureChannels(app);
        assertNull(nm.getNotificationChannel(LocalNotifications.CHANNEL_ID_TASKS));
    }

    @Test
    public void canPostNotifications_requiresRuntimePermission() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertFalse(LocalNotifications.canPostNotifications(app));
        assertFalse(LocalNotifications.canStartForegroundService(app));
    }

    @Test
    public void canPostNotifications_respectsSystemToggle() {
        shadowNm.setNotificationsEnabled(false);
        assertFalse(LocalNotifications.canPostNotifications(app));
        assertFalse(LocalNotifications.canStartForegroundService(app));
    }

    @Test
    public void canPostNotifications_respectsAppPreference() {
        AppPreferences.setNotificationsEnabled(app, false);
        assertFalse(LocalNotifications.canPostNotifications(app));
        assertTrue(LocalNotifications.canStartForegroundService(app));
    }

    @Test
    public void canPostNotifications_trueWhenAllowed() {
        assertTrue(LocalNotifications.canPostNotifications(app));
    }

    @Test
    public void showCleanComplete_postsSuccessText() {
        LocalNotifications.showCleanComplete(app, 3, 0);
        assertEquals(app.getString(R.string.notification_clean_body, 3), onlyNotificationText());
    }

    @Test
    public void showCleanComplete_postsPartialText() {
        LocalNotifications.showCleanComplete(app, 2, 1);
        assertEquals(app.getString(R.string.notification_clean_partial, 2, 1), onlyNotificationText());
    }

    @Test
    public void showCleanComplete_postsFailureText() {
        LocalNotifications.showCleanComplete(app, 0, 4);
        assertEquals(app.getString(R.string.notification_clean_failed), onlyNotificationText());
    }

    @Test
    public void showCleanComplete_suppressedWhenCleanNotificationsDisabled() {
        AppPreferences.setCleanNotificationsEnabled(app, false);
        LocalNotifications.showCleanComplete(app, 1, 0);
        assertEquals(0, shadowNm.size());
    }

    @Test
    public void showConversionComplete_postsSuccessText() {
        LocalNotifications.showConversionComplete(app, 5, 0);
        assertEquals(app.getString(R.string.notification_convert_all_succeeded, 5), onlyNotificationText());
    }

    @Test
    public void showConversionComplete_postsPartialText() {
        LocalNotifications.showConversionComplete(app, 1, 2);
        assertEquals(app.getString(R.string.notification_convert_partial, 1, 2), onlyNotificationText());
    }

    @Test
    public void showConversionComplete_postsFailureText() {
        LocalNotifications.showConversionComplete(app, 0, 0);
        assertEquals(app.getString(R.string.notification_convert_failed), onlyNotificationText());
    }

    @Test
    public void showConversionComplete_suppressedWhenMasterToggleOff() {
        AppPreferences.setNotificationsEnabled(app, false);
        LocalNotifications.showConversionComplete(app, 1, 0);
        assertEquals(0, shadowNm.size());
    }

    @Test
    public void cancelConvertProgress_removesConversionNotification() {
        LocalNotifications.showConversionComplete(app, 1, 0);
        assertEquals(1, shadowNm.size());
        LocalNotifications.cancelConvertProgress(app);
        assertEquals(0, shadowNm.size());
    }

    @Test
    public void mainContentIntent_isNotNull() {
        assertNotNull(LocalNotifications.mainContentIntent(app));
    }
}
