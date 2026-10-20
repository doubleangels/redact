package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.media.ProcessingCancelledException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Map;

import io.sentry.ITransaction;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryId;

/**
 * With crash reporting enabled but the Sentry SDK not initialised (as in unit tests, where the
 * facade is a no-op), every reporting helper still runs its full validation and scrubbing logic.
 */
@RunWith(RobolectricTestRunner.class)
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
        assertTrue(SentryManager.isIgnored(new ProcessingCancelledException("Processing cancelled")));
        assertTrue(SentryManager.isIgnored(new RuntimeException(new InterruptedException())));
        assertFalse(SentryManager.isIgnored(new IllegalStateException("real bug")));
        // Only cancellation types count: a real error that mentions it is still reported.
        assertFalse(SentryManager.isIgnored(new IOException("Codec operation cancelled by the system")));
        assertFalse(SentryManager.isIgnored(new IllegalStateException("Decoder interrupted mid-frame")));
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
        SentryManager.recordException(new ProcessingCancelledException("Processing cancelled"));
        AppPreferences.setCrashReportingEnabled(app, false);
        SentryManager.recordException(new IllegalStateException("not sent"));
    }

    @Test
    public void recordException_returnsNoIdWhenNothingIsSent() {
        assertNull(SentryManager.recordException(new UnknownHostException("offline")));
        assertNull(SentryManager.recordException(new ProcessingCancelledException("Processing cancelled")));
        AppPreferences.setCrashReportingEnabled(app, false);
        assertNull(SentryManager.recordException(new IllegalStateException("not sent")));
    }

    @Test
    public void reportedSince_isNullWhenNoNewErrorWasSent() {
        SentryId before = SentryManager.lastReportedEventId();
        SentryManager.recordException(new UnknownHostException("offline"));
        assertNull(SentryManager.reportedSince(before));
    }

    @Test
    public void sendFeedback_skipsBlankNotesAndDisabledReporting() {
        SentryId eventId = new SentryId();
        assertFalse(SentryManager.sendFeedback(null, eventId));
        assertFalse(SentryManager.sendFeedback("   ", eventId));
        // The SDK isn't initialised here, so even a valid note returns false; it must not throw.
        assertFalse(SentryManager.sendFeedback("It failed on content://media/1", eventId));
        assertFalse(SentryManager.sendFeedback("x".repeat(SentryManager.MAX_FEEDBACK_LENGTH + 50), null));
        AppPreferences.setCrashReportingEnabled(app, false);
        assertFalse(SentryManager.sendFeedback("not sent", eventId));
    }

    @Test
    public void operationData_isSplitIntoTagsAndContext() {
        SentryManager.beginOperation("image_to_mediastore");
        SentryManager.setCustomKey("success", false);
        SentryManager.setCustomKey("image_width", 4032);

        SentryEvent event = new SentryEvent();
        SentryManager.applyOperationData(event);

        assertEquals("image_to_mediastore", event.getTag("operation_type"));
        assertEquals("false", event.getTag("success"));
        assertNull("measurements are not tags", event.getTag("image_width"));
        Map<?, ?> context = (Map<?, ?>) event.getContexts().get(SentryManager.OPERATION_CONTEXT);
        assertEquals("4032", context.get("image_width"));
    }

    @Test
    public void beginOperation_dropsThePreviousJobsData() {
        SentryManager.beginOperation("image_to_mediastore");
        SentryManager.setCustomKey("success", false);
        SentryManager.setCustomKey("error_type", "java.io.IOException");
        SentryManager.beginOperation("video_to_mediastore");

        SentryEvent event = new SentryEvent();
        SentryManager.applyOperationData(event);

        assertEquals("video_to_mediastore", event.getTag("operation_type"));
        assertNull(event.getTag("success"));
        assertNull(event.getTag("error_type"));
    }

    @Test
    public void operationData_staysOnTheThreadThatSetIt() throws Exception {
        SentryManager.beginOperation("image_to_mediastore");
        SentryManager.setCustomKey("success", true);

        SentryEvent[] other = new SentryEvent[1];
        Thread scan = new Thread(() -> {
            SentryManager.beginOperation("extract_metadata");
            other[0] = new SentryEvent();
            SentryManager.applyOperationData(other[0]);
        });
        scan.start();
        scan.join();

        assertEquals("extract_metadata", other[0].getTag("operation_type"));
        assertNull("another job's tags don't leak in", other[0].getTag("success"));
        SentryEvent mine = new SentryEvent();
        SentryManager.applyOperationData(mine);
        assertEquals("image_to_mediastore", mine.getTag("operation_type"));
        assertEquals("true", mine.getTag("success"));
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
