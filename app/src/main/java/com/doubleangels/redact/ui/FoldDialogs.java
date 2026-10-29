package com.doubleangels.redact.ui;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.MainActivity;

/**
 * Keeps a dialog off the hinge. A dialog is centred on the whole window, which on a folded screen
 * is the hinge itself: folded like a book it moves into the half the app uses and takes that
 * half's width, and on a table it is centred in the top half.
 */
public final class FoldDialogs {

    private FoldDialogs() {}

    /**
     * Places {@code dialog}, already shown, for the fold; returns it so a call can wrap {@code show()}.
     * Left and right are meant: the hinge is a physical place that does not swap sides for a
     * right-to-left language.
     */
    @SuppressLint("RtlHardcoded")
    @Nullable
    public static <T extends Dialog> T place(@Nullable T dialog) {
        if (dialog == null) {
            return null;
        }
        Window window = dialog.getWindow();
        // Only MainActivity follows the fold; the other screens fill the whole window.
        if (window == null || !(Notice.activityOf(dialog.getContext()) instanceof MainActivity activity)) {
            return dialog;
        }
        FoldPosture posture = new ViewModelProvider(activity).get(MainViewModel.class).getFoldPosture().getValue();
        if (posture == null || !posture.isSplit()) {
            return dialog;
        }
        WindowManager.LayoutParams params = window.getAttributes();
        if (posture.type() == FoldPosture.Type.BOOK) {
            int windowWidth = activity.getWindow().getDecorView().getWidth();
            window.setLayout(windowWidth - posture.leftInset() - posture.rightInset(),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_VERTICAL
                    | (posture.leftInset() > 0 ? Gravity.RIGHT : Gravity.LEFT);
            params.x = 0;
            window.setAttributes(params);
            return dialog;
        }
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        window.setAttributes(params);
        // The dialog's height is known only once it is laid out. Its window starts below the status bar.
        int hingeTop = posture.hinge().top;
        View decor = window.getDecorView();
        decor.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            int statusBar = insets != null ? insets.getInsets(WindowInsets.Type.statusBars()).top : 0;
            int y = Math.max(0, (hingeTop - statusBar - (bottom - top)) / 2);
            WindowManager.LayoutParams current = window.getAttributes();
            if (current.y != y) {
                current.y = y;
                window.setAttributes(current);
            }
        });
        return dialog;
    }
}
