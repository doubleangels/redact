package com.doubleangels.redact.ui;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;

/**
 * Applies the "Hide Content in Screenshots and Recents" setting to an activity's window.
 *
 * <p>{@link WindowManager.LayoutParams#FLAG_SECURE} blocks screenshots and screen recording of the
 * window and blanks its preview on the recent apps screen. It can be changed on a live window, so a
 * settings change takes effect without restarting the activity.
 */
public final class SecureWindow {

    private SecureWindow() {
    }

    /** Sets or clears the flag on {@code activity}'s window to match the current preference. */
    public static void apply(@NonNull Activity activity) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        if (AppPreferences.isSecureWindow(activity) || needsFlagForPhoto(window)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /**
     * Keeps the window out of the recent apps preview while a photo is open in Censor, whatever the
     * setting, so the uncovered photo is never shown there. Android 13 and later can hide only the
     * preview; Android 12 has no such switch, so the window is made secure until the photo closes.
     */
    public static void setPhotoOpen(@NonNull Activity activity, boolean open) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (Boolean.valueOf(open).equals(decor.getTag(R.id.secure_window_photo_open))) {
            return;
        }
        decor.setTag(R.id.secure_window_photo_open, open);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!open);
        } else {
            apply(activity);
        }
    }

    private static boolean needsFlagForPhoto(@NonNull Window window) {
        // peekDecorView: apply() runs before setContentView, when there is no decor to create yet.
        View decor = window.peekDecorView();
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && decor != null
                && Boolean.TRUE.equals(decor.getTag(R.id.secure_window_photo_open));
    }
}
