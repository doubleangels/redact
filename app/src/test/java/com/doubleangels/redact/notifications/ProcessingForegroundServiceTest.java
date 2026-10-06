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
import android.content.Intent;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.AppProcessingScope;

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
@Config(sdk = 34)
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

    private static CharSequence extra(Notification n, String key) {
        return n.extras.getCharSequence(key);
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
        ServiceController<ProcessingForegroundService> controller = run(intent);

        Notification n = shadowOf(controller.get()).getLastForegroundNotification();
        String detail = String.valueOf(extra(n, Notification.EXTRA_TEXT));
        assertEquals(118, detail.length());
        assertTrue(detail.endsWith("…"));
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS));

        Intent negative = action(ProcessingForegroundService.ACTION_UPDATE);
        negative.putExtra(ProcessingForegroundService.EXTRA_PERCENT, -5);
        negative.putExtra(ProcessingForegroundService.EXTRA_MESSAGE, "short message");
        Notification second = shadowOf(run(negative).get()).getLastForegroundNotification();
        assertEquals("short message", String.valueOf(extra(second, Notification.EXTRA_TEXT)));
        assertEquals(0, second.extras.getInt(Notification.EXTRA_PROGRESS));
    }

    @Test
    public void emptyMessage_fallsBackToThePercentText() {
        Intent intent = action(ProcessingForegroundService.ACTION_UPDATE);
        intent.putExtra(ProcessingForegroundService.EXTRA_PERCENT, 42);
        intent.putExtra(ProcessingForegroundService.EXTRA_MESSAGE, "");
        Notification n = shadowOf(run(intent).get()).getLastForegroundNotification();
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
                run(action(ProcessingForegroundService.ACTION_STOP));

        ShadowService shadow = shadowOf(controller.get());
        assertTrue(shadow.isStoppedBySelf());
        assertTrue(shadow.isForegroundStopped());
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
}
