package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;

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

    @Test
    public void scrub_handlesNullEmptyLongStringsAndFilePatterns() {
        assertNull(SentryPrivacyScrubber.scrub(null));
        assertEquals("", SentryPrivacyScrubber.scrub(""));

        String out =
                SentryPrivacyScrubber.scrub(
                        "See file:///storage/emulated/0/DCIM/IMG_0001.jpg and item: IMG_0001.jpg at 40.7128, -74.0060");
        assertFalse(out.contains("file://"));
        assertFalse(out.contains("IMG_0001.jpg"));
        assertFalse(out.contains("40.7128"));
        assertTrue(out.contains("[redacted]"));

        String longValue = "x".repeat(501);
        assertTrue(SentryPrivacyScrubber.scrub(longValue).endsWith("…"));
    }

    @Test
    public void scrubBreadcrumb_redactsMessageAndStringData_only() {
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("content://media/external/images/123");
        HashMap<String, Object> data = new HashMap<>();
        data.put("uri", "file:///storage/emulated/0/video.mp4");
        data.put("count", 3);
        data.forEach(breadcrumb::setData);

        SentryPrivacyScrubber.scrubBreadcrumb(breadcrumb);
        SentryPrivacyScrubber.scrubBreadcrumb(null);

        assertFalse(breadcrumb.getMessage().contains("content://"));
        assertEquals("[redacted]", breadcrumb.getData().get("uri"));
        assertEquals(3, breadcrumb.getData().get("count"));
    }

    @Test
    public void scrubEvent_redactsMessageBreadcrumbsAndSensitiveTags() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage("Resolved processed file: IMG_9999.png");
        event.setMessage(message);

        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("lat=12.34");
        event.addBreadcrumb(breadcrumb);

        event.setTags(new HashMap<>());
        event.getTags().put("file_name", "IMG_9999.png");
        event.getTags().put("safe_key", "content://provider/item");
        event.getTags().put("nullable_value", null);

        SentryPrivacyScrubber.scrubEvent(event);
        SentryPrivacyScrubber.scrubEvent(null);

        assertTrue(event.getMessage().getMessage().contains("[redacted]"));
        assertTrue(event.getBreadcrumbs().get(0).getMessage().contains("[redacted]"));
        assertEquals("[redacted]", event.getTags().get("file_name"));
        assertEquals("[redacted]", event.getTags().get("safe_key"));
        assertNull(event.getTags().get("nullable_value"));
    }
}
