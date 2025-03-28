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
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Manages runtime permissions for media access in Android applications.
 *
 * This class handles the complexities of requesting and managing permissions across different
 * Android versions (particularly the changes in Android 13/Tiramisu). It includes:
 * - Permission checking and requesting
 * - UI interactions for permission rationales
 * - Handling both temporary and permanent permission denials
 * - Integration with Firebase Crashlytics for permission-related analytics
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

    /** Firebase Crashlytics instance for logging events and errors */
    private final FirebaseCrashlytics crashlytics;

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
        this.crashlytics = FirebaseCrashlytics.getInstance();

        // Log device SDK version and package name for diagnostics
        crashlytics.setCustomKey("device_sdk", Build.VERSION.SDK_INT);
        crashlytics.setCustomKey("app_package", activity.getPackageName());
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
            crashlytics.setCustomKey("needs_permissions", needsPermissions);

            if (needsPermissions) {
                // Start permission flow if permissions are needed
                crashlytics.log("Starting permission request flow");
                callback.onPermissionsRequestStarted();
                requestStoragePermission();
            } else {
                // Notify that all permissions are already granted
                crashlytics.log("All permissions already granted");
                callback.onPermissionsGranted();
            }
        } catch (Exception e) {
            // Handle any exceptions during permission checking
            crashlytics.recordException(e);

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

                // Log permission status for diagnostics
                crashlytics.setCustomKey("has_image_permission", hasImagePermission);
                crashlytics.setCustomKey("has_video_permission", hasVideoPermission);

                // Need permissions if either images or videos permission is missing
                result = !hasImagePermission || !hasVideoPermission;
            } else {
                // Pre-Android 13 uses the storage permission
                boolean hasStoragePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

                crashlytics.setCustomKey("has_storage_permission", hasStoragePermission);
                result = !hasStoragePermission;
            }
            return result;
        } catch (Exception e) {
            // Log exception and fall back to direct permission check
            crashlytics.recordException(new Exception("Error checking permissions: " + e.getMessage(), e));

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
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            crashlytics.setCustomKey("has_location_permission", hasLocationPermission);
            return !hasLocationPermission;
        } catch (Exception e) {
            crashlytics.recordException(new Exception("Error checking location permission: " + e.getMessage(), e));
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
            hasShownRationale = true;
            crashlytics.setCustomKey("has_shown_rationale", true);

            // Android 13+ (API 33) uses granular media permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean shouldShowImageRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean shouldShowVideoRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);

                crashlytics.setCustomKey("should_show_image_rationale", shouldShowImageRationale);
                crashlytics.setCustomKey("should_show_video_rationale", shouldShowVideoRationale);
                crashlytics.log("Requesting Android 13+ media permissions");

                // Show rationale if Android indicates we should
                if (shouldShowImageRationale || shouldShowVideoRationale) {
                    showMediaRationaleSnackbar();
                } else {
                    requestMediaPermissions();
                }
            } else {
                // Pre-Android 13 uses the storage permission
                boolean shouldShowStorageRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);

                crashlytics.setCustomKey("should_show_storage_rationale", shouldShowStorageRationale);
                crashlytics.log("Requesting Android 12 storage permission");

                // Show rationale if Android indicates we should
                if (shouldShowStorageRationale) {
                    showStorageRationaleSnackbar();
                } else {
                    requestStoragePermissions();
                }
            }
        } catch (Exception e) {
            // Log exception and fall back to direct permission request
            crashlytics.recordException(new Exception("Error requesting permissions: " + e.getMessage(), e));

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
            hasShownLocationRationale = true;
            crashlytics.setCustomKey("has_shown_location_rationale", true);

            boolean shouldShowLocationRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.ACCESS_MEDIA_LOCATION);

            crashlytics.setCustomKey("should_show_location_rationale", shouldShowLocationRationale);
            crashlytics.log("Requesting ACCESS_MEDIA_LOCATION permission");

            // Show rationale if Android indicates we should
            if (shouldShowLocationRationale) {
                showLocationRationaleSnackbar();
            } else {
                requestMediaLocationPermission();
            }
        } catch (Exception e) {
            // Log exception and fall back to direct permission request
            crashlytics.recordException(new Exception("Error requesting location permission: " + e.getMessage(), e));
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
        crashlytics.log("Showing media permissions rationale snackbar");
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
        crashlytics.log("Requesting READ_MEDIA_IMAGES and READ_MEDIA_VIDEO permissions");
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
        crashlytics.log("Showing storage permission rationale snackbar");
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
        crashlytics.log("Requesting READ_EXTERNAL_STORAGE permission");
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                PERMISSION_REQUEST_CODE);
    }

    /**
     * Shows a Snackbar explaining why location permission is needed.
     * This provides context to the user about why the app needs this permission.
     */
    private void showLocationRationaleSnackbar() {
        crashlytics.log("Showing location permission rationale snackbar");
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
        crashlytics.log("Requesting ACCESS_MEDIA_LOCATION permission");
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
            crashlytics.recordException(new Exception("Error handling permission result: " + e.getMessage(), e));

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

            // Log individual permission results for diagnostics
            crashlytics.setCustomKey("permission_" + permission.replace(".", "_"), granted);

            if (!granted) {
                allPermissionsGranted = false;
            }
        }

        crashlytics.setCustomKey("all_permissions_granted", allPermissionsGranted);

        if (allPermissionsGranted) {
            // All permissions were granted
            crashlytics.log("All permissions granted");
            callback.onPermissionsGranted();
        } else {
            // At least one permission was denied
            crashlytics.log("Some permissions denied");
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
                locationPermissionGranted = granted;
                crashlytics.setCustomKey("permission_ACCESS_MEDIA_LOCATION", granted);
            }
        }

        if (locationPermissionGranted) {
            // Location permission was granted
            crashlytics.log("Location permission granted");
            callback.onLocationPermissionGranted();
        } else {
            // Location permission was denied
            crashlytics.log("Location permission denied");
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
                        activity, Manifest.permission.READ_MEDIA_VIDEO);

                crashlytics.setCustomKey("can_ask_images_again", canAskImagesAgain);
                crashlytics.setCustomKey("can_ask_video_again", canAskVideoAgain);

                // If we've shown rationale before and now Android says we can't show it again,
                // this indicates a permanent denial
                shouldShowSettings = hasShownRationale && (!canAskImagesAgain || !canAskVideoAgain);
            } else {
                boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);

                crashlytics.setCustomKey("can_ask_storage_again", canAskAgain);
                shouldShowSettings = hasShownRationale && !canAskAgain;
            }

            crashlytics.setCustomKey("should_show_settings", shouldShowSettings);

            if (shouldShowSettings) {
                // Show settings Snackbar for permanent denial
                crashlytics.log("Showing settings snackbar (permanent denial)");
                Snackbar.make(
                                rootView,
                                "Permission denied permanently! Please enable in settings.",
                                Snackbar.LENGTH_LONG)
                        .setAction("SETTINGS", view -> {
                            crashlytics.log("User opening app settings");
                            // Open app settings page
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                            intent.setData(uri);
                            settingsLauncher.launch(intent);
                        })
                        .show();
            } else {
                // Show retry Snackbar for temporary denial
                crashlytics.log("Showing retry snackbar (temporary denial)");
                Snackbar.make(
                                rootView,
                                "Permissions are required to select media files!",
                                Snackbar.LENGTH_LONG)
                        .setAction("RETRY", view -> {
                            crashlytics.log("User retrying permission request");
                            requestStoragePermission();
                        })
                        .show();
            }
        } catch (Exception e) {
            crashlytics.recordException(new Exception("Error handling permission denial: " + e.getMessage(), e));
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
                    activity, Manifest.permission.ACCESS_MEDIA_LOCATION);

            crashlytics.setCustomKey("can_ask_location_again", canAskAgain);

            // If we've shown rationale before and now Android says we can't show it again,
            // this indicates a permanent denial
            boolean shouldShowSettings = hasShownLocationRationale && !canAskAgain;
            crashlytics.setCustomKey("should_show_location_settings", shouldShowSettings);

            if (shouldShowSettings) {
                // Show settings Snackbar for permanent denial
                crashlytics.log("Showing settings snackbar for location (permanent denial)");
                Snackbar.make(
                                rootView,
                                "Location permission denied permanently! Enable in settings to access media location data.",
                                Snackbar.LENGTH_LONG)
                        .setAction("SETTINGS", view -> {
                            crashlytics.log("User opening app settings for location permission");
                            // Open app settings page
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                            intent.setData(uri);
                            settingsLauncher.launch(intent);
                        })
                        .show();
            } else {
                // Show retry Snackbar for temporary denial
                crashlytics.log("Showing retry snackbar for location (temporary denial)");
                Snackbar.make(
                                rootView,
                                "Location permission is needed to access geolocation data in media files.",
                                Snackbar.LENGTH_LONG)
                        .setAction("RETRY", view -> {
                            crashlytics.log("User retrying location permission request");
                            requestLocationPermission();
                        })
                        .show();
            }
        } catch (Exception e) {
            crashlytics.recordException(new Exception("Error handling location permission denial: " + e.getMessage(), e));
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
