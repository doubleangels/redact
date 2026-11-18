package com.doubleangels.redact.sentry;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.AppPreferences;

import java.util.List;

/**
 * Counts why Android ended the app's earlier processes (low memory, a crash, a freeze, killed for
 * using too much, the person swiping it away) as {@code app.exit}, with the memory it held as
 * {@code app.exit.memory_mb}. This is how a large video job that the system killed shows up: no
 * crash report is written for it. Only exits since crash reporting was turned on are counted,
 * each once.
 */
public final class ProcessExits {

    /** Android keeps a few exits per app; more than this at once is never needed. */
    private static final int MAX_EXITS = 16;

    private ProcessExits() {
    }

    /** Counts the exits not yet counted. Call off the main thread once Sentry is running. */
    public static void report(@NonNull Context context) {
        if (!SentryManager.isEnabled()) {
            return;
        }
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) {
                return;
            }
            List<ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, MAX_EXITS);
            long since = Math.max(AppPreferences.getCrashReportingSince(context),
                    AppPreferences.getLastReportedExit(context));
            long newest = since;
            for (ApplicationExitInfo exit : exits) {
                if (exit.getTimestamp() <= since) {
                    continue;
                }
                String reason = reasonName(exit.getReason());
                AppMetrics.appExit(reason, importanceName(exit.getImportance()), exit.getRss());
                newest = Math.max(newest, exit.getTimestamp());
            }
            if (newest > since) {
                AppPreferences.setLastReportedExit(context, newest);
            }
        } catch (RuntimeException e) {
            SentryManager.logEvent("Lifecycle", "The process exit history could not be read: " + e.getClass().getSimpleName() + ".");
        }
    }

    @NonNull
    static String reasonName(int reason) {
        return switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED -> "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory";
            case ApplicationExitInfo.REASON_CRASH -> "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native";
            case ApplicationExitInfo.REASON_ANR -> "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died";
            case ApplicationExitInfo.REASON_OTHER -> "other";
            case ApplicationExitInfo.REASON_FREEZER -> "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated";
            default -> "unknown";
        };
    }

    /** Where the process stood when it ended: on screen, visible, running a job, or in the background. */
    @NonNull
    static String importanceName(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return "foreground";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
            return "foreground_service";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE) {
            return "visible";
        }
        return "background";
    }
}
