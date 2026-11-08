package com.doubleangels.redact.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Turns a raw metadata name such as {@code GPS_LATITUDE} into the label Scan shows, "GPS Latitude".
 * Names stay raw everywhere else (search, copy-all and cleaning logic still see the original).
 */
public final class MetadataLabels {

    private MetadataLabels() {
    }

    /**
     * Title-cases every word of a raw name and replaces underscores with spaces. Acronyms are
     * title-cased too, so GPS_LATITUDE shows as "Gps Latitude".
     */
    @NonNull
    public static String format(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(key.length());
        for (String word : key.split("[_\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
