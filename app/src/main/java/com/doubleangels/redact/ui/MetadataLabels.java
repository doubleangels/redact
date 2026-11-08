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

    /** Words that are initialisms or format names, kept in capitals. */
    private static final Set<String> ACRONYMS = Set.of(
            "GPS", "XMP", "EXIF", "IPTC", "ICC", "ICCP", "ISO", "MIME", "DNG", "HDR", "GIF", "PNG",
            "JPEG", "JFIF", "HEIC", "HEIF", "WEBP", "MP4", "URI", "URL", "ID", "UUID", "GUID", "SDK",
            "JUMBF", "RIFF", "APP0", "APP1", "APP2", "APP13", "IFD", "DPI", "FPS", "RGB", "YCBCR",
            "CMYK", "RDF", "XML", "SEFT", "VP8", "VP8L", "VP8X", "IHDR", "IDAT", "IEND", "PLTE",
            "ALPH", "ANIM", "ANMF", "C2PA", "MPF", "XYZ", "AAC", "AVC", "HEVC", "AV1", "VP9", "OBU",
            "TIFF", "BMP", "AVIF", "MOV", "M4V", "MKV", "UTC", "OS", "AI");

    private MetadataLabels() {
    }

    /**
     * Title-cases every word of a raw name and replaces underscores with spaces. Known acronyms
     * stay in capitals, so GPS_LATITUDE shows as "GPS Latitude" and APERTURE_VALUE as "Aperture Value".
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
            String upper = word.toUpperCase(Locale.ROOT);
            if (ACRONYMS.contains(upper) || looksLikeAcronym(upper)) {
                out.append(upper);
            } else {
                out.append(upper.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    /**
     * Fail-over for names not in {@link #ACRONYMS}: a word with a digit (APP13, MP4) or without a
     * vowel (HDR, SDK, DNG) is almost certainly an initialism, not a plain word.
     */
    private static boolean looksLikeAcronym(String upper) {
        boolean vowel = false;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (Character.isDigit(c)) {
                return true;
            }
            vowel |= "AEIOUY".indexOf(c) >= 0;
        }
        return !vowel;
    }
}
