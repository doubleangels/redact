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
 */
public class PermissionManager {
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final String TAG = "PermissionManager";

    /** Activity context used for permission requests */
    private final Activity activity;

    /** Root view for displaying Snackbar messages */
    private final View rootView;

    /** Launcher for settings intent when permissions are permanently denied */
    private final ActivityResultLauncher<Intent> settingsLauncher;

    /** Tracks whether permission rationale has been shown to the user */
    private boolean hasShownRationale = false;

    /** Callback interface for permission status updates */
    private final PermissionCallback callback;

    /** Firebase Crashlytics instance for logging events and errors */
    private final FirebaseCrashlytics crashlytics;

    /**
     * Interface for notifying permission status changes to clients.
     */
    public interface PermissionCallback {
        /** Called when all required permissions are granted */
        void onPermissionsGranted();

        /** Called when one or more required permissions are denied */
        void onPermissionsDenied();

        /** Called when the permission request process begins */
        void onPermissionsRequestStarted();
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

        crashlytics.setCustomKey("device_sdk", Build.VERSION.SDK_INT);
        crashlytics.setCustomKey("app_package", activity.getPackageName());
    }

    /**
     * Checks if permissions are needed and initiates the permission request flow if necessary.
     * This is the main entry point for permission handling.
     */
    public void checkPermissions() {
        try {
            boolean needsPermissions = needsPermissions();
            crashlytics.setCustomKey("needs_permissions", needsPermissions);

            if (needsPermissions) {
                crashlytics.log("Starting permission request flow");
                callback.onPermissionsRequestStarted();
                requestStoragePermission();
            } else {
                crashlytics.log("All permissions already granted");
                callback.onPermissionsGranted();
            }
        } catch (Exception e) {
            crashlytics.recordException(e);
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
     * @return true if permissions need to be requested, false if all are granted
     */
    public boolean needsPermissions() {
        try {
            boolean result;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean hasImagePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
                boolean hasVideoPermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;

                crashlytics.setCustomKey("has_image_permission", hasImagePermission);
                crashlytics.setCustomKey("has_video_permission", hasVideoPermission);

                result = !hasImagePermission || !hasVideoPermission;
            } else {
                boolean hasStoragePermission = ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

                crashlytics.setCustomKey("has_storage_permission", hasStoragePermission);
                result = !hasStoragePermission;
            }
            return result;
        } catch (Exception e) {
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
     * Requests the appropriate storage or media permissions based on Android version.
     * Shows rationale UI when required before requesting permissions.
     */
    public void requestStoragePermission() {
        try {
            hasShownRationale = true;
            crashlytics.setCustomKey("has_shown_rationale", true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean shouldShowImageRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean shouldShowVideoRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);

                crashlytics.setCustomKey("should_show_image_rationale", shouldShowImageRationale);
                crashlytics.setCustomKey("should_show_video_rationale", shouldShowVideoRationale);
                crashlytics.log("Requesting Android 13+ media permissions");

                if (shouldShowImageRationale || shouldShowVideoRationale) {
                    showMediaRationaleSnackbar();
                } else {
                    requestMediaPermissions();
                }
            } else {
                boolean shouldShowStorageRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);

                crashlytics.setCustomKey("should_show_storage_rationale", shouldShowStorageRationale);
                crashlytics.log("Requesting Android 12 storage permission");

                if (shouldShowStorageRationale) {
                    showStorageRationaleSnackbar();
                } else {
                    requestStoragePermissions();
                }
            }
        } catch (Exception e) {
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
     * Shows a Snackbar explaining why media permissions are needed (for Android 13+).
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
     */
    private void requestStoragePermissions() {
        crashlytics.log("Requesting READ_EXTERNAL_STORAGE permission");
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                PERMISSION_REQUEST_CODE);
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
            if (requestCode == PERMISSION_REQUEST_CODE) {
                boolean allPermissionsGranted = true;

                for (int i = 0; i < permissions.length; i++) {
                    String permission = permissions[i];
                    boolean granted = (i < grantResults.length) &&
                            (grantResults[i] == PackageManager.PERMISSION_GRANTED);

                    crashlytics.setCustomKey("permission_" + permission.replace(".", "_"), granted);

                    if (!granted) {
                        allPermissionsGranted = false;
                    }
                }

                crashlytics.setCustomKey("all_permissions_granted", allPermissionsGranted);

                if (allPermissionsGranted) {
                    crashlytics.log("All permissions granted");
                    callback.onPermissionsGranted();
                } else {
                    crashlytics.log("Some permissions denied");
                    callback.onPermissionsDenied();
                    handlePermissionDenial();
                }
            }
        } catch (Exception e) {
            crashlytics.recordException(new Exception("Error handling permission result: " + e.getMessage(), e));

            boolean allGranted = grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (allGranted) {
                callback.onPermissionsGranted();
            } else {
                callback.onPermissionsDenied();
                handlePermissionDenial();
            }
        }
    }

    /**
     * Handles the case when permissions are denied.
     * Shows appropriate UI based on whether the denial is temporary or permanent.
     */
    private void handlePermissionDenial() {
        try {
            boolean shouldShowSettings;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean canAskImagesAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_IMAGES);
                boolean canAskVideoAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_MEDIA_VIDEO);

                crashlytics.setCustomKey("can_ask_images_again", canAskImagesAgain);
                crashlytics.setCustomKey("can_ask_video_again", canAskVideoAgain);

                shouldShowSettings = hasShownRationale && (!canAskImagesAgain || !canAskVideoAgain);
            } else {
                boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.READ_EXTERNAL_STORAGE);

                crashlytics.setCustomKey("can_ask_storage_again", canAskAgain);
                shouldShowSettings = hasShownRationale && !canAskAgain;
            }

            crashlytics.setCustomKey("should_show_settings", shouldShowSettings);

            if (shouldShowSettings) {
                crashlytics.log("Showing settings snackbar (permanent denial)");
                Snackbar.make(
                                rootView,
                                "Permission denied permanently! Please enable in settings.",
                                Snackbar.LENGTH_LONG)
                        .setAction("SETTINGS", view -> {
                            crashlytics.log("User opening app settings");
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                            intent.setData(uri);
                            settingsLauncher.launch(intent);
                        })
                        .show();
            } else {
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
     * Returns the permission request code used by this manager.
     *
     * @return The permission request code
     */
    public int getPermissionRequestCode() {
        return PERMISSION_REQUEST_CODE;
    }
}
