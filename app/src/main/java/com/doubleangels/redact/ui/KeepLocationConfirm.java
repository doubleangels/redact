package com.doubleangels.redact.ui;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The question asked before a clean keeps locations, wherever that can be turned on: the Keep
 * Location switch and chip, and a preset that keeps a location. Nothing changes unless the user
 * says yes.
 */
public final class KeepLocationConfirm {

    private KeepLocationConfirm() {
    }

    public static void ask(@NonNull Context context, @NonNull Runnable onConfirmed) {
        FoldDialogs.place(new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_preserve_location_confirm_title)
                .setMessage(R.string.settings_preserve_location_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> onConfirmed.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
