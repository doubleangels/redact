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

    /** Initialisms and format names that have a vowel, so {@link #looksLikeAcronym} cannot tell. */
    private static final Set<String> ACRONYMS = Set.of(
            "EXIF", "IPTC", "ICC", "ICCP", "ISO", "MIME", "DNG", "GIF", "PNG", "JPEG", "JFIF", "HEIC",
            "HEIF", "AVIF", "WEBP", "TIFF", "BMP", "URI", "URL", "UUID", "GUID", "JUMBF", "RIFF",
            "IFD", "IHDR", "IDAT", "IEND", "PLTE", "ALPH", "ANIM", "ANMF", "SEFT", "AAC", "AVC",
            "HEVC", "MOV", "MKV", "UTC", "OS", "AI", "OEM", "NAL", "SEI", "EXR", "WAV", "OGG", "UI",
            "IO", "IP", "YCBCR", "XYZ", "ID", "IR", "PAL", "NTSC", "MPF", "C2PA");

    private MetadataLabels() {
    }

    /**
     * Title-cases every word of a raw name, splitting on anything that is not a letter or digit
     * (and on a lower-case letter followed by a capital), so GPS_LATITUDE shows as "GPS Latitude" and
     * APERTURE_VALUE as "Aperture Value". Initialisms stay in capitals, from {@link #ACRONYMS} or
     * as {@link #looksLikeAcronym} guesses, so a name nobody listed still reads right.
     */
    @NonNull
    public static String format(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(key.length() + 4);
        int start = -1;
        for (int i = 0; i <= key.length(); i++) {
            boolean letter = i < key.length() && Character.isLetterOrDigit(key.charAt(i));
            boolean camel = letter && start >= 0 && i > start
                    && Character.isUpperCase(key.charAt(i)) && Character.isLowerCase(key.charAt(i - 1));
            if (start >= 0 && (!letter || camel)) {
                appendWord(out, key, start, i);
                start = -1;
            }
            if (letter && start < 0) {
                start = i;
            }
        }
        return out.toString();
    }

    private static void appendWord(StringBuilder out, String key, int start, int end) {
        if (out.length() > 0) {
            out.append(' ');
        }
        String upper = key.substring(start, end).toUpperCase(Locale.ROOT);
        if (ACRONYMS.contains(upper) || looksLikeAcronym(upper)) {
            out.append(upper);
        } else {
            out.append(upper.charAt(0)).append(upper.substring(1).toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Fail-over for names not in {@link #ACRONYMS}: a word with a digit (APP13, MP4) or without a
     * vowel (GPS, HDR, XMP, SDK) is almost certainly an initialism, not a plain word.
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
