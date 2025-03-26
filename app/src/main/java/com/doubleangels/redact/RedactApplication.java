package com.doubleangels.redact;

import android.app.Application;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Custom Application class for the Redact application.
 *
 * This class extends the base Android Application class to perform initialization tasks
 * when the application first starts. It handles the setup of Firebase services including
 * Crashlytics for crash reporting and analytics.
 *
 * Any application-wide initialization that needs to happen once at app startup should be
 * placed in this class.
 */
public class RedactApplication extends Application {
    // TAG constant for logging purposes - uses class name for easy filtering in logcat
    private static final String TAG = "RedactApplication";

    /**
     * Called when the application is starting, before any activity, service,
     * or receiver objects (excluding content providers) have been created.
     *
     * This method initializes Firebase services including Crashlytics for crash reporting.
     * It wraps the initialization in a try-catch block to gracefully handle any initialization
     * errors that might occur, ensuring the application can still start even if Firebase
     * initialization fails.
     *
     * @see Application#onCreate()
     */
    @Override
    public void onCreate() {
        // Always call the superclass implementation first
        super.onCreate();

        try {
            // Initialize the Firebase SDK with the application context
            FirebaseApp.initializeApp(this);

            // Get an instance of Firebase Crashlytics for crash reporting
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();

            // Log the application start event to Crashlytics for debugging and analytics
            crashlytics.log("Application started!");

        } catch (Exception e) {
            // Log any exceptions that occur during Firebase initialization
            // This ensures we can diagnose initialization problems even if Crashlytics isn't working
            Log.e(TAG, "Error during application initialization:", e);
        }
    }
}
