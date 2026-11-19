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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SentryEvent;
import io.sentry.SpanStatus;
import io.sentry.protocol.SentryId;

/**
 * With crash reporting enabled but the Sentry SDK not initialised (as in unit tests, where the
 * facade is a no-op), every reporting helper still runs its full validation and scrubbing logic.
 */
/*
 * The tests named ..._doesNotThrow run every helper with good and bad input to show it copes;
 * what reaches Sentry is asserted elsewhere: the tag gate and breadcrumb scrubbing in
 * SentryInitializerTest (against a started SDK), metric attributes in SentryManagerTest, and the
 * scrubber itself in SentryPrivacyScrubberTest.
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
    public void logEvent_withNullsOrUserText_doesNotThrow() {
        SentryManager.logEvent("scan", "opened content://media/external/images/media/1");
        SentryManager.logEvent(null, null);
    }

    @Test
    public void counters_withAnyDimensions_doNotThrow() {
        SentryManager.count("video.output_downscale", 1);
        SentryManager.count("", 1);
        SentryManager.count(null, 1);
        SentryManager.count("video.output_downscale", 1, "file.media_type", "true");
        SentryManager.count("video.output_downscale", 1, "file_name", "secret.jpg");
        SentryManager.count("job.file_outcome", 1, "file.media_type", null);
        SentryManager.count("job.file_outcome", 1, "file.media_type", "false", "error_type", "IOException");
        SentryManager.count("job.file_outcome", 1, "file.media_type", "false", "uri", "content://x");
        SentryManager.count("job.file_outcome", 1, "uri", "x", "file.media_type", "false");
        SentryManager.count("job.file_outcome", 1, "file.media_type", null, "error_type", null);
    }

    @Test
    public void distributionsAndGauges_withAnyInput_doNotThrow() {
        SentryManager.distribution("video.smaller_size_retry", 3);
        SentryManager.distribution("", 3);
        SentryManager.distribution("video.smaller_size_retry", 3, "job.type", "clean");
        SentryManager.distribution("video.smaller_size_retry", 3, "path", "/storage/x");
        SentryManager.distributionDurationMs("job.batch.duration", 1234, "job.type", "convert");
        SentryManager.distributionDurationMs("job.batch.duration", 1234, "location", "x");
    }

    @Test
    public void recordException_ofAnyError_doesNotThrow() {
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
        SentryManager.setCustomKey("outcome", "failed");
        SentryManager.setCustomKey("image.decode_sample_size", 4);
        SentryManager.setCustomKey("image.resolution_class", SentryManager.resolutionClass(4032, 3024));

        SentryEvent event = new SentryEvent();
        SentryManager.applyOperationData(event);

        assertEquals("image_to_mediastore", event.getTag("job.operation"));
        assertEquals("failed", event.getTag("outcome"));
        assertEquals("4k", event.getTag("image.resolution_class"));
        assertNull("measurements are not tags", event.getTag("image.decode_sample_size"));
        Map<?, ?> context = (Map<?, ?>) event.getContexts().get(SentryManager.OPERATION_CONTEXT);
        assertEquals("4", context.get("image.decode_sample_size"));
    }

    @Test
    public void measuredValuesFromTheFile_areNeverSent() {
        SentryManager.beginOperation("extract_sectioned_metadata");
        for (String key : new String[] {"image_width", "image_height", "video_width", "video_height",
                "video_duration_ms", "video_frame_rate", "video_bitrate_kbps", "audio_sample_rate", "tile_tab"}) {
            SentryManager.setCustomKey(key, 1234);
        }

        SentryEvent event = new SentryEvent();
        SentryManager.applyOperationData(event);

        Map<?, ?> context = (Map<?, ?>) event.getContexts().get(SentryManager.OPERATION_CONTEXT);
        for (String key : new String[] {"image_width", "video_duration_ms", "video_bitrate_kbps", "tile_tab"}) {
            assertNull(key, event.getTag(key));
            assertTrue(key, context == null || !context.containsKey(key));
        }
    }

    @Test
    public void classes_replaceExactValues() {
        assertEquals("unknown", SentryManager.resolutionClass(0, 100));
        assertEquals("up_to_720p", SentryManager.resolutionClass(1280, 720));
        assertEquals("1080p", SentryManager.resolutionClass(1080, 1920));
        assertEquals("4k", SentryManager.resolutionClass(4032, 3024));
        assertEquals("8k", SentryManager.resolutionClass(8192, 4320));
        assertEquals("larger", SentryManager.resolutionClass(12000, 9000));
        assertEquals("unknown", SentryManager.durationClass(-1));
        assertEquals("under_1_min", SentryManager.durationClass(59_999));
        assertEquals("under_10_min", SentryManager.durationClass(60_000));
        assertEquals("10_min_or_more", SentryManager.durationClass(600_000));
        assertEquals("image/jpeg", SentryManager.mimeTypeClass("IMAGE/JPEG"));
        assertEquals("image/other", SentryManager.mimeTypeClass("image/x-secret-camera-raw"));
        assertEquals("video/other", SentryManager.mimeTypeClass("video/x-weird"));
        assertEquals("other", SentryManager.mimeTypeClass("application/octet-stream"));
        assertEquals("unknown", SentryManager.mimeTypeClass(null));
    }

    @Test
    public void beginOperation_tagsTheThermalAndBatterySaverState() {
        SentryManager.beginOperation("image_to_mediastore");

        SentryEvent event = new SentryEvent();
        SentryManager.applyOperationData(event);

        assertEquals("none", event.getTag("device.thermal_status"));
        assertEquals("false", event.getTag("device.battery_saver_on"));
        assertEquals("none", SentryManager.thermalStatus());
        assertFalse(SentryManager.isPowerSaveMode());
    }

    @Test
    public void beginOperation_dropsThePreviousJobsData() {
        SentryManager.beginOperation("image_to_mediastore");
        SentryManager.setCustomKey("outcome", "failed");
        SentryManager.setCustomKey("error_type", "java.io.IOException");
        SentryManager.beginOperation("video_to_mediastore");

        SentryEvent event = new SentryEvent();
        SentryManager.applyOperationData(event);

        assertEquals("video_to_mediastore", event.getTag("job.operation"));
        assertNull(event.getTag("outcome"));
        assertNull(event.getTag("error_type"));
    }

    @Test
    public void operationData_staysOnTheThreadThatSetIt() throws Exception {
        SentryManager.beginOperation("image_to_mediastore");
        SentryManager.setCustomKey("outcome", "success");

        SentryEvent[] other = new SentryEvent[1];
        Thread scan = new Thread(() -> {
            SentryManager.beginOperation("extract_metadata");
            other[0] = new SentryEvent();
            SentryManager.applyOperationData(other[0]);
        });
        scan.start();
        scan.join();

        assertEquals("extract_metadata", other[0].getTag("job.operation"));
        assertNull("another job's tags don't leak in", other[0].getTag("outcome"));
        SentryEvent mine = new SentryEvent();
        SentryManager.applyOperationData(mine);
        assertEquals("image_to_mediastore", mine.getTag("job.operation"));
        assertEquals("success", mine.getTag("outcome"));
    }

    @Test
    public void customKeys_ofAnyKeyOrValue_doNotThrow() {
        SentryManager.setCustomKey("job.type", "clean");
        SentryManager.setCustomKey("job.type", (String) null);
        SentryManager.setCustomKey("file.mime_type", "x".repeat(400));
        SentryManager.setCustomKey("unknown_key", "ignored");
        SentryManager.setCustomKey(null, "ignored");
        SentryManager.setCustomKey("permission_android_permission_X", "true");
        SentryManager.setCustomKey("video_verify_failed_key", "LOCATION");
        SentryManager.setCustomKey("can_ask_images_again", "false");
        SentryManager.setCustomKey("outcome", "success");
        SentryManager.setCustomKey("batch_size", 3);
        SentryManager.setCustomKey("file_size_mb", 4L);
        SentryManager.setCustomKey("image_width", 4.5f);
        SentryManager.setCustomKey("image_height", 6.5d);
        AppPreferences.setCrashReportingEnabled(app, false);
        SentryManager.setCustomKey("job.type", "ignored while disabled");
    }

    @Test
    public void startTransaction_alwaysReturnsAUsableTransaction() {
        ITransaction enabled = SentryManager.startTransaction("convert.batch");
        assertNotNull(enabled);
        enabled.finish();

        AppPreferences.setCrashReportingEnabled(app, false);
        ITransaction disabled = SentryManager.startTransaction("convert.batch");
        assertNotNull(disabled);
        disabled.finish();
    }

    @Test
    public void inSpan_outsideTracedWork_onlyRunsTheWork() throws Exception {
        assertEquals("done", SentryManager.inSpan("verify.image_metadata_check", () -> "done"));
        SentryManager.Span span = SentryManager.span("video.transcode");
        span.failed(new IOException("x"));
        span.close();
    }

    @Test
    public void inSpan_nestsUnderTheThreadsSpanAndEndsOk() throws Exception {
        FakeSpan parent = new FakeSpan("clean.item");
        SentryManager.runUnder(parent.proxy(), () -> {
            String result = SentryManager.inSpan("image.metadata_free_rebuild",
                    () -> SentryManager.inSpan("verify.image_metadata_check", () -> "clean"));
            assertEquals("clean", result);
        });
        assertEquals(1, parent.children.size());
        FakeSpan rebuild = parent.children.get(0);
        assertEquals("image.metadata_free_rebuild", rebuild.operation);
        assertEquals(SpanStatus.OK, rebuild.status);
        assertTrue(rebuild.finished);
        assertEquals(1, rebuild.children.size());
        assertEquals("verify.image_metadata_check", rebuild.children.get(0).operation);
        assertTrue(rebuild.children.get(0).finished);
        // The parent belongs to whoever started it: neither finished nor given a status here.
        assertFalse(parent.finished);
        assertNull(parent.status);
        // And the thread is back outside traced work.
        assertEquals("plain", SentryManager.inSpan("media.source_copy", () -> "plain"));
        assertEquals(1, parent.children.size());
    }

    @Test
    public void inSpan_marksAFailureAndRethrowsIt() {
        FakeSpan parent = new FakeSpan("clean.item");
        IOException thrown = new IOException("left");
        try {
            SentryManager.runUnder(parent.proxy(), () -> SentryManager.runInSpan("verify.video_metadata_check", () -> {
                throw thrown;
            }));
            throw new AssertionError("The failure was swallowed");
        } catch (IOException e) {
            assertTrue(e == thrown);
        }
        assertEquals(SpanStatus.INTERNAL_ERROR, parent.children.get(0).status);
        assertTrue(parent.children.get(0).finished);
    }

    @Test
    public void inSpan_marksACancellationAsCancelled() {
        FakeSpan parent = new FakeSpan("share.item");
        try {
            SentryManager.runUnder(parent.proxy(), () -> SentryManager.runInSpan("video.transcode", () -> {
                throw new ProcessingCancelledException("stopped");
            }));
        } catch (ProcessingCancelledException expected) {
            // rethrown unchanged
        }
        assertEquals(SpanStatus.CANCELLED, parent.children.get(0).status);
    }

    @Test
    public void inTransaction_insideTracedWork_isASpan() {
        FakeSpan parent = new FakeSpan("censor.copy_save");
        SentryManager.runUnder(parent.proxy(), () ->
                SentryManager.runInTransaction("scan.multi_file_summary", () -> SentryManager.failCurrentSpan(
                        new IllegalStateException("failed"))));
        assertEquals("scan.multi_file_summary", parent.children.get(0).operation);
        assertEquals(SpanStatus.INTERNAL_ERROR, parent.children.get(0).status);
    }

    @Test
    public void spanHandle_isTheThreadsSpanUntilClosed() {
        FakeSpan parent = new FakeSpan("convert.item");
        SentryManager.runUnder(parent.proxy(), () -> {
            SentryManager.Span span = SentryManager.span("video.transcode");
            SentryManager.runInSpan("output.file_save", () -> { });
            span.failed(new InterruptedException());
            span.close();
            SentryManager.runInSpan("verify.video_metadata_check", () -> { });
        });
        assertEquals(2, parent.children.size());
        FakeSpan transcode = parent.children.get(0);
        assertEquals(SpanStatus.CANCELLED, transcode.status);
        assertTrue(transcode.finished);
        assertEquals("output.file_save", transcode.children.get(0).operation);
        assertEquals("verify.video_metadata_check", parent.children.get(1).operation);
    }

    @Test
    public void inSpan_whileReportingIsOff_recordsNothing() {
        FakeSpan parent = new FakeSpan("clean.item");
        AppPreferences.setCrashReportingEnabled(app, false);
        SentryManager.runUnder(parent.proxy(), () -> SentryManager.runInSpan("image.metadata_free_rebuild", () -> { }));
        assertTrue(parent.children.isEmpty());
    }

    /** An {@link ISpan} that records what the tracing helpers do to it. */
    private static final class FakeSpan {
        final String operation;
        final List<FakeSpan> children = new ArrayList<>();
        SpanStatus status;
        boolean finished;

        FakeSpan(String operation) {
            this.operation = operation;
        }

        ISpan proxy() {
            return (ISpan) Proxy.newProxyInstance(ISpan.class.getClassLoader(), new Class<?>[] {ISpan.class},
                    (self, method, args) -> {
                        switch (method.getName()) {
                            case "startChild": {
                                FakeSpan child = new FakeSpan((String) args[0]);
                                children.add(child);
                                return child.proxy();
                            }
                            case "setStatus":
                                status = (SpanStatus) args[0];
                                return null;
                            case "getStatus":
                                return status;
                            case "finish":
                                finished = true;
                                return null;
                            case "isFinished":
                                return finished;
                            case "getOperation":
                                return operation;
                            case "hashCode":
                                return System.identityHashCode(self);
                            case "equals":
                                return self == args[0];
                            default:
                                Class<?> type = method.getReturnType();
                                return type == boolean.class ? Boolean.FALSE : null;
                        }
                    });
        }
    }
}
