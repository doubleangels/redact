package com.doubleangels.redact.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;

/**
 * Copies scanned metadata (GPS coordinates, serial numbers, ...) to the clipboard flagged as sensitive,
 * so Android 13+ keeps it out of the clipboard preview and keyboard suggestions.
 */
public final class SensitiveClipboard {

    private SensitiveClipboard() {
    }

    /** @return true if the text was placed on the clipboard */
    public static boolean copy(@NonNull Context context, @NonNull String label, @NonNull String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return false;
        }
        ClipData clip = ClipData.newPlainText(label, text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        return true;
    }
}
