package com.doubleangels.redact.ui;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The crash-report consent dialog behind the Send Crash Reports switch in Settings. On first start
 * the question is asked on its own page of the introduction instead (see OnboardingActivity).
 */
public final class CrashReportingPrompt {

    private CrashReportingPrompt() {}

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
