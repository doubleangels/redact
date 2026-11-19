package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.pm.PackageManager;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.sentry.Sentry;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryMetricsEvent;

/**
 * Every metric reaches Sentry with its name and only fixed labels. A key outside the
 * {@link SentryManager} allowlist would drop the metric silently, so each helper is checked
 * against what actually reaches the SDK. Never uses the project's real DSN.
 */
@RunWith(RobolectricTestRunner.class)
public class AppMetricsTest {

    private static final String UNREACHABLE_DSN = "https://publickey@127.0.0.1:9/1";

    private Application app;
    private final List<SentryMetricsEvent> sent = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        SentryInitializer.shutdown();
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

    private SentryMetricsEvent only(String name) {
        SentryMetricsEvent found = null;
        for (SentryMetricsEvent metric : sent) {
            if (name.equals(metric.getName())) {
                assertEquals("one " + name, null, found);
                found = metric;
            }
        }
        assertNotNull(name + " was not sent; sent " + names(), found);
        return found;
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (SentryMetricsEvent metric : sent) {
            names.add(metric.getName());
        }
        return names;
    }

    private static String attr(SentryMetricsEvent metric, String key) {
        SentryLogEventAttributeValue value = metric.getAttributes().get(key);
        assertNotNull(key + " missing on " + metric.getName(), value);
        return String.valueOf(value.getValue());
    }

    @Test
    public void newGaps_eachSendTheirMetric() {
        AppMetrics.externalAppMissing(AppMetrics.EXTERNAL_MAPS);
        assertEquals("maps", attr(only("ui.external_app_missing"), "reason"));

        AppMetrics.outputFolderGrantFailure(true);
        assertEquals("video", attr(only("output.folder_grant_failure"), "file.media_type"));

        AppMetrics.videoProbeFailure("tracks", new IllegalStateException("x"));
        SentryMetricsEvent probe = only("video.source_probe_failure");
        assertEquals("tracks", attr(probe, "reason"));
        assertEquals("IllegalStateException", attr(probe, "error_type"));

        AppMetrics.staleFilesRemoved(0);
        AppMetrics.staleFilesRemoved(4);
        assertEquals(4.0, only("cache.stale_file_removal").getValue(), 0.0);

        AppMetrics.unfinishedOutputRemoval(null);
        assertEquals("success", attr(only("output.unfinished_file_removal"), "outcome"));
    }

    @Test
    public void unfinishedOutputRemoval_failureCarriesTheErrorType() {
        AppMetrics.unfinishedOutputRemoval(new SecurityException("revoked"));
        SentryMetricsEvent removal = only("output.unfinished_file_removal");
        assertEquals("failed", attr(removal, "outcome"));
        assertEquals("SecurityException", attr(removal, "error_type"));
    }

    @Test
    public void item_countsEveryOutcome_andTimesOnlySuccesses() {
        AppMetrics.item(AppMetrics.CLEAN, true, AppMetrics.SUCCESS, 1200);
        SentryMetricsEvent count = only("job.file_outcome");
        assertEquals("clean", attr(count, "job.type"));
        assertEquals("video", attr(count, "file.media_type"));
        assertEquals("success", attr(count, "outcome"));
        SentryMetricsEvent duration = only("job.file.duration");
        assertEquals(1200.0, duration.getValue(), 0.0);
        assertEquals("clean", attr(duration, "job.type"));
        assertEquals("video", attr(duration, "file.media_type"));

        sent.clear();
        AppMetrics.item(AppMetrics.SHARE, false, AppMetrics.FAILED, -1);
        SentryMetricsEvent failed = only("job.file_outcome");
        assertEquals("share", attr(failed, "job.type"));
        assertEquals("failed", attr(failed, "outcome"));
        assertEquals("a failure has no duration", 1, sent.size());
    }

    /** A failed file is one processing.item with the kind of error, not a second counter. */
    @Test
    public void itemFailed_namesTheKindOfError() {
        AppMetrics.itemFailed(AppMetrics.CONVERT, false, new java.io.IOException("/storage/a.jpg"), false);
        SentryMetricsEvent thrown = only("job.file_outcome");
        assertEquals("convert", attr(thrown, "job.type"));
        assertEquals("image", attr(thrown, "file.media_type"));
        assertEquals("failed", attr(thrown, "outcome"));
        assertEquals("only the class, never the message", "IOException", attr(thrown, "error_type"));

        sent.clear();
        AppMetrics.itemNotCleaned(AppMetrics.SHARE, true, true);
        SentryMetricsEvent unsupported = only("job.file_outcome");
        assertEquals("unsupported", attr(unsupported, "outcome"));
        assertEquals("unsupported", attr(unsupported, "error_type"));

        sent.clear();
        AppMetrics.itemNotCleaned(AppMetrics.CLEAN, true, false);
        assertEquals("clean_failed", attr(only("job.file_outcome"), "error_type"));
    }

    @Test
    public void outcomeOf_namesMemoryUnsupportedAndOtherFailures() {
        assertEquals(AppMetrics.OUT_OF_MEMORY, AppMetrics.outcomeOf(new OutOfMemoryError(), true));
        assertEquals(AppMetrics.UNSUPPORTED, AppMetrics.outcomeOf(new RuntimeException(), true));
        assertEquals(AppMetrics.FAILED, AppMetrics.outcomeOf(new RuntimeException(), false));
    }

