package com.doubleangels.redact.sentry;

import android.content.Context;
import android.util.Log;

import com.doubleangels.redact.BuildConfig;

import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;

import java.util.concurrent.atomic.AtomicBoolean;

import io.sentry.ProfileLifecycle;
import io.sentry.Sentry;
import io.sentry.SentryLogLevel;
import io.sentry.android.core.SentryAndroid;

/**
 * Initializes Sentry on a background thread with options aligned to production diagnostics:
 * release tracking, breadcrumbs, tracing, continuous UI profiling, structured logs, metrics, and ANR.
 */
public final class SentryInitializer {

    private static final String TAG = "SentryInitializer";

    /** Trace and profiling session sample rate in release (debug uses 100%). */
    private static final double RELEASE_TELEMETRY_SAMPLE_RATE = 0.25;

    @Nullable
    static String testDsnOverride;

    private static volatile boolean initialized;
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initializing;

    private SentryInitializer() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** Whether a non-empty DSN is available for initialization. */
    public static boolean hasConfiguredDsn() {
        String dsn = testDsnOverride != null ? testDsnOverride : BuildConfig.SENTRY_DSN;
        return dsn != null && !dsn.isEmpty();
    }

    /** Starts Sentry only when crash reporting and network consent are both enabled. */
    public static void initializeIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        if (initialized
                || !AppPreferences.isCrashReportingEnabled(app)
                || !AppPreferences.isNetworkAccessConfirmed(app)) {
            return;
        }
        initialize(app);
    }

    /**
     * Initializes Sentry on the calling thread. Use when the user explicitly enables crash
     * reporting so the SDK is ready before they leave Settings.
     */
    public static void initializeBlocking(Context context) {
        Context app = context.getApplicationContext();
        if (initialized
                || !AppPreferences.isCrashReportingEnabled(app)
                || !AppPreferences.isNetworkAccessConfirmed(app)) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized || initializing) {
                return;
            }
            initializing = true;
            try {
                if (runSentryInit(app)) {
                    initialized = true;
                }
            } finally {
                initializing = false;
            }
        }
    }

    public static void initialize(Context context) {
        synchronized (INIT_LOCK) {
            if (initialized || initializing) {
                return;
            }
            initializing = true;
        }
        new Thread(() -> {
            try {
                if (runSentryInit(context)) {
                    synchronized (INIT_LOCK) {
                        initialized = true;
                    }
                }
            } finally {
                synchronized (INIT_LOCK) {
                    initializing = false;
                }
            }
        }).start();
    }

    private static boolean runSentryInit(Context context) {
        AtomicBoolean configured = new AtomicBoolean(false);
        SentryAndroid.init(context, options -> {
            String dsn = testDsnOverride != null ? testDsnOverride : BuildConfig.SENTRY_DSN;
            if (dsn == null || dsn.isEmpty()) {
                Log.w(TAG, "Crash reporting is enabled but Sentry DSN is not configured");
                return;
            }
            configured.set(true);
            options.setDsn(dsn);
            options.setRelease(BuildConfig.SENTRY_RELEASE);
            options.setEnvironment(BuildConfig.DEBUG ? "development" : "production");
            options.addInAppInclude("com.doubleangels.redact");

            // Thread stacks are scrubbed in beforeSend; omit by default to limit path leakage.
            options.setAttachThreads(false);

            // Privacy: do NOT attach screenshots or view hierarchy — could capture user media.
            options.setAttachScreenshot(false);
            options.setAttachViewHierarchy(false);
            options.setSendDefaultPii(false); // Explicit lock against future SDK defaults.

            // Privacy: do NOT collect broad device context or send all auto breadcrumbs.
            options.setCollectAdditionalContext(false);
            options.enableAllAutoBreadcrumbs(false);

            // Keep ANR detection and frame tracking (crash diagnostics only, no PII).
            options.setAnrEnabled(true);
            options.setEnableAnrFingerprinting(true); // Groups noisy system-frame ANRs.
            options.setEnableFramesTracking(false);
            options.setEnableRootCheck(false);

            // Sample 25% of traces in release to balance diagnostics vs. data sent externally.
            options.setTracesSampleRate(BuildConfig.DEBUG ? 1.0 : RELEASE_TELEMETRY_SAMPLE_RATE);

            // Continuous UI profiling tied to sampled transactions (SDK ≥ 8.7).
            options.setProfileSessionSampleRate(
                    BuildConfig.DEBUG ? 1.0 : RELEASE_TELEMETRY_SAMPLE_RATE);
            options.setProfileLifecycle(ProfileLifecycle.TRACE);

            options.getLogs().setEnabled(true);
            options.getLogs().setBeforeSend(logEvent -> {
                if (!SentryManager.isEnabled()) {
                    return null;
                }
                if (!BuildConfig.DEBUG && isVerboseLogLevel(logEvent.getLevel())) {
                    return null;
                }
                SentryPrivacyScrubber.scrubLog(logEvent);
                return logEvent;
            });

            options.getMetrics().setBeforeSend((metric, hint) -> {
                if (!SentryManager.isEnabled()) {
                    return null;
                }
                if (!BuildConfig.DEBUG && metric.getName() != null && metric.getName().startsWith("debug.")) {
                    return null;
                }
                SentryPrivacyScrubber.scrubMetric(metric);
                return metric;
            });

            options.setBeforeBreadcrumb((breadcrumb, hint) -> {
                SentryPrivacyScrubber.scrubBreadcrumb(breadcrumb);
                return breadcrumb;
            });

            // Drop Sentry's own HTTP client errors; scrub remaining event data.
            options.setBeforeSend((event, hint) -> {
                if (event.getThrowable() != null
                        && event.getThrowable().getClass().getSimpleName().equals("SentryHttpClientException")) {
                    return null;
                }
                if (!SentryManager.isEnabled()) {
                    return null;
                }
                SentryPrivacyScrubber.scrubEvent(event);
                return event;
            });

            options.setBeforeSendTransaction((transaction, hint) -> {
                if (!SentryManager.isEnabled()) {
                    return null;
                }
                SentryPrivacyScrubber.scrubTransaction(transaction);
                return transaction;
            });
        });
        if (configured.get() && BuildConfig.DEBUG) {
            Sentry.logger().info("Redact Sentry telemetry enabled");
            Sentry.metrics().count("debug.init", 1.0);
        }
        return configured.get();
    }

    private static boolean isVerboseLogLevel(@Nullable SentryLogLevel level) {
        return level == SentryLogLevel.TRACE || level == SentryLogLevel.DEBUG;
    }

    public static void shutdown() {
        synchronized (INIT_LOCK) {
            if (!initialized) {
                initializing = false;
                return;
            }
            try {
                Sentry.close();
            } catch (Exception ignored) {
                // Best-effort teardown when user revokes network consent.
            } finally {
                initialized = false;
                initializing = false;
            }
        }
    }
}
