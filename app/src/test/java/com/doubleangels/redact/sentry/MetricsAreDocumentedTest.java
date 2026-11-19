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
            "already_clean.check", "already_clean.check.duration",
            "app.exit", "app.exit.memory",
            "cache.orphan",
            "censor.load", "censor.save", "censor.save.regions",
            "clean.dropped_part", "clean.method",
            "convert.codec_fallback", "convert.output",
            "job.batch", "job.batch.duration", "job.batch.size",
            "job.item", "job.item.duration", "job.start", "job.warning",
            "media.unsupported_type", "memory.shortage",
            "notification.failure", "onboarding.end", "output.folder_fallback", "permission.result",
            "secure_delete.fallback", "secure_delete.overwrite.duration",
            "service.failure", "share.snapshot_failure",
            "update.check", "update.check.duration",
            "verify.result",
            "video.downscale", "video.retry", "video.transcode_failure"));

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
