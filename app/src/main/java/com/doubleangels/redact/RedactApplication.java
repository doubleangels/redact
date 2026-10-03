package com.doubleangels.redact;

import android.app.Application;
import android.os.StrictMode;

import androidx.annotation.NonNull;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.sentry.SentryInitializer;
import com.doubleangels.redact.sentry.SentryManager;

/**
 * Custom Application class for the Redact application.
 *
 *
 * <p>Initializes Sentry and notification channels.
 */
public class RedactApplication extends Application {

    private AppProcessingScope processingScope;

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            // Log-only: surfaces main-thread disk/network I/O in logcat (tag StrictMode) while profiling startup.
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll().penaltyLog().build());
        }

        SentryManager.init(this);
        SentryInitializer.initializeIfNeeded(this);
        LocalNotifications.ensureChannels(this);

        // Runs on every process start, including a cold start into ShareHandlerActivity via the
        // share sheet -- not just when MainActivity happens to be opened -- so stale temp copies
        // of source media (unredacted EXIF/GPS included) don't linger past the cutoff just
        // because the user never opens the main app.
        CacheCleanup.scheduleAutoCleanupIfEnabled(this);
    }

    @NonNull
    public AppProcessingScope getProcessingScope() {
        if (processingScope == null) {
            processingScope = AppProcessingScope.get(this);
        }
        return processingScope;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Glide's memory cache and bitmap pool only shrink proactively if asked; without this,
        // thumbnails loaded across Clean/Scan/Convert stay cached until the system kills the
        // process outright instead of this app giving memory back when it's under pressure.
        com.bumptech.glide.Glide.get(this).onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        com.bumptech.glide.Glide.get(this).onLowMemory();
    }
}
