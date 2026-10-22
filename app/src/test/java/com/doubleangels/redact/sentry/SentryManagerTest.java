package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.doubleangels.redact.media.ProcessingCancelledException;

import org.junit.Test;

import java.io.IOException;

public class SentryManagerTest {

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
    public void isUserCancellation_ignoresRealFailures() {
        assertFalse(SentryManager.isUserCancellation(new IOException("Video conversion timed out")));
        assertFalse(SentryManager.isUserCancellation(new IOException("Video conversion failed: codec")));
    }
}
