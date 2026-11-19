package com.doubleangels.redact.sentry;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.doubleangels.redact.BuildConfig;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.AppPreferences;

import java.util.concurrent.atomic.AtomicBoolean;

import io.sentry.EventProcessor;
import io.sentry.Hint;
import io.sentry.ProfileLifecycle;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLogLevel;
import io.sentry.android.core.SentryAndroid;
import io.sentry.android.fragment.FragmentLifecycleIntegration;
import io.sentry.hints.Backfillable;
import io.sentry.util.HintUtils;

/**
 * Initializes Sentry on a background thread with every diagnostic the SDK offers that can't tie
 * reports to one phone or carry anything about a file: release health, breadcrumbs, threads, view
 * hierarchy, tracing, continuous UI profiling, structured logs, metrics, and ANR.
 */
public final class SentryInitializer {

    private static final String TAG = "SentryInitializer";

    /** Profiling session sample rate in release (debug uses 100%). */
    private static final double RELEASE_PROFILE_SAMPLE_RATE = 0.25;

    @Nullable
    @VisibleForTesting
    static String testDsnOverride;

    /**
     * The DSN reports go to. Tests point it elsewhere with {@link #testDsnOverride}, which only a
     * debug build reads: in a release build the check is a constant false and R8 removes it, so
     * nothing in a shipping app can send reports anywhere but the configured project.
     */
    private static String configuredDsn() {
        if (BuildConfig.DEBUG && testDsnOverride != null) {
            return testDsnOverride;
        }
        return BuildConfig.SENTRY_DSN;
    }

    private static volatile boolean initialized;
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initializing;

    private SentryInitializer() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    static boolean isInitializingForTests() {
        return initializing;
    }

    /** Whether a non-empty DSN is available for initialization. */
    public static boolean hasConfiguredDsn() {
        String dsn = configuredDsn();
        return dsn != null && !dsn.isEmpty();
    }

