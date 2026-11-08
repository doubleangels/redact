package com.doubleangels.redact.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a raw metadata name such as {@code GPS_LATITUDE} into the label Scan shows, "GPS Latitude".
 * Names stay raw everywhere else (search, copy-all and cleaning logic still see the original).
 */
public final class MetadataLabels {

    private static final Set<String> ACRONYMS = Set.of(
            "GPS", "XMP", "EXIF", "IPTC", "ICC", "ICCP", "ISO", "MIME", "DNG", "HDR", "GIF", "PNG",
            "JPEG", "JFIF", "HEIC", "HEIF", "WEBP", "MP4", "URI", "URL", "ID", "UUID", "GUID", "SDK",
            "GCAM", "JUMBF", "RIFF", "APP0", "APP1", "APP2", "APP13", "IFD", "DPI", "FPS", "RGB",
            "YCBCR", "CMYK", "RDF", "XML", "DNS", "SEFT", "VP8", "VP8L", "VP8X", "IHDR", "IDAT",
            "IEND", "PLTE", "ALPH", "ANIM", "ANMF");

    private MetadataLabels() {
    }

    /**
     * Title-cases a raw name made of capitals and underscores, keeping acronyms upper case. A name
     * that already has lower-case letters (text from a translated string) is left as it is.
     */
    @NonNull
    public static String format(@Nullable String key) {
        if (key == null || key.isEmpty() || !key.equals(key.toUpperCase(Locale.ROOT))) {
            return key == null ? "" : key;
        }
        StringBuilder out = new StringBuilder(key.length());
        for (String word : key.split("[_\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            if (ACRONYMS.contains(word)) {
                out.append(word);
            } else {
                out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }
}
