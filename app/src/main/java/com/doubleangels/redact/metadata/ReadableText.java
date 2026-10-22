package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * What Scan can show of metadata stored as binary: the readable strings inside it, ISO 6709
 * coordinates and timestamps. Plain Java, so the container walkers can use it anywhere.
 */
final class ReadableText {

    /** Longest text {@link #strings} returns; Scan shows it in one row. */
    static final int MAX_LENGTH = 300;
    private static final int MIN_RUN = 4;
    private static final int MAX_RUNS = 12;

    private ReadableText() {
    }

    @Nullable
    static String strings(@NonNull byte[] data) {
        return strings(data, 0, data.length);
    }

    /**
     * The runs of readable text in {@code data[offset, offset + length)}, such as a vendor's name
     * or the software that wrote a C2PA manifest, joined with {@code " · "}; null when there are
     * none. Runs too short or too repetitive to mean anything are left out.
     */
    @Nullable
    static String strings(@NonNull byte[] data, int offset, int length) {
        int end = Math.min(data.length, offset + Math.max(0, length));
        Set<String> runs = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        int i = Math.max(0, offset);
        while (i < end && runs.size() < MAX_RUNS) {
            int b = data[i] & 0xFF;
            int sequence = b < 0x80 ? 1 : b >= 0xC2 && b <= 0xDF ? 2 : b >= 0xE0 && b <= 0xEF ? 3
                    : b >= 0xF0 && b <= 0xF4 ? 4 : 0;
            boolean printable;
            if (sequence == 1) {
                printable = (b >= 0x20 && b < 0x7F) || b == '\t';
            } else {
                printable = sequence > 0 && i + sequence <= end;
                for (int j = 1; printable && j < sequence; j++) {
                    printable = (data[i + j] & 0xC0) == 0x80;
                }
            }
            if (printable) {
                run.append(sequence == 1 ? String.valueOf((char) b)
                        : new String(data, i, sequence, StandardCharsets.UTF_8));
                i += sequence;
            } else {
                addRun(runs, run);
                i++;
            }
        }
        addRun(runs, run);
        if (runs.isEmpty()) {
            return null;
        }
        String joined = String.join(" · ", runs);
        return joined.length() > MAX_LENGTH ? joined.substring(0, MAX_LENGTH).trim() + "…" : joined;
    }

    /**
     * {@code data} as one piece of text when all of it is, such as a tag holding an ISO 6709
     * location or a number; otherwise the readable strings inside it.
     */
    @Nullable
    static String textOrStrings(@NonNull byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '�' || c == 0x7F || (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != 0)) {
                return strings(data);
            }
        }
        String trimmed = text.replace('\0', ' ').trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH).trim() + "…" : trimmed;
    }

    private static void addRun(@NonNull Set<String> runs, @NonNull StringBuilder run) {
        String text = run.toString().trim();
        run.setLength(0);
        if (text.codePointCount(0, text.length()) < MIN_RUN) {
            return;
        }
        boolean letter = false;
        Set<Integer> distinct = new java.util.HashSet<>();
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            letter |= Character.isLetter(c);
            distinct.add(c);
            i += Character.charCount(c);
        }
        // "AAAA" or "...." is padding, and a run without a letter is rarely words.
        if (letter && distinct.size() >= 3) {
            runs.add(text);
        }
    }

    /**
     * The bytes in an ImageMagick raw profile: a line naming the type, one with the length, then
     * hex digits. Null when the text is not one.
     */
    @Nullable
    static byte[] hexProfile(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.trim().split("\n", 3);
        if (lines.length < 3) {
            return null;
        }
        int expected;
        try {
            expected = Integer.parseInt(lines[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        // The length comes from the file, so it only sizes the buffer as far as the hex can fill it.
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.max(0, Math.min(expected, lines[2].length() / 2)));
        int high = -1;
        for (int i = 0; i < lines[2].length() && out.size() < expected; i++) {
            int digit = Character.digit(lines[2].charAt(i), 16);
            if (digit < 0) {
                if (!Character.isWhitespace(lines[2].charAt(i))) {
                    return null;
                }
                continue;
            }
            if (high < 0) {
                high = digit;
            } else {
                out.write((high << 4) | digit);
                high = -1;
            }
        }
        return out.size() > 0 ? out.toByteArray() : null;
    }

    /**
     * Latitude and longitude from an ISO 6709 string such as {@code +40.6892-074.0445+010.000/};
     * null when it is not one or names no usable place.
     */
    @Nullable
    static double[] iso6709(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String clean = raw.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        int lonStart = nextSign(clean, 1);
        if (lonStart <= 0) {
            return null;
        }
        int lonEnd = nextSign(clean, lonStart + 1);
        try {
            double lat = Double.parseDouble(clean.substring(0, lonStart));
            double lon = Double.parseDouble(clean.substring(lonStart, lonEnd < 0 ? clean.length() : lonEnd));
            if (Math.abs(lat) > 90 && Math.abs(lon) <= 90) {
                double swapped = lat;
                lat = lon;
                lon = swapped;
            }
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) {
                return null;
            }
            return new double[] {lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The index of the next + or - after a digit, from {@code from}; -1 when there is none. */
    private static int nextSign(@NonNull String s, int from) {
        boolean seenDigit = false;
        for (int i = Math.max(0, from - 1); i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                seenDigit = true;
            } else if ((c == '+' || c == '-') && seenDigit && i >= from) {
                return i;
            } else if (c != '+' && c != '-') {
                return -1;
            }
        }
        return -1;
    }

    /** {@code millis} since 1970 as {@code yyyy-MM-dd HH:mm:ss UTC}, like a PNG's modified time. */
    @NonNull
    static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
