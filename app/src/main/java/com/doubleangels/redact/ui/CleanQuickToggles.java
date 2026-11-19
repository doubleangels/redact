package com.doubleangels.redact.ui;

import android.content.Context;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The chips above the Clean tab's files: Strict Clean, Keep Location, Keep Date Taken, Keep
 * Camera Settings and Remove Audio. They change the same preferences Settings does, so a choice
 * made here stays for the next batch too, and they follow the same rules: Strict Clean turns the
 * Keep options off and greys them out, and Keep Location asks first.
 */
public final class CleanQuickToggles {

    private final Context context;
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
        } finally {
            syncing = false;
        }
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
        FoldDialogs.place(new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_preserve_location_confirm_title)
                .setMessage(R.string.settings_preserve_location_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    AppPreferences.setPreserveLocation(context, true);
                    sync();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
