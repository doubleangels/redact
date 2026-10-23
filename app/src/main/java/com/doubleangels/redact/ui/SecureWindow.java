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

    /** What Censor shows: the photo, before it is covered. */
    public static final String SOURCE_CENSOR = "censor";
    /** What Scan shows: the photo and what it gives away, its location included. */
    public static final String SOURCE_SCAN = "scan";

    /** Like {@link #setSensitiveContentShowing}, for Censor's open photo. */
    public static void setPhotoOpen(@NonNull Activity activity, boolean open) {
        setSensitiveContentShowing(activity, SOURCE_CENSOR, open);
    }

    /**
     * Keeps the window out of the recent apps preview while any tab shows a photo or its metadata,
     * whatever the setting, so neither is ever shown there. {@code source} names the tab, so one
     * tab closing its photo does not uncover another's. Android 13 and later can hide only the
     * preview; Android 12 has no such switch, so the window is made secure meanwhile.
     */
    public static void setSensitiveContentShowing(@NonNull Activity activity, @NonNull String source,
                                                  boolean showing) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        java.util.Set<String> before = showingSources(decor);
        java.util.Set<String> after = new java.util.HashSet<>(before);
        if (showing) {
            after.add(source);
        } else {
            after.remove(source);
        }
        decor.setTag(R.id.secure_window_photo_open, after);
        if (before.isEmpty() == after.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(after.isEmpty());
        } else {
            apply(activity);
        }
    }

    @NonNull
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> showingSources(@NonNull View decor) {
        Object tag = decor.getTag(R.id.secure_window_photo_open);
        return tag instanceof java.util.Set<?> set ? (java.util.Set<String>) set : java.util.Collections.emptySet();
    }

    private static boolean needsFlagForPhoto(@NonNull Window window) {
        // peekDecorView: apply() runs before setContentView, when there is no decor to create yet.
        View decor = window.peekDecorView();
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && decor != null
                && !showingSources(decor).isEmpty();
    }
}
