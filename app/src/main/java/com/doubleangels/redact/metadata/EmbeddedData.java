package com.doubleangels.redact.metadata;

import android.content.Context;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Pictures and other binary data carried as base64 inside XMP, such as the depth map
 * (GDepth:Data) and original image (GImage:Data) a phone's portrait mode writes into extended XMP.
 * Shown as text they are thousands of lines of letters, so Scan shows what each one is instead:
 * its format, its size in pixels when it is a picture, and its size in bytes.
 *
 * <p>{@link #summarize} runs where the whole packet is still at hand (before it is cut for display)
 * and puts a short token in place of each block; {@link #render} turns the tokens into text for
 * the screen, in the phone's language for the size.
 */
final class EmbeddedData {

    /** Shorter runs are left alone: real values (hashes, IDs) are rarely this long. */
    static final int MIN_RUN = 512;
    /** A wrapped block's lines are at least this long; no word in ordinary text is. */
    private static final int MIN_WRAPPED_LINE = 40;

    private static final char START = '';
    private static final char SEPARATOR = '';
    private static final char END = '';

    private EmbeddedData() {
    }

    /** {@code text} with each long base64 block replaced by a token describing what it decodes to. */
    @NonNull
    static String summarize(@NonNull String text) {
        StringBuilder out = null;
        int copiedTo = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (!isBase64(text.charAt(i))) {
                i++;
                continue;
            }
            // A block may be wrapped onto several lines, but only long lines: words in ordinary
            // text are short, so a short piece followed by a space ends the block.
            int start = i;
            int letters = 0;
            int runEnd = i;
            while (i < n) {
                int pieceStart = i;
                while (i < n && isBase64(text.charAt(i))) {
                    i++;
                }
                int piece = i - pieceStart;
                if (piece == 0) {
                    break;
                }
                letters += piece;
                runEnd = i;
                if (piece < MIN_WRAPPED_LINE) {
                    break;
                }
                int spaces = i;
                while (i < n && isSpace(text.charAt(i))) {
                    i++;
                }
                if (i == spaces) {
                    break;
                }
            }
            int end = runEnd;
            // '=' padding closes a block.
            while (end < n && text.charAt(end) == '=') {
                end++;
            }
            if (letters < MIN_RUN) {
                i = Math.max(runEnd, start + 1);
                continue;
            }
            String token = token(text.substring(start, end));
            if (token == null) {
                i = Math.max(i, end);
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length() / 4);
            }
            out.append(text, copiedTo, start).append(token);
            copiedTo = end;
            i = end;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copiedTo, n).toString();
    }

    /** {@code text} with each token from {@link #summarize} written out for the screen. */
    @NonNull
    static String render(@NonNull Context context, @NonNull String text) {
        if (text.indexOf(START) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 32);
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf(START, i);
            int end = start < 0 ? -1 : text.indexOf(END, start);
            if (start < 0 || end < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            String[] parts = text.substring(start + 1, end).split(String.valueOf(SEPARATOR), -1);
            if (parts.length == 3) {
                StringBuilder value = new StringBuilder();
                if (!parts[0].isEmpty()) {
                    value.append(parts[0]).append(" · ");
                }
                if (!parts[1].isEmpty()) {
                    value.append(parts[1]).append(" · ");
                }
                long bytes;
                try {
                    bytes = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    bytes = 0;
                }
                value.append(Formatter.formatShortFileSize(context, bytes));
                out.append('[').append(value).append(']');
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** The token for one base64 block, or null when it does not decode. */
    @Nullable
    private static String token(@NonNull String block) {
        byte[] data;
        try {
            data = Base64.getMimeDecoder().decode(block.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (data.length == 0) {
            return null;
        }
        String[] what = describe(data);
        return START + what[0] + SEPARATOR + what[1] + SEPARATOR + data.length + END;
    }

    /** The format ("JPEG", "PNG", ...) and the size in pixels ("640×480"), each empty when unknown. */
    @NonNull
    static String[] describe(@NonNull byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return new String[] {"JPEG", jpegSize(d)};
        }
        if (d.length >= 24 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return new String[] {"PNG", size(u32(d, 16), u32(d, 20))};
        }
        if (d.length >= 10 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return new String[] {"GIF", size((d[6] & 0xFF) | (d[7] & 0xFF) << 8, (d[8] & 0xFF) | (d[9] & 0xFF) << 8)};
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return new String[] {"WebP", ""};
        }
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            return new String[] {"HEIF", ""};
        }
        return new String[] {"", ""};
    }

    /** A JPEG's size from its first frame header; empty when there is none to read. */
    @NonNull
    private static String jpegSize(@NonNull byte[] d) {
        int pos = 2;
        while (pos + 9 < d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                return "";
            }
            int type = d[pos + 1] & 0xFF;
            if (type == 0xFF) {
                pos++;
                continue;
            }
            if (type == 0xD8 || type == 0x01 || (type >= 0xD0 && type <= 0xD7)) {
                pos += 2;
                continue;
            }
            if (type == 0xD9 || type == 0xDA) {
                return "";
            }
            int length = (d[pos + 2] & 0xFF) << 8 | (d[pos + 3] & 0xFF);
            boolean frame = type >= 0xC0 && type <= 0xCF && type != 0xC4 && type != 0xC8 && type != 0xCC;
            if (frame) {
                int height = (d[pos + 5] & 0xFF) << 8 | (d[pos + 6] & 0xFF);
                int width = (d[pos + 7] & 0xFF) << 8 | (d[pos + 8] & 0xFF);
                return size(width, height);
            }
            if (length < 2) {
                return "";
            }
            pos += 2 + length;
        }
        return "";
    }

    @NonNull
    private static String size(long width, long height) {
        return width > 0 && height > 0 ? String.format(Locale.ROOT, "%d×%d", width, height) : "";
    }

    private static long u32(byte[] d, int at) {
        return (d[at] & 0xFFL) << 24 | (d[at + 1] & 0xFFL) << 16 | (d[at + 2] & 0xFFL) << 8 | (d[at + 3] & 0xFFL);
    }

    private static boolean isBase64(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}
