package com.doubleangels.redact.notifications;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.doubleangels.redact.R;

/**
 * Keeps long-running clean/convert/share work alive when the app is backgrounded.
 */
public class ProcessingForegroundService extends Service {

    public static final String ACTION_START = "com.doubleangels.redact.action.PROCESSING_START";
    public static final String ACTION_STOP = "com.doubleangels.redact.action.PROCESSING_STOP";
    public static final String ACTION_UPDATE = "com.doubleangels.redact.action.PROCESSING_UPDATE";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_PERCENT = "extra_percent";

    static final int NOTIFICATION_ID = 7103;

    public static void start(@Nullable Context context, @Nullable String title) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!LocalNotifications.canPostNotifications(app)) {
            return;
        }
        LocalNotifications.ensureChannels(app);
        Intent intent = new Intent(app, ProcessingForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE, title != null ? title : app.getString(R.string.status_processing));
        try {
            app.startForegroundService(intent);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS denied on API 33+.
        }
    }

    public static void updateProgress(@Nullable Context context, int percent, @Nullable String title) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!LocalNotifications.canPostNotifications(app)) {
            return;
        }
        Intent intent = new Intent(app, ProcessingForegroundService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_PERCENT, percent);
        if (title != null) {
            intent.putExtra(EXTRA_TITLE, title);
        }
        app.startService(intent);
    }

    public static void stop(@Nullable Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, ProcessingForegroundService.class);
        intent.setAction(ACTION_STOP);
        app.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!LocalNotifications.canPostNotifications(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = intent != null && intent.hasExtra(EXTRA_TITLE)
                ? intent.getStringExtra(EXTRA_TITLE)
                : getString(R.string.status_processing);
        int percent = intent != null ? intent.getIntExtra(EXTRA_PERCENT, 0) : 0;
        percent = Math.min(100, Math.max(0, percent));
        if (title == null) {
            title = getString(R.string.status_processing);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, LocalNotifications.CHANNEL_ID_TASKS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(getString(R.string.notification_progress_percent, percent))
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(LocalNotifications.mainContentIntent(this));
        try {
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
