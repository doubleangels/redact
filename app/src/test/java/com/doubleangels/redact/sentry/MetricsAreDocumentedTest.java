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
            "already_clean.check", "already_clean.duration_ms",
            "app.exit", "app.exit.memory_mb",
            "cache.orphans_found",
            "censor.load", "censor.regions", "censor.save",
            "clean.part_dropped", "clean.path",
            "convert.codec_fallback", "convert.format",
            "foreground_service.failure", "notification.failure",
            "media.unsupported", "memory.low",
            "onboarding.finished", "output.folder_fallback", "permission.result",
            "processing.batch", "processing.batch_size", "processing.duration_ms",
            "processing.item", "processing.item_duration_ms", "processing.thermal_status",
            "resource.warning",
            "secure_delete.fallback", "secure_delete.overwrite",
            "share.snapshot.failure",
            "update.check", "update.check_duration_ms",
            "verification.failed", "verification.passed",
            "video.downscaled", "video.retry_smaller", "video.transcode.failure"));

    private static final File APP_METRICS =
            new File("src/main/java/com/doubleangels/redact/sentry/AppMetrics.java");
    private static final Pattern METRIC = Pattern.compile(
            "SentryManager\\.(?:count|distribution|distributionDurationMs|gauge)\\(\\s*\"([a-z_.]+)\"");

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
