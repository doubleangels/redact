package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

@RunWith(RobolectricTestRunner.class)
public class SentryManagerTest {

    @Test
    public void testIgnoredErrors() {
        assertTrue(SentryManager.isIgnored(new UnknownHostException("Offline")));
        assertTrue(SentryManager.isIgnored(new SocketTimeoutException("Timeout")));
        assertFalse(SentryManager.isIgnored(new NullPointerException("Crash")));
    }

    @Test
    public void testSetCustomKeyDoesNotCrashUninitialized() {
        // Sentry is designed to drop data silently via NoOp logger if Sentry Android isn't initialized yet
        // This ensures the custom wrapper safely maps primitives without throwing cast exceptions
        SentryManager.setCustomKey("key1", "value1");
        SentryManager.setCustomKey("key2", true);
        SentryManager.setCustomKey("key3", 5.0f);
        
        SentryManager.log("Test breadcrumb");
    }
}
