package com.doubleangels.redact.sentry;

import android.content.Context;

import com.doubleangels.redact.BuildConfig;

import io.sentry.android.core.SentryAndroid;

/**
 * Initializes Sentry on a background thread with options aligned to production diagnostics:
 * release tracking, breadcrumbs, screenshots, view hierarchy, tracing, ANR, and frame tracking.
 */
public final class SentryInitializer {

    private SentryInitializer() {
    }

    public static void initialize(Context context) {
        new Thread(() -> SentryAndroid.init(context, options -> {
            options.setDsn(
                    "https://f92d27d08095710804ab4250a73b5ff8@o244019.ingest.us.sentry.io/4510514277580800");
            options.setRelease(BuildConfig.VERSION_NAME);
            options.setEnvironment(BuildConfig.DEBUG ? "development" : "production");
            options.addInAppInclude("com.doubleangels.redact");

            // Attach all thread stacktraces during a crash (no PII, helps concurrency bugs).
            options.setAttachThreads(true);

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
            options.setEnableAppStartProfiling(false);
            options.setEnableFramesTracking(false);
            options.setEnableRootCheck(false);

            // Sample only 5% of traces to minimize data sent to external servers.
            options.setTracesSampleRate(0.05);

            // Drop Sentry's own HTTP client errors (environmental noise).
            options.setBeforeSend((event, hint) -> {
                if (event.getThrowable() != null
                        && event.getThrowable().getClass().getSimpleName().equals("SentryHttpClientException")) {
                    return null;
                }
                return event;
            });
        })).start();
    }
}
