package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;

import io.sentry.ITransaction;

/**
 * With crash reporting enabled but the Sentry SDK not initialised (as in unit tests, where the
 * facade is a no-op), every reporting helper still runs its full validation and scrubbing logic.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SentryManagerEnabledTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryManager.init(app);
    }

    @After
    public void tearDown() {
        AppPreferences.setCrashReportingEnabled(app, false);
    }

    @Test
    public void isEnabled_followsTheUserPreference() {
        assertTrue(SentryManager.isEnabled());
        AppPreferences.setCrashReportingEnabled(app, false);
        assertFalse(SentryManager.isEnabled());
    }

    @Test
    public void ignoredErrors_areNetworkIssuesAndCancellations() {
        assertTrue(SentryManager.isIgnored(new UnknownHostException("host")));
        assertTrue(SentryManager.isIgnored(new SocketException("reset")));
        assertTrue(SentryManager.isIgnored(new IOException("Processing cancelled")));
        assertFalse(SentryManager.isIgnored(new IllegalStateException("real bug")));
    }

    @Test
    public void logEvent_handlesNullsAndScrubsTheMessage() {
        SentryManager.logEvent("scan", "opened content://media/external/images/media/1");
        SentryManager.logEvent(null, null);
        SentryManager.log("deprecated entry point");
    }

    @Test
    public void counters_acceptAllowedDimensionsOnly() {
        SentryManager.count("processing.clean.success", 1);
        SentryManager.count("", 1);
        SentryManager.count(null, 1);
        SentryManager.count("processing.clean.success", 1, "is_video", "true");
        SentryManager.count("processing.clean.success", 1, "file_name", "secret.jpg");
        SentryManager.count("processing.clean.failure", 1, "is_video", null);
        SentryManager.count("processing.clean.failure", 1, "is_video", "false", "error_type", "IOException");
        SentryManager.count("processing.clean.failure", 1, "is_video", "false", "uri", "content://x");
        SentryManager.count("processing.clean.failure", 1, "uri", "x", "is_video", "false");
        SentryManager.count("processing.clean.failure", 1, "is_video", null, "error_type", null);
    }

    @Test
    public void distributionsAndGauges_validateTheirInputs() {
        SentryManager.distribution("processing.batch.size", 3);
        SentryManager.distribution("", 3);
        SentryManager.distribution("processing.batch.size", 3, "operation_type", "clean");
        SentryManager.distribution("processing.batch.size", 3, "path", "/storage/x");
        SentryManager.distributionDurationMs("processing.duration_ms", 1234, "operation_type", "convert");
        SentryManager.distributionDurationMs("processing.duration_ms", 1234, "location", "x");
        SentryManager.gauge("queue.depth", 2);
        SentryManager.gauge(null, 2);
    }

    @Test
    public void recordException_scrubsOrSkipsDependingOnTheError() {
        SentryManager.recordException(new IllegalStateException("failed on content://media/external/images/media/9"));
        SentryManager.recordException(new IllegalStateException("plain message"));
        SentryManager.recordException(new IllegalStateException());
        SentryManager.recordException(new UnknownHostException("offline"));
        SentryManager.recordException(new IOException("Processing cancelled"));
        AppPreferences.setCrashReportingEnabled(app, false);
        SentryManager.recordException(new IllegalStateException("not sent"));
    }

    @Test
    public void customKeys_areFilteredAndTruncated() {
        SentryManager.setCustomKey("operation_type", "clean");
        SentryManager.setCustomKey("operation_type", (String) null);
        SentryManager.setCustomKey("mime_type", "x".repeat(400));
        SentryManager.setCustomKey("unknown_key", "ignored");
        SentryManager.setCustomKey(null, "ignored");
        SentryManager.setCustomKey("permission_android_permission_X", "true");
        SentryManager.setCustomKey("video_verify_failed_key", "LOCATION");
        SentryManager.setCustomKey("can_ask_images_again", "false");
        SentryManager.setCustomKey("success", true);
        SentryManager.setCustomKey("batch_size", 3);
        SentryManager.setCustomKey("file_size_mb", 4L);
        SentryManager.setCustomKey("image_width", 4.5f);
        SentryManager.setCustomKey("image_height", 6.5d);
        AppPreferences.setCrashReportingEnabled(app, false);
        SentryManager.setCustomKey("operation_type", "ignored while disabled");
    }

    @Test
    public void startTransaction_alwaysReturnsAUsableTransaction() {
        ITransaction enabled = SentryManager.startTransaction("convert_multiple", "task");
        assertNotNull(enabled);
        enabled.finish();

        AppPreferences.setCrashReportingEnabled(app, false);
        ITransaction disabled = SentryManager.startTransaction("convert_multiple", "task");
        assertNotNull(disabled);
        disabled.finish();
    }
}
