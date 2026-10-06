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
import org.robolectric.annotation.Config;

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
@Config(sdk = 34)
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
        assertFalse(options.isAttachThreads());

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
        while (System.currentTimeMillis() < end) {
            Thread.sleep(25);
        }

        assertFalse("an empty DSN never configures the SDK", SentryInitializer.isInitialized());
    }
}
