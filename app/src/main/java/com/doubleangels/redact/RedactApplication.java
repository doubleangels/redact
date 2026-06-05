package com.doubleangels.redact;

import android.app.Application;


import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.privacy.NetworkAccess;
import com.doubleangels.redact.sentry.SentryInitializer;
import com.doubleangels.redact.sentry.SentryManager;

/**
 * Custom Application class for the Redact application.
 *
 *
 * <p>Initializes Sentry and notification channels.
 */
public class RedactApplication extends Application {
    private static final String TAG = "RedactApplication";

    @Override
    public void onCreate() {
        super.onCreate();



        SentryManager.init(this);
        NetworkAccess.reconcileOrphanedFeatures(this);
        SentryInitializer.initializeIfNeeded(this);
        LocalNotifications.ensureChannels(this);
    }
}
