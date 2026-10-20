package com.doubleangels.redact.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.MainActivity;
import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Local notifications: channel setup, permission checks, and completion alerts
 * for long-running
 * work (convert, clean).
 */
public final class LocalNotifications {

    public static final String CHANNEL_ID_TASKS = "tasks";

    static Integer testSdkIntOverride;

    private static final int NOTIFICATION_ID_CONVERT = 7101;
    private static final int NOTIFICATION_ID_CLEAN = 7102;

    private static final long PROGRESS_THROTTLE_MS = 400L;
    private static final AtomicLong lastConvertProgressNotifyMs = new AtomicLong(0);
    private static final AtomicLong lastCleanProgressNotifyMs = new AtomicLong(0);

    private static final int PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    private LocalNotifications() {
    }

    /**
     * Creates notification channels on API 26+. Call from
     * {@link android.app.Application#onCreate()}.
     */
    public static void ensureChannels(@NonNull Context context) {
        int sdk = testSdkIntOverride != null ? testSdkIntOverride : Build.VERSION.SDK_INT;
        if (sdk < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID_TASKS,
                context.getString(R.string.notification_channel_tasks_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_tasks_description));
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * Whether we may show notifications (runtime permission on API 33+ and app
     * notifications enabled).
     */
    public static boolean canPostNotifications(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(app,
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            return false;
        }
        return AppPreferences.areNotificationsEnabled(app);
    }

    /**
     * Whether optional progress notification updates may be posted. Starting the processing
     * foreground service is always attempted so long work can continue when the app is backgrounded.
     */
    public static boolean canStartForegroundService(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(app,
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return NotificationManagerCompat.from(app).areNotificationsEnabled();
    }

    /**
     * Whether clean-specific notifications are enabled (master must also be on).
     */
    private static boolean canPostCleanNotifications(@NonNull Context context) {
        if (!canPostNotifications(context))
            return false;
        return AppPreferences.areCleanNotificationsEnabled(context);
    }

    /**
     * Whether convert-specific notifications are enabled (master must also be on).
     */
    private static boolean canPostConvertNotifications(@NonNull Context context) {
        if (!canPostNotifications(context))
            return false;
        return AppPreferences.areConvertNotificationsEnabled(context);
    }

    /**
     * Ongoing notification while a batch conversion runs. Same ID as completion so
     * the bar is
     * replaced by the result. Updates are throttled to avoid flooding the system.
     */
    public static void updateConversionProgress(
            @NonNull Context context, int percent, @NonNull String message) {
        if (!canPostConvertNotifications(context)) {
            return;
        }
        if (!AppPreferences.areProgressNotificationsEnabled(context)) {
            return;
        }
        if (shouldThrottleProgress(lastConvertProgressNotifyMs)) {
            return;
        }
        Context app = context.getApplicationContext();
        String title = app.getString(R.string.notification_convert_progress_title);
        try {
            ProcessingForegroundService.updateProgress(
                    app, clampPercent(percent), title, null);
        } catch (SecurityException e) {
            SentryManager.recordException(e);
        }
    }

    /**
     * Ongoing notification while cleaning metadata. Same ID as completion.
     */
    public static void updateCleanProgress(
            @NonNull Context context, int percent, @NonNull String message) {
        if (!canPostCleanNotifications(context)) {
            return;
        }
        if (!AppPreferences.areProgressNotificationsEnabled(context)) {
            return;
        }
        if (shouldThrottleProgress(lastCleanProgressNotifyMs)) {
            return;
        }
        Context app = context.getApplicationContext();
        String title = app.getString(R.string.notification_clean_progress_title);
        try {
            ProcessingForegroundService.updateProgress(
                    app, clampPercent(percent), title, null);
        } catch (SecurityException e) {
            SentryManager.recordException(e);
        }
    }

    /**
     * Shown when a batch conversion finishes (any outcome).
     */
    public static void showConversionComplete(
            @NonNull Context context, int okCount, int failCount) {
        if (!canPostConvertNotifications(context)) {
            return;
        }
        Context app = context.getApplicationContext();
        String title = app.getString(R.string.notification_convert_title);
        String text;
        if (failCount == 0 && okCount > 0) {
            text = app.getString(R.string.notification_convert_all_succeeded, okCount);
        } else if (okCount > 0) {
            text = app.getString(R.string.notification_convert_partial, okCount, failCount);
        } else {
            text = app.getString(R.string.notification_convert_failed);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID_TASKS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(app, MainActivity.class));

        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID_CONVERT, builder.build());
        } catch (SecurityException e) {
            SentryManager.recordException(e);
        }
    }

    /**
     * Cancels any ongoing clean-progress notification. Call this whenever the batch
     * ends,
     * regardless of success/failure, so the persistent progress bar is never left
     * behind.
     */
    public static void cancelCleanProgress(@NonNull Context context) {
        NotificationManagerCompat.from(context.getApplicationContext()).cancel(NOTIFICATION_ID_CLEAN);
        lastCleanProgressNotifyMs.set(0);
    }

    public static void cancelConvertProgress(@NonNull Context context) {
        NotificationManagerCompat.from(context.getApplicationContext()).cancel(NOTIFICATION_ID_CONVERT);
        lastConvertProgressNotifyMs.set(0);
    }

