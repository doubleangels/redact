package com.doubleangels.redact.permission;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import io.sentry.Sentry;

/**
 * Manages runtime permissions for media access in Android applications.
 *
 * This class handles the complexities of requesting and managing permissions across different
 * Android versions (particularly the changes in Android 13/Tiramisu). It includes:
 * - Permission checking and requesting
 * - UI interactions for permission rationales
 * - Handling both temporary and permanent permission denials
 * - Integration with Sentry for permission-related analytics
 *
 * Usage pattern:
 * 1. Create an instance in your Activity with appropriate callback
 * 2. Call checkPermissions() to start the permission flow
 * 3. Forward onRequestPermissionsResult() calls to handlePermissionResult()
 * 4. Implement PermissionCallback to respond to permission state changes
 */
public class PermissionManager {
    // Request codes for identifying permission requests in onRequestPermissionsResult
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 124;
    private static final String TAG = "PermissionManager";

    /** Activity context used for permission requests */
    private final Activity activity;

    /** Root view for displaying Snackbar messages */
    private final View rootView;

    /** Launcher for settings intent when permissions are permanently denied */
    private final ActivityResultLauncher<Intent> settingsLauncher;

    /** Tracks whether permission rationale has been shown to the user */
    private boolean hasShownRationale = false;

    /** Tracks whether location permission rationale has been shown to the user */
    private boolean hasShownLocationRationale = false;

    /** Callback interface for permission status updates */
    private final PermissionCallback callback;

    // Sentry is used via static methods, no instance needed
    // Sentry is used via static methods, no instance needed

    /**
     * Interface for notifying permission status changes to clients.
     * Implement this interface in your Activity or Fragment to respond to permission changes.
     */
    public interface PermissionCallback {
        /** Called when all required permissions are granted */
        void onPermissionsGranted();

        /** Called when one or more required permissions are denied */
        void onPermissionsDenied();

        /** Called when the permission request process begins */
        void onPermissionsRequestStarted();

        /**
         * Called when location permission is granted
         * Optional method - implementation can be empty if location permission is not needed
         */
        default void onLocationPermissionGranted() {}

        /**
         * Called when location permission is denied
         * Optional method - implementation can be empty if location permission is not needed
         */
        default void onLocationPermissionDenied() {}
    }

    /**
     * Creates a new PermissionManager instance.
     *
     * @param activity The host Activity
     * @param rootView The root View for showing Snackbar messages
     * @param settingsLauncher Launcher for settings intent when permissions are permanently denied
     * @param callback Callback interface for permission status updates
     */
    public PermissionManager(Activity activity, View rootView,
                             ActivityResultLauncher<Intent> settingsLauncher,
                             PermissionCallback callback) {
        this.activity = activity;
        this.rootView = rootView;
        this.settingsLauncher = settingsLauncher;
        this.callback = callback;
        // Sentry initialized in Application class
    }

    /**
     * Checks if permissions are needed and initiates the permission request flow if necessary.
     * This is the main entry point for permission handling.
     *
     * Call this method from your Activity's onCreate or onResume to ensure permissions
     * are properly requested before accessing media files.
     */
    public void checkPermissions() {
        try {
            // Check if any permissions are missing
            boolean needsPermissions = needsPermissions();
            if (needsPermissions) {
                // Start permission flow if permissions are needed
                Sentry.captureMessage("Starting permission request flow");
                callback.onPermissionsRequestStarted();
                requestStoragePermission();
            } else {
                // Notify that all permissions are already granted
                Sentry.captureMessage("All permissions already granted");
                callback.onPermissionsGranted();
            }
        } catch (Exception e) {
            // Handle any exceptions during permission checking
            Sentry.captureException(e);

            // Fallback to direct permission check in case of exception
            if (needsPermissions()) {
                callback.onPermissionsRequestStarted();
                requestStoragePermission();
            } else {
                callback.onPermissionsGranted();
            }
        }
    }

