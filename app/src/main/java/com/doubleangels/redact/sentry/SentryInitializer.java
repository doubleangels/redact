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
            options.enableAllAutoBreadcrumbs(true);
            options.setAttachScreenshot(true);
            options.setAttachViewHierarchy(true);
            options.setTracesSampleRate(1.0);
            options.setEnableAppStartProfiling(true);
            options.setAnrEnabled(true);
            options.setCollectAdditionalContext(true);
            options.setEnableFramesTracking(true);
            options.setEnableRootCheck(true);
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
