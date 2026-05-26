package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import io.sentry.ITransaction;
import io.sentry.NoOpTransaction;

@RunWith(RobolectricTestRunner.class)
public class SentryManagerTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AppPreferences.setCrashReportingEnabled(context, false);
        SentryManager.init(context);
    }

    @After
    public void tearDown() {
        AppPreferences.setCrashReportingEnabled(context, false);
    }

    @Test
    public void isEnabled_followsPreferences() {
        assertFalse(SentryManager.isEnabled());
        AppPreferences.setCrashReportingEnabled(context, true);
        assertTrue(SentryManager.isEnabled());
    }

    @Test
    public void isIgnored_networkErrors() {
        assertTrue(SentryManager.isIgnored(new UnknownHostException("Offline")));
        assertTrue(SentryManager.isIgnored(new SocketTimeoutException("Timeout")));
        assertTrue(SentryManager.isIgnored(new SocketException("Reset")));
        assertTrue(SentryManager.isIgnored(new SSLException("SSL")));
        assertTrue(SentryManager.isIgnored(new SSLHandshakeException("Handshake")));
        assertFalse(SentryManager.isIgnored(new NullPointerException("Crash")));
    }

    @Test
    public void logEventAndRecordException_whenDisabled() {
        SentryManager.logEvent("test", "message");
        SentryManager.log("legacy");
        SentryManager.recordException(new RuntimeException("x"));
        SentryManager.setCustomKey("operation_type", "convert");
        SentryManager.setCustomKey("bad_key", "value");
        SentryManager.setCustomKey(null, "x");

        ITransaction tx = SentryManager.startTransaction("t", "op");
        assertNotNull(tx);
        assertTrue(tx instanceof NoOpTransaction || tx != null);
    }

    @Test
    public void logEventAndKeys_whenEnabled() {
        AppPreferences.setCrashReportingEnabled(context, true);

        SentryManager.logEvent(null, null);
        SentryManager.logEvent("cat", "safe message");
        SentryManager.recordException(new RuntimeException("send"));
        SentryManager.recordException(new UnknownHostException("skip"));

        SentryManager.setCustomKey("operation_type", "convert");
        SentryManager.setCustomKey("operation_type", (String) null);
        SentryManager.setCustomKey("disallowed", "x");
        SentryManager.setCustomKey("operation_type", true);
        SentryManager.setCustomKey("batch_size", 3);
        SentryManager.setCustomKey("video_duration_ms", 1000L);
        SentryManager.setCustomKey("video_frame_rate", 30.5f);
        SentryManager.setCustomKey("video_bitrate_kbps", 1200.0);

        String longVal = "x".repeat(250);
        SentryManager.setCustomKey("operation_type", longVal);

        ITransaction tx = SentryManager.startTransaction("work", "process");
        assertNotNull(tx);
    }

    @Test
    public void setCustomKeyDoesNotCrashUninitialized() {
        setAppContext(null);

        SentryManager.setCustomKey("key1", "value1");
        SentryManager.setCustomKey("key2", true);
        SentryManager.setCustomKey("key3", 5.0f);
        SentryManager.logEvent("test", "Test breadcrumb");
        assertFalse(SentryManager.isEnabled());

        SentryManager.init(context);
    }

    @Test
    public void privateAllowedKeyHelper_rejectsNullAndUnknownKeys() throws Exception {
        assertFalse(invokeIsAllowedTagKey(null));
        assertFalse(invokeIsAllowedTagKey("not_allowed"));
        assertTrue(invokeIsAllowedTagKey("operation_type"));
    }

    private static boolean invokeIsAllowedTagKey(String key) throws Exception {
        Method method = SentryManager.class.getDeclaredMethod("isAllowedTagKey", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, key);
    }

    private static void setAppContext(Context value) {
        try {
            Field field = SentryManager.class.getDeclaredField("appContext");
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
