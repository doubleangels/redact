package com.doubleangels.redact.whatsnew;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.doubleangels.redact.R;

import java.util.Map;
import java.util.Set;

/** The icon beside a What's New item, by the key written in {@code whats_new_icons}. */
public final class WhatsNewIcons {

    private static final Map<String, Integer> ICONS = Map.of(
            "clean", R.drawable.ic_clean,
            "scan", R.drawable.ic_scan,
            "convert", R.drawable.ic_convert,
            "censor", R.drawable.ic_censor,
            "settings", R.drawable.ic_settings,
            "stats", R.drawable.ic_insights,
            "fixes", R.drawable.ic_check_circle);

    private WhatsNewIcons() {
    }

    /** Every key the notes may use. */
    @NonNull
    public static Set<String> keys() {
        return ICONS.keySet();
    }

    /** The drawable for {@code key}, or a generic one for a key this version doesn't know. */
    @DrawableRes
    public static int forKey(@NonNull String key) {
        Integer icon = ICONS.get(key);
        return icon != null ? icon : R.drawable.ic_update;
    }
}
