package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ApplicationExitInfoBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryMetricsEvent;
import io.sentry.hints.Backfillable;
import io.sentry.util.HintUtils;

/**
 * Process exits and the system's backfilled crash reports are sent only for what happened after
 * crash reporting was turned on, each once. Never uses the project's real DSN.
 */
@RunWith(RobolectricTestRunner.class)
public class ProcessExitsTest {

    private static final String UNREACHABLE_DSN = "https://publickey@127.0.0.1:9/1";

    private Application app;
    private final List<SentryMetricsEvent> sent = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        SentryInitializer.shutdown();
        AppPreferences.setCrashReportingEnabled(app, false);
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryManager.init(app);
        SentryInitializer.initializeBlocking(app);
        assertTrue(SentryInitializer.isInitialized());
        Sentry.getCurrentScopes().getOptions().getMetrics().setBeforeSend((metric, hint) -> {
            sent.add(metric);
            return null;
        });
    }

    @After
    public void tearDown() {
        SentryInitializer.shutdown();
        SentryInitializer.testDsnOverride = null;
        AppPreferences.setCrashReportingEnabled(app, false);
    }

    private void addExit(int reason, long timestamp, int importance) {
        ActivityManager am = app.getSystemService(ActivityManager.class);
        shadowOf(am).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setPackageName(app.getPackageName())
                .setProcessName(app.getPackageName())
                .setReason(reason)
                .setTimestamp(timestamp)
                .setImportance(importance)
                .setRss(512 * 1024)
                .build());
    }

    private List<SentryMetricsEvent> named(String name) {
        List<SentryMetricsEvent> found = new ArrayList<>();
        for (SentryMetricsEvent metric : sent) {
            if (name.equals(metric.getName())) {
                found.add(metric);
            }
        }
        return found;
    }

    @Test
    public void exitsSinceOptIn_areCountedOnce_andOlderOnesNever() {
        long since = AppPreferences.getCrashReportingSince(app);
        addExit(ApplicationExitInfo.REASON_ANR, since - 60_000,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        addExit(ApplicationExitInfo.REASON_LOW_MEMORY, since + 60_000,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);

        ProcessExits.report(app);

        List<SentryMetricsEvent> exits = named("app.exit");
        assertEquals("only the exit after opting in", 1, exits.size());
        assertEquals("low_memory", exits.get(0).getAttributes().get("outcome").getValue());
        assertEquals("foreground_service",
                exits.get(0).getAttributes().get("processing_state").getValue());
        List<SentryMetricsEvent> memory = named("app.exit.memory_mb");
        assertEquals(1, memory.size());
        assertEquals(512.0, memory.get(0).getValue(), 0.0);
        assertEquals(since + 60_000, AppPreferences.getLastReportedExit(app));

        sent.clear();
        ProcessExits.report(app);
        assertTrue("an exit is counted once", named("app.exit").isEmpty());
    }

    @Test
    public void turningReportingOff_forgetsTheWindow() {
        AppPreferences.setLastReportedExit(app, 123L);
        AppPreferences.setCrashReportingEnabled(app, false);
        assertEquals(0L, AppPreferences.getLastReportedExit(app));
        long before = System.currentTimeMillis();
        AppPreferences.setCrashReportingEnabled(app, true);
        assertTrue(AppPreferences.getCrashReportingSince(app) >= before);
    }

    @Test
    public void nothingIsReadWhileReportingIsOff() {
        AppPreferences.setCrashReportingEnabled(app, false);
        addExit(ApplicationExitInfo.REASON_CRASH, System.currentTimeMillis() + 60_000,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ProcessExits.report(app);
        assertTrue(sent.isEmpty());
    }

    @Test
    public void names_coverEveryReasonAndImportance() {
        assertEquals("anr", ProcessExits.reasonName(ApplicationExitInfo.REASON_ANR));
        assertEquals("crash_native", ProcessExits.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE));
        assertEquals("excessive_resource_usage",
                ProcessExits.reasonName(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE));
        assertEquals("user_requested", ProcessExits.reasonName(ApplicationExitInfo.REASON_USER_REQUESTED));
        assertEquals("unknown", ProcessExits.reasonName(-1));
        assertEquals("foreground", ProcessExits.importanceName(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND));
        assertEquals("visible", ProcessExits.importanceName(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE));
        assertEquals("background", ProcessExits.importanceName(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED));
    }

    /** A crash or freeze the SDK reads from the system's history is dropped if it predates opt-in. */
    @Test
    public void backfilledEvents_fromBeforeOptIn_areDropped() {
        long since = AppPreferences.getCrashReportingSince(app);
        Hint backfilled = HintUtils.createWithTypeCheckHint((Backfillable) () -> false);

        assertTrue(SentryInitializer.isFromBeforeOptIn(
                new SentryEvent(new Date(since - 60_000)), backfilled, app));
        assertFalse(SentryInitializer.isFromBeforeOptIn(
                new SentryEvent(new Date(since + 60_000)), backfilled, app));
        assertFalse("a live event is never dropped for its time",
                SentryInitializer.isFromBeforeOptIn(new SentryEvent(new Date(since - 60_000)), new Hint(), app));

        SentryEvent old = new SentryEvent(new Date(since - 60_000));
        assertNull(Sentry.getCurrentScopes().getOptions().getBeforeSend().execute(old, backfilled));
    }
}
