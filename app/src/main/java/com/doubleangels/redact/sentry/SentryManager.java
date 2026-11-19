package com.doubleangels.redact.sentry;

import android.content.Context;
import android.os.OperationCanceledException;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.BuildConfig;
import com.doubleangels.redact.media.CleanFailedException;
import com.doubleangels.redact.media.MediaSizeLimits;
import com.doubleangels.redact.media.ProcessingCancelledException;
import com.doubleangels.redact.media.UnsupportedMediaException;

import io.sentry.Breadcrumb;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.NoOpTransaction;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryAttribute;
import io.sentry.SentryAttributes;
import io.sentry.SentryLevel;
import io.sentry.SpanStatus;
import io.sentry.metrics.MetricsUnit;
import io.sentry.metrics.SentryMetricsParameters;
import io.sentry.protocol.Feedback;
import io.sentry.protocol.SentryId;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

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

    /*
     * Naming, which SentryNamingTest enforces for tags, metric attributes, metric names and
     * breadcrumb categories alike:
     *
     *   - A key that describes state is "<group>.<field>": job.type, file.mime_type,
     *     video.codec, permission.images, setting.strict_clean, device.low_ram. The group is one
     *     of SentryNamingTest.GROUPS; the field is lower snake_case. A boolean field is named for
     *     what is true (images, has_audio, request_needed, battery_saver_on); a banded value ends in
     *     _class, a count in _count. At most 32 characters, Sentry's limit for a tag key.
     *   - A key that describes what happened is one of three bare words, the same in every tag
     *     and metric: outcome (success, failed, ...), reason (why, in a fixed word) and error_type
     *     (an exception's simple class name).
     *   - No key mentions a path, URI, file name or location: the scrubber blanks such keys, so
     *     GPS is "gps".
     *   - A metric is "<area>.<event>" when it counts and "<area>.<event>.<measure>" when it is a
     *     distribution. The name says exactly what one count or one value is, so it reads without
     *     the code: job.file_outcome is one file a job finished, job.batch_start one job starting,
     *     job.file.duration how long one file took, job.batch.file_count how many files one job
     *     was given. What came of it goes in outcome; a unit goes in the metric's unit, never in
     *     the name.
     *   - A transaction or span is "<area>.<event>" naming the work it times, as a plain phrase:
     *     verify.image_metadata_check, video.fast_remux, share.inbound_copy.
     *   - A breadcrumb category is a lower-case area, optionally ".<sub>" (clean, app.lifecycle),
     *     like the SDK's own (ui.click, navigation).
     */

    /**
     * Low-cardinality keys that describe one scan, clean or convert. They are kept per thread, not
     * on the shared scope, so a Scan running during a Clean can't tag the other job's events, and
     * a later unrelated event doesn't carry a finished job's outcome or error_type.
     */
    private static final Set<String> OPERATION_TAG_KEYS = new HashSet<>(Arrays.asList(
            "job.type",
            "job.operation",
            "outcome",
            "error_type",
            "file.media_type",
            "file.mime_type",
            "file.output_mime_type",
            "file.has_gps",
            "scan.extraction_failed",
            "image.resolution_class",
            "video.resolution_class",
            "video.duration_class",
            "video.frame_rate_class",
            "video.rotation",
            "video.codec",
            "video.output_codec",
            "video.has_audio",
            "verify.image_passed",
            "verify.video_passed",
            "verify.failed_check",
            "device.thermal_status",
            "device.battery_saver_on"));

    /**
     * Per-item measurements. They have too many distinct values to be useful as indexed tags, so
     * they go into the event's {@value #OPERATION_CONTEXT} context instead.
     */
    private static final Set<String> OPERATION_CONTEXT_KEYS = new HashSet<>(Arrays.asList(
            "scan.section_count",
            "clean.kept_exif_count",
            "clean.removed_exif_count",
            "image.decode_sample_size"));

    /** Keys that describe the app as a whole, set on the shared scope, and metric-only keys. */
    private static final Set<String> SCOPE_TAG_KEYS = new HashSet<>(Arrays.asList(
            "permission.type",
            "permission.request_code",
            "permission.request_needed",
            "permission.all_granted",
            "permission.images",
            "permission.videos",
            "permission.selected_media",
            "permission.storage",
            "permission.media_gps",
            "permission.partial_media_access",
            "permission.persisted_grant",
            "permission.rationale_shown",
            "permission.gps_rationale_shown",
            "permission.settings_needed",
            "permission.gps_settings_needed",
            "permission.images_can_ask",
            "permission.videos_can_ask",
            "permission.storage_can_ask",
            "permission.gps_can_ask",
            "setting.notifications",
            "setting.clean_notifications",
            "setting.convert_notifications",
            "intent.action",
            "intent.mime_type",
            "intent.unsupported_mime_type",
            "intent.unsupported_action",
            "ui.theme",
            "clean.selected_count",
            "clean.processing_count",
            "clean.processed_count",
            "clean.screen_state",
            "share.file_count",
            "app.process_importance",
            "reason"));

    /** Every key a tag or metric attribute may use; any other is dropped. */
    private static final Set<String> ALLOWED_TAG_KEYS = new HashSet<>();

    static {
        ALLOWED_TAG_KEYS.addAll(OPERATION_TAG_KEYS);
        ALLOWED_TAG_KEYS.addAll(OPERATION_CONTEXT_KEYS);
        ALLOWED_TAG_KEYS.addAll(SCOPE_TAG_KEYS);
    }

    static final String OPERATION_CONTEXT = "operation";

    /** MIME types the app handles, sent as they are; any other is sent only as its family. */
    private static final Set<String> KNOWN_MIME_TYPES = new HashSet<>(Arrays.asList(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif",
            "image/gif", "image/bmp", "image/x-ms-bmp", "image/x-adobe-dng",
            "video/mp4", "video/quicktime", "video/webm", "video/x-matroska", "video/3gpp",
            "video/3gpp2", "video/avc", "video/hevc", "video/mpeg", "video/av01",
            "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/dolby-vision", "video/mp4v-es"));

    /** A file's MIME type for a report: a known one as it is, else "image/other", "video/other" or "other". */
    @NonNull
    public static String mimeTypeClass(@Nullable String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return "unknown";
        }
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (KNOWN_MIME_TYPES.contains(lower)) {
            return lower;
        }
        if (lower.startsWith("image/")) {
            return "image/other";
        }
        return lower.startsWith("video/") ? "video/other" : "other";
    }

    /** A picture's size for a report, as a class of its longer side rather than the exact size. */
    @NonNull
    public static String resolutionClass(long width, long height) {
        if (width <= 0 || height <= 0) {
            return "unknown";
        }
        long side = Math.max(width, height);
        if (side <= 1280) {
            return "up_to_720p";
        }
        if (side <= 1920) {
            return "1080p";
        }
        if (side <= 4096) {
            return "4k";
        }
        return side <= 8192 ? "8k" : "larger";
    }

    /** A video's frame rate for a report, as a band rather than the exact rate. */
    @NonNull
    public static String frameRateClass(int framesPerSecond) {
        if (framesPerSecond <= 0) {
            return "unknown";
        }
        if (framesPerSecond <= 30) {
            return "up_to_30";
        }
        return framesPerSecond <= 60 ? "up_to_60" : "above_60";
    }

    /** A video's length for a report, as a band rather than the exact duration. */
    @NonNull
    public static String durationClass(long durationMs) {
        if (durationMs < 0) {
            return "unknown";
        }
        if (durationMs < 60_000) {
            return "under_1_min";
        }
        return durationMs < 600_000 ? "under_10_min" : "10_min_or_more";
    }

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

    /**
     * An exception's kind for the {@code error_type} tag and metric attributes: its simple class
     * name, the same on every event and metric. The app's own exceptions get their source names,
     * since a release build renames their classes and Sentry deobfuscates stack frames, not tag
     * values.
     */
    @NonNull
    public static String errorType(@NonNull Throwable e) {
        if (e instanceof CleanFailedException) {
            return "CleanFailedException";
        }
        if (e instanceof UnsupportedMediaException) {
            return "UnsupportedMediaException";
        }
        if (e instanceof ProcessingCancelledException) {
            return "ProcessingCancelledException";
        }
        if (e instanceof MediaSizeLimits.TooLargeException) {
            return "TooLargeException";
        }
        return e.getClass().getSimpleName();
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
            // ClosedByInterruptException is how file channel I/O reports that its thread was
            // interrupted, which is how a share or job is cancelled.
            if (t instanceof ProcessingCancelledException
                    || t instanceof InterruptedException
                    || t instanceof ClosedByInterruptException
                    || t instanceof CancellationException
                    || t instanceof OperationCanceledException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a fixed-category message as a breadcrumb, which travels with the next error, and as a
     * structured log, which is sent on its own so slow or odd runs can be followed without a
     * crash. The message must not contain user data, and is scrubbed anyway.
     */
    public static void logEvent(String category, String message) {
        // Nothing would be sent, and release builds strip Log.i: scrubbing would be wasted work,
        // on every log line, for the users who left crash reports off (the default).
        if (!isEnabled() && !BuildConfig.DEBUG) {
            return;
        }
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
        // A template, so a '%' in the message is never read as a format specifier. The SDK sends
        // the parameters as attributes too, which scrubLog scrubs like the body.
        Sentry.logger().info("%s: %s", safeCategory, safeMessage);
    }

    /** Counter metric with no attributes. */
    public static void count(String name, double value) {
        if (!isEnabled() || name == null || name.isEmpty()) {
            return;
        }
        Sentry.metrics().count(name, value);
    }

    /**
     * Counter metric with allowed attribute dimensions, given as key, value pairs. Nothing is
     * sent when a key is not allowed or a value is missing its key.
     */
    public static void count(String name, double value, String... keysAndValues) {
        SentryMetricsParameters params = metricsParams(keysAndValues);
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

    /** Distribution metric with allowed attribute dimensions, as key, value pairs. */
    public static void distribution(String name, double value, String... keysAndValues) {
        SentryMetricsParameters params = metricsParams(keysAndValues);
        if (!isEnabled() || params == null) {
            return;
        }
        Sentry.metrics().distribution(name, value, null, params);
    }

    /** Duration distribution in milliseconds with allowed attribute dimensions, as key, value pairs. */
    public static void distributionDurationMs(String name, double millis, String... keysAndValues) {
        SentryMetricsParameters params = metricsParams(keysAndValues);
        if (!isEnabled() || params == null) {
            return;
        }
        Sentry.metrics().distribution(name, millis, MetricsUnit.Duration.MILLISECOND, params);
    }

    /** Size distribution in megabytes with allowed attribute dimensions, as key, value pairs. */
    public static void distributionMegabytes(String name, double megabytes, String... keysAndValues) {
        SentryMetricsParameters params = metricsParams(keysAndValues);
        if (!isEnabled() || params == null) {
            return;
        }
        Sentry.metrics().distribution(name, megabytes, MetricsUnit.Information.MEGABYTE, params);
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
        // The step a job is in (image_to_mediastore, video_for_sharing, censor_export...). Metrics
        // use job.type for the job itself (clean, share, convert, scan, censor).
        setCustomKey("job.operation", operationType);
        // A hot or battery-saving phone throttles the CPU and codecs: it explains slow or failed
        // video work without saying anything about the person.
        setCustomKey("device.thermal_status", thermalStatus());
        setCustomKey("device.battery_saver_on", isPowerSaveMode());
    }

    /**
     * How hot the phone is (none, light, moderate, severe, critical, emergency or shutdown), or
     * unknown.
     */
    @NonNull
    public static String thermalStatus() {
        PowerManager pm = appContext != null ? appContext.getSystemService(PowerManager.class) : null;
        if (pm == null) {
            return "unknown";
        }
        return switch (pm.getCurrentThermalStatus()) {
            case PowerManager.THERMAL_STATUS_NONE -> "none";
            case PowerManager.THERMAL_STATUS_LIGHT -> "light";
            case PowerManager.THERMAL_STATUS_MODERATE -> "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE -> "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL -> "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown";
            default -> "unknown";
        };
    }

    /** Whether Battery Saver is on. */
    public static boolean isPowerSaveMode() {
        PowerManager pm = appContext != null ? appContext.getSystemService(PowerManager.class) : null;
        return pm != null && pm.isPowerSaveMode();
    }

    /**
     * Adds the settings in effect and the phone's media capabilities ({@link AppStateTags}) to
     * {@code event}, without replacing a tag the event already has.
     */
    static void applyAppState(@Nullable SentryEvent event) {
        if (event == null) {
            return;
        }
        for (Map.Entry<String, String> tag : AppStateTags.collect(appContext).entrySet()) {
            if (event.getTag(tag.getKey()) == null) {
                event.setTag(tag.getKey(), tag.getValue());
            }
        }
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
        return OPERATION_TAG_KEYS.contains(lowerKey) || OPERATION_CONTEXT_KEYS.contains(lowerKey);
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
        // permission.<type>, where the type comes from AppMetrics.permissionType.
        return lower.matches("permission\\.[a-z_]{1,21}");
    }

    /**
     * The attributes for a metric, from key, value pairs: every key must be one the allow-list
     * names, and every value is scrubbed. Null when any key is not allowed or a value is missing.
     */
    @VisibleForTesting
    @Nullable
    static SentryMetricsParameters metricsParams(String... keysAndValues) {
        if (keysAndValues == null || keysAndValues.length == 0 || keysAndValues.length % 2 != 0) {
            return null;
        }
        SentryAttribute[] attributes = new SentryAttribute[keysAndValues.length / 2];
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (!isAllowedTagKey(keysAndValues[i])) {
                return null;
            }
            attributes[i / 2] = SentryAttribute.stringAttribute(keysAndValues[i], scrubbedValue(keysAndValues[i + 1]));
        }
        return SentryMetricsParameters.create(SentryAttributes.of(attributes));
    }

    /**
     * Metric attribute values the app writes as constants (job, media type and outcome names).
     * They can't hold anything the scrubber would remove, so they skip its regex passes, which
     * otherwise ran on every metric of every file.
     */
    private static final Set<String> FIXED_VALUES = new HashSet<>(Arrays.asList(
            AppMetrics.CLEAN, AppMetrics.SHARE, AppMetrics.CONVERT, AppMetrics.SCAN, AppMetrics.CENSOR,
            "image", "video", "true", "false",
            AppMetrics.SUCCESS, AppMetrics.FAILED, AppMetrics.UNSUPPORTED, AppMetrics.OUT_OF_MEMORY,
            AppMetrics.CANCELLED, "partial", "clean_failed"));

    @NonNull
    private static String scrubbedValue(@Nullable String value) {
        if (value != null && FIXED_VALUES.contains(value)) {
            return value;
        }
        String scrubbed = SentryPrivacyScrubber.scrub(value != null ? value : "");
        return scrubbed != null ? scrubbed : "";
    }

    /*
     * Tracing. A transaction is one piece of work someone waits for or the app does on its own
     * (a batch, a scan, a censor save, an update check, a cache sweep); a span is one step inside
     * it (copying the source, a transcode, a check that the output is clean). Both are named
     * "<area>.<event>" with an area from the naming scheme above, and the names are fixed strings:
     * a span never carries a file's name, path or anything read from it. SentryNamingTest checks
     * the shape and that README.md and site/index.html list every one.
     *
     * The span work nests under is kept per thread, like the operation data, so a step reached
     * from a batch's file lands under that file, and the same step reached outside any traced
     * work records nothing.
     */

    /**
     * Ends the screen's time-to-full-display span once {@code view} has drawn. Every screen builds
     * its content without waiting on anything, so the first frame that shows {@code view} is the
     * screen fully displayed. Call it from the main thread once {@code view} holds the content.
     */
    public static void reportFullyDisplayedOnDraw(@NonNull View view) {
        if (!isEnabled()) {
            return;
        }
        boolean[] reported = {false};
        ViewTreeObserver.OnDrawListener[] listener = new ViewTreeObserver.OnDrawListener[1];
        // A draw listener can't remove itself while the tree is drawing, so it posts the rest.
        listener[0] = () -> view.post(() -> {
            if (reported[0]) {
                return;
            }
            reported[0] = true;
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnDrawListener(listener[0]);
            }
            Sentry.reportFullyDisplayed();
        });
        view.getViewTreeObserver().addOnDrawListener(listener[0]);
    }

    /** The operation every app transaction uses, so they can be told from the SDK's own. */
    static final String TASK_OPERATION = "task";

    /** The span work on this thread nests under: a transaction, or a batch's file. */
    private static final ThreadLocal<ISpan> CURRENT_SPAN = new ThreadLocal<>();

    /** Work that returns a value, run inside a span. */
    public interface TracedWork<T, E extends Throwable> {
        T run() throws E;
    }

    /** Work that returns nothing, run inside a span. */
    public interface TracedTask<E extends Throwable> {
        void run() throws E;
    }

    /**
     * Starts a transaction that the caller finishes, for work that starts on one thread and ends
     * in a callback on another. Not bound to any thread: pass it to {@link #runUnder} on the
     * thread that does the work so its steps nest under it. A no-op while reporting is off.
     */
    @NonNull
    public static ITransaction startTransaction(@NonNull String name) {
        if (!isEnabled()) {
            return NoOpTransaction.getInstance();
        }
        return Sentry.startTransaction(name, TASK_OPERATION);
    }

    /**
     * Runs {@code work} as the transaction {@code name}, or as a span of that name when this thread
     * is already inside traced work, so a job started from inside another doesn't split the trace.
     * The status is ok when it returns, cancelled when it is stopped and an internal error when it
     * throws anything else, which is rethrown unchanged.
     */
    public static <T, E extends Throwable> T inTransaction(@NonNull String name, @NonNull TracedWork<T, E> work)
            throws E {
        ISpan parent = CURRENT_SPAN.get();
        if (parent != null) {
            return inSpan(name, work);
        }
        return traced(startTransaction(name), work);
    }

    /** {@link #inTransaction(String, TracedWork)} for work that returns nothing. */
    public static <E extends Throwable> void runInTransaction(@NonNull String name, @NonNull TracedTask<E> task)
            throws E {
        inTransaction(name, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Runs {@code work} as a span named {@code operation} under the traced work on this thread,
     * with the status set as in {@link #inTransaction(String, TracedWork)}. Outside traced work, or
     * while reporting is off, it only runs {@code work}.
     */
    public static <T, E extends Throwable> T inSpan(@NonNull String operation, @NonNull TracedWork<T, E> work)
            throws E {
        ISpan parent = CURRENT_SPAN.get();
        if (parent == null || parent.isFinished() || !isEnabled()) {
            return work.run();
        }
        return traced(parent.startChild(operation, operation), work);
    }

    /** {@link #inSpan(String, TracedWork)} for work that returns nothing. */
    public static <E extends Throwable> void runInSpan(@NonNull String operation, @NonNull TracedTask<E> task)
            throws E {
        inSpan(operation, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Runs background housekeeping (a sweep of leftover files) as the transaction {@code name}. A
     * {@code RuntimeException} is reported and ends only this sweep, which the next start runs
     * again, instead of ending the app at launch with nothing on screen to explain it.
     */
    public static void runHousekeeping(@NonNull String name, @NonNull Runnable work) {
        runInTransaction(name, () -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                recordException(e);
                failCurrentSpan(e);
            }
        });
    }

    /**
     * Runs {@code task} with {@code parent} as the span this thread's steps nest under, for a
     * transaction started on another thread ({@link #startTransaction(String)}). It neither
     * finishes {@code parent} nor sets its status; a null {@code parent} only runs {@code task}.
     */
    public static <E extends Throwable> void runUnder(@Nullable ISpan parent, @NonNull TracedTask<E> task)
            throws E {
        ISpan previous = CURRENT_SPAN.get();
        CURRENT_SPAN.set(parent);
        try {
            task.run();
        } finally {
            restoreCurrentSpan(previous);
        }
    }

    /**
     * Makes {@code span} the one this thread's steps nest under and returns the one it replaces,
     * for a caller that manages a span itself ({@code BatchRun}'s span per file). Hand the
     * returned span to {@link #restoreCurrentSpan} when it finishes.
     */
    @Nullable
    public static ISpan bindCurrentSpan(@Nullable ISpan span) {
        ISpan previous = CURRENT_SPAN.get();
        CURRENT_SPAN.set(span);
        return previous;
    }

    /** Puts back the span {@link #bindCurrentSpan} returned. */
    public static void restoreCurrentSpan(@Nullable ISpan previous) {
        if (previous == null) {
            CURRENT_SPAN.remove();
        } else {
            CURRENT_SPAN.set(previous);
        }
    }

    /**
     * Marks the traced work on this thread as failed by {@code error}, for work that catches its
     * own exceptions instead of letting them reach {@link #inTransaction} or {@link #inSpan}.
     */
    public static void failCurrentSpan(@NonNull Throwable error) {
        setCurrentSpanStatus(statusFor(error));
    }

    /** Sets the status of the traced work on this thread, which otherwise ends ok. */
    public static void setCurrentSpanStatus(@NonNull SpanStatus status) {
        ISpan span = CURRENT_SPAN.get();
        if (span != null && !span.isFinished()) {
            span.setStatus(status);
        }
    }

    /** The status a span gets for work that threw {@code error}. */
    @NonNull
    public static SpanStatus statusFor(@NonNull Throwable error) {
        return isUserCancellation(error) ? SpanStatus.CANCELLED : SpanStatus.INTERNAL_ERROR;
    }

    /**
     * Opens a span named {@code operation} under the traced work on this thread, for work a lambda
     * can't wrap without losing its exception types (it throws two kinds of checked exception).
     * Call {@link Span#failed} from a catch and {@link Span#close} from a finally. Outside traced
     * work, or while reporting is off, the span does nothing.
     */
    @NonNull
    public static Span span(@NonNull String operation) {
        ISpan parent = CURRENT_SPAN.get();
        if (parent == null || parent.isFinished() || !isEnabled()) {
            return Span.NONE;
        }
        return new Span(parent.startChild(operation, operation));
    }

    /** A span that is this thread's current one until it is closed. */
    public static final class Span implements AutoCloseable {

        static final Span NONE = new Span(null);

        @Nullable
        private final ISpan span;
        @Nullable
        private final ISpan previous;

        private Span(@Nullable ISpan span) {
            this.span = span;
            this.previous = span != null ? bindCurrentSpan(span) : null;
        }

        /** The work threw {@code error}: cancelled when it was stopped, else an internal error. */
        public void failed(@NonNull Throwable error) {
            if (span != null) {
                span.setStatus(statusFor(error));
            }
        }

        /** Finishes the span, ok unless {@link #failed} said otherwise, and puts back its parent. */
        @Override
        public void close() {
            if (span == null) {
                return;
            }
            restoreCurrentSpan(previous);
            if (span.getStatus() == null) {
                span.setStatus(SpanStatus.OK);
            }
            span.finish();
        }
    }

    private static <T, E extends Throwable> T traced(@NonNull ISpan span, @NonNull TracedWork<T, E> work)
            throws E {
        ISpan previous = bindCurrentSpan(span);
        try {
            T result = work.run();
            if (span.getStatus() == null) {
                span.setStatus(SpanStatus.OK);
            }
            return result;
        } catch (Throwable t) {
            span.setStatus(statusFor(t));
            throw t;
        } finally {
            restoreCurrentSpan(previous);
            span.finish();
        }
    }
}