    /**
     * Checks if any required permissions are still missing.
     * Different permissions are checked based on the Android version.
     *
     * For Android 13+ (Tiramisu): Checks READ_MEDIA_IMAGES and READ_MEDIA_VIDEO
     * For Android 12 and below: Checks READ_EXTERNAL_STORAGE
     *
     * @return true if permissions need to be requested, false if all are granted
     */
    public boolean needsPermissions() {
        try {
            boolean result;

            // Android 13+ (API 33) uses granular media permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean hasImagePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
                boolean hasVideoPermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;

                // Log permission status for diagnostics                // Need permissions if either images or videos permission is missing
                result = !hasImagePermission || !hasVideoPermission;
            } else {
                // Pre-Android 13 uses the storage permission
                boolean hasStoragePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;                result = !hasStoragePermission;
            }
            return result;
        } catch (Exception e) {
            // Log exception and fall back to direct permission check
            Sentry.captureException(new Exception("Error checking permissions: " + e.getMessage(), e));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)
                        != PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                        != PackageManager.PERMISSION_GRANTED;
            } else {
                return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED;
            }
        }
    }

    /**
     * Checks if location permission is needed.
     * This permission is required to access geolocation metadata in media files.
     *
     * @return true if location permission needs to be requested, false if it's already granted
     */
    public boolean needsLocationPermission() {
        try {
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;            return !hasLocationPermission;
        } catch (Exception e) {
            Sentry.captureException(new Exception("Error checking location permission: " + e.getMessage(), e));
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_MEDIA_LOCATION)
                    != PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Requests the appropriate storage or media permissions based on Android version.
     * Shows rationale UI when required before requesting permissions.
     *
     * This handles the Android version-specific permission requests and UI flows.
     */
    public void requestStoragePermission() {
        try {
            // Mark that we've shown rationale to track permanent denials
            hasShownRationale = true;            // Android 13+ (API 33) uses granular media permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean shouldShowImageRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean shouldShowVideoRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);                Sentry.captureMessage("Requesting Android 13+ media permissions");

                // Show rationale if Android indicates we should
                if (shouldShowImageRationale || shouldShowVideoRationale) {
                    showMediaRationaleSnackbar();
                } else {
                    requestMediaPermissions();
                }
            } else {
                // Pre-Android 13 uses the storage permission
                boolean shouldShowStorageRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);                Sentry.captureMessage("Requesting Android 12 storage permission");

                // Show rationale if Android indicates we should
                if (shouldShowStorageRationale) {
                    showStorageRationaleSnackbar();
                } else {
                    requestStoragePermissions();
                }
            }
        } catch (Exception e) {
            // Log exception and fall back to direct permission request
            Sentry.captureException(new Exception("Error requesting permissions: " + e.getMessage(), e));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO
                        },
                        PERMISSION_REQUEST_CODE);
            } else {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    /**
     * Requests the ACCESS_MEDIA_LOCATION permission for accessing media geolocation data.
     * Shows rationale UI when required before requesting permission.
     *
     * This permission is optional but needed to access location metadata in media files.
     */
    public void requestLocationPermission() {
        try {
            // Mark that we've shown location rationale to track permanent denials
            hasShownLocationRationale = true;            boolean shouldShowLocationRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.ACCESS_MEDIA_LOCATION);            Sentry.captureMessage("Requesting ACCESS_MEDIA_LOCATION permission");

            // Show rationale if Android indicates we should
            if (shouldShowLocationRationale) {
                showLocationRationaleSnackbar();
            } else {
                requestMediaLocationPermission();
            }
        } catch (Exception e) {
            // Log exception and fall back to direct permission request
            Sentry.captureException(new Exception("Error requesting location permission: " + e.getMessage(), e));
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * Shows a Snackbar explaining why media permissions are needed (for Android 13+).
     * This provides context to the user about why the app needs these permissions.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void showMediaRationaleSnackbar() {
        Sentry.captureMessage("Showing media permissions rationale snackbar");
        Snackbar.make(
                        rootView,
                        "Media access is needed to select and process media files!",
                        Snackbar.LENGTH_LONG)
                .setAction("OK", view -> requestMediaPermissions())
                .show();
    }

    /**
     * Requests media permissions specifically for Android 13+ (Tiramisu).
     * Requests both image and video permissions separately as required by Android 13+.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestMediaPermissions() {
        Sentry.captureMessage("Requesting READ_MEDIA_IMAGES and READ_MEDIA_VIDEO permissions");
        ActivityCompat.requestPermissions(activity,
                new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                },
                PERMISSION_REQUEST_CODE);
    }

    /**
     * Shows a Snackbar explaining why storage permission is needed (pre-Android 13).
     * This provides context to the user about why the app needs this permission.
     */
    private void showStorageRationaleSnackbar() {
        Sentry.captureMessage("Showing storage permission rationale snackbar");
        Snackbar.make(
                        rootView,
                        "Storage access is needed to select and process media files!",
                        Snackbar.LENGTH_LONG)
                .setAction("OK", view -> requestStoragePermissions())
                .show();
    }

    /**
     * Requests storage permission for pre-Android 13 devices.
     * Uses the READ_EXTERNAL_STORAGE permission which was required before Android 13.
     */
    private void requestStoragePermissions() {
        Sentry.captureMessage("Requesting READ_EXTERNAL_STORAGE permission");
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                PERMISSION_REQUEST_CODE);
    }

    /**
     * Shows a Snackbar explaining why location permission is needed.
     * This provides context to the user about why the app needs this permission.
     */
    private void showLocationRationaleSnackbar() {
        Sentry.captureMessage("Showing location permission rationale snackbar");
        Snackbar.make(
                        rootView,
                        "Location permission is needed to access geolocation data in media files!",
                        Snackbar.LENGTH_LONG)
                .setAction("OK", view -> requestMediaLocationPermission())
                .show();
    }

    /**
     * Requests ACCESS_MEDIA_LOCATION permission.
     * This permission is needed to access location metadata in media files.
     */
    private void requestMediaLocationPermission() {
        Sentry.captureMessage("Requesting ACCESS_MEDIA_LOCATION permission");
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                LOCATION_PERMISSION_REQUEST_CODE);
    }

    /**
     * Handles permission request results.
     * This should be called from the host Activity's onRequestPermissionsResult method.
     *
     * @param requestCode The request code passed to requestPermissions
     * @param permissions The requested permissions
     * @param grantResults The grant results for the permissions
     */
    public void handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        try {
            // Route to appropriate handler based on request code
            if (requestCode == PERMISSION_REQUEST_CODE) {
                handleStoragePermissionResult(permissions, grantResults);
            } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
                handleLocationPermissionResult(permissions, grantResults);
            }
        } catch (Exception e) {
            // Log exception and fall back to simple result handling
            Sentry.captureException(new Exception("Error handling permission result: " + e.getMessage(), e));

            boolean allGranted = grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (allGranted) {
                    callback.onPermissionsGranted();
                } else {
                    callback.onPermissionsDenied();
                    handlePermissionDenial();
                }
            } else {
                if (allGranted) {
                    callback.onLocationPermissionGranted();
                } else {
                    callback.onLocationPermissionDenied();
                    handleLocationPermissionDenial();
                }
            }
        }
    }

    /**
     * Handles storage permission request results.
     * Processes the results of the storage/media permission requests.
     *
     * @param permissions Array of permission strings that were requested
     * @param grantResults Array of grant results for each permission
     */
    private void handleStoragePermissionResult(String[] permissions, int[] grantResults) {
        boolean allPermissionsGranted = true;

        // Check each permission result
        for (int i = 0; i < permissions.length; i++) {
            String permission = permissions[i];
            boolean granted = (i < grantResults.length) &&
                    (grantResults[i] == PackageManager.PERMISSION_GRANTED);

            // Check if permission was granted
            if (!granted) {
                allPermissionsGranted = false;
            }
        }
        
        if (allPermissionsGranted) {
            // All permissions were granted
            Sentry.captureMessage("All permissions granted");
            callback.onPermissionsGranted();
        } else {
            // At least one permission was denied
            Sentry.captureMessage("Some permissions denied");
            callback.onPermissionsDenied();
            handlePermissionDenial();
        }
    }

    /**
     * Handles location permission request results.
     * Processes the results of the ACCESS_MEDIA_LOCATION permission request.
     *
     * @param permissions Array of permission strings that were requested
     * @param grantResults Array of grant results for each permission
     */
    private void handleLocationPermissionResult(String[] permissions, int[] grantResults) {
        boolean locationPermissionGranted = false;

        // Check each permission result
        for (int i = 0; i < permissions.length; i++) {
            String permission = permissions[i];
            boolean granted = (i < grantResults.length) &&
                    (grantResults[i] == PackageManager.PERMISSION_GRANTED);

            if (Manifest.permission.ACCESS_MEDIA_LOCATION.equals(permission)) {
                locationPermissionGranted = granted;            }
        }

        if (locationPermissionGranted) {
            // Location permission was granted
            Sentry.captureMessage("Location permission granted");
            callback.onLocationPermissionGranted();
        } else {
            // Location permission was denied
            Sentry.captureMessage("Location permission denied");
            callback.onLocationPermissionDenied();
            handleLocationPermissionDenial();
        }
    }

    /**
     * Handles the case when permissions are denied.
     * Shows appropriate UI based on whether the denial is temporary or permanent.
     *
     * A permanent denial occurs when the user selects "Don't ask again" or "Deny"
     * multiple times, requiring the user to enable permissions from Settings.
     */
    private void handlePermissionDenial() {
        try {
            boolean shouldShowSettings;

            // Check if this is a permanent denial based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean canAskImagesAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean canAskVideoAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);                // If we've shown rationale before and now Android says we can't show it again,
                // this indicates a permanent denial
                shouldShowSettings = hasShownRationale && (!canAskImagesAgain || !canAskVideoAgain);
            } else {
                boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);                shouldShowSettings = hasShownRationale && !canAskAgain;
            }            if (shouldShowSettings) {
                // Show settings Snackbar for permanent denial
                Sentry.captureMessage("Showing settings snackbar (permanent denial)");
                Snackbar.make(
                                rootView,
                                "Permission denied permanently! Please enable in settings.",
                                Snackbar.LENGTH_LONG)
                        .setAction("SETTINGS", view -> {
                            Sentry.captureMessage("User opening app settings");
                            // Open app settings page
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                            intent.setData(uri);
                            settingsLauncher.launch(intent);
                        })
                        .show();
            } else {
                // Show retry Snackbar for temporary denial
                Sentry.captureMessage("Showing retry snackbar (temporary denial)");
                Snackbar.make(
                                rootView,
                                "Permissions are required to select media files!",
                                Snackbar.LENGTH_LONG)
                        .setAction("RETRY", view -> {
                            Sentry.captureMessage("User retrying permission request");
                            requestStoragePermission();
                        })
                        .show();
            }
        } catch (Exception e) {
            Sentry.captureException(new Exception("Error handling permission denial: " + e.getMessage(), e));
        }
    }

    /**
     * Handles the case when location permission is denied.
     * Shows appropriate UI based on whether the denial is temporary or permanent.
     *
     * A permanent denial occurs when the user selects "Don't ask again" or "Deny"
     * multiple times, requiring the user to enable permissions from Settings.
     */
    private void handleLocationPermissionDenial() {
        try {
            boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.ACCESS_MEDIA_LOCATION);            // If we've shown rationale before and now Android says we can't show it again,
            // this indicates a permanent denial
            boolean shouldShowSettings = hasShownLocationRationale && !canAskAgain;            if (shouldShowSettings) {
                // Show settings Snackbar for permanent denial
                Sentry.captureMessage("Showing settings snackbar for location (permanent denial)");
                Snackbar.make(
                                rootView,
                                "Location permission denied permanently! Enable in settings to access media location data.",
                                Snackbar.LENGTH_LONG)
                        .setAction("SETTINGS", view -> {
                            Sentry.captureMessage("User opening app settings for location permission");
                            // Open app settings page
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                            intent.setData(uri);
                            settingsLauncher.launch(intent);
                        })
                        .show();
            } else {
                // Show retry Snackbar for temporary denial
                Sentry.captureMessage("Showing retry snackbar for location (temporary denial)");
                Snackbar.make(
                                rootView,
                                "Location permission is needed to access geolocation data in media files.",
                                Snackbar.LENGTH_LONG)
                        .setAction("RETRY", view -> {
                            Sentry.captureMessage("User retrying location permission request");
                            requestLocationPermission();
                        })
                        .show();
            }
        } catch (Exception e) {
            Sentry.captureException(new Exception("Error handling location permission denial: " + e.getMessage(), e));
        }
    }

    /**
     * Returns the permission request code used by this manager.
     * This can be used to identify permission requests in onRequestPermissionsResult.
     *
     * @return The permission request code
     */
    public int getPermissionRequestCode() {
        return PERMISSION_REQUEST_CODE;
    }

    /**
     * Returns the location permission request code used by this manager.
     * This can be used to identify location permission requests in onRequestPermissionsResult.
     *
     * @return The location permission request code
     */
    public int getLocationPermissionRequestCode() {
        return LOCATION_PERMISSION_REQUEST_CODE;
    }
}
