package com.doubleangels.redact.ui;

import android.content.Context;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.CleanPreset;
import com.doubleangels.redact.CleanPresets;
import com.doubleangels.redact.R;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The chips above the Clean tab's files: the preset, Strict Clean, Keep Location, Keep Date Taken, Keep
 * Camera Settings and Remove Audio. They change the same preferences Settings does, so a choice
 * made here stays for the next batch too, and they follow the same rules: Strict Clean turns the
 * Keep options off and greys them out, and Keep Location asks first. The preset chip shows which
 * {@link CleanPreset} the options match and opens a menu to pick another; one that keeps a location
 * asks first too.
 */
public final class CleanQuickToggles {

    private final Context context;
    @Nullable private final Chip preset;
    @Nullable private final Chip strict;
    @Nullable private final Chip location;
    @Nullable private final Chip date;
    @Nullable private final Chip camera;
    @Nullable private final Chip audio;
    /** Set while the chips are being set from the preferences, so that isn't taken for a tap. */
    private boolean syncing;
    private boolean locked;

    public CleanQuickToggles(@NonNull View root) {
        this.context = root.getContext();
        preset = root.findViewById(R.id.chipCleanPreset);
        if (preset != null) {
            preset.setOnClickListener(v -> showPresetMenu());
            preset.setOnCloseIconClickListener(v -> showPresetMenu());
        }
        strict = root.findViewById(R.id.chipStrictClean);
        location = root.findViewById(R.id.chipKeepLocation);
        date = root.findViewById(R.id.chipKeepDate);
        camera = root.findViewById(R.id.chipKeepCamera);
        audio = root.findViewById(R.id.chipRemoveAudio);
        listen(strict, this::onStrict);
        listen(location, this::onLocation);
        listen(date, (chip, on) -> AppPreferences.setPreserveDateTaken(context, on));
        listen(camera, (chip, on) -> AppPreferences.setPreserveCameraSettings(context, on));
        listen(audio, (chip, on) -> AppPreferences.setRemoveAudio(context, on));
        sync();
    }

    private void listen(@Nullable Chip chip, @NonNull CompoundButton.OnCheckedChangeListener listener) {
        if (chip != null) {
            // The chips show the preferences, which sync() reads. A checked state restored with the
            // view would be stale, and the listener would write it back: after Reset All
            // Settings recreates the activity, that put Strict Clean back off.
            chip.setSaveEnabled(false);
            chip.setOnCheckedChangeListener((button, on) -> {
                if (!syncing) {
                    listener.onCheckedChanged(button, on);
                    showPreset();
                }
            });
        }
    }

    /** Sets every chip from the preferences, as Settings may have changed them. */
    public void sync() {
        syncing = true;
        try {
            boolean strictClean = AppPreferences.isStrictClean(context);
            set(strict, strictClean, !locked);
            set(location, !strictClean && AppPreferences.isPreserveLocation(context), !locked && !strictClean);
            set(date, !strictClean && AppPreferences.isPreserveDateTaken(context), !locked && !strictClean);
            set(camera, !strictClean && AppPreferences.isPreserveCameraSettings(context), !locked && !strictClean);
            set(audio, AppPreferences.isRemoveAudio(context), !locked);
            showPreset();
        } finally {
            syncing = false;
        }
    }

    /** Labels the preset chip with the preset the options match, or Custom. */
    private void showPreset() {
        if (preset == null) {
            return;
        }
        String name = context.getString(CleanPresets.current(context).nameRes);
        String description = context.getString(R.string.clean_preset_chip_description, name);
        preset.setText(name);
        preset.setContentDescription(description);
        preset.setCloseIconContentDescription(description);
        preset.setEnabled(!locked);
    }

    /** The presets with their descriptions; picking one affects the next batch, not a running one. */
    private void showPresetMenu() {
        if (locked) {
            return;
        }
        CleanPreset[] choices = CleanPreset.choices();
        CharSequence[] labels = new CharSequence[choices.length];
        int checked = -1;
        CleanPreset current = CleanPresets.current(context);
        for (int i = 0; i < choices.length; i++) {
            labels[i] = CleanPresetLabels.twoLine(context, choices[i]);
            if (choices[i] == current) {
                checked = i;
            }
        }
        FoldDialogs.place(new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_clean_preset)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    pickPreset(choices[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void pickPreset(@NonNull CleanPreset chosen) {
        if (chosen == CleanPresets.current(context)) {
            return;
        }
        if (!chosen.keepsLocation()) {
            CleanPresets.apply(context, chosen, false);
            sync();
            return;
        }
        // Not applied until confirmed, the same question Keep Location asks.
        KeepLocationConfirm.ask(context, () -> {
            CleanPresets.apply(context, chosen, true);
            sync();
        });
    }

    /** Locks the chips while a clean runs: it has already read the settings it uses. */
    public void setLocked(boolean locked) {
        this.locked = locked;
        sync();
    }

    private static void set(@Nullable Chip chip, boolean checked, boolean enabled) {
        if (chip != null) {
            chip.setChecked(checked);
            chip.setEnabled(enabled);
        }
    }

    private void onStrict(@NonNull CompoundButton chip, boolean on) {
        AppPreferences.setStrictClean(context, on);
        if (on) {
            // As Settings does: Strict Clean keeps nothing, so the Keep options go off.
            AppPreferences.setPreserveCameraSettings(context, false);
            AppPreferences.setPreserveLocation(context, false);
            AppPreferences.setPreserveDateTaken(context, false);
            AppPreferences.setPreserveMotionPhoto(context, false);
        }
        sync();
    }

    private void onLocation(@NonNull CompoundButton chip, boolean on) {
        if (!on) {
            AppPreferences.setPreserveLocation(context, false);
            return;
        }
        // Not kept until confirmed, the same question Settings asks.
        syncing = true;
        chip.setChecked(false);
        syncing = false;
        KeepLocationConfirm.ask(context, () -> {
            AppPreferences.setPreserveLocation(context, true);
            sync();
        });
    }
}
