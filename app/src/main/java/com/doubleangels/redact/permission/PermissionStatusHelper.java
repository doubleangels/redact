package com.doubleangels.redact.permission;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Read-only permission status checks for the Settings screen.
 */
public final class PermissionStatusHelper {

    static Integer testSdkIntOverride;

    public enum Status {
        GRANTED,
        DENIED,
        NOT_REQUIRED
    }

    private PermissionStatusHelper() {
    }

    @android.annotation.SuppressLint("InlinedApi")
    @NonNull
    public static Status getMediaAccessStatus(@NonNull Context context) {
        Context app = context.getApplicationContext();
        int sdk = testSdkIntOverride != null ? testSdkIntOverride : Build.VERSION.SDK_INT;
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            boolean images = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
            boolean videos = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
            return images && videos ? Status.GRANTED : Status.DENIED;
        }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            boolean images = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
            boolean videos = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
            return images && videos ? Status.GRANTED : Status.DENIED;
        }
        return ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED ? Status.GRANTED : Status.DENIED;
    }

    @NonNull
    public static Status getLocationStatus(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_MEDIA_LOCATION)
                == PackageManager.PERMISSION_GRANTED ? Status.GRANTED : Status.DENIED;
    }

    @NonNull
    public static Status getNotificationsStatus(@NonNull Context context) {
        Context app = context.getApplicationContext();
        int sdk = testSdkIntOverride != null ? testSdkIntOverride : Build.VERSION.SDK_INT;
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return Status.DENIED;
            }
            return NotificationManagerCompat.from(app).areNotificationsEnabled()
                    ? Status.GRANTED : Status.DENIED;
        }
        if (sdk >= Build.VERSION_CODES.N) {
            return NotificationManagerCompat.from(app).areNotificationsEnabled()
                    ? Status.GRANTED : Status.DENIED;
        }
        return Status.NOT_REQUIRED;
    }
}
