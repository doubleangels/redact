package com.doubleangels.redact;

import android.app.Application;
import android.content.pm.PackageManager;
import android.util.Log;

import com.google.firebase.FirebaseApp;

import io.sentry.Sentry;
import io.sentry.android.core.SentryAndroid;

/**
 * Custom Application class for the Redact application.
 *
 * This class extends the base Android Application class to perform initialization tasks
 * when the application first starts. It handles the setup of Firebase services and
 * Sentry for crash reporting and error tracking.
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
     * This method initializes Firebase services and Sentry for crash reporting.
     * It wraps the initialization in a try-catch block to gracefully handle any initialization
     * errors that might occur, ensuring the application can still start even if initialization fails.
     *
     * @see Application#onCreate()
     */
    @Override
    public void onCreate() {
        // Always call the superclass implementation first
        super.onCreate();

        try {
            // LeakCanary automatically initializes itself when included as a dependency
            // It only runs in debug builds and will detect memory leaks automatically

            // Initialize the Firebase SDK with the application context
            FirebaseApp.initializeApp(this);

            // Initialize Sentry with DSN from AndroidManifest.xml
            SentryAndroid.init(this, options -> {
                // DSN is automatically read from AndroidManifest.xml meta-data
                
                // Set the release version in Sentry to match the app's version name
                // Useful for tracking errors that occur across different app releases
                try {
                    String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    options.setRelease(versionName);
                } catch (PackageManager.NameNotFoundException e) {
                    // Fallback to a default if version name cannot be retrieved
                    options.setRelease("unknown");
                    Log.w(TAG, "Could not retrieve version name for Sentry release", e);
                }
                
                // Enable all auto-breadcrumbs. This helps capture UI events,
                // network calls, and more to provide context around errors
                options.enableAllAutoBreadcrumbs(true);
                
                // Attach a screenshot of the user's view hierarchy at the time of the error
                // This helps visualize the state of the UI when the crash or error occurred
                options.setAttachScreenshot(true);
                
                // Attach the full view hierarchy for debugging layout or view-related issues
                options.setAttachViewHierarchy(true);
                
                // Sets the sample rate for performance tracing (transactions)
                // 1.0 means all transactions are captured
                options.setTracesSampleRate(1.0);
                
                // Enables application start profiling, which measures cold/warm start
                // performance
                options.setEnableAppStartProfiling(true);
                
                // Enables ANR (Application Not Responding) detection to capture when the app
                // has blocked the UI thread for too long
                options.setAnrEnabled(true);
                
                // Allows collection of additional context such as environment info or other
                // device data
                options.setCollectAdditionalContext(true);
                
                // Enables frames tracking for performance monitoring
                // Tracks dropped or slow frames to measure UI responsiveness
                options.setEnableFramesTracking(true);
                
                // Check if the device is rooted, adding extra context for debugging certain
                // crashes that may be more likely on rooted devices
                options.setEnableRootCheck(true);
                
                // Set to true for debugging Sentry integration
                options.setDebug(false);
            });

            // Log the application start event to Sentry for debugging and analytics
            Sentry.captureMessage("Application started!");

        } catch (Exception e) {
            // Log any exceptions that occur during initialization
            // This ensures we can diagnose initialization problems even if Sentry isn't working
            Log.e(TAG, "Error during application initialization:", e);
            Sentry.captureException(e);
        }
    }
}
