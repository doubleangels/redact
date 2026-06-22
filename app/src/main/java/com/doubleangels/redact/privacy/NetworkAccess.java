package com.doubleangels.redact.privacy;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.sentry.SentryInitializer;

/**
 * Tracks explicit user consent before any outbound network use (Sentry crash reporting).
 * Android grants {@code INTERNET} at install time; this gate enforces opt-in in application code.
 */
public final class NetworkAccess {

    private NetworkAccess() {
    }

    public static boolean isConfirmed(@NonNull Context context) {
        return AppPreferences.isNetworkAccessConfirmed(context);
    }

    public static boolean isRequiredFor(@NonNull Context context) {
        return AppPreferences.isCrashReportingEnabled(context);
    }

    public static void confirm(@NonNull Context context) {
        AppPreferences.setNetworkAccessConfirmed(context, true);
        if (AppPreferences.isCrashReportingEnabled(context)) {
            SentryInitializer.initializeBlocking(context);
        }
    }

    /** Enables crash reporting after network consent and starts Sentry when configured. */
    public static void enableCrashReportingWithConsent(@NonNull Context context) {
        AppPreferences.setCrashReportingEnabled(context, true);
        confirm(context);
    }

    /**
     * Revokes network consent when no network-backed feature remains enabled.
     * Disables crash reporting and shuts down Sentry.
     */
    public static void revokeIfUnused(@NonNull Context context) {
        if (isRequiredFor(context)) {
            return;
        }
        revokeCompletely(context);
    }

    /**
     * Revokes network consent and disables crash reporting.
     */
    public static void revokeCompletely(@NonNull Context context) {
        AppPreferences.setCrashReportingEnabled(context, false);
        AppPreferences.setNetworkAccessConfirmed(context, false);
        SentryInitializer.shutdown();
    }

    /**
     * Ensures features enabled before network consent existed (app upgrade) are turned off
     * until the user confirms again in Settings.
     */
    public static void reconcileOrphanedFeatures(@NonNull Context context) {
        if (AppPreferences.isNetworkAccessConfirmed(context)) {
            return;
        }
        if (AppPreferences.isCrashReportingEnabled(context)) {
            AppPreferences.setCrashReportingEnabled(context, false);
        }
    }
}
