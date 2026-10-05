package com.doubleangels.redact.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;

import java.util.UUID;

/**
 * Copies scanned metadata (GPS coordinates, serial numbers, ...) to the clipboard flagged as sensitive,
 * so Android 13+ keeps it out of the clipboard preview and keyboard suggestions, and clears it again
 * after the delay chosen in Settings.
 *
 * <p>Clearing is best-effort: it is a timer inside Redact's process, so it cannot fire if Android
 * stops the process first. Each copy is tagged with a random token in its clip description, and the
 * timer only clears the clipboard if that token is still there, so something the user copied in the
 * meantime is never wiped. The description can be read from the background, unlike the clip itself.
 */
public final class SensitiveClipboard {

    private static final String EXTRA_REDACT_TOKEN = "com.doubleangels.redact.CLIP_TOKEN";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    @Nullable
    private static Runnable pendingClear;

    private SensitiveClipboard() {
    }

    /** @return true if the text was placed on the clipboard */
    public static boolean copy(@NonNull Context context, @NonNull String label, @NonNull String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return false;
        }
        String token = UUID.randomUUID().toString();
        ClipData clip = ClipData.newPlainText(label, text);
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_REDACT_TOKEN, token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        }
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        scheduleClear(context.getApplicationContext(), clipboard, token);
        return true;
    }

    private static void scheduleClear(
            @NonNull Context appContext, @NonNull ClipboardManager clipboard, @NonNull String token) {
        cancelPendingClear();
        int seconds = AppPreferences.getClipboardClearSeconds(appContext);
        if (seconds <= 0) {
            return;
        }
        Runnable clear = () -> {
            pendingClear = null;
            clearIfStillOurs(clipboard, token);
        };
        pendingClear = clear;
        HANDLER.postDelayed(clear, seconds * 1000L);
    }

    private static void cancelPendingClear() {
        if (pendingClear != null) {
            HANDLER.removeCallbacks(pendingClear);
            pendingClear = null;
        }
    }

    private static void clearIfStillOurs(@NonNull ClipboardManager clipboard, @NonNull String token) {
        try {
            ClipDescription description = clipboard.getPrimaryClipDescription();
            PersistableBundle extras = description != null ? description.getExtras() : null;
            if (extras != null && token.equals(extras.getString(EXTRA_REDACT_TOKEN))) {
                clipboard.clearPrimaryClip();
            }
        } catch (RuntimeException ignored) {
            // The system refused access; leave the clipboard alone.
        }
    }
}
