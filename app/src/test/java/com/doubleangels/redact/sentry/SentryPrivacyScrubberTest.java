package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import io.sentry.SentryAttributeType;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryLogLevel;
import io.sentry.SentryMetricsEvent;
import io.sentry.protocol.SentryId;

public class SentryPrivacyScrubberTest {

    @Test
    public void scrubRedactsContentUris() {
        String input = "Failed to open content://media/external/images/media/12345";
        String scrubbed = SentryPrivacyScrubber.scrub(input);
        assertNotNull(scrubbed);
        assertTrue(scrubbed.contains("[redacted]"));
        assertTrue(!scrubbed.contains("content://"));
    }

    @Test
    public void scrubRedactsGpsCoordinatePairs() {
        String input = "Location: 39.7392,-104.9903";
        String scrubbed = SentryPrivacyScrubber.scrub(input);
        assertNotNull(scrubbed);
        assertTrue(scrubbed.contains("[redacted]"));
        assertTrue(!scrubbed.contains("39.7392"));
    }

    @Test
    public void scrubLeavesNonGpsNumericPairs() {
        String input = "Dimensions: 1920.0,1080.0";
        String scrubbed = SentryPrivacyScrubber.scrub(input);
        assertEquals(input, scrubbed);
    }

    @Test
    public void scrubLogRedactsBodyAndStripsUserAttributes() {
        SentryLogEvent logEvent =
                new SentryLogEvent(new SentryId(), 1.0, "placeholder", SentryLogLevel.INFO);
        logEvent.setBody("Opened content://media/external/images/media/1");
        logEvent.setAttribute(
                "user.id",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "secret-user"));
        logEvent.setAttribute(
                "operation_type",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "clean"));

        SentryPrivacyScrubber.scrubLog(logEvent);

        assertNotNull(logEvent.getBody());
        assertTrue(logEvent.getBody().contains("[redacted]"));
        assertFalse(logEvent.getAttributes().containsKey("user.id"));
        assertEquals("clean", logEvent.getAttributes().get("operation_type").getValue());
    }

    @Test
    public void scrubMetricRedactsStringAttributesAndStripsPiiKeys() {
        SentryMetricsEvent metric =
                new SentryMetricsEvent(new SentryId(), 1.0, "processing.clean.success", null, 1.0);
        metric.setAttribute(
                "user.email",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "a@b.com"));
        metric.setAttribute(
                "error_type",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "IOException"));

        SentryPrivacyScrubber.scrubMetric(metric);

        assertFalse(metric.getAttributes().containsKey("user.email"));
        assertEquals("IOException", metric.getAttributes().get("error_type").getValue());
    }
}