    @Test
    public void batches_recordTheirSizeAndHowTheyEnded() {
        AppMetrics.batchStarted(AppMetrics.CONVERT, 7);
        SentryMetricsEvent size = only("job.batch.file_count");
        assertEquals(7.0, size.getValue(), 0.0);
        assertEquals("convert", attr(size, "job.type"));

        AppMetrics.batchFinished(AppMetrics.CONVERT, 5, 2, false);
        SentryMetricsEvent batch = only("job.batch_outcome");
        assertEquals("convert", attr(batch, "job.type"));
        assertEquals("partial", attr(batch, "outcome"));

        assertEquals("success", AppMetrics.batchOutcome(3, 0, false));
        assertEquals("failed", AppMetrics.batchOutcome(0, 3, false));
        assertEquals("cancelled", AppMetrics.batchOutcome(3, 0, true));
    }

    @Test
    public void convert_namesTheTargetFormatByMimeType() {
        assertEquals("image/jpeg", AppMetrics.imageFormatMimeType("JPEG"));
        assertEquals("image/png", AppMetrics.imageFormatMimeType("PNG"));
        assertEquals("image/webp", AppMetrics.imageFormatMimeType("WEBP_LOSSY"));
        assertEquals("image/heic", AppMetrics.imageFormatMimeType("HEIC"));
        assertEquals("unknown", AppMetrics.imageFormatMimeType(null));
        assertEquals("video/avc", AppMetrics.videoFormatMimeType(0));
        assertEquals("video/hevc", AppMetrics.videoFormatMimeType(1));
        assertEquals("video/vp9", AppMetrics.videoFormatMimeType(2));
        assertEquals("video/av01", AppMetrics.videoFormatMimeType(3));

        AppMetrics.convertFormat("image/webp", AppMetrics.SUCCESS);
        SentryMetricsEvent format = only("convert.output_format");
        assertEquals("image/webp", attr(format, "file.output_mime_type"));
        assertEquals("success", attr(format, "outcome"));

        AppMetrics.codecFallback(0);
        assertEquals("video/avc", attr(only("convert.video_codec_fallback"), "video.output_codec"));
    }

    @Test
    public void otherAreas_eachSendTheirMetric() {
        AppMetrics.verificationPassed(false);
        assertEquals("image", attr(only("verify.clean_check_outcome"), "file.media_type"));
        assertEquals("passed", attr(only("verify.clean_check_outcome"), "outcome"));

        AppMetrics.alreadyCleanResult("ALREADY_CLEAN");
        assertEquals("already_clean", attr(only("already_clean.file_status"), "outcome"));
        AppMetrics.alreadyCleanDuration(40);
        only("already_clean.selection_check.duration");

        AppMetrics.updateCheck("up_to_date", 300);
        assertEquals("up_to_date", attr(only("update.release_check"), "outcome"));
        only("update.release_check.duration");

        AppMetrics.censorLoad(AppMetrics.UNSUPPORTED);
        assertEquals("unsupported", attr(only("censor.photo_open"), "outcome"));
        AppMetrics.censorRegions(3);
        assertEquals(3.0, only("censor.copy_save.region_count").getValue(), 0.0);

        AppMetrics.secureDeleteOverwrite(15);
        only("secure_delete.overwrite.duration");

        AppMetrics.foregroundServiceFailure("start_service", new IllegalStateException());
        SentryMetricsEvent service = only("service.foreground_start_failure");
        assertEquals("failed", attr(service, "outcome"));
        assertEquals("start_service", attr(service, "reason"));
        assertEquals("IllegalStateException", attr(service, "error_type"));

        AppMetrics.notificationFailure(new SecurityException());
        assertEquals("SecurityException", attr(only("notification.post_failure"), "error_type"));

        AppMetrics.onboardingFinished(true);
        assertEquals("finished", attr(only("onboarding.intro_exit"), "outcome"));
    }

    @Test
    public void permissions_areCountedByKindAndAnswer() {
        AppMetrics.permissionResults(
                new String[] {"android.permission.READ_MEDIA_IMAGES", "android.permission.CAMERA"},
                new int[] {PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED});
        assertEquals(2, sent.size());
        assertEquals("images", attr(sent.get(0), "permission.type"));
        assertEquals("granted", attr(sent.get(0), "outcome"));
        assertEquals("other", attr(sent.get(1), "permission.type"));
        assertEquals("denied", attr(sent.get(1), "outcome"));

        sent.clear();
        Map<String, Boolean> results = new LinkedHashMap<>();
        results.put("android.permission.ACCESS_MEDIA_LOCATION", true);
        results.put("android.permission.POST_NOTIFICATIONS", null);
        AppMetrics.permissionResults(results);
        assertEquals("media_gps", attr(sent.get(0), "permission.type"));
        assertEquals("notifications", attr(sent.get(1), "permission.type"));
        assertEquals("denied", attr(sent.get(1), "outcome"));

        sent.clear();
        AppMetrics.permissionResults(null, null);
        AppMetrics.permissionResults((Map<String, Boolean>) null);
        assertTrue(sent.isEmpty());
        assertEquals("other", AppMetrics.permissionType(null));
        assertEquals("storage", AppMetrics.permissionType("android.permission.READ_EXTERNAL_STORAGE"));
        assertEquals("videos", AppMetrics.permissionType("android.permission.READ_MEDIA_VIDEO"));
        assertEquals("selected_media",
                AppMetrics.permissionType("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"));
    }
}
