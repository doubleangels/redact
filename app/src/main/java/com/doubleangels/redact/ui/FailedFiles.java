package com.doubleangels.redact.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaProcessor;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Clean tab's list of the files a batch did not clean: a button under the status line that
 * says how many, and a dialog that names each with the reason and offers to try just those
 * again. Everything here stays on the phone; the names are shown, never reported.
 */
public final class FailedFiles {

    private FailedFiles() {
    }

    /** Shows {@code button} for {@code failed}, or hides it when there are none. */
    public static void bind(@Nullable MaterialButton button, @NonNull List<MediaProcessor.Failed> failed,
                            @NonNull Consumer<List<MediaItem>> retry) {
        if (button == null) {
            return;
        }
        if (failed.isEmpty()) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            return;
        }
        Context context = button.getContext();
        button.setText(context.getString(R.string.clean_failed_button, failed.size()));
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> show(context, failed, retry));
    }

    public static void hide(@Nullable MaterialButton button) {
        if (button != null) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
        }
    }

    /** Each failed file on its own line, with why it was not cleaned. */
    @NonNull
    static String describe(@NonNull Context context, @NonNull List<MediaProcessor.Failed> failed) {
        StringBuilder text = new StringBuilder();
        for (MediaProcessor.Failed f : failed) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(f.item().displayName(context)).append('\n')
                    .append(context.getString(reasonText(f.reason())));
        }
        return text.toString();
    }

    @StringRes
    static int reasonText(@NonNull MediaProcessor.FailReason reason) {
        return switch (reason) {
            case UNSUPPORTED -> R.string.clean_failed_reason_unsupported;
            case OUT_OF_MEMORY -> R.string.clean_failed_reason_memory;
            default -> R.string.clean_failed_reason_not_cleaned;
        };
    }

    private static void show(@NonNull Context context, @NonNull List<MediaProcessor.Failed> failed,
                             @NonNull Consumer<List<MediaItem>> retry) {
        List<MediaItem> items = new ArrayList<>(failed.size());
        for (MediaProcessor.Failed f : failed) {
            // A format this phone can't open fails the same way every time, so it isn't retried.
            if (f.reason() != MediaProcessor.FailReason.UNSUPPORTED) {
                items.add(f.item());
            }
        }
        boolean anyRetryable = !items.isEmpty();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clean_failed_title)
                .setMessage(describe(context, failed))
                .setNegativeButton(R.string.licenses_close, null);
        if (anyRetryable) {
            builder.setPositiveButton(R.string.clean_failed_retry, (d, w) -> retry.accept(items));
        }
        FoldDialogs.place(builder.show());
    }
}
