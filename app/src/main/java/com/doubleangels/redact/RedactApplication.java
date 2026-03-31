package com.doubleangels.redact;

import android.app.Application;
import android.util.Log;

import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.sentry.SentryInitializer;

/**
 * Custom Application class for the Redact application.
 *
 * Initializes Sentry for error reporting and Firebase (Analytics, Performance) when configured.
 */
public class RedactApplication extends Application {
    private static final String TAG = "RedactApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        SentryInitializer.initialize(this);
        LocalNotifications.ensureChannels(this);
    }
}