    /** Starts Sentry only when crash reporting is enabled. */
    public static void initializeIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        if (initialized || !AppPreferences.isCrashReportingEnabled(app)) {
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
        if (initialized || !AppPreferences.isCrashReportingEnabled(app)) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized || initializing) {
                return;
            }
            initializing = true;
            boolean kept = false;
            try {
                kept = runSentryInitSafely(app) && keepIfStillWanted(app);
            } finally {
                initializing = false;
            }
            if (kept) {
                ProcessExits.report(app);
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
            boolean kept = false;
            try {
                boolean started = runSentryInitSafely(context);
                synchronized (INIT_LOCK) {
                    kept = started && keepIfStillWanted(context);
                }
            } finally {
                synchronized (INIT_LOCK) {
                    initializing = false;
                }
            }
            if (kept) {
                ProcessExits.report(context);
            }
        }, "redact-sentry-init").start();
    }

    /**
     * Called under {@link #INIT_LOCK} once the SDK has started. Crash reports can be turned off
     * while it starts in the background, and {@link #shutdown} then finds nothing to close yet, so
     * the decision is made here: the SDK stays only if reports are still on, and is closed
     * otherwise. Sessions and profiles have no consent check of their own to stop them later.
     */
    @VisibleForTesting
    static boolean keepIfStillWanted(Context context) {
        if (AppPreferences.isCrashReportingEnabled(context)) {
            initialized = true;
            return true;
        }
        try {
            Sentry.close();
        } catch (RuntimeException ignored) {
            // Best effort, as in shutdown.
        }
        initialized = false;
        return false;
    }

    /**
     * {@link #runSentryInit}, but a failure to start the SDK leaves the app running without
     * crash reporting instead of ending it: nothing would be there yet to report the crash.
     */
    @VisibleForTesting
    static boolean runSentryInitSafely(Context context) {
        try {
            return runSentryInit(context);
        } catch (RuntimeException e) {
            Log.e(TAG, "Sentry could not start; continuing without crash reporting", e);
            return false;
        }
    }

    private static boolean runSentryInit(Context context) {
        AtomicBoolean configured = new AtomicBoolean(false);
        SentryAndroid.init(context, options -> {
            String dsn = configuredDsn();
            if (dsn == null || dsn.isEmpty()) {
                Log.w(TAG, "Crash reporting is enabled but Sentry DSN is not configured");
                return;
            }
            configured.set(true);
            options.setDsn(dsn);
            options.setRelease(BuildConfig.SENTRY_RELEASE);
            options.setEnvironment(BuildConfig.DEBUG ? "development" : "production");
            options.addInAppInclude("com.doubleangels.redact");

            // The rule for everything below: as much technical detail as the SDK offers, but nothing
            // that ties reports to one phone or person, and nothing about a file (name, path,
            // contents, metadata values).

            // The SDK sets the distinct ID to its random per-install ID, and sends it as the
            // session's "did" and as user.id on every log and metric. Without it, sessions,
            // logs and metrics from one phone can't be linked. Events and transactions lose the
            // same ID in SentryPrivacyScrubber.
            options.setDistinctId(null);

            // Every thread's stack with each event. Frames hold class and method names only, and
            // beforeSend scrubs them like the crashing thread's.
            options.setAttachThreads(true);

            // Never screenshots or session replay: they would show the person's photos and file
            // names. Replay is off unless a rate is set, and these lock it against SDK defaults.
            options.setAttachScreenshot(false);
            options.getSessionReplay().setSessionSampleRate(0.0);
            options.getSessionReplay().setOnErrorSampleRate(0.0);
            // The view hierarchy holds each view's class, resource ID, position, size, alpha and
            // visibility, never its text or pixels, so it can't show a file name or a photo.
            options.setAttachViewHierarchy(true);
            options.setSendDefaultPii(false); // Explicit lock against future SDK defaults.
            // Release-health sessions give the crash-free rate. Without the distinct ID above they
            // carry no install ID, so they are counted in total, never per phone.
            options.setEnableAutoSessionTracking(true);

            // Memory, storage, battery and connection state at the time of the report.
            options.setCollectAdditionalContext(true);
            // Screen changes, taps (by view resource ID), system events, network changes, low
            // memory and orientation. beforeBreadcrumb scrubs every one.
            options.enableAllAutoBreadcrumbs(true);
            // Twice the SDK's default of 100: a long batch logs a few per file, so the steps that
            // led to an error would otherwise have scrolled out. Every one is scrubbed the same.
            options.setMaxBreadcrumbs(200);
            // Android killing the app for going over its memory limit (a large video job), as a
            // fatal event from the system's exit history. Only the latest, and isFromBeforeOptIn
            // drops one from before reporting was on. Native tombstones stay off: their memory
            // maps can list the paths of files the app had open.
            options.setMemoryLimiterEnabled(true);
            options.setReportHistoricalMemoryLimiterExits(false);
            // Intent extras of system broadcasts stay off: they are open-ended values.
            options.setEnableSystemEventBreadcrumbsExtras(false);
            // A transaction per tap, named by the view's resource ID.
            options.setEnableUserInteractionTracing(true);

            // Keep ANR detection and frame tracking (crash diagnostics only, no PII).
            options.setAnrEnabled(true);
            options.setEnableAnrFingerprinting(true); // Groups noisy system-frame ANRs.
            options.setEnableFramesTracking(true); // Slow/frozen frame counts on screen transactions.
            options.setEnableRootCheck(false);

            // Activity (cold/warm start, TTID) and fragment (the four tab screens) lifecycle spans.
            options.setEnableAutoActivityLifecycleTracing(true);
            options.setEnableActivityLifecycleTracingAutoFinish(true);
            // Time to full display: each screen's load ends when its content has drawn
            // (SentryManager.reportFullyDisplayedOnDraw), not just its first frame.
            options.setEnableTimeToFullDisplayTracing(true);
            // Fragment breadcrumbs and spans for the four tab screens.
            options.addIntegration(new FragmentLifecycleIntegration(
                    (Application) context.getApplicationContext(), true, true));

            // Every transaction: none of them carries an install ID, so sampling would only lose
            // detail.
            options.setTracesSampleRate(1.0);

            // Continuous UI profiling tied to transactions (SDK ≥ 8.7). Sampled per app session in
            // release because profile hours count against the plan's quota.
            options.setProfileSessionSampleRate(
                    BuildConfig.DEBUG ? 1.0 : RELEASE_PROFILE_SAMPLE_RATE);
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

            // Adds the capturing thread's scan/clean/convert data, so concurrent jobs don't mix,
            // and the settings and media capabilities in effect when the error happened.
            options.addEventProcessor(new EventProcessor() {
                @Override
                public SentryEvent process(@NonNull SentryEvent event, @NonNull Hint hint) {
                    SentryManager.applyOperationData(event);
                    SentryManager.applyAppState(event);
                    return event;
                }
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
                if (isFromBeforeOptIn(event, hint, context)) {
                    return null;
                }
                SentryPrivacyScrubber.scrubEvent(event);
                return event;
            });

            // User feedback skips beforeSend, so it gets its own consent check and scrub.
            options.setBeforeSendFeedback((event, hint) -> {
                if (!SentryManager.isEnabled()) {
                    return null;
                }
                SentryPrivacyScrubber.scrubFeedbackEvent(event);
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

    /**
     * Whether {@code event} is a crash or freeze Android recorded before crash reporting was turned
     * on. The SDK reads the latest one from the system's exit history at startup (a "backfilled"
     * event), even when it happened while reporting was off, so it is dropped here.
     */
    static boolean isFromBeforeOptIn(@NonNull SentryEvent event, @NonNull Hint hint, @NonNull Context context) {
        if (!HintUtils.hasType(hint, Backfillable.class) || event.getTimestamp() == null) {
            return false;
        }
        return event.getTimestamp().getTime() < AppPreferences.getCrashReportingSince(context);
    }

    private static boolean isVerboseLogLevel(@Nullable SentryLogLevel level) {
        return level == SentryLogLevel.TRACE || level == SentryLogLevel.DEBUG;
    }

    public static void shutdown() {
        synchronized (INIT_LOCK) {
            if (!initialized) {
                // A start still in flight sees reports are off when it finishes
                // (keepIfStillWanted) and closes the SDK itself.
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
