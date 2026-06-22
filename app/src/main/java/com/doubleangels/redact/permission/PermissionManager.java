package com.doubleangels.redact.permission;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.doubleangels.redact.sentry.SentryManager;

/**
 * Manages runtime permissions for media access in Android applications.
 *
 * This class handles the complexities of requesting and managing permissions across different
 * Android versions (particularly the changes in Android 13/Tiramisu). It includes:
 * - Permission checking and requesting
 * - Handling permission denials
 * - Integration with Sentry for permission-related diagnostics
 *
 * Usage pattern:
 * 1. Create an instance in your Activity with appropriate callback
 * 2. Call checkPermissions() to start the permission flow
 * 3. Forward onRequestPermissionsResult() calls to handlePermissionResult()
 * 4. Implement PermissionCallback to respond to permission state changes
 */
public class PermissionManager {
    // Request codes for identifying permission requests in onRequestPermissionsResult
    public static final int STORAGE_PERMISSION_REQUEST_CODE = 123;
    public static final int LOCATION_PERMISSION_REQUEST_CODE = 124;
    private static final String TAG = "PermissionManager";

    private static final class PendingPermissionResult {
        final int requestCode;
        final String[] permissions;
        final int[] grantResults;

        PendingPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
            this.requestCode = requestCode;
            this.permissions = permissions.clone();
            this.grantResults = grantResults.clone();
        }
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingPermissionResult>
            pendingPermissionResults = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Avoid stacking duplicate system dialogs when MainActivity and fragments both check on launch. */
    private static volatile boolean runtimePermissionRequestInFlight = false;

    /** Activity context used for permission requests */
    private final Activity activity;

    /** Callback interface for permission status updates */
    private final PermissionCallback callback;

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

    /** Tracks whether a permission request has been shown to the user */
    private boolean hasShownRationale = false;

    /** Tracks whether location permission request has been shown to the user */
    private boolean hasShownLocationRationale = false;

    /**
     * Creates a new PermissionManager instance.
     *
     * @param activity The host Activity
     * @param callback Callback interface for permission status updates
     */
    public PermissionManager(Activity activity, PermissionCallback callback) {
        this.activity = activity;
        this.callback = callback;

        // Log device SDK version and package name for diagnostics
        SentryManager.setCustomKey("device_sdk", Build.VERSION.SDK_INT);
        SentryManager.setCustomKey("app_package", activity.getPackageName());
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
            java.util.List<String> missing = collectMissingRuntimePermissions(activity);
            SentryManager.setCustomKey("needs_permissions", !missing.isEmpty());

            if (missing.isEmpty()) {
                SentryManager.log("All permissions already granted");
                notifyAllGranted();
            } else if (runtimePermissionRequestInFlight) {
                SentryManager.log("Runtime permission request already in flight");
                callback.onPermissionsRequestStarted();
            } else {
                SentryManager.log("Starting permission request flow");
                callback.onPermissionsRequestStarted();
                requestMissingRuntimePermissions(activity);
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            java.util.List<String> missing = collectMissingRuntimePermissions(activity);
            if (missing.isEmpty()) {
                notifyAllGranted();
            } else if (!runtimePermissionRequestInFlight) {
                callback.onPermissionsRequestStarted();
                requestMissingRuntimePermissions(activity);
            }
        }
    }

    private void notifyAllGranted() {
        callback.onPermissionsGranted();
        if (!needsLocationPermission()) {
            callback.onLocationPermissionGranted();
        }
    }

