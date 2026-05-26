package com.doubleangels.redact.sentry;

import android.content.Context;
import android.util.Log;

import com.doubleangels.redact.AppPreferences;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import io.sentry.Breadcrumb;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.NoOpTransaction;
import io.sentry.Sentry;
import io.sentry.SentryLevel;

/**
 * Crashlytics-style helpers: breadcrumbs, scoped tags, and exception capture.
 * Network-related errors that are usually environmental are not sent to Sentry.
 * User-derived strings (URIs, paths, filenames, GPS) are blocked or scrubbed.
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

    /** Tags that may be set without user-identifying values. */
    private static final Set<String> ALLOWED_TAG_KEYS = new HashSet<>(Arrays.asList(
            "operation_type",
            "is_video",
            "mime_type",
            "media_type",
            "extraction_failed",
            "error_type",
            "sections_count",
            "has_location_permission",
            "has_location_data",
            "has_image_permission",
            "has_video_permission",
            "has_user_selected_permission",
            "needs_permissions",
            "device_sdk",
            "app_package",
            "app_started",
            "theme_mode",
            "app_version",
            "permission_request_code",
            "notifications_enabled",
            "clean_notifications_enabled",
            "convert_notifications_enabled",
            "crash_reporting_enabled",
            "intent_action",
            "intent_type",
            "unsupported_type",
            "unsupported_action",
            "media_count",
            "image_width",
            "image_height",
            "video_width",
            "video_height",
            "video_duration_ms",
            "video_rotation",
            "video_frame_rate",
            "video_bitrate_kbps",
            "audio_sample_rate",
            "image_orientation",
            "processing_failed",
            "processing_success",
            "batch_size",
            "items_processed",
            "items_failed"));

    private SentryManager() {
    }

    /**
     * Called once from
     * {@link com.doubleangels.redact.RedactApplication#onCreate()}.
     */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /** Returns true when the user has not opted out of crash reporting. */
    public static boolean isEnabled() {
        if (appContext == null) {
            return false;
        }
        return AppPreferences.isCrashReportingEnabled(appContext);
    }

    public static boolean isIgnored(Throwable e) {
        for (Class<? extends Throwable> ignored : IGNORED_ERRORS) {
            if (ignored.isInstance(e)) {
                return true;
            }
        }
        return false;
    }

    /** Fixed-category breadcrumb; message must not contain user data. */
    public static void logEvent(String category, String message) {
        String safeCategory = category != null ? category : "app";
        String safeMessage = SentryPrivacyScrubber.scrub(message != null ? message : "");
        Log.i(TAG, safeCategory + ": " + safeMessage);
        if (!isEnabled()) {
            return;
        }
        Breadcrumb b = new Breadcrumb();
        b.setMessage(safeMessage);
        b.setCategory(safeCategory);
        b.setLevel(SentryLevel.INFO);
        Sentry.addBreadcrumb(b);
    }

    /** @deprecated Use {@link #logEvent(String, String)} with non-identifying messages. */
    @Deprecated
    public static void log(String message) {
        logEvent("custom", SentryPrivacyScrubber.scrub(message));
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
        if (!isEnabled() || !isAllowedTagKey(key)) {
            return;
        }
        String v = value != null ? SentryPrivacyScrubber.scrub(value) : "";
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

    private static boolean isAllowedTagKey(String key) {
        if (key == null) {
            return false;
        }
        return ALLOWED_TAG_KEYS.contains(key.toLowerCase(Locale.US));
    }

    public static ITransaction startTransaction(String name, String operation) {
        if (!isEnabled()) {
            return NoOpTransaction.getInstance();
        }
        return Sentry.startTransaction(name, operation);
    }
}
