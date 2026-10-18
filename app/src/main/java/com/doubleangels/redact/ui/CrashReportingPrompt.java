package com.doubleangels.redact.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.SentryInitializer;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Asks once, on first start, whether to turn on Send Crash Reports. It runs when the first-launch
 * permission prompts finish, so it never sits on top of a system permission dialog, and that flow
 * only runs on a fresh install, so updating users are never asked. Either answer ends it for good;
 * Not Now says where the setting is.
 */
public final class CrashReportingPrompt {

    // Kept by AppPreferences.resetAll, so resetting settings never brings the question back.
    public static final String KEY_ASKED = "crash_reporting_prompted";

    private CrashReportingPrompt() {}

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @VisibleForTesting
    static boolean wasAsked(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ASKED, false);
    }

    /** Called when the first-launch permission prompts finish. */
    public static void onFirstStartPermissionsDone(@NonNull Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || wasAsked(activity)
                || AppPreferences.isCrashReportingEnabled(activity)) {
            return;
        }
        // Marked before the dialog shows, so a rotation or process death while it is open never
        // brings it back.
        prefs(activity).edit().putBoolean(KEY_ASKED, true).apply();
        consentDialog(activity,
                () -> {
                    AppPreferences.setCrashReportingEnabled(activity, true);
                    SentryInitializer.initializeIfNeeded(activity);
                    SentryManager.setCustomKey("crash_reporting_enabled", true);
                },
                () -> {
                    View content = activity.findViewById(android.R.id.content);
                    if (content != null) {
                        ReviewPrompt.showDeclinedNote(content, R.string.crash_prompt_declined);
                    }
                })
                .setCancelable(false)
                .show();
    }

    /**
     * The crash-report consent dialog, shared by the first-start question and the Send Crash
     * Reports switch in Settings so both always look the same.
     */
    @NonNull
    public static MaterialAlertDialogBuilder consentDialog(@NonNull Context context,
            @NonNull Runnable onAllow, @NonNull Runnable onDeny) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_sentry_consent_title)
                .setMessage(R.string.settings_sentry_consent_message)
                .setPositiveButton(R.string.settings_sentry_consent_allow, (d, w) -> onAllow.run())
                .setNegativeButton(R.string.settings_sentry_consent_cancel, (d, w) -> onDeny.run());
    }
}