    public boolean needsPermissions() {
        try {
            boolean result;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34): full access OR user-selected partial access are both valid.
                // READ_MEDIA_VISUAL_USER_SELECTED is granted when the user picks specific photos/videos
                // rather than granting all-or-nothing access.
                boolean hasImagePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
                boolean hasVideoPermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
                boolean hasUserSelectedPermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;

                SentryManager.setCustomKey("has_image_permission", hasImagePermission);
                SentryManager.setCustomKey("has_video_permission", hasVideoPermission);
                SentryManager.setCustomKey("has_user_selected_permission", hasUserSelectedPermission);

                // Either full (IMAGES+VIDEO) or partial (USER_SELECTED) grants are acceptable.
                result = !(hasImagePermission && hasVideoPermission) && !hasUserSelectedPermission;

            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13 (API 33): granular media permissions, no partial access option.
                boolean hasImagePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
                boolean hasVideoPermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;

                SentryManager.setCustomKey("has_image_permission", hasImagePermission);
                SentryManager.setCustomKey("has_video_permission", hasVideoPermission);

                result = !hasImagePermission || !hasVideoPermission;
            } else {
                // Pre-Android 13: legacy READ_EXTERNAL_STORAGE
                boolean hasStoragePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

                SentryManager.setCustomKey("has_storage_permission", hasStoragePermission);
                result = !hasStoragePermission;
            }
            return result;
        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error checking permissions: " + e.getMessage(), e));
            return true;
        }
    }

    /** On API 33+, the system photo picker does not require READ_MEDIA_* for selection. */
    public static boolean canUseSystemPhotoPickerWithoutMediaRead() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /** Whether the picker should be blocked until storage permissions are granted. */
    public boolean shouldRequestStorageBeforePicker() {
        return !canUseSystemPhotoPickerWithoutMediaRead() && needsPermissions();
    }

    /**
     * Whether Select can open a file picker. On API 33+, SAF does not require READ_MEDIA_*.
     */
    public boolean isMediaPickerAvailable() {
        return !shouldRequestStorageBeforePicker() || !needsPermissions();
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

            SentryManager.setCustomKey("has_location_permission", hasLocationPermission);
            return !hasLocationPermission;
        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error checking location permission: " + e.getMessage(), e));
            return true;
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
            hasShownRationale = true;
            SentryManager.setCustomKey("has_shown_rationale", true);
            SentryManager.log("Requesting media/storage permissions");
            requestMissingRuntimePermissions(activity);
        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error requesting permissions: " + e.getMessage(), e));
            requestMissingRuntimePermissions(activity);
        }
    }

    /**
     * Requests the ACCESS_MEDIA_LOCATION permission for accessing media geolocation data.
     */
    public void requestLocationPermission() {
        try {
            hasShownLocationRationale = true;
            SentryManager.setCustomKey("has_shown_location_rationale", true);
            SentryManager.log("Requesting ACCESS_MEDIA_LOCATION permission");
            requestMediaLocationPermission();
        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error requesting location permission: " + e.getMessage(), e));
            requestMediaLocationPermission();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestMediaPermissions() {
        requestMissingRuntimePermissions(activity);
    }

    private void requestStoragePermissions() {
        requestMissingRuntimePermissions(activity);
    }

    private void requestMediaLocationPermission() {
        if (!needsLocationPermission()) {
            return;
        }
        runtimePermissionRequestInFlight = true;
        SentryManager.log("Requesting ACCESS_MEDIA_LOCATION permission");
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
    /**
     * Stores a permission result delivered before fragment {@link PermissionManager} instances exist.
     */
    public static void storeActivityPermissionResult(
            int requestCode, String[] permissions, int[] grantResults) {
        runtimePermissionRequestInFlight = false;
        pendingPermissionResults.offer(
                new PendingPermissionResult(requestCode, permissions, grantResults));
    }

    /** Applies a stored activity-level permission result matching one of the given request codes. */
    public void applyPendingPermissionResultIfAny(int... requestCodes) {
        if (requestCodes == null || requestCodes.length == 0) {
            return;
        }
        for (PendingPermissionResult pending : pendingPermissionResults) {
            for (int requestCode : requestCodes) {
                if (pending.requestCode == requestCode
                        && pendingPermissionResults.remove(pending)) {
                    handlePermissionResult(
                            pending.requestCode, pending.permissions, pending.grantResults);
                    return;
                }
            }
        }
    }

    public void handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        try {
            // Route to appropriate handler based on request code
            if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
                handleStoragePermissionResult(permissions, grantResults);
            } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
                handleLocationPermissionResult(permissions, grantResults);
            }
        } catch (Exception e) {
            // Log exception and fall back to simple result handling
            SentryManager.recordException(new Exception("Error handling permission result: " + e.getMessage(), e));

            boolean allGranted = grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
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
        runtimePermissionRequestInFlight = false;
        // Log individual permission results for diagnostics
        for (int i = 0; i < permissions.length; i++) {
            String permission = permissions[i];
            boolean granted = (i < grantResults.length) &&
                    (grantResults[i] == PackageManager.PERMISSION_GRANTED);
            SentryManager.setCustomKey("permission_" + permission.replace(".", "_"), granted);
        }

        // On Android 14+, READ_MEDIA_VISUAL_USER_SELECTED will be denied when the user
        // chooses "Allow All" (full access), and READ_MEDIA_IMAGES/VIDEO will be denied
        // when the user chooses "Select Photos" (partial access). Checking individual grant
        // results is therefore unreliable; delegate to needsPermissions() which handles all
        // three cases correctly: full access, partial access, and denied.
        boolean stillNeedsPermissions = needsPermissions();
        SentryManager.setCustomKey("all_permissions_granted", !stillNeedsPermissions);

        if (!stillNeedsPermissions) {
            SentryManager.log("All media permissions granted");
            callback.onPermissionsGranted();
        } else {
            SentryManager.log("Some media permissions denied");
            callback.onPermissionsDenied();
            handlePermissionDenial();
        }

        notifyLocationPermissionResult(permissions);
    }

    private void notifyLocationPermissionResult(String[] permissions) {
        boolean locationRequested = false;
        for (String permission : permissions) {
            if (Manifest.permission.ACCESS_MEDIA_LOCATION.equals(permission)) {
                locationRequested = true;
                break;
            }
        }
        if (!needsLocationPermission()) {
            SentryManager.log("Location permission granted");
            callback.onLocationPermissionGranted();
        } else if (locationRequested) {
            SentryManager.log("Location permission denied");
            callback.onLocationPermissionDenied();
            handleLocationPermissionDenial();
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
        runtimePermissionRequestInFlight = false;
        boolean locationPermissionGranted = false;

        // Check each permission result
        for (int i = 0; i < permissions.length; i++) {
            String permission = permissions[i];
            boolean granted = (i < grantResults.length) &&
                    (grantResults[i] == PackageManager.PERMISSION_GRANTED);

            if (Manifest.permission.ACCESS_MEDIA_LOCATION.equals(permission)) {
                locationPermissionGranted = granted;
                SentryManager.setCustomKey("permission_ACCESS_MEDIA_LOCATION", granted);
            }
        }

        if (locationPermissionGranted) {
            // Location permission was granted
            SentryManager.log("Location permission granted");
            callback.onLocationPermissionGranted();
        } else {
            // Location permission was denied
            SentryManager.log("Location permission denied");
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean canAskImagesAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean canAskVideoAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);
                boolean hasPartialAccess = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        == PackageManager.PERMISSION_GRANTED;

                SentryManager.setCustomKey("can_ask_images_again", canAskImagesAgain);
                SentryManager.setCustomKey("can_ask_video_again", canAskVideoAgain);
                SentryManager.setCustomKey("has_partial_media_access", hasPartialAccess);
                SentryManager.setCustomKey("should_show_settings",
                        hasShownRationale && !hasPartialAccess
                                && (!canAskImagesAgain || !canAskVideoAgain));
            } else {
                boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);

                SentryManager.setCustomKey("can_ask_storage_again", canAskAgain);
                SentryManager.setCustomKey("should_show_settings", hasShownRationale && !canAskAgain);
            }
            SentryManager.log("Media/storage permissions denied");
        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error handling permission denial: " + e.getMessage(), e));
        }
    }

    private void handleLocationPermissionDenial() {
        try {
            boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.ACCESS_MEDIA_LOCATION);

            SentryManager.setCustomKey("can_ask_location_again", canAskAgain);
            SentryManager.setCustomKey("should_show_location_settings",
                    hasShownLocationRationale && !canAskAgain);
            SentryManager.log("Location permission denied");
        } catch (Exception e) {
            SentryManager.recordException(new Exception("Error handling location permission denial: "
                    + e.getMessage(), e));
        }
    }

    /**
     * Returns the permission request code used by this manager.
     * This can be used to identify permission requests in onRequestPermissionsResult.
     *
     * @return The permission request code
     */
    public int getPermissionRequestCode() {
        return STORAGE_PERMISSION_REQUEST_CODE;
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

    /**
     * Requests all necessary permissions (media, location, notifications) on first startup.
     * This avoids prompting the user sequentially across different screens.
     */
    public static void requestAllInitialPermissions(Activity activity) {
        requestMissingRuntimePermissions(activity);
    }

    static void requestMissingRuntimePermissions(Activity activity) {
        try {
            java.util.List<String> permissions = collectMissingRuntimePermissions(activity);
            if (permissions.isEmpty()) {
                runtimePermissionRequestInFlight = false;
                return;
            }
            runtimePermissionRequestInFlight = true;
            SentryManager.log("Requesting runtime permissions count: " + permissions.size());
            ActivityCompat.requestPermissions(
                    activity, permissions.toArray(new String[0]), STORAGE_PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            runtimePermissionRequestInFlight = false;
            SentryManager.recordException(new Exception("Error requesting runtime permissions: "
                    + e.getMessage(), e));
        }
    }

    static java.util.List<String> collectMissingRuntimePermissions(Activity activity) {
        java.util.List<String> permissions = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        return permissions;
    }

    /** Requests missing runtime permissions when the activity resumes without an in-flight dialog. */
    public static void requestInitialPermissionsIfNeeded(Activity activity) {
        if (runtimePermissionRequestInFlight) {
            return;
        }
        if (!collectMissingRuntimePermissions(activity).isEmpty()) {
            requestMissingRuntimePermissions(activity);
        }
    }

    /** Clears an in-flight permission dialog flag when the host activity is destroyed. */
    public static void clearRuntimePermissionRequestOnDestroy() {
        runtimePermissionRequestInFlight = false;
    }

    /** Visible for unit tests. */
    static void resetRuntimePermissionRequestStateForTests() {
        runtimePermissionRequestInFlight = false;
        pendingPermissionResults.clear();
    }
}
