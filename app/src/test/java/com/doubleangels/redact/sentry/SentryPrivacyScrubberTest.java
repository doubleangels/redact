package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SentryPrivacyScrubberTest {

    @Test
    public void scrubRedactsContentUri() {
        String in = "Processing media URI: content://media/external/images/123";
        String out = SentryPrivacyScrubber.scrub(in);
        assertFalse(out.contains("content://"));
        assertTrue(out.contains("[redacted]"));
    }

    @Test
    public void scrubRedactsLatLon() {
        String in = "Parsed video location: lat=37.77, lon=-122.42";
        String out = SentryPrivacyScrubber.scrub(in);
        assertFalse(out.contains("37.77"));
        assertTrue(out.contains("[redacted]"));
    }

    @Test
    public void scrubRedactsAbsolutePath() {
        String in = "Failed to delete /data/user/0/com.example/cache/temp.jpg";
        String out = SentryPrivacyScrubber.scrub(in);
        assertFalse(out.contains("/data/user"));
        assertTrue(out.contains("[redacted]"));
    }
}
