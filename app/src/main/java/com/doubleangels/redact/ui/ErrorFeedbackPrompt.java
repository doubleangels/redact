package com.doubleangels.redact.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import io.sentry.protocol.SentryId;

/**
 * After a scan, clean or convert that failed and sent an error report, offers to add a note to
 * that report. The note shows on the matching issue in Sentry. It is only offered while crash
 * reporting is on, and never asks for a name or email.
 */
public final class ErrorFeedbackPrompt {

    private ErrorFeedbackPrompt() {}

    /** Shows a "Tell us what happened" snackbar for {@code eventId}; does nothing when null. */
    public static void offer(@Nullable View view, @Nullable SentryId eventId) {
        if (view == null || eventId == null || !SentryManager.isEnabled()) {
            return;
        }
        Snackbar bar = Snackbar.make(view, R.string.error_feedback_offer, Snackbar.LENGTH_LONG)
                .setAction(R.string.error_feedback_action, v -> showDialog(view.getContext(), eventId));
        View nav = view.getRootView().findViewById(R.id.bottomNavigation);
        if (nav != null) {
            bar.setAnchorView(nav);
        }
        bar.show();
    }

    private static void showDialog(@NonNull Context context, @NonNull SentryId eventId) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_error_feedback, null);
        EditText input = content.findViewById(R.id.errorFeedbackInput);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.error_feedback_title)
                .setMessage(R.string.error_feedback_message)
                .setView(content)
                .setPositiveButton(R.string.error_feedback_send, (d, w) -> {
                    boolean sent = SentryManager.sendFeedback(String.valueOf(input.getText()), eventId);
                    if (sent) {
                        Notice.show(context, R.string.error_feedback_thanks, false);
                    }
                })
                .setNegativeButton(R.string.error_feedback_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            send.setEnabled(false);
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    send.setEnabled(s.toString().trim().length() > 0);
                }
            });
        });
        dialog.show();
    }
}
