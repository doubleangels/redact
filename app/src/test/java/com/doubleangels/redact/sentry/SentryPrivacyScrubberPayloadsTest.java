package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.sentry.Breadcrumb;
import io.sentry.SentryAttributeType;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryMetricsEvent;
import io.sentry.SpanId;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.SentryThread;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.TransactionInfo;
import io.sentry.protocol.User;

/** Event, transaction, breadcrumb and metric payloads must come out without URIs, paths or coordinates. */
public class SentryPrivacyScrubberPayloadsTest {

    private static final String LEAK = "content://media/external/images/media/42";

    private static void assertRedacted(Object value) {
        assertTrue(String.valueOf(value), String.valueOf(value).contains("[redacted]"));
        assertFalse(String.valueOf(value), String.valueOf(value).contains("content://"));
    }

    // ---- scrub(String) ------------------------------------------------------------------

    @Test
    public void scrub_handlesNullEmptyAndOtherPatterns() {
        assertNull(SentryPrivacyScrubber.scrub(null));
        assertEquals("", SentryPrivacyScrubber.scrub(""));
        assertRedacted(SentryPrivacyScrubber.scrub("opened " + LEAK));
        assertFalse(SentryPrivacyScrubber.scrub("see file:///sdcard/a.jpg now").contains("file://"));
        assertFalse(SentryPrivacyScrubber.scrub("at /storage/emulated/0/DCIM/x").contains("/storage/"));
        assertFalse(SentryPrivacyScrubber.scrub("at /data/user/0/app/files/x").contains("/data/user"));
        assertFalse(SentryPrivacyScrubber.scrub("C:\\Users\\me\\pic.jpg").contains("Users"));
        assertFalse(SentryPrivacyScrubber.scrub("latitude=40.5 longitude: -73.9").contains("40.5"));
        assertFalse(SentryPrivacyScrubber.scrub("video +39.6594-104.9620 end").contains("39.6594"));
        assertFalse(SentryPrivacyScrubber.scrub("resolved processed file holiday.jpg").contains("holiday"));
        assertFalse(SentryPrivacyScrubber.scrub("failed on IMG_2024.mp4 today").contains("IMG_2024"));
    }

    @Test
    public void scrub_truncatesVeryLongText() {
        String scrubbed = SentryPrivacyScrubber.scrub("a".repeat(900));
        assertEquals(501, scrubbed.length());
        assertTrue(scrubbed.endsWith("…"));
    }

    // ---- events -----------------------------------------------------------------------------

    @Test
    public void scrubEvent_cleansMessageExceptionsTagsExtrasContextsAndUser() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage("Failed " + LEAK);
        event.setMessage(message);

        SentryException exception = new SentryException();
        exception.setValue("could not read " + LEAK);
        exception.setModule("com.example/" + LEAK);
        SentryStackFrame frame = new SentryStackFrame();
        frame.setFilename(LEAK);
        frame.setAbsPath("/storage/emulated/0/secret.jpg");
        frame.setModule(LEAK);
        frame.setFunction("load " + LEAK);
        exception.setStacktrace(new SentryStackTrace(Arrays.asList(frame)));
        event.setExceptions(new ArrayList<>(Arrays.asList(exception)));

        Breadcrumb crumb = new Breadcrumb("opened " + LEAK);
        crumb.setData("uri", LEAK);
        event.setBreadcrumbs(new ArrayList<>(Arrays.asList(crumb)));

        event.setTag("file_name", "holiday.jpg");
        event.setTag("note", "saw " + LEAK);

        SentryThread thread = new SentryThread();
        SentryStackFrame threadFrame = new SentryStackFrame();
        threadFrame.setFilename(LEAK);
        thread.setStacktrace(new SentryStackTrace(Arrays.asList(threadFrame)));
        event.setThreads(new ArrayList<>(Arrays.asList(thread)));

        event.setExtra("path", LEAK);
        Map<String, Object> nested = new HashMap<>();
        nested.put("uri", LEAK);
        event.setExtra("nested", nested);
        event.setExtra("list", new ArrayList<>(Arrays.asList(LEAK, 5)));
        event.setExtra("array", new Object[] {LEAK, 7});
        event.setExtra("number", 12);

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("where", LEAK);
        event.getContexts().put("custom", ctx);

        User user = new User();
        user.setUsername("u " + LEAK);
        user.setEmail("e " + LEAK);
        user.setIpAddress("ip " + LEAK);
        event.setUser(user);

        SentryPrivacyScrubber.scrubEvent(event);

