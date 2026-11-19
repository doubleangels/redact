package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every metric the app sends has one method in {@link AppMetrics}, so the list the crash-report
 * tables in the README and on the site must match lives in one file. This fails when code sends a
 * metric straight through {@link SentryManager} instead.
 */
public class MetricsGoThroughAppMetricsTest {

    private static final File SOURCES = new File("src/main/java");
    private static final Pattern DIRECT_METRIC =
            Pattern.compile("SentryManager\\.(count|distribution|distributionDurationMs|distributionMegabytes)\\s*\\(");

    @Test
    public void noMetricBypassesAppMetrics() throws IOException {
        assertTrue("Run from the app module so " + SOURCES + " exists.", SOURCES.isDirectory());
        List<String> direct = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES.toPath())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String name = file.getFileName().toString();
                if (name.equals("AppMetrics.java") || name.equals("SentryManager.java")) {
                    continue;
                }
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = DIRECT_METRIC.matcher(source);
                while (m.find()) {
                    int line = source.substring(0, m.start()).split("\n", -1).length;
                    direct.add(SOURCES.toPath().relativize(file) + ":" + line);
                }
            }
        }
        assertEquals("Add a method to AppMetrics and call it instead:\n" + String.join("\n", direct),
                0, direct.size());
    }
}
