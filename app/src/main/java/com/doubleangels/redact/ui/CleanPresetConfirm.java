package com.doubleangels.redact.ui;

import android.content.Context;

import androidx.annotation.NonNull;

import com.doubleangels.redact.CleanPreset;
import com.doubleangels.redact.CleanPresets;
import com.doubleangels.redact.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Asks before a preset replaces the clean options: what it does, and for a preset that keeps a
 * location, the same warning Keep Location gives. Nothing changes unless the user taps Apply.
 */
public final class CleanPresetConfirm {

    private CleanPresetConfirm() {
    }

    public static void ask(@NonNull Context context, @NonNull CleanPreset preset,
                           @NonNull Runnable onApplied) {
        String message = context.getString(preset.descriptionRes);
        if (preset.keepsLocation()) {
            message += "\n\n" + context.getString(R.string.settings_preserve_location_confirm_message);
        }
        FoldDialogs.place(new MaterialAlertDialogBuilder(context)
                .setTitle(preset.nameRes)
                .setMessage(message)
                .setPositiveButton(R.string.clean_preset_apply, (d, w) -> {
                    // Applying from this dialog is the user's yes, location included.
                    CleanPresets.apply(context, preset, true);
                    onApplied.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
