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

import com.doubleangels.redact.MainActivity;
import com.doubleangels.redact.R;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Local notifications: channel setup, permission checks, and completion alerts for long-running
 * work (convert, clean).
 */
public final class LocalNotifications {

    public static final String CHANNEL_ID_TASKS = "tasks";

    private static final int NOTIFICATION_ID_CONVERT = 7101;
    private static final int NOTIFICATION_ID_CLEAN = 7102;

    private static final long PROGRESS_THROTTLE_MS = 400L;
    private static final AtomicLong lastConvertProgressNotifyMs = new AtomicLong(0);
    private static final AtomicLong lastCleanProgressNotifyMs = new AtomicLong(0);

    private static final int PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    private LocalNotifications() {
    }

    /**
     * Creates notification channels on API 26+. Call from {@link android.app.Application#onCreate()}.
     */
    public static void ensureChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel =
                new NotificationChannel(
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
     * Whether we may show notifications (runtime permission on API 33+ and app notifications enabled).
     */
    public static boolean canPostNotifications(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return NotificationManagerCompat.from(app).areNotificationsEnabled();
    }

    /**
     * Ongoing notification while a batch conversion runs. Same ID as completion so the bar is
     * replaced by the result. Updates are throttled to avoid flooding the system.
     */
    public static void updateConversionProgress(
            @NonNull Context context, int percent, @NonNull String message) {
        if (!canPostNotifications(context)) {
            return;
        }
        if (shouldThrottleProgress(lastConvertProgressNotifyMs)) {
            return;
        }
        Context app = context.getApplicationContext();
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(app, CHANNEL_ID_TASKS)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(app.getString(R.string.notification_convert_progress_title))
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setProgress(100, clampPercent(percent), false)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setContentIntent(contentIntent(app, MainActivity.class));

        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID_CONVERT, builder.build());
    }

    /**
     * Ongoing notification while cleaning metadata. Same ID as completion.
     */
    public static void updateCleanProgress(
            @NonNull Context context, int percent, @NonNull String message) {
        if (!canPostNotifications(context)) {
            return;
        }
        if (shouldThrottleProgress(lastCleanProgressNotifyMs)) {
            return;
        }
        Context app = context.getApplicationContext();
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(app, CHANNEL_ID_TASKS)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(app.getString(R.string.notification_clean_progress_title))
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setProgress(100, clampPercent(percent), false)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setContentIntent(contentIntent(app, MainActivity.class));

        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID_CLEAN, builder.build());
    }

    /**
     * Shown when a batch conversion finishes (any outcome).
     */
    public static void showConversionComplete(
            @NonNull Context context, int okCount, int failCount) {
        if (!canPostNotifications(context)) {
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

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(app, CHANNEL_ID_TASKS)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setContentIntent(contentIntent(app, MainActivity.class));

        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID_CONVERT, builder.build());
    }

    /**
     * Shown when metadata stripping on the Clean tab finishes with at least one success.
     */
    public static void showCleanComplete(@NonNull Context context, int processedCount) {
        if (!canPostNotifications(context) || processedCount <= 0) {
            return;
        }
        Context app = context.getApplicationContext();
        String title = app.getString(R.string.notification_clean_title);
        String text = app.getString(R.string.notification_clean_body, processedCount);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(app, CHANNEL_ID_TASKS)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setContentIntent(contentIntent(app, MainActivity.class));

        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID_CLEAN, builder.build());
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
