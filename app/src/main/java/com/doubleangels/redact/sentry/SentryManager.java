package com.doubleangels.redact.sentry;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.media.ProcessingCancelledException;
import com.doubleangels.redact.media.UnsupportedMediaException;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import io.sentry.Breadcrumb;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.NoOpTransaction;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryAttribute;
import io.sentry.SentryAttributes;
import io.sentry.SentryLevel;
import io.sentry.metrics.MetricsUnit;
import io.sentry.metrics.SentryMetricsParameters;
import io.sentry.protocol.Feedback;
import io.sentry.protocol.SentryId;

/**
 * Crashlytics-style helpers: breadcrumbs, structured logs, metrics, scoped tags, and exception capture.
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
            "items_failed",
            "success",
            "file_size_mb",
            "metadata_verification_passed",
            "preserved_exif_count",
            "exif_tags_removed",
            "bitmap_sample_size",
            "original_width",
            "original_height",
            "video_metadata_verification_passed",
            "video_verify_failed_key",
            "permissions_granted",
            "selected_media_count",
            "selected_items_count",
            "persistable_uri_permission",
            "processing_state",
            "processed_items",
            "processing_items_count",
            "has_shown_rationale",
            "all_permissions_granted",
            "has_storage_permission"));

    /**
     * Low-cardinality keys that describe one scan, clean or convert. They are kept per thread, not
     * on the shared scope, so a Scan running during a Clean can't tag the other job's events, and
     * a later unrelated event doesn't carry a finished job's {@code success} or {@code error_type}.
     */
    private static final Set<String> OPERATION_TAG_KEYS = new HashSet<>(Arrays.asList(
            "operation_type",
            "is_video",
            "mime_type",
            "media_type",
            "extraction_failed",
            "error_type",
            "has_location_data",
            "video_rotation",
            "image_orientation",
            "processing_failed",
            "processing_success",
            "success",
            "metadata_verification_passed",
            "video_metadata_verification_passed",
            "video_verify_failed_key"));

    /**
     * Per-item measurements. They have too many distinct values to be useful as indexed tags, so
     * they go into the event's {@value #OPERATION_CONTEXT} context instead.
     */
    private static final Set<String> OPERATION_CONTEXT_KEYS = new HashSet<>(Arrays.asList(
            "sections_count",
            "image_width",
            "image_height",
            "video_width",
            "video_height",
            "video_duration_ms",
            "video_frame_rate",
            "video_bitrate_kbps",
            "audio_sample_rate",
            "file_size_mb",
            "preserved_exif_count",
            "exif_tags_removed",
            "bitmap_sample_size",
            "original_width",
            "original_height"));

    static final String OPERATION_CONTEXT = "operation";

    /** The current thread's job data; each job runs on its own worker thread. */
    private static final ThreadLocal<Map<String, String>> OPERATION_DATA =
            ThreadLocal.withInitial(LinkedHashMap::new);

    /** Feedback longer than this is cut, so a pasted log can't make an oversized item. */
    static final int MAX_FEEDBACK_LENGTH = 1000;

    /** Event ID of the last error sent, so the user can attach feedback to it. */
    @Nullable
    private static volatile SentryId lastReportedEventId;

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
        return isUserCancellation(e);
    }

    /**
     * True when video/clean work was stopped by the user or lifecycle, not a real failure. Matches
     * cancellation types only: a library error that merely mentions "cancelled" or "interrupted"
     * in its message is still a real failure and is reported.
     */
    public static boolean isUserCancellation(Throwable e) {
        // Bounded walk: a cyclic cause chain must not hang error handling.
        Throwable t = e;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof ProcessingCancelledException
                    || t instanceof InterruptedException
                    || t instanceof java.util.concurrent.CancellationException
                    || t instanceof android.os.OperationCanceledException) {
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
        Sentry.logger().info("%s: %s", safeCategory, safeMessage);
    }

    /** Counter metric with no attributes. */
    public static void count(String name, double value) {
        if (!isEnabled() || name == null || name.isEmpty()) {
            return;
        }
        Sentry.metrics().count(name, value);
    }

    /** Counter metric with one allowed attribute dimension. */
    public static void count(String name, double value, String attrKey, String attrValue) {
        SentryMetricsParameters params = metricsParams(attrKey, attrValue);
        if (!isEnabled() || params == null) {
            return;
        }
        Sentry.metrics().count(name, value, null, params);
    }

    /** Counter metric with two allowed attribute dimensions. */
    public static void count(
            String name, double value, String attrKey1, String attrValue1, String attrKey2, String attrValue2) {
        SentryMetricsParameters params = metricsParams(attrKey1, attrValue1, attrKey2, attrValue2);
        if (!isEnabled() || params == null) {
            return;
        }
        Sentry.metrics().count(name, value, null, params);
    }

    /** Distribution metric (dimensionless). */
    public static void distribution(String name, double value) {
        if (!isEnabled() || name == null || name.isEmpty()) {
            return;
        }
        Sentry.metrics().distribution(name, value);
    }

    /** Distribution metric with one allowed attribute dimension. */
    public static void distribution(String name, double value, String attrKey, String attrValue) {
        SentryMetricsParameters params = metricsParams(attrKey, attrValue);
        if (!isEnabled() || params == null) {
            return;
        }
        Sentry.metrics().distribution(name, value, null, params);
    }

    /** Duration distribution in milliseconds with one allowed attribute dimension. */
    public static void distributionDurationMs(String name, double millis, String attrKey, String attrValue) {
        SentryMetricsParameters params = metricsParams(attrKey, attrValue);
        if (!isEnabled() || params == null) {
            return;
        }
        Sentry.metrics().distribution(name, millis, MetricsUnit.Duration.MILLISECOND, params);
    }

    /** Point-in-time gauge (dimensionless). */
    public static void gauge(String name, double value) {
        if (!isEnabled() || name == null || name.isEmpty()) {
            return;
        }
        Sentry.metrics().gauge(name, value);
    }

    /** @deprecated Use {@link #logEvent(String, String)} with non-identifying messages. */
    @Deprecated
    public static void log(String message) {
        logEvent("custom", SentryPrivacyScrubber.scrub(message));
    }

    /**
     * Sends {@code e} to Sentry. Returns the event ID, or null when nothing was sent (unsupported
     * media, an ignored or cancelled error, or crash reporting off).
     */
    @Nullable
    public static SentryId recordException(Throwable e) {
        if (UnsupportedMediaException.isCauseOf(e)) {
            Log.i(TAG, "Unsupported media, not sending:", e);
            logEvent("media", "An item in an unsupported format was skipped.");
            return null;
        }
        if (isIgnored(e)) {
            if (isUserCancellation(e)) {
                Log.i(TAG, "User cancellation, not sending:", e);
            } else {
                Log.e(TAG, "Ignored error:", e);
            }
            return null;
        }
        if (!isEnabled()) {
            Log.e(TAG, "Crash reporting disabled, not sending:", e);
            return null;
        }
        // Sent as-is so the issue keeps the real exception type and grouping; beforeSend scrubs the
        // message of every exception in the cause chain.
        SentryId id = Sentry.captureException(e);
        if (id == null || SentryId.EMPTY_ID.equals(id)) {
            return null;
        }
        lastReportedEventId = id;
        return id;
    }

    /** Event ID of the last error sent, or null when none has been sent since the app started. */
    @Nullable
    public static SentryId lastReportedEventId() {
        return lastReportedEventId;
    }

    /**
     * The last error sent if it is newer than {@code before} (a {@link #lastReportedEventId()}
     * read when a job started), or null when the job sent nothing.
     */
    @Nullable
    public static SentryId reportedSince(@Nullable SentryId before) {
        SentryId last = lastReportedEventId;
        return last != null && !last.equals(before) ? last : null;
    }

    /**
     * Sends what the user wrote about the error {@code eventId}, scrubbed like every other text,
     * so it shows on that issue in Sentry. Returns false when nothing was sent.
     */
    public static boolean sendFeedback(@Nullable String message, @Nullable SentryId eventId) {
        if (!isEnabled() || message == null) {
            return false;
        }
        String safe = SentryPrivacyScrubber.scrub(message.trim());
        if (safe.isEmpty()) {
            return false;
        }
        if (safe.length() > MAX_FEEDBACK_LENGTH) {
            safe = safe.substring(0, MAX_FEEDBACK_LENGTH);
        }
        Feedback feedback = new Feedback(safe);
        if (eventId != null) {
            feedback.setAssociatedEventId(eventId);
        }
        SentryId sent = Sentry.captureFeedback(feedback);
        return sent != null && !SentryId.EMPTY_ID.equals(sent);
    }

    /**
     * Starts a job on the current thread: drops the previous job's data from this thread and
     * records the new job's type. Call it on the thread that does the work.
     */
    public static void beginOperation(String operationType) {
        OPERATION_DATA.get().clear();
        setCustomKey("operation_type", operationType);
    }

    /**
     * Adds the capturing thread's job data to {@code event}: low-cardinality keys as tags, the
     * rest in the {@value #OPERATION_CONTEXT} context. Runs as an event processor, which Sentry
     * calls on the thread that captured the event (for a crash, the thread that crashed).
     */
    static void applyOperationData(@Nullable SentryEvent event) {
        Map<String, String> data = OPERATION_DATA.get();
        if (event == null || data.isEmpty()) {
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (OPERATION_CONTEXT_KEYS.contains(entry.getKey())) {
                context.put(entry.getKey(), entry.getValue());
            } else {
                event.setTag(entry.getKey(), entry.getValue());
            }
        }
        if (!context.isEmpty()) {
            event.getContexts().put(OPERATION_CONTEXT, context);
        }
    }

    private static boolean isOperationKey(@NonNull String lowerKey) {
        return OPERATION_TAG_KEYS.contains(lowerKey)
                || OPERATION_CONTEXT_KEYS.contains(lowerKey)
                || lowerKey.startsWith("video_verify_");
    }

    public static void setCustomKey(String key, String value) {
        if (!isEnabled() || !isAllowedTagKey(key)) {
            return;
        }
        String v = value != null ? SentryPrivacyScrubber.scrub(value) : "";
        if (v.length() > 200) {
            v = v.substring(0, 200);
        }
        final String tagKey = key.toLowerCase(Locale.US);
        final String tagVal = v;
        if (isOperationKey(tagKey)) {
            OPERATION_DATA.get().put(tagKey, tagVal);
            return;
        }
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
        String lower = key.toLowerCase(Locale.US);
        if (ALLOWED_TAG_KEYS.contains(lower)) {
            return true;
        }
        return lower.startsWith("permission_")
                || lower.startsWith("video_verify_")
                || lower.startsWith("can_ask_");
    }

    @Nullable
    private static SentryMetricsParameters metricsParams(String attrKey, String attrValue) {
        if (!isAllowedTagKey(attrKey)) {
            return null;
        }
        String safe = SentryPrivacyScrubber.scrub(attrValue != null ? attrValue : "");
        return SentryMetricsParameters.create(
                SentryAttributes.of(SentryAttribute.stringAttribute(attrKey, safe)));
    }

    @Nullable
    private static SentryMetricsParameters metricsParams(
            String attrKey1, String attrValue1, String attrKey2, String attrValue2) {
        if (!isAllowedTagKey(attrKey1) || !isAllowedTagKey(attrKey2)) {
            return null;
        }
        return SentryMetricsParameters.create(
                SentryAttributes.of(
                        SentryAttribute.stringAttribute(
                                attrKey1, SentryPrivacyScrubber.scrub(attrValue1 != null ? attrValue1 : "")),
                        SentryAttribute.stringAttribute(
                                attrKey2, SentryPrivacyScrubber.scrub(attrValue2 != null ? attrValue2 : ""))));
    }

    public static ITransaction startTransaction(String name, String operation) {
        if (!isEnabled()) {
            return NoOpTransaction.getInstance();
        }
        return Sentry.startTransaction(name, operation);
    }
}
