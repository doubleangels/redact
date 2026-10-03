package com.doubleangels.redact;

import android.app.Application;

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
}
