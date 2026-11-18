package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryAttributeType;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryLogLevel;
import io.sentry.SentryMetricsEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.TransactionInfo;

/**
 * Never uses the project's real DSN: the override points at a closed local port, so even the SDK's
 * own startup telemetry cannot leave the machine.
 */
@RunWith(RobolectricTestRunner.class)
public class SentryInitializerTest {

    private static final String UNREACHABLE_DSN = "https://publickey@127.0.0.1:9/1";

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        SentryInitializer.shutdown();
        SentryInitializer.testDsnOverride = null;
        AppPreferences.setCrashReportingEnabled(app, false);
    }

    @After
    public void tearDown() {
        SentryInitializer.shutdown();
        SentryInitializer.testDsnOverride = null;
        AppPreferences.setCrashReportingEnabled(app, false);
    }

    private static SentryTransaction transaction() {
        return new SentryTransaction(
                "tx", 1.0, 2.0, new java.util.ArrayList<>(), new java.util.HashMap<>(), new TransactionInfo("custom"));
    }

    private static SentryMetricsEvent metric() {
        return new SentryMetricsEvent(new SentryId(), (Double) 1.0, "count", "counter", (Double) 1.0);
    }

    /**
     * Crash reports turned off while the SDK was still starting in the background: shutdown()
     * found nothing to close then, so the start must close the SDK itself rather than keep it.
     * Sessions and profiles have no consent check that would stop them later.
     */
    @Test
    public void reportsTurnedOffWhileStarting_closeTheSdkInsteadOfKeepingIt() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        assertTrue(SentryInitializer.runSentryInitSafely(app));
        assertTrue(Sentry.isEnabled());

        AppPreferences.setCrashReportingEnabled(app, false);
        SentryInitializer.shutdown(); // too early: nothing is initialized yet

        assertFalse(SentryInitializer.keepIfStillWanted(app));
        assertFalse(SentryInitializer.isInitialized());
        assertFalse("the SDK was closed", Sentry.isEnabled());
    }

    @Test
    public void reportsStillOn_keepTheSdk() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        assertTrue(SentryInitializer.runSentryInitSafely(app));

        assertTrue(SentryInitializer.keepIfStillWanted(app));
        assertTrue(SentryInitializer.isInitialized());
    }

    /** The SDK refusing to start must leave the app running, not end it before reporting is up. */
    @Test
    public void anSdkThatFailsToStart_leavesTheAppRunningWithoutReporting() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = "not a dsn";

        assertFalse(SentryInitializer.runSentryInitSafely(app));
        assertTrue("the SDK's own exception was caught, not avoided",
                org.robolectric.shadows.ShadowLog.getLogsForTag("SentryInitializer").stream()
                        .anyMatch(log -> log.throwable instanceof RuntimeException));
        SentryInitializer.initializeBlocking(app);
        assertFalse(SentryInitializer.isInitialized());
    }

    /**
     * The tag gate on the shared scope: a key off the allow-list never becomes a tag, and a value
     * is cut to 200 characters. Read back from the SDK's own scope, as a report would carry it.
     */
    @Test
    public void scopeTags_takeOnlyAllowedKeys_cutTo200Characters() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryManager.init(app);
        SentryInitializer.initializeBlocking(app);

        SentryManager.setCustomKey("unknown_key", "should not be a tag");
        SentryManager.setCustomKey("file_name", "IMG_1.jpg");
        SentryManager.setCustomKey("theme_mode", "x".repeat(400));
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        Sentry.configureScope(scope -> tags.putAll(scope.getTags()));

        assertFalse(tags.containsKey("unknown_key"));
        assertFalse(tags.containsKey("file_name"));
        assertEquals(200, tags.get("theme_mode").length());
    }

    /** A breadcrumb from logEvent is scrubbed before it is added, so an error never carries a URI. */
    @Test
    public void logEventBreadcrumbs_areScrubbed() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryManager.init(app);
        SentryInitializer.initializeBlocking(app);

        SentryManager.logEvent("Scan", "Opened content://media/external/images/media/7 and /storage/emulated/0/DCIM/a.jpg");
        java.util.List<String> messages = new java.util.ArrayList<>();
        Sentry.configureScope(scope -> {
            for (io.sentry.Breadcrumb b : scope.getBreadcrumbs()) {
                messages.add(b.getMessage());
            }
        });

        String last = messages.get(messages.size() - 1);
        assertTrue(last, last.startsWith("Opened [redacted]"));
        assertFalse(last, last.contains("content://") || last.contains("/storage/") || last.contains("a.jpg"));
    }

    @Test
    public void logEvent_alsoSendsAScrubbedStructuredLog() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryManager.init(app);
        SentryInitializer.initializeBlocking(app);
        assertTrue(SentryInitializer.isInitialized());

        java.util.List<String> sentLogs = new java.util.ArrayList<>();
        Sentry.getCurrentScopes().getOptions().getLogs().setBeforeSend(logEvent -> {
            sentLogs.add(logEvent.getBody());
            return null;
        });
        // The probe works: a direct structured log reaches beforeSend.
        Sentry.logger().info("probe");
        assertEquals(1, sentLogs.size());
        sentLogs.clear();

        SentryManager.logEvent("Clean", "Opened content://media/external/images/media/5 at 100%.");
        assertEquals(1, sentLogs.size());
        assertTrue(sentLogs.get(0), sentLogs.get(0).startsWith("Clean: Opened [redacted]"));
        assertFalse(sentLogs.get(0), sentLogs.get(0).contains("content://"));
        assertTrue("a '%' is not a format specifier", sentLogs.get(0).endsWith("at 100%."));
    }

    /**
     * Logs and metrics carry the distinct ID as user.id, and sessions as their "did". The SDK
     * fills it with its per-install ID, which would link every report from one phone.
     */
    @Test
    public void initialisation_clearsTheInstallIdSessionsLogsAndMetricsWouldCarry() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryManager.init(app);
        SentryInitializer.initializeBlocking(app);
        assertTrue(SentryInitializer.isInitialized());

        SentryOptions options = Sentry.getCurrentScopes().getOptions();
        assertNull(options.getDistinctId());

        java.util.List<SentryLogEvent> sentLogs = new java.util.ArrayList<>();
        options.getLogs().setBeforeSend(logEvent -> {
            sentLogs.add(logEvent);
            return null;
        });
        Sentry.logger().info("probe");
        assertEquals(1, sentLogs.size());
        assertNull(sentLogs.get(0).getAttributes().get("user.id"));
    }

    @Test
    public void hasConfiguredDsn_followsTheOverride() {
        SentryInitializer.testDsnOverride = "";
        assertFalse(SentryInitializer.hasConfiguredDsn());
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        assertTrue(SentryInitializer.hasConfiguredDsn());
    }

    @Test
    public void initialisation_isSkippedWhileCrashReportingIsOff() {
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryInitializer.initializeIfNeeded(app);
        SentryInitializer.initializeBlocking(app);
        assertFalse(SentryInitializer.isInitialized());
    }

    @Test
    public void initializeBlocking_withoutADsn_doesNotInitialise() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = "";
        SentryInitializer.initializeBlocking(app);
        assertFalse(SentryInitializer.isInitialized());
    }

    @Test
    public void initializeBlocking_configuresTheSdkAndItsPrivacyHooks() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryManager.init(app);

        SentryInitializer.initializeBlocking(app);
        assertTrue(SentryInitializer.isInitialized());
        // A second call is a no-op.
        SentryInitializer.initializeBlocking(app);
        SentryInitializer.initializeIfNeeded(app);

        SentryOptions options = Sentry.getCurrentScopes().getOptions();
        assertEquals(UNREACHABLE_DSN, options.getDsn());
        assertFalse(options.isSendDefaultPii());
        assertNull(options.getDistinctId());
        // Nothing that records the screen's pixels.
        assertFalse(((io.sentry.android.core.SentryAndroidOptions) options).isAttachScreenshot());
        assertEquals(0.0, options.getSessionReplay().getSessionSampleRate(), 0.0);
        assertEquals(0.0, options.getSessionReplay().getOnErrorSampleRate(), 0.0);
        // Every technical detail that can't identify a phone or a file.
        assertTrue(options.isAttachThreads());
        assertTrue(((io.sentry.android.core.SentryAndroidOptions) options).isAttachViewHierarchy());
        assertTrue(options.isEnableAutoSessionTracking());
        assertTrue(((io.sentry.android.core.SentryAndroidOptions) options).isCollectAdditionalContext());
        assertFalse(((io.sentry.android.core.SentryAndroidOptions) options)
                .isEnableSystemEventBreadcrumbsExtras());
        assertEquals(1.0, options.getTracesSampleRate(), 0.0);

        // Breadcrumbs are scrubbed.
        Breadcrumb crumb = new Breadcrumb("opened content://media/external/images/media/5");
        Breadcrumb kept = options.getBeforeBreadcrumb().execute(crumb, new Hint());
        assertNotNull(kept);
        assertTrue(kept.getMessage().contains("[redacted]"));

        // Events are scrubbed; Sentry's own HTTP client failures are dropped.
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage("failed content://media/external/images/media/5");
        event.setMessage(message);
        SentryEvent sent = options.getBeforeSend().execute(event, new Hint());
        assertNotNull(sent);
        assertTrue(sent.getMessage().getMessage().contains("[redacted]"));

        class SentryHttpClientException extends RuntimeException {
        }
        SentryEvent httpFailure = new SentryEvent(new SentryHttpClientException());
        assertNull(options.getBeforeSend().execute(httpFailure, new Hint()));

        // Transactions are scrubbed.
        SentryTransaction tx = transaction();
        tx.setTag("uri", "content://x/y");
        assertNotNull(options.getBeforeSendTransaction().execute(tx, new Hint()));
        assertEquals("[redacted]", tx.getTags().get("uri"));

        // Structured logs are scrubbed.
        SentryLogEvent log = new SentryLogEvent(new SentryId(), 1.0, "placeholder", SentryLogLevel.INFO);
        log.setBody("opened content://media/external/images/media/5");
        log.setAttribute("user.id", new SentryLogEventAttributeValue(SentryAttributeType.STRING, "me"));
        SentryLogEvent loggedEvent = options.getLogs().getBeforeSend().execute(log);
        assertNotNull(loggedEvent);
        assertTrue(loggedEvent.getBody().contains("[redacted]"));

        // Metrics are scrubbed.
        SentryMetricsEvent metric = metric();
        metric.setAttribute("file_name", new SentryLogEventAttributeValue(SentryAttributeType.STRING, "a.jpg"));
        SentryMetricsEvent sentMetric = options.getMetrics().getBeforeSend().execute(metric, new Hint());
        assertNotNull(sentMetric);
        assertFalse(sentMetric.getAttributes().containsKey("file_name"));

        SentryInitializer.shutdown();
        assertFalse(SentryInitializer.isInitialized());
    }

    @Test
    public void hooks_dropEverythingOnceTheUserOptsOut() {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = UNREACHABLE_DSN;
        SentryManager.init(app);
        SentryInitializer.initializeBlocking(app);
        assertTrue(SentryInitializer.isInitialized());
        SentryOptions options = Sentry.getCurrentScopes().getOptions();

        AppPreferences.setCrashReportingEnabled(app, false);

        assertNull(options.getBeforeSend().execute(new SentryEvent(), new Hint()));
        assertNull(options.getBeforeSendTransaction().execute(transaction(), new Hint()));
        SentryLogEvent log = new SentryLogEvent(new SentryId(), 1.0, "x", SentryLogLevel.INFO);
        assertNull(options.getLogs().getBeforeSend().execute(log));
        assertNull(options.getMetrics().getBeforeSend().execute(metric(), new Hint()));
    }

    @Test
    public void shutdown_isSafeWhenNothingWasStarted() {
        SentryInitializer.shutdown();
        assertFalse(SentryInitializer.isInitialized());
    }

    @Test
    public void asyncInitialize_runsOnABackgroundThreadAndSettles() throws Exception {
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryInitializer.testDsnOverride = "";

        SentryInitializer.initialize(app);
        SentryInitializer.initialize(app); // a concurrent duplicate request is ignored
        long end = System.currentTimeMillis() + 3000;
        while (SentryInitializer.isInitializingForTests() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertFalse("the background attempt settles", SentryInitializer.isInitializingForTests());

        assertFalse("an empty DSN never configures the SDK", SentryInitializer.isInitialized());
    }
}
