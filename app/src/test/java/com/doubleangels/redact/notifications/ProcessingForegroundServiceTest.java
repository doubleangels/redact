package com.doubleangels.redact.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.AppProcessingScope;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowService;

@RunWith(RobolectricTestRunner.class)
public class ProcessingForegroundServiceTest {

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

    private static Intent action(String action) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ProcessingForegroundService.class);
        intent.setAction(action);
        return intent;
    }

    private ServiceController<ProcessingForegroundService> run(Intent intent) {
        ServiceController<ProcessingForegroundService> controller =
                Robolectric.buildService(ProcessingForegroundService.class, intent).create();
        controller.startCommand(0, 1);
        return controller;
    }

    /** Delivers {@code intent} to an instance that is already foreground from ACTION_START. */
    private ServiceController<ProcessingForegroundService> runAfterStart(Intent intent) {
        ServiceController<ProcessingForegroundService> controller =
                run(action(ProcessingForegroundService.ACTION_START));
        controller.get().onStartCommand(intent, 0, 2);
        return controller;
    }

    private Notification postedProgressNotification() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        return shadowOf(nm).getNotification(ProcessingForegroundService.NOTIFICATION_ID);
    }

    private static CharSequence extra(Notification n, String key) {
        return n.extras.getCharSequence(key);
    }

    /**
     * From the background the system refuses a foreground service with
     * ForegroundServiceStartNotAllowedException, an IllegalStateException. The job must go on
     * without it rather than crash the app.
     */
    @Test
    public void start_fromTheBackground_doesNotThrow() {
        Context refusing = new android.content.ContextWrapper(app) {
            @Override
            public android.content.ComponentName startForegroundService(Intent service) {
                throw new IllegalStateException("startForegroundService() not allowed");
            }
        };
        Context caller = new android.content.ContextWrapper(app) {
            @Override
            public Context getApplicationContext() {
                return refusing;
            }
        };
        ProcessingForegroundService.start(caller, "Cleaning");
    }

    @Test
    public void start_postsAnOngoingProgressNotificationWithTheTitle() {
        Intent intent = action(ProcessingForegroundService.ACTION_START);
        intent.putExtra(ProcessingForegroundService.EXTRA_TITLE, "Cleaning");
        ServiceController<ProcessingForegroundService> controller = run(intent);

        ShadowService shadow = shadowOf(controller.get());
        Notification n = shadow.getLastForegroundNotification();
        assertNotNull(n);
        assertEquals("Cleaning", String.valueOf(extra(n, Notification.EXTRA_TITLE)));
        assertEquals(app.getString(R.string.notification_progress_percent, 0),
                String.valueOf(extra(n, Notification.EXTRA_TEXT)));
        assertEquals(ProcessingForegroundService.NOTIFICATION_ID, shadow.getLastForegroundNotificationId());
        assertFalse(shadow.isStoppedBySelf());
    }

    @Test
    public void update_showsPercentAndMessage_clampedAndTruncated() {
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 250);
        intent.putExtra(ProcessingForegroundService.EXTRA_MESSAGE, "x".repeat(200));
        runAfterStart(intent);

        Notification n = postedProgressNotification();
        String detail = String.valueOf(extra(n, Notification.EXTRA_TEXT));
        assertEquals(118, detail.length());
        assertTrue(detail.endsWith("…"));
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS));

        Intent negative = action(ProcessingForegroundService.ACTION_UPDATE);
        negative.putExtra(ProcessingForegroundService.EXTRA_PERCENT, -5);
        negative.putExtra(ProcessingForegroundService.EXTRA_MESSAGE, "short message");
        runAfterStart(negative);
        Notification second = postedProgressNotification();
        assertEquals("short message", String.valueOf(extra(second, Notification.EXTRA_TEXT)));
        assertEquals(0, second.extras.getInt(Notification.EXTRA_PROGRESS));
    }

    @Test
    public void emptyMessage_fallsBackToThePercentText() {
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 42);
        intent.putExtra(ProcessingForegroundService.EXTRA_MESSAGE, "");
        runAfterStart(intent);
        Notification n = postedProgressNotification();
        assertEquals(app.getString(R.string.notification_progress_percent, 42),
                String.valueOf(extra(n, Notification.EXTRA_TEXT)));
    }

    @Test
    public void nullIntent_stillPostsTheDefaultNotification() {
        ServiceController<ProcessingForegroundService> controller =
                Robolectric.buildService(ProcessingForegroundService.class).create();
        controller.get().onStartCommand(null, 0, 1);

        Notification n = shadowOf(controller.get()).getLastForegroundNotification();
        assertEquals(app.getString(R.string.status_processing), String.valueOf(extra(n, Notification.EXTRA_TITLE)));
    }

    @Test
    public void nullTitleExtra_usesTheDefaultTitle() {
        Intent intent = action(ProcessingForegroundService.ACTION_START);
        intent.putExtra(ProcessingForegroundService.EXTRA_TITLE, (String) null);
        Notification n = shadowOf(run(intent).get()).getLastForegroundNotification();
        assertEquals(app.getString(R.string.status_processing), String.valueOf(extra(n, Notification.EXTRA_TITLE)));
    }

    @Test
    public void stop_removesTheForegroundStateAndStopsTheService() {
        ServiceController<ProcessingForegroundService> controller =
                runAfterStart(action(ProcessingForegroundService.ACTION_STOP));

        ShadowService shadow = shadowOf(controller.get());
        assertTrue(shadow.isStoppedBySelf());
        assertTrue(shadow.isForegroundStopped());
    }

    @Test
    public void updateOrStop_onAnInstanceThatNeverWentForeground_stopsWithoutStartForeground() {
        // REDACT-20: startService() can recreate the service after it already stopped; calling
        // startForeground() there from the background throws ForegroundServiceStartNotAllowedException.
        for (String a : new String[] {
                ProcessingForegroundService.ACTION_UPDATE, ProcessingForegroundService.ACTION_STOP}) {
            ShadowService shadow = shadowOf(run(action(a)).get());
            assertNull(shadow.getLastForegroundNotification());
            assertTrue(shadow.isStoppedBySelf());
        }
    }

    @Test
    public void onTimeout_cancelsWorkAndStops() {
        AppProcessingScope scope = AppProcessingScope.get(app);
        scope.convertInProgress().set(true);
        int generation = scope.convertGeneration().get();
        ServiceController<ProcessingForegroundService> controller =
                run(action(ProcessingForegroundService.ACTION_START));

        controller.get().onTimeout(1, 0);

        // The convert loop reacts to the flag and clears convertInProgress itself; the generation is
        // left alone so that loop can still report CANCELLED (see MainViewModelSuccessFlowTest).
        assertTrue(scope.convertTimedOut().get());
        assertTrue(scope.convertInProgress().get());
        assertEquals(generation, scope.convertGeneration().get());
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
    }

    @Test
    public void bindAndTaskRemoved_areNoOps() {
        ServiceController<ProcessingForegroundService> controller =
                run(action(ProcessingForegroundService.ACTION_START));
        assertNull(controller.get().onBind(new Intent()));
        controller.get().onTaskRemoved(new Intent());
        assertFalse(shadowOf(controller.get()).isStoppedBySelf());
    }

    // ---- static entry points ----------------------------------------------------------------

    @Test
    public void staticStart_launchesTheServiceWithTheRightIntent() {
        ProcessingForegroundService.start(app, "Converting");

        Intent started = shadowOf(app).getNextStartedService();
        assertNotNull(started);
        assertEquals(ProcessingForegroundService.ACTION_START, started.getAction());
        assertEquals("Converting", started.getStringExtra(ProcessingForegroundService.EXTRA_TITLE));
    }

    @Test
    public void staticStart_withoutTitle_usesTheDefault() {
        ProcessingForegroundService.start(app, null);
        Intent started = shadowOf(app).getNextStartedService();
        assertEquals(app.getString(R.string.status_processing),
                started.getStringExtra(ProcessingForegroundService.EXTRA_TITLE));
    }

    @Test
    public void staticUpdateAndStop_sendTheirActions() {
        ProcessingForegroundService.updateProgress(app, 30, "Title", "Doing things");
        Intent update = shadowOf(app).getNextStartedService();
        assertNotNull(update);
        assertEquals(ProcessingForegroundService.ACTION_UPDATE, update.getAction());
        assertEquals(30, update.getIntExtra(ProcessingForegroundService.EXTRA_PERCENT, -1));
        assertEquals("Title", update.getStringExtra(ProcessingForegroundService.EXTRA_TITLE));
        assertEquals("Doing things", update.getStringExtra(ProcessingForegroundService.EXTRA_MESSAGE));

        ProcessingForegroundService.updateProgress(app, 55, null);
        Intent bare = shadowOf(app).getNextStartedService();
        assertFalse(bare.hasExtra(ProcessingForegroundService.EXTRA_TITLE));
        assertFalse(bare.hasExtra(ProcessingForegroundService.EXTRA_MESSAGE));

        ProcessingForegroundService.stop(app);
        Intent stop = shadowOf(app).getNextStartedService();
        assertEquals(ProcessingForegroundService.ACTION_STOP, stop.getAction());
    }

    @Test
    public void staticEntryPoints_ignoreANullContext() {
        ProcessingForegroundService.start(null, "x");
        ProcessingForegroundService.updateProgress(null, 1, "x");
        ProcessingForegroundService.stop(null);
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test
    public void staticUpdate_isSkippedWhenForegroundServicesCannotStart() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ProcessingForegroundService.updateProgress(app, 10, "x");
        assertNull(shadowOf(app).getNextStartedService());
    }

    // ---- the status bar chip (a Live Update, Android 16+) ---------------------------------------

    @Test
    public void statusChipText_countsFilesInABatch_andShowsThePercentForOne() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("2/4", ProcessingForegroundService.statusChipText(37, 2, 4));
            assertEquals("4/4", ProcessingForegroundService.statusChipText(100, 5, 4));
            assertEquals("42%", ProcessingForegroundService.statusChipText(42, 1, 1));
            assertEquals("42%", ProcessingForegroundService.statusChipText(42, 0, 0));
            assertEquals("0%", ProcessingForegroundService.statusChipText(0, 0, 3));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void statusChipText_usesThePhonesDigits() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals("٢/٤", ProcessingForegroundService.statusChipText(37, 2, 4));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    @Config(sdk = 36)
    public void update_onAndroid16_isALiveUpdateWithTheFileCount() {
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 30);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_NUMBER, 2);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_COUNT, 3);
        runAfterStart(intent);

        Notification n = postedProgressNotification();
        assertTrue(NotificationCompat.isRequestPromotedOngoing(n));
        assertEquals(ProcessingForegroundService.statusChipText(30, 2, 3),
                NotificationCompat.getShortCriticalText(n));
    }

    @Test
    public void progressStyle_hasASegmentPerFile_filledToTheJobsProgress() {
        NotificationCompat.ProgressStyle batch = ProcessingForegroundService.progressStyle(50, 4);
        assertEquals(4, batch.getProgressSegments().size());
        assertEquals(400, batch.getProgressMax());
        assertEquals(200, batch.getProgress());

        NotificationCompat.ProgressStyle single = ProcessingForegroundService.progressStyle(42, 0);
        assertEquals(1, single.getProgressSegments().size());
        assertEquals(42, single.getProgress());
    }

    @Test
    @Config(sdk = 36)
    public void update_onAndroid16_usesTheProgressTemplate() {
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 30);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_NUMBER, 2);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_COUNT, 3);
        runAfterStart(intent);

        Notification n = postedProgressNotification();
        assertEquals(Notification.ProgressStyle.class.getName(),
                n.extras.getString(Notification.EXTRA_TEMPLATE));
        assertEquals(90, n.extras.getInt(Notification.EXTRA_PROGRESS));
        assertEquals(300, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
    }

    @Test
    @Config(sdk = 35)
    public void update_beforeAndroid16_keepsThePlainProgressBar() {
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 30);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_COUNT, 3);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_NUMBER, 1);
        runAfterStart(intent);

        Notification n = postedProgressNotification();
        assertNull(n.extras.getString(Notification.EXTRA_TEMPLATE));
        assertEquals(30, n.extras.getInt(Notification.EXTRA_PROGRESS));
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
    }

    @Test
    @Config(sdk = 36)
    public void update_withProgressUpdatesOff_isNotALiveUpdate() {
        AppPreferences.setProgressNotificationsEnabled(app, false);
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 30);
        runAfterStart(intent);

        Notification n = postedProgressNotification();
        assertFalse(NotificationCompat.isRequestPromotedOngoing(n));
        assertNull(NotificationCompat.getShortCriticalText(n));
    }

    @Test
    @Config(sdk = 36)
    public void start_isNotALiveUpdateUntilProgressArrives() {
        ServiceController<ProcessingForegroundService> controller =
                run(action(ProcessingForegroundService.ACTION_START));

        Notification n = shadowOf(controller.get()).getLastForegroundNotification();
        assertFalse(NotificationCompat.isRequestPromotedOngoing(n));
    }

    @Test
    public void start_showsOnlyWorkingOnTheLockScreen_andStaysOnThePhone() {
        Intent intent = action(ProcessingForegroundService.ACTION_START);
        intent.putExtra(ProcessingForegroundService.EXTRA_TITLE, "Cleaning");
        Notification n = shadowOf(run(intent).get()).getLastForegroundNotification();
        LocalNotificationsTest.assertKeptPrivate(n, app.getString(R.string.notification_public_working));
    }

    @Test
    @Config(sdk = 36)
    public void update_asALiveUpdate_showsOnlyWorkingOnTheLockScreen() {
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 30);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_NUMBER, 2);
        intent.putExtra(ProcessingForegroundService.EXTRA_FILE_COUNT, 3);
        runAfterStart(intent);

        Notification n = postedProgressNotification();
        assertTrue(NotificationCompat.isRequestPromotedOngoing(n));
        LocalNotificationsTest.assertKeptPrivate(n, app.getString(R.string.notification_public_working));
        assertNull(NotificationCompat.getShortCriticalText(n.publicVersion));
    }

    @Test
    public void update_withProgressUpdatesOff_showsOnlyWorkingOnTheLockScreen() {
        AppPreferences.setProgressNotificationsEnabled(app, false);
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 30);
        runAfterStart(intent);

        LocalNotificationsTest.assertKeptPrivate(postedProgressNotification(),
                app.getString(R.string.notification_public_working));
    }

    @Test
    public void staticUpdate_carriesTheFileCount_onlyWhenKnown() {
        ProcessingForegroundService.updateProgress(app, 10, "t", null, 2, 5);
        Intent counted = shadowOf(app).getNextStartedService();
        assertEquals(2, counted.getIntExtra(ProcessingForegroundService.EXTRA_FILE_NUMBER, -1));
        assertEquals(5, counted.getIntExtra(ProcessingForegroundService.EXTRA_FILE_COUNT, -1));

        ProcessingForegroundService.updateProgress(app, 10, "t");
        Intent plain = shadowOf(app).getNextStartedService();
        assertFalse(plain.hasExtra(ProcessingForegroundService.EXTRA_FILE_COUNT));
    }
}
