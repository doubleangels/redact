package com.doubleangels.redact.ui;

import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Subtle haptic feedback for the few moments that deserve it. Goes through
 * {@link View#performHapticFeedback}, so it follows the system's touch-feedback setting and needs
 * no permission or setting of its own.
 */
public final class Haptics {

    private Haptics() {
    }

    /** Something finished well: a clean or convert completed, or text was copied. */
    public static void confirm(@Nullable View view) {
        perform(view, HapticFeedbackConstants.CONFIRM);
    }

    /** A run finished with nothing to show for it, because every file failed. */
    public static void reject(@Nullable View view) {
        perform(view, HapticFeedbackConstants.REJECT);
    }

    /** A light tick for a press that starts or stops something, such as Share or Cancel. */
    public static void tick(@Nullable View view) {
        perform(view, HapticFeedbackConstants.CONTEXT_CLICK);
    }

    /**
     * Confirms a run that just finished: {@link #confirm} when at least one file succeeded,
     * {@link #reject} when all of them failed.
     */
    public static void runFinished(@Nullable View view, int successCount) {
        if (successCount > 0) {
            confirm(view);
        } else {
            reject(view);
        }
    }

    private static void perform(@Nullable View view, int feedback) {
        if (view != null) {
            view.performHapticFeedback(feedback);
        }
    }

    /**
     * True only when the state moves from {@code PROCESSING} to {@code COMPLETED} while the screen
     * is watching. The completed state is re-delivered to every new view (after a rotation, say),
     * and that replay must not buzz again.
     */
    public static boolean justCompleted(@Nullable MainViewModel.ProcessingState previous,
                                        @NonNull MainViewModel.ProcessingState current) {
        return previous == MainViewModel.ProcessingState.PROCESSING
                && current == MainViewModel.ProcessingState.COMPLETED;
    }
}
