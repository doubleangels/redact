package com.doubleangels.redact;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Settings screen. Currently exposes notification toggles (master, Clean, Convert).
 * Preferences are stored in the app's default SharedPreferences under well-known keys
 * so they can be read from {@link com.doubleangels.redact.notifications.LocalNotifications}
 * and the convert/clean flows without coupling them to this fragment.
 */
public class SettingsFragment extends Fragment {

    /** SharedPreferences file name — must match the key used in LocalNotifications. */
    public static final String PREFS_NAME = "redact_prefs";

    /** Master switch: when false, no completion notifications are posted. */
    public static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";

    /** Per-feature switches (only checked when master switch is on). */
    public static final String KEY_CLEAN_NOTIFICATIONS_ENABLED = "clean_notifications_enabled";
    public static final String KEY_CONVERT_NOTIFICATIONS_ENABLED = "convert_notifications_enabled";

    /** Privacy: whether crash/error reports are sent to Sentry. Default on. */
    public static final String KEY_CRASH_REPORTING_ENABLED = "crash_reporting_enabled";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            SharedPreferences prefs = requireContext()
                    .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);

            MaterialSwitch switchNotifications = view.findViewById(R.id.switchNotifications);
            MaterialSwitch switchClean = view.findViewById(R.id.switchCleanNotifications);
            MaterialSwitch switchConvert = view.findViewById(R.id.switchConvertNotifications);
            MaterialSwitch switchCrashReporting = view.findViewById(R.id.switchCrashReporting);
            View rowClean = view.findViewById(R.id.rowCleanNotifications);
            View rowConvert = view.findViewById(R.id.rowConvertNotifications);

            // Initialise state from prefs (default: all enabled).
            boolean masterOn = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
            switchNotifications.setChecked(masterOn);
            switchClean.setChecked(prefs.getBoolean(KEY_CLEAN_NOTIFICATIONS_ENABLED, true));
            switchConvert.setChecked(prefs.getBoolean(KEY_CONVERT_NOTIFICATIONS_ENABLED, true));
            switchCrashReporting.setChecked(prefs.getBoolean(KEY_CRASH_REPORTING_ENABLED, true));
            setSubRowsEnabled(rowClean, switchClean, rowConvert, switchConvert, masterOn);

            switchNotifications.setOnCheckedChangeListener((btn, isChecked) -> {
                try {
                    prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, isChecked).apply();
                    setSubRowsEnabled(rowClean, switchClean, rowConvert, switchConvert, isChecked);
                    SentryManager.setCustomKey("notifications_enabled", isChecked);
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });

            switchClean.setOnCheckedChangeListener((btn, isChecked) -> {
                try {
                    prefs.edit().putBoolean(KEY_CLEAN_NOTIFICATIONS_ENABLED, isChecked).apply();
                    SentryManager.setCustomKey("clean_notifications_enabled", isChecked);
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });

            switchConvert.setOnCheckedChangeListener((btn, isChecked) -> {
                try {
                    prefs.edit().putBoolean(KEY_CONVERT_NOTIFICATIONS_ENABLED, isChecked).apply();
                    SentryManager.setCustomKey("convert_notifications_enabled", isChecked);
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });

            switchCrashReporting.setOnCheckedChangeListener((btn, isChecked) -> {
                try {
                    prefs.edit().putBoolean(KEY_CRASH_REPORTING_ENABLED, isChecked).apply();
                    // Note: SentryManager.isEnabled() will pick up the change immediately
                    // on the next call — no restart needed.
                } catch (Exception e) {
                    // Can't use SentryManager here if it's being disabled, log locally.
                    android.util.Log.e("SettingsFragment", "Error saving crash reporting pref", e);
                }
            });

        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    /** Dims and disables the per-feature rows while the master switch is off. */
    private void setSubRowsEnabled(View rowClean, MaterialSwitch switchClean,
                                   View rowConvert, MaterialSwitch switchConvert,
                                   boolean enabled) {
        float alpha = enabled ? 1f : 0.38f;
        rowClean.setAlpha(alpha);
        rowConvert.setAlpha(alpha);
        switchClean.setEnabled(enabled);
        switchConvert.setEnabled(enabled);
    }
}