        assertRedacted(event.getMessage().getMessage());
        assertRedacted(exception.getValue());
        assertRedacted(exception.getModule());
        assertRedacted(frame.getFilename());
        assertRedacted(frame.getAbsPath());
        assertRedacted(frame.getModule());
        assertRedacted(frame.getFunction());
        assertRedacted(crumb.getMessage());
        assertRedacted(crumb.getData().get("uri"));
        assertEquals("[redacted]", event.getTags().get("file_name"));
        assertRedacted(event.getTags().get("note"));
        assertRedacted(threadFrame.getFilename());
        assertRedacted(event.getExtras().get("path"));
        assertRedacted(nested.get("uri"));
        assertRedacted(((List<?>) event.getExtras().get("list")).get(0));
        assertEquals(5, ((List<?>) event.getExtras().get("list")).get(1));
        assertRedacted(((Object[]) event.getExtras().get("array"))[0]);
        assertEquals(12, event.getExtras().get("number"));
        assertRedacted(ctx.get("where"));
        assertRedacted(user.getUsername());
        assertRedacted(user.getEmail());
        assertRedacted(user.getIpAddress());
    }

    @Test
    public void scrubEvent_toleratesNullAndSparseEvents() {
        SentryPrivacyScrubber.scrubEvent(null);
        SentryEvent empty = new SentryEvent();
        SentryPrivacyScrubber.scrubEvent(empty);
        SentryException bare = new SentryException();
        empty.setExceptions(new ArrayList<>(Arrays.asList(bare)));
        SentryPrivacyScrubber.scrubEvent(empty);
        assertNull(bare.getValue());
    }

    // ---- transactions -----------------------------------------------------------------------

    @Test
    public void scrubTransaction_cleansSpansTagsExtrasContextsAndUnknown() {
        Map<String, String> spanTags = new HashMap<>();
        spanTags.put("uri", LEAK);
        spanTags.put("op", "see " + LEAK);
        Map<String, Object> spanData = new HashMap<>();
        spanData.put("source", LEAK);
        SentrySpan span = new SentrySpan(
                1.0, 2.0, new SentryId(), new SpanId(), null, "op", "desc", null, "manual",
                spanTags, new HashMap<>(), spanData);

        SentryTransaction tx = new SentryTransaction(
                "tx", 1.0, 2.0, new ArrayList<>(Arrays.asList(span)), new HashMap<>(),
                new TransactionInfo("custom"));
        tx.setTag("filename", "x.jpg");
        tx.setTag("note", LEAK);
        tx.setExtra("where", LEAK);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("loc", LEAK);
        tx.getContexts().put("custom", ctx);
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("raw", LEAK);
        tx.setUnknown(unknown);

        SentryPrivacyScrubber.scrubTransaction(tx);

        assertEquals("[redacted]", span.getTags().get("uri"));
        assertRedacted(span.getTags().get("op"));
        assertRedacted(span.getData().get("source"));
        assertEquals("[redacted]", tx.getTags().get("filename"));
        assertRedacted(tx.getTags().get("note"));
        assertRedacted(tx.getExtras().get("where"));
        assertRedacted(ctx.get("loc"));
        assertRedacted(tx.getUnknown().get("raw"));
        SentryPrivacyScrubber.scrubTransaction(null);
    }

    // ---- breadcrumbs / logs / metrics -----------------------------------------------------

    @Test
    public void scrubBreadcrumb_handlesNullAndBareBreadcrumbs() {
        SentryPrivacyScrubber.scrubBreadcrumb(null);
        Breadcrumb bare = new Breadcrumb();
        SentryPrivacyScrubber.scrubBreadcrumb(bare);
        assertNull(bare.getMessage());
    }

    @Test
    public void scrubMetric_dropsSensitiveAndPiiAttributesAndScrubsStrings() {
        SentryMetricsEvent metric = new SentryMetricsEvent(new SentryId(), (Double) 1.0, "count", "counter", (Double) 1.0);
        metric.setAttribute("user.id",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "me"));
        metric.setAttribute("file_name",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "a.jpg"));
        metric.setAttribute("source",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "from " + LEAK));
        metric.setAttribute("count_value",
                new SentryLogEventAttributeValue(SentryAttributeType.INTEGER, 3));

        SentryPrivacyScrubber.scrubMetric(metric);
        SentryPrivacyScrubber.scrubMetric(null);

        assertFalse(metric.getAttributes().containsKey("user.id"));
        assertFalse(metric.getAttributes().containsKey("file_name"));
        assertRedacted(metric.getAttributes().get("source").getValue());
        assertEquals(3, metric.getAttributes().get("count_value").getValue());
    }
}