    /** The jobs that keep the processing foreground service running. */
    public enum ForegroundJob { CLEAN, CONVERT, SHARE }

    /** Jobs currently using the service; it stops when the last one ends. Guarded by itself. */
    private static final java.util.Set<ForegroundJob> foregroundJobs =
            java.util.EnumSet.noneOf(ForegroundJob.class);

    /**
     * Keeps the app running in the foreground for {@code job}. A share can run while a clean is
     * still going, so the service is shared and only stops once every job using it has ended.
     */
    public static void startProcessingForeground(
            @NonNull Context context, @NonNull ForegroundJob job, @NonNull String title) {
        synchronized (foregroundJobs) {
            foregroundJobs.add(job);
        }
        ProcessingForegroundService.start(context, title);
    }

    /** Ends {@code job}'s use of the service. Safe to call more than once, or for a job never started. */
    public static void stopProcessingForeground(@NonNull Context context, @NonNull ForegroundJob job) {
        synchronized (foregroundJobs) {
            if (!foregroundJobs.remove(job) || !foregroundJobs.isEmpty()) {
                return;
            }
        }
        ProcessingForegroundService.stop(context);
    }

    /** Forgets every job, for when the system has stopped the service itself. */
    static void clearForegroundJobs() {
        synchronized (foregroundJobs) {
            foregroundJobs.clear();
        }
    }

    @NonNull
    public static PendingIntent mainContentIntent(@NonNull Context context) {
        return contentIntent(context.getApplicationContext(), MainActivity.class);
    }

    /**
     * Shown when metadata stripping on the Clean tab finishes.
     * Always cancels the ongoing progress notification first, then shows a result.
     */
    public static void showCleanComplete(@NonNull Context context, int okCount, int failCount) {
        showCleanComplete(context, okCount, failCount, null);
    }

    /** Same as {@link #showCleanComplete(Context, int, int)}, followed by what the batch removed. */
    public static void showCleanComplete(@NonNull Context context, int okCount, int failCount,
                                         @androidx.annotation.Nullable String summary) {
        showCleanComplete(context, okCount, failCount, summary, null);
    }

    /**
     * Same as {@link #showCleanComplete(Context, int, int, String)}. Expanded, the notification
     * also lists the other kinds of detail the batch removed ({@code details}) and the running
     * totals from Statistics.
     */
    public static void showCleanComplete(@NonNull Context context, int okCount, int failCount,
                                         @androidx.annotation.Nullable String summary,
                                         @androidx.annotation.Nullable String details) {
        cancelCleanProgress(context);

        if (!canPostCleanNotifications(context)) {
            return;
        }
        Context app = context.getApplicationContext();
        String title = app.getString(R.string.notification_clean_title);
        String text;
        if (failCount == 0 && okCount > 0) {
            text = app.getString(R.string.notification_clean_body, okCount);
        } else if (okCount > 0) {
            text = app.getString(R.string.notification_clean_partial, okCount, failCount);
        } else {
            text = app.getString(R.string.notification_clean_failed);
        }
        String expanded = text;
        if (okCount > 0) {
            text = com.doubleangels.redact.ui.CleanSummary.append(text, summary);
            expanded = text;
            if (details != null && !details.isEmpty()) {
                expanded += "\n" + details;
            }
            String totals = cleanTotals(app);
            if (totals != null) {
                expanded += "\n" + totals;
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID_TASKS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(app, MainActivity.class));

        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID_CLEAN, builder.build());
        } catch (SecurityException e) {
            SentryManager.recordException(e);
        }
    }

    /**
     * "On this device so far: Fields Removed (1,284) · Locations Removed (312)", from Statistics,
     * or null when nothing has been counted.
     */
    @androidx.annotation.Nullable
    static String cleanTotals(@NonNull Context app) {
        com.doubleangels.redact.stats.StatsSnapshot s =
                com.doubleangels.redact.stats.StatsStore.snapshot(app);
        if (s.filesCleaned() == 0) {
            return null;
        }
        android.content.res.Resources res = app.getResources();
        return app.getString(R.string.notification_clean_totals,
                com.doubleangels.redact.ui.CleanSummary.joinItems(java.util.List.of(
                        com.doubleangels.redact.ui.CleanSummary.item(res,
                                res.getString(R.string.stats_fields_removed), s.fieldsRemoved),
                        com.doubleangels.redact.ui.CleanSummary.item(res,
                                res.getString(R.string.stats_locations_removed), s.locationsRemoved))));
    }

    private static boolean shouldThrottleProgress(@NonNull AtomicLong lastNotifyMs) {
        long now = SystemClock.elapsedRealtime();
        long prev = lastNotifyMs.get();
        if (now - prev < PROGRESS_THROTTLE_MS && prev != 0) {
            return true;
        }
        lastNotifyMs.set(now);
        return false;
    }

    private static int clampPercent(int percent) {
        if (percent < 0) {
            return 0;
        }
        if (percent > 100) {
            return 100;
        }
        return percent;
    }

    @NonNull
    private static PendingIntent contentIntent(@NonNull Context app, @NonNull Class<?> activityClass) {
        Intent intent = new Intent(app, activityClass);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(app, 0, intent, PENDING_INTENT_FLAGS);
    }
}
