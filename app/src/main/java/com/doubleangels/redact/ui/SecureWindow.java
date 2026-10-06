package com.doubleangels.redact.ui;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.doubleangels.redact.AppPreferences;

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
        if (AppPreferences.isSecureWindow(activity)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
