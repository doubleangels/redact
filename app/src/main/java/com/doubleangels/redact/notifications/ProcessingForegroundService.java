package com.doubleangels.redact.notifications;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.sentry.AppMetrics;
import com.doubleangels.redact.sentry.SentryManager;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Keeps long-running clean/convert/share work alive when the app is backgrounded.
 */
public class ProcessingForegroundService extends Service {

    public static final String ACTION_START = "com.doubleangels.redact.action.PROCESSING_START";
    public static final String ACTION_STOP = "com.doubleangels.redact.action.PROCESSING_STOP";
    public static final String ACTION_UPDATE = "com.doubleangels.redact.action.PROCESSING_UPDATE";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_PERCENT = "extra_percent";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_FILE_NUMBER = "extra_file_number";
    public static final String EXTRA_FILE_COUNT = "extra_file_count";

    static final int NOTIFICATION_ID = 7103;

    public static void start(@Nullable Context context, @Nullable String title) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        LocalNotifications.ensureChannels(app);
        Intent intent = new Intent(app, ProcessingForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE, title != null ? title : app.getString(R.string.status_processing));
        try {
            app.startForegroundService(intent);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS denied on API 33+.
            AppMetrics.foregroundServiceFailure("no_notification_permission", e);
        } catch (IllegalStateException e) {
            // ForegroundServiceStartNotAllowedException: the app went to the background before
            // the job began. The job runs without the service, as updateProgress and stop allow.
            SentryManager.logEvent("notification", "The processing service could not start from the background.");
            AppMetrics.foregroundServiceFailure("start_service", e);
        }
    }

    public static void updateProgress(@Nullable Context context, int percent, @Nullable String title) {
        updateProgress(context, percent, title, null);
    }

    public static void updateProgress(
            @Nullable Context context, int percent, @Nullable String title, @Nullable String message) {
        updateProgress(context, percent, title, message, 0, 0);
    }

    /**
     * Updates the progress notification. {@code fileNumber} of {@code fileCount} is the file the
     * job is on (0 and 0 when unknown); with several files the status bar chip shows the count.
     */
    public static void updateProgress(
            @Nullable Context context, int percent, @Nullable String title, @Nullable String message,
            int fileNumber, int fileCount) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!LocalNotifications.canStartForegroundService(app)) {
            return;
        }
        Intent intent = new Intent(app, ProcessingForegroundService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_PERCENT, percent);
        if (title != null) {
            intent.putExtra(EXTRA_TITLE, title);
        }
        if (message != null && !message.isEmpty()) {
            intent.putExtra(EXTRA_MESSAGE, message);
        }
        if (fileCount > 0) {
            intent.putExtra(EXTRA_FILE_NUMBER, fileNumber);
            intent.putExtra(EXTRA_FILE_COUNT, fileCount);
        }
        try {
            app.startService(intent);
        } catch (IllegalStateException ignored) {
            // Service already stopped (e.g. self-stopped after a permission denial) and the
            // app is now backgrounded, so this would start a new instance; nothing to update.
        }
    }

    public static void stop(@Nullable Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, ProcessingForegroundService.class);
        intent.setAction(ACTION_STOP);
        try {
            app.startService(intent);
        } catch (IllegalStateException ignored) {
            // Service already stopped and the app is backgrounded; nothing to stop.
        }
    }

    /** Whether this instance successfully called startForeground(). */
    private boolean inForeground;

    @SuppressLint("InlinedApi") // guarded by the sdk check before startForeground
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        // Only ACTION_START arrives via startForegroundService(). Update and stop arrive via plain
        // startService(), which can create a fresh instance when the previous one already stopped
        // (self-stop, timeout, process restart). Calling startForeground() on such an instance
        // while the app is in the background throws ForegroundServiceStartNotAllowedException,
        // and there is nothing to update or stop anyway.
        boolean isStart = action == null || ACTION_START.equals(action);
        if (!isStart && !inForeground) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            inForeground = false;
            stopSelf(startId);
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
        String detail = intent != null && intent.hasExtra(EXTRA_MESSAGE)
                ? intent.getStringExtra(EXTRA_MESSAGE)
                : null;
        if (detail == null || detail.isEmpty()) {
            detail = getString(R.string.notification_progress_percent, percent);
        } else if (detail.length() > 120) {
            detail = detail.substring(0, 117) + "…";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, LocalNotifications.CHANNEL_ID_TASKS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(LocalNotifications.mainContentIntent(this));
        // A locked phone sees only "Redact, Working": the percent, file count and Live Update chip
        // stay on the private version, and nothing is bridged to a watch.
        LocalNotifications.keepPrivate(builder, this, true);

        if (!isStart && AppPreferences.areProgressNotificationsEnabled(this)) {
            // A Live Update (Android 16+): the status bar shows a chip with the file count or
            // percent. Only while progress updates are on, or it would sit at 0%. Older versions
            // ignore both calls.
            int fileNumber = intent.getIntExtra(EXTRA_FILE_NUMBER, 0);
            int fileCount = intent.getIntExtra(EXTRA_FILE_COUNT, 0);
            builder.setRequestPromotedOngoing(true)
                    .setShortCriticalText(statusChipText(percent, fileNumber, fileCount));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                // Android 16's progress-centric template: one segment per file in a batch.
                builder.setStyle(progressStyle(percent, fileCount));
            }
        }

        if (!isStart) {
            // Already foreground: refresh the existing notification instead of re-entering
            // startForeground(), which the background-start restrictions also apply to.
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
            } catch (SecurityException ignored) {
                // POST_NOTIFICATIONS revoked mid-run; the work itself continues.
            }
            return START_NOT_STICKY;
        }

        try {
            // Call Service.startForeground directly: ServiceCompat masks out
            // FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, which becomes type none and is rejected on
            // targetSdk 34+. The mediaProcessing type only exists from API 35; on API 34 passing it throws
            // InvalidForegroundServiceTypeException ("type unknown"), so Android 14 uses dataSync, which
            // the manifest also declares. Before API 34 no type is required.
            int sdk = Build.VERSION.SDK_INT;
            if (sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                startForeground(
                        NOTIFICATION_ID,
                        builder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            } else if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        builder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
            inForeground = true;
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS denied; we still satisfied the startForeground
            // contract via the try, so just stop cleanly.
            AppMetrics.foregroundServiceFailure("no_notification_permission", e);
            stopSelf();
            return START_NOT_STICKY;
        } catch (RuntimeException e) {
            // InvalidForegroundServiceTypeException and similar FGS failures on newer Android. Record it:
            // swallowing it silently is how the API 34 failure went unnoticed.
            SentryManager.recordException(e);
            AppMetrics.foregroundServiceFailure("start_foreground", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Android 15+ (API 35): {@code dataSync}/{@code mediaProcessing} foreground services get a
     * background execution time limit; the OS calls this shortly before stopping the service so
     * it can wind down instead of being killed outright. Declared unconditionally -- overriding
     * a callback newer than minSdk is safe (older platforms simply never invoke it) -- so there
     * is no {@code @RequiresApi} guard to add here.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        SentryManager.logEvent("notification", 
                "The ProcessingForegroundService hit the Android background execution time limit.");
        AppProcessingScope scope =
                AppProcessingScope.get(getApplicationContext());
        scope.mediaProcessor().cancel();
        // The Convert loop notices this, stops, and reports CANCELLED itself (it clears
        // convertInProgress when it ends). Moving the generation here would make it skip that
        // final update and leave the UI stuck on "Cancel".
        scope.convertTimedOut().set(true);
        VideoMedia3Converter.cancelActiveTranscode();
        LocalNotifications.clearForegroundJobs();
        stopForeground(STOP_FOREGROUND_REMOVE);
        inForeground = false;
        stopSelf();
    }

    /**
     * The status bar chip's text: "2/4" while working through several files, otherwise the
     * percent ("42%"), with the phone's own digits. Never a file name.
     */
    @NonNull
    static String statusChipText(int percent, int fileNumber, int fileCount) {
        Locale locale = Locale.getDefault();
        if (fileCount > 1 && fileNumber > 0) {
            return String.format(locale, "%d/%d", Math.min(fileNumber, fileCount), fileCount);
        }
        return NumberFormat.getPercentInstance(locale).format(percent / 100.0);
    }

    /**
     * The Android 16 progress template for {@code percent} of the whole job: a segment of 100 per
     * file (one segment when the count is unknown or one), filled up to where the job is.
     */
    @NonNull
    static NotificationCompat.ProgressStyle progressStyle(int percent, int fileCount) {
        int segments = Math.max(1, fileCount);
        NotificationCompat.ProgressStyle style = new NotificationCompat.ProgressStyle()
                .setStyledByProgress(true);
        for (int i = 0; i < segments; i++) {
            style.addProgressSegment(new NotificationCompat.ProgressStyle.Segment(100));
        }
        return style.setProgress(percent * segments);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
