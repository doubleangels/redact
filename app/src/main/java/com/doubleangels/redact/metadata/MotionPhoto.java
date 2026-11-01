package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the video of a Motion Photo: the MP4 a phone stores after the JPEG picture.
 *
 * <p>Three layouts are read. Google's Motion Photo format names the video's length in an XMP
 * {@code Container:Directory} and puts it at the end of the file. The older MicroVideo format
 * gives its offset from the end. Samsung writes the video after a {@code MotionPhoto_Data} marker
 * inside its own trailer, which follows the video. In each, the video is followed box by box to
 * where it really ends, so nothing behind it is taken for part of it.
 */
public final class MotionPhoto {

    static final String GCAMERA_NS = "http://ns.google.com/photos/1.0/camera/";

    private static final byte[] XMP_ID = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SAMSUNG_MARKER = "MotionPhoto_Data".getBytes(StandardCharsets.ISO_8859_1);
    /** How far from the end a Samsung trailer is searched for its video. */
    private static final long SAMSUNG_PEEK = 512L * 1024 * 1024;
    private static final long MAX_HEADER_BYTES = 4L * 1024 * 1024;

    private static final Pattern DECLARED = Pattern.compile(
            "(?:MotionPhoto|MicroVideo)(?:\\s*=\\s*\"\\s*1\\s*\"|>\\s*1\\s*<)");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?:MotionPhoto|MicroVideo)PresentationTimestampUs(?:\\s*=\\s*\"\\s*|>\\s*)(-?\\d{1,19})");
    private static final Pattern MICRO_VIDEO_OFFSET = Pattern.compile(
            "MicroVideoOffset(?:\\s*=\\s*\"\\s*|>\\s*)(\\d{1,19})");
    private static final Pattern ITEM = Pattern.compile("<[A-Za-z_][\\w.-]*:Item\\b[^>]*>");
    private static final Pattern MOTION_SEMANTIC = Pattern.compile("Semantic\\s*=\\s*\"MotionPhoto\"");
    private static final Pattern LENGTH = Pattern.compile("Length\\s*=\\s*\"(\\d{1,19})\"");

    /** The top-level boxes an MP4 is made of. */
    private static final Set<String> TOP_LEVEL = Set.of(
            "ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "meta", "moof", "mfra", "sidx");

    private MotionPhoto() {
    }

    /** Where a source's video is, {@code [start, end)}, and when in it the still was taken. */
    public static final class Found {
        public final long start;
        public final long end;
        /** The still's moment in the video, in microseconds; -1 when the file doesn't say. */
        public final long presentationTimestampUs;

        Found(long start, long end, long presentationTimestampUs) {
            this.start = start;
            this.end = end;
            this.presentationTimestampUs = presentationTimestampUs;
        }
    }

    /** A cleaned video to put after a clean picture. */
    public static final class Clip {
        @NonNull public final File video;
        public final long presentationTimestampUs;

        public Clip(@NonNull File video, long presentationTimestampUs) {
            this.video = video;
            this.presentationTimestampUs = presentationTimestampUs;
        }

        long length() {
            return video.length();
        }
    }

    /** {@link #find(FileChannel)} for a file; null when it can't be read. */
    @Nullable
    public static Found find(@NonNull File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return find(in.getChannel());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The video of a JPEG Motion Photo, or null when the file declares none or it can't be found. */
    @Nullable
    static Found find(@NonNull FileChannel ch) throws IOException {
        long length = ch.size();
        if (length < 16) {
            return null;
        }
        byte[] head = read(ch, 0, 8);
        if ("ftyp".equals(new String(head, 4, 4, StandardCharsets.ISO_8859_1))) {
            return findInHeif(ch, length);
        }
        if (head[0] != (byte) 0xFF || head[1] != (byte) 0xD8) {
            return null;
        }
        String xmp = String.join("", primaryXmp(ch, length));
        boolean declared = DECLARED.matcher(xmp).find();
        long timestamp = -1;
        Matcher ts = TIMESTAMP.matcher(xmp);
        if (ts.find()) {
            try {
                timestamp = Long.parseLong(ts.group(1));
            } catch (NumberFormatException ignored) {
                timestamp = -1;
            }
        }
        List<Long> starts = new ArrayList<>();
        if (declared) {
            Matcher item = ITEM.matcher(xmp);
            while (item.find()) {
                if (MOTION_SEMANTIC.matcher(item.group()).find()) {
                    Matcher len = LENGTH.matcher(item.group());
                    if (len.find()) {
                        starts.add(length - parse(len.group(1)));
                    }
                }
            }
            Matcher offset = MICRO_VIDEO_OFFSET.matcher(xmp);
            if (offset.find()) {
                starts.add(length - parse(offset.group(1)));
            }
        }
        if (length >= 8 && "SEFT".equals(new String(read(ch, length - 4, 4), StandardCharsets.ISO_8859_1))) {
            // The name can come up again in the trailer's index, so each place is tried, last first.
            long from = Math.max(0, length - SAMSUNG_PEEK);
            long to = length;
            for (int tries = 0; tries < 8; tries++) {
                long marker = lastIndexOf(ch, SAMSUNG_MARKER, from, to);
                if (marker < 0) {
                    break;
                }
                starts.add(marker + SAMSUNG_MARKER.length);
                to = marker + SAMSUNG_MARKER.length - 1;
            }
        }
        for (long start : starts) {
            long end = mp4End(ch, start, length);
            if (end > start) {
                return new Found(start, end, timestamp);
            }
        }
        return null;
    }

    /**
     * The video of a HEIC or AVIF Motion Photo: in the {@code mpvd} box that ends the HEIF, as the
     * Motion Photo format puts it, or after a {@code MotionPhoto_Data} marker in a Samsung trailer.
     */
    @Nullable
    private static Found findInHeif(@NonNull FileChannel ch, long length) throws IOException {
        long pos = 0;
        long mpvd = -1;
        long mpvdEnd = -1;
        long heifEnd = 0;
        for (int boxes = 0; pos + 8 <= length && boxes < 10_000; boxes++) {
            byte[] h = read(ch, pos, 8);
            long size = ByteBuffer.wrap(h, 0, 4).getInt() & 0xFFFFFFFFL;
            String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1) {
                if (pos + 16 > length) {
                    break;
                }
                size = ByteBuffer.wrap(read(ch, pos + 8, 8)).getLong();
                header = 16;
            } else if (size == 0) {
                size = length - pos;
            }
            if (size < header || pos + size > length || !type.matches("[a-z0-9 ]{4}")) {
                break;
            }
            if ("mpvd".equals(type)) {
                mpvd = pos + header;
                mpvdEnd = pos + size;
            }
            pos += size;
            heifEnd = pos;
        }
        long timestamp = heifTimestamp(ch, mpvd >= 0 ? mpvd : heifEnd);
        if (mpvd >= 0) {
            long end = mp4End(ch, mpvd, mpvdEnd);
            if (end == mpvdEnd) {
                return new Found(mpvd, end, timestamp);
            }
        }
        if (length >= 8 && "SEFT".equals(new String(read(ch, length - 4, 4), StandardCharsets.ISO_8859_1))) {
            long marker = lastIndexOf(ch, SAMSUNG_MARKER, heifEnd, length);
            if (marker >= 0) {
                long start = marker + SAMSUNG_MARKER.length;
                long end = mp4End(ch, start, length);
                if (end > start) {
                    return new Found(start, end, timestamp);
                }
            }
        }
        return null;
    }

    /** The still's moment from a HEIF's XMP, found as text in front of the video; -1 when none. */
    private static long heifTimestamp(@NonNull FileChannel ch, long before) throws IOException {
        int n = (int) Math.min(before, MAX_HEADER_BYTES);
        String text = new String(read(ch, 0, n), StandardCharsets.ISO_8859_1);
        int start = text.indexOf("<x:xmpmeta");
        int end = start >= 0 ? text.indexOf("</x:xmpmeta>", start) : -1;
        if (end < 0) {
            return -1;
        }
        Matcher ts = TIMESTAMP.matcher(text.substring(start, end));
        return ts.find() ? parse(ts.group(1)) : -1;
    }

    private static long parse(@NonNull String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The XMP packets before the picture's first scan. */
    @NonNull
    private static List<String> primaryXmp(@NonNull FileChannel ch, long length) throws IOException {
        List<String> packets = new ArrayList<>();
        long pos = 2;
        long read = 0;
        while (pos + 4 <= length) {
            byte[] head = read(ch, pos, 4);
            if ((head[0] & 0xFF) != 0xFF) {
                break;
            }
            int marker = head[1] & 0xFF;
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int size = ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
            if (size < 2 || pos + 2 + size > length) {
                break;
            }
            if (marker == 0xE1 && size - 2 > XMP_ID.length && (read += size) <= MAX_HEADER_BYTES) {
                byte[] data = read(ch, pos + 4, size - 2);
                if (startsWith(data, XMP_ID)) {
                    packets.add(new String(data, XMP_ID.length, data.length - XMP_ID.length,
                            StandardCharsets.UTF_8));
                }
            }
            pos += 2 + size;
        }
        return packets;
    }

    /**
     * Where the MP4 starting at {@code start} ends, following its top-level boxes until something
     * that is not one, or -1 when there is no MP4 there with both its header and its media.
     */
    @VisibleForTesting
    static long mp4End(@NonNull FileChannel ch, long start, long length) throws IOException {
        if (start < 0 || start + 16 > length
                || !"ftyp".equals(new String(read(ch, start + 4, 4), StandardCharsets.ISO_8859_1))) {
            return -1;
        }
        boolean moov = false;
        boolean mdat = false;
        long pos = start;
        int boxes = 0;
        while (pos + 8 <= length && boxes++ < 10_000) {
            byte[] head = read(ch, pos, 8);
            long size = ByteBuffer.wrap(head, 0, 4).getInt() & 0xFFFFFFFFL;
            String type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
            if (!TOP_LEVEL.contains(type)) {
                break;
            }
            if (size == 1) {
                if (pos + 16 > length) {
                    break;
                }
                size = ByteBuffer.wrap(read(ch, pos + 8, 8)).getLong();
            } else if (size == 0) {
                size = length - pos;
            }
            if (size < 8 || pos + size > length) {
                break;
            }
            moov |= "moov".equals(type);
            mdat |= "mdat".equals(type);
            pos += size;
        }
        return moov && mdat ? pos : -1;
    }

    private static long lastIndexOf(@NonNull FileChannel ch, @NonNull byte[] needle, long from, long to)
            throws IOException {
        int chunk = 1 << 20;
        long end = to;
        while (end > from) {
            long begin = Math.max(from, end - chunk);
            byte[] data = read(ch, begin, (int) (end - begin));
            for (int i = data.length - needle.length; i >= 0; i--) {
                if (matches(data, i, needle)) {
                    return begin + i;
                }
            }
            if (begin == from) {
                break;
            }
            end = begin + needle.length - 1; // overlap, so a marker across chunks is found
        }
        return -1;
    }

    private static boolean matches(@NonNull byte[] data, int at, @NonNull byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (data[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(@NonNull byte[] data, @NonNull byte[] prefix) {
        return data.length >= prefix.length && matches(data, 0, prefix);
    }

    @NonNull
    private static byte[] read(@NonNull FileChannel ch, long pos, int n) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(n);
        while (buffer.hasRemaining()) {
            if (ch.read(buffer, pos + buffer.position()) < 0) {
                throw new IOException("Unexpected end of file");
            }
        }
        return buffer.array();
    }
}
