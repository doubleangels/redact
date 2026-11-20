package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "What Send Crash Reports captures" tables in README.md and site/index.html, and Section 6
 * of the privacy policy, must say exactly which counters and timings Sentry receives. Every metric
 * the app sends is named in {@link AppMetrics} (see MetricsGoThroughAppMetricsTest), and this list
 * holds the ones the tables describe. A new metric fails here until it is described there too and
 * added below; one that is removed fails until its line goes.
 */
public class MetricsAreDocumentedTest {

    /** Each described in the "Counters and timings" row of the crash-report tables. */
    private static final Set<String> DOCUMENTED = new TreeSet<>(List.of(
            "already_clean.file_status", "already_clean.selection_check.duration",
            "app.process_exit", "app.process_exit.memory_used",
            "cache.leftover_file", "cache.stale_file_removal",
            "censor.copy_save.region_count", "censor.downscaled_save", "censor.photo_open",
            "clean.discarded_part", "clean.method_used",
            "convert.output_format", "convert.video_codec_fallback",
            "job.batch.duration", "job.batch.file_count", "job.batch_outcome", "job.batch_start",
            "job.file.duration", "job.file_outcome", "job.resource_warning",
            "media.unsupported_shared_type",
            "memory.decode_shortage",
            "notification.post_failure",
            "onboarding.intro_exit",
            "output.custom_folder_fallback", "output.folder_grant_failure", "output.unfinished_file_removal",
            "permission.request_answer",
            "secure_delete.failure", "secure_delete.overwrite.duration",
            "service.foreground_start_failure",
            "share.inbound_copy_failure",
            "ui.external_app_missing",
            "update.release_check", "update.release_check.duration",
            "verify.clean_check_outcome",
            "video.output_downscale", "video.smaller_size_retry", "video.source_probe_failure",
            "video.transcode_attempt_failure"));

    private static final File APP_METRICS =
            new File("src/main/java/com/doubleangels/redact/sentry/AppMetrics.java");
    private static final Pattern METRIC = Pattern.compile(
            "SentryManager\\.(?:count|distribution|distributionDurationMs|distributionMegabytes)\\(\\s*\"([a-z_.]+)\"");

    @Test
    public void everyMetricSent_isOneTheCrashReportTablesDescribe() throws IOException {
        assertTrue("Run from the app module so " + APP_METRICS + " exists.", APP_METRICS.isFile());
        String source = new String(Files.readAllBytes(APP_METRICS.toPath()), StandardCharsets.UTF_8);
        Set<String> sent = new TreeSet<>();
        Matcher m = METRIC.matcher(source);
        while (m.find()) {
            sent.add(m.group(1));
        }
        List<String> undocumented = new ArrayList<>(sent);
        undocumented.removeAll(DOCUMENTED);
        List<String> gone = new ArrayList<>(DOCUMENTED);
        gone.removeAll(sent);
        assertEquals("Describe these in README.md, site/index.html and Section 6 of legal/redact.html, "
                + "then add them here: " + undocumented, List.of(), undocumented);
        assertEquals("No longer sent; take them out of the tables and this list: " + gone, List.of(), gone);
    }
}
