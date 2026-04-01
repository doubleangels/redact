package com.doubleangels.redact.sentry;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import io.sentry.Breadcrumb;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryLevel;

/**
 * Crashlytics-style helpers: breadcrumbs, scoped tags, and exception capture.
 * Network-related errors that are usually environmental are not sent to Sentry.
 */
public final class SentryManager {

    private static final String TAG = "SentryManager";

    /** Application context set once from RedactApplication.onCreate(). */
    private static Context appContext;

    private static final List<Class<? extends Throwable>> IGNORED_ERRORS = Arrays.asList(
            UnknownHostException.class,
            SocketTimeoutException.class,
            SocketException.class,
            SSLException.class,
            SSLHandshakeException.class);

    private SentryManager() {
    }

    /** Called once from {@link com.doubleangels.redact.RedactApplication#onCreate()}. */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /** Returns true when the user has not opted out of crash reporting. */
    private static boolean isEnabled() {
        if (appContext == null) return true;
        SharedPreferences prefs = appContext.getSharedPreferences(
                com.doubleangels.redact.SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(
                com.doubleangels.redact.SettingsFragment.KEY_CRASH_REPORTING_ENABLED, true);
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
        Log.i(TAG, message);
        if (!isEnabled()) return;
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
        if (!isEnabled()) {
            Log.e(TAG, "Crash reporting disabled — not sending:", e);
            return;
        }
        Sentry.captureException(e);
    }

    public static void setCustomKey(String key, String value) {
        if (!isEnabled()) return;
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

    public static ITransaction startTransaction(String name, String operation) {
        if (!isEnabled()) return Sentry.startTransaction(name, operation); // no-op transaction still needed
        return Sentry.startTransaction(name, operation);
    }
}
