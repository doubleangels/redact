package com.doubleangels.redact.sentry;

import android.util.Log;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;

/**
 * Crashlytics-style helpers: breadcrumbs, scoped tags, and exception capture.
 * Network-related errors that are usually environmental are not sent to Sentry.
 */
public final class SentryManager {

    private static final String TAG = "SentryManager";

    private static final List<Class<? extends Throwable>> IGNORED_ERRORS = Arrays.asList(
            UnknownHostException.class,
            SocketTimeoutException.class,
            SocketException.class,
            SSLException.class,
            SSLHandshakeException.class);

    private SentryManager() {
    }

    public static boolean isIgnored(Throwable e) {
        for (Class<? extends Throwable> ignored : IGNORED_ERRORS) {
            if (ignored.isInstance(e)) {
                return true;
            }
        }
        return false;
    }

    public static void log(String message) {
        Breadcrumb b = new Breadcrumb();
        b.setMessage(message);
        b.setCategory("custom");
        b.setLevel(SentryLevel.DEBUG);
        Sentry.addBreadcrumb(b);
    }

    public static void recordException(Throwable e) {
        if (isIgnored(e)) {
            Log.e(TAG, "Ignored error:", e);
            return;
        }
        Sentry.captureException(e);
    }

    public static void setCustomKey(String key, String value) {
        String v = value != null ? value : "";
        if (v.length() > 200) {
            v = v.substring(0, 200);
        }
        final String tagKey = key;
        final String tagVal = v;
        Sentry.configureScope(scope -> scope.setTag(tagKey, tagVal));
    }

    public static void setCustomKey(String key, boolean value) {
        setCustomKey(key, String.valueOf(value));
    }

    public static void setCustomKey(String key, int value) {
        setCustomKey(key, String.valueOf(value));
    }

    public static void setCustomKey(String key, long value) {
        setCustomKey(key, String.valueOf(value));
    }

    public static void setCustomKey(String key, float value) {
        setCustomKey(key, String.valueOf(value));
    }

    public static void setCustomKey(String key, double value) {
        setCustomKey(key, String.valueOf(value));
    }
}
