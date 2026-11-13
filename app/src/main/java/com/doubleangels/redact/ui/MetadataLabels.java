package com.doubleangels.redact.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
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
            "IO", "IP", "YCBCR", "XYZ", "ISO6709", "ID", "IR", "PAL", "NTSC", "MPF", "C2PA", "CFA", "OECF");

    /**
     * Words that run letters together or are codes, which the rules above cannot split or read:
     * ExifInterface's GPSDOP and ISO_SPEED_LATITUDEYYY, the retriever's SAMPLERATE and MIMETYPE, and
     * the handler codes of a video's tracks.
     */
    private static final Map<String, String> WORD_FIXES = Map.ofEntries(
            Map.entry("GPSDOP", "GPS DOP"),
            Map.entry("GPSH", "GPS Horizontal"),
            Map.entry("LATITUDEYYY", "Latitude YYY"),
            Map.entry("LATITUDEZZZ", "Latitude ZZZ"),
            Map.entry("IN35MM", "In 35mm"),
            Map.entry("IMG", "Image"),
            Map.entry("ALBUMARTIST", "Album Artist"),
            Map.entry("SAMPLERATE", "Sample Rate"),
            Map.entry("FRAMERATE", "Frame Rate"),
            Map.entry("MIMETYPE", "MIME Type"),
            Map.entry("VIDE", "Video"),
            Map.entry("SOUN", "Sound"),
            Map.entry("SBTL", "Subtitle"),
            Map.entry("SUBT", "Subtitle"),
            Map.entry("TMCD", "Timecode"));

    /**
     * Key prefixes that are followed by a four-character or hexadecimal code from the file (a PNG
     * chunk's "tEXt", a HEIF box's "ftyp", a GIF extension's "FE"). The code stays in capitals.
     */
    private static final String[] CODE_PREFIXES = {
            "PNG_CHUNK_", "WEBP_CHUNK_", "HEIF_BOX_", "HEIF_META_", "HEIF_PROPERTY_", "JPEG_SEGMENT_",
            "GIF_EXTENSION_"};

    /** The camel-case tag name that Scan splits into Y_CB_CR; it reads best as the standard "YCbCr". */
    private static final String Y_CB_CR = "Y_CB_CR";

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
        for (String prefix : CODE_PREFIXES) {
            if (key.length() > prefix.length() && key.startsWith(prefix)) {
                return format(key.substring(0, prefix.length() - 1)) + ' '
                        + key.substring(prefix.length()).replace('_', ' ').toUpperCase(Locale.ROOT);
            }
        }
        int ycc = key.indexOf(Y_CB_CR);
        if (ycc >= 0) {
            String joined = format(key.substring(0, ycc)) + " YCbCr "
                    + format(key.substring(ycc + Y_CB_CR.length()));
            return joined.trim().replaceAll(" {2,}", " ");
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
        // A name with no letters or digits at all (a symbol) is shown as it is.
        return out.length() == 0 ? key : out.toString();
    }

    private static void appendWord(StringBuilder out, String key, int start, int end) {
        if (out.length() > 0) {
            out.append(' ');
        }
        String upper = key.substring(start, end).toUpperCase(Locale.ROOT);
        String fix = WORD_FIXES.get(upper);
        if (fix != null) {
            out.append(fix);
        } else if (ACRONYMS.contains(upper) || looksLikeAcronym(upper)) {
            out.append(upper);
        } else {
            out.append(upper.charAt(0)).append(upper.substring(1).toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Fail-over for names not in {@link #ACRONYMS}: a short word with a digit (APP13, MP4) or a word without a
     * vowel (GPS, HDR, XMP, SDK) is almost certainly an initialism, not a plain word.
     */
    private static boolean looksLikeAcronym(String upper) {
        boolean vowel = false;
        boolean digit = false;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            digit |= Character.isDigit(c);
            vowel |= "AEIOUY".indexOf(c) >= 0;
        }
        // A short word with a digit is a code (APP13, MP4, H264); a long one is a name (Netscape2).
        return !vowel || (digit && upper.length() <= 6);
    }
}
