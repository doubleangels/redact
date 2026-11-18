package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.doubleangels.redact.media.ProcessingCancelledException;

import org.junit.Test;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;

public class SentryManagerTest {

    /** Every metric attribute key must be on the allow-list, and pairs must be whole. */
    @Test
    public void metricAttributes_areAllowListedPairs() {
        assertNotNull(SentryManager.metricsParams("operation_type", "clean"));
        assertNotNull(SentryManager.metricsParams("operation_type", "clean", "media_type", "image",
                "outcome", "failed", "error_type", "IOException"));
        assertNotNull("a missing value is sent empty", SentryManager.metricsParams("is_video", null));
        assertNull("a file name is never a dimension", SentryManager.metricsParams("file_name", "a.jpg"));
        assertNull("one bad key drops the lot",
                SentryManager.metricsParams("operation_type", "clean", "uri", "content://x"));
        assertNull("a value without its key", SentryManager.metricsParams("operation_type"));
        assertNull(SentryManager.metricsParams());
        assertNull(SentryManager.metricsParams((String[]) null));
    }

    @Test
    public void isUserCancellation_detectsInterruptedCause() {
        IOException wrapped =
                new IOException("Video conversion interrupted", new InterruptedException());
        assertTrue(SentryManager.isUserCancellation(wrapped));
        assertTrue(SentryManager.isIgnored(wrapped));
    }

    @Test
    public void isUserCancellation_detectsTheAppsCancellationType() {
        assertTrue(SentryManager.isUserCancellation(
                new RuntimeException(new ProcessingCancelledException("Processing cancelled"))));
        assertFalse("a message alone is not a cancellation",
                SentryManager.isUserCancellation(new IOException("Processing cancelled")));
    }

    @Test
    public void isUserCancellation_detectsAnInterruptedFileChannel() {
        // A share cancelled while a file channel is reading surfaces this, not InterruptedException.
        IOException wrapped = new IOException(new ClosedByInterruptException());
        assertTrue(SentryManager.isUserCancellation(new ClosedByInterruptException()));
        assertTrue(SentryManager.isUserCancellation(wrapped));
        assertTrue(SentryManager.isIgnored(wrapped));
        assertFalse("a channel closed for another reason is a real failure",
                SentryManager.isUserCancellation(new ClosedChannelException()));
    }

    @Test
    public void isUserCancellation_ignoresRealFailures() {
        assertFalse(SentryManager.isUserCancellation(new IOException("Video conversion timed out")));
        assertFalse(SentryManager.isUserCancellation(new IOException("Video conversion failed: codec")));
    }
}
