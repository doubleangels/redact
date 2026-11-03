package com.doubleangels.redact.metadata;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Finds metadata carried inside an H.264, H.265 or MPEG-4 Part 2 video stream rather than its
 * container: SEI "user data unregistered" messages, where camcorders keep the recording date and
 * GPS (AVCHD's MDPM), drones their telemetry, and encoders their settings; SEI registered under
 * ITU-T T.35 by anyone but HDR10+, Dolby Vision and broadcast captions, which describe the
 * picture; MPEG-4 Part 2's user data, where encoders and cameras write their names; and AV1's
 * metadata OBUs (T.35 by anyone but those same picture formats, timecodes, and the types left for
 * private use) and padding OBUs that hold anything but zeros. A clean re-encodes the video, so
 * none of it survives: Scan uses this to show that it is there, and the stripper to skip the
 * quick transmux of Faster Video Cleaning, which would copy it, for a file that carries any.
 *
 * <p>VP8 and VP9 have no syntax for data beside the picture, so there is nothing to read in them.
 * Any other video codec cannot be read here, and the scan answers null for it, as it does when it
 * fails: the stripper re-encodes rather than copy a stream it could not check.
 */
public final class VideoStreamMetadata {

    /** SEI messages found, and the readable text of the first one that has any. */
    public static final class Report {
        public final int userDataMessages;
        public final long userDataBytes;
        @Nullable public final String text;

        Report(int userDataMessages, long userDataBytes, @Nullable String text) {
            this.userDataMessages = userDataMessages;
            this.userDataBytes = userDataBytes;
            this.text = text;
        }
    }

    private static final int MAX_SAMPLES = 90;
    private static final long MAX_SAMPLE_BYTES = 32L * 1024 * 1024;
    private static final int SEI_USER_DATA_REGISTERED = 4;
    private static final int SEI_USER_DATA_UNREGISTERED = 5;
    /** MPEG-4 Part 2's user_data start code value. */
    private static final int MPEG4_USER_DATA = 0xB2;
    private static final int OBU_METADATA = 5;
    private static final int OBU_PADDING = 15;
    /** AV1 metadata_type values: HDR content light level, mastering display, scalability. */
    private static final int AV1_METADATA_SCALABILITY = 3;
    private static final int AV1_METADATA_ITUT_T35 = 4;

    private VideoStreamMetadata() {
    }

    /**
     * Reads the codec configuration and the first samples of the video track; null when it cannot,
     * or when the track's codec is not one this can read.
     */
    @Nullable
    public static Report scan(@NonNull Context context, @NonNull Uri uri) {
        return scan(context, uri, false);
    }

    /**
     * {@link #scan(Context, Uri)}, reading every sample when {@code everySample} is set: what a
     * clean that copies the stream as it is checks, since data could start partway through.
     */
    @Nullable
    public static Report scan(@NonNull Context context, @NonNull Uri uri, boolean everySample) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                boolean hevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime);
                boolean mpeg4 = MediaFormat.MIMETYPE_VIDEO_MPEG4.equals(mime);
                boolean av1 = MediaFormat.MIMETYPE_VIDEO_AV1.equals(mime);
                if (MediaFormat.MIMETYPE_VIDEO_VP8.equals(mime) || MediaFormat.MIMETYPE_VIDEO_VP9.equals(mime)) {
                    return new Report(0, 0, null);
                }
                if (!hevc && !mpeg4 && !av1 && !MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
                    // A codec whose stream this cannot read, so it cannot say the stream is clean.
                    return null;
                }
                Counter counter = new Counter();
                counter.mpeg4 = mpeg4;
                counter.av1 = av1;
                for (String key : new String[] {"csd-0", "csd-1"}) {
                    ByteBuffer csd = format.containsKey(key) ? format.getByteBuffer(key) : null;
                    if (csd != null) {
                        counter.add(csd, hevc);
                    }
                }
                extractor.selectTrack(i);
                ByteBuffer sample = null;
                for (int n = 0; everySample || n < MAX_SAMPLES; n++) {
                    long next = extractor.getSampleSize();
                    if (next < 0) {
                        break;
                    }
                    // A sample too large to hold is skipped rather than ending the scan; SEI sits
                    // in front of the picture data, but a frame this size is rare.
                    if (next <= MAX_SAMPLE_BYTES) {
                        if (sample == null || sample.capacity() < next) {
                            sample = ByteBuffer.allocate((int) Math.max(next, 256 * 1024));
                        }
                        sample.clear();
                        int size = extractor.readSampleData(sample, 0);
                        if (size < 0) {
                            break;
                        }
                        sample.position(0);
                        sample.limit(size);
                        counter.add(sample, hevc);
                    }
                    if (!extractor.advance()) {
                        break;
                    }
                }
                return new Report(counter.messages, counter.bytes, counter.text);
            }
            return new Report(0, 0, null);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            extractor.release();
        }
    }

    private static final class Counter {
        int messages;
        long bytes;
        @Nullable String text;
        /** MPEG-4 Part 2, whose stream is split by start codes rather than into NAL units. */
        boolean mpeg4;
        /** AV1, whose stream is a sequence of OBUs. */
        boolean av1;

        void add(@NonNull ByteBuffer buffer, boolean hevc) {
            byte[] data = new byte[buffer.remaining()];
            buffer.duplicate().get(data);
            if (av1) {
                av1Obus(data, this);
                return;
            }
            if (mpeg4) {
                for (byte[] unit : units(data, first -> first == MPEG4_USER_DATA)) {
                    if (unit.length > 1) {
                        messages++;
                        bytes += unit.length - 1;
                        if (text == null) {
                            text = readableText(unit, 1, unit.length - 1);
                        }
                    }
                }
                return;
            }
            java.util.function.IntPredicate sei = hevc
                    ? first -> ((first >> 1) & 0x3F) == 39 || ((first >> 1) & 0x3F) == 40
                    : first -> (first & 0x1F) == 6;
            for (byte[] nal : units(data, sei)) {
                if (isSei(nal, hevc)) {
                    seiMessages(unescape(nal, hevc ? 2 : 1), this);
                }
            }
        }
    }

    @VisibleForTesting
    static Report scanBuffer(@NonNull byte[] data, boolean hevc) {
        return scanBuffer(data, hevc, false);
    }

    @VisibleForTesting
    static Report scanBuffer(@NonNull byte[] data, boolean hevc, boolean mpeg4) {
        Counter counter = new Counter();
        counter.mpeg4 = mpeg4;
        counter.add(ByteBuffer.wrap(data), hevc);
        return new Report(counter.messages, counter.bytes, counter.text);
    }

    @VisibleForTesting
    static Report scanAv1Buffer(@NonNull byte[] data) {
        Counter counter = new Counter();
        counter.av1 = true;
        counter.add(ByteBuffer.wrap(data), false);
        return new Report(counter.messages, counter.bytes, counter.text);
    }

    /**
     * Counts the AV1 OBUs in {@code d} that carry data of their own. {@code d} is a sample in the
     * low overhead format MP4 and Matroska use, or a codec configuration: an AV1CodecConfiguration
     * record, whose four-byte header (marker and version, 0x81) is skipped to reach its OBUs.
     */
    private static void av1Obus(@NonNull byte[] d, @NonNull Counter counter) {
        int pos = d.length >= 4 && (d[0] & 0xFF) == 0x81 ? 4 : 0;
        while (pos < d.length) {
            int header = d[pos] & 0xFF;
            if ((header & 0x80) != 0) {
                return; // the forbidden bit: not an OBU, so not a stream this can follow
            }
            int type = (header >> 3) & 0x0F;
            boolean extension = (header & 0x04) != 0;
            boolean hasSize = (header & 0x02) != 0;
            int payload = pos + (extension ? 2 : 1);
            if (payload > d.length) {
                return;
            }
            long size;
            if (hasSize) {
                long[] leb = leb128(d, payload);
                if (leb == null) {
                    return;
                }
                size = leb[0];
                payload = (int) leb[1];
            } else {
                size = d.length - payload; // the last OBU runs to the end of the sample
            }
            if (size < 0 || payload + size > d.length) {
                return;
            }
            int length = (int) size;
            if (type == OBU_METADATA) {
                av1Metadata(d, payload, length, counter);
            } else if (type == OBU_PADDING && !allZero(d, payload, length)) {
                count(counter, d, payload, length);
            }
            pos = payload + length;
        }
    }

    private static void av1Metadata(@NonNull byte[] d, int pos, int length, @NonNull Counter counter) {
        long[] leb = leb128(d, pos);
        if (leb == null || leb[1] > pos + length) {
            return;
        }
        long metadataType = leb[0];
        int body = (int) leb[1];
        int bodyLength = pos + length - body;
        if (metadataType >= 1 && metadataType <= AV1_METADATA_SCALABILITY) {
            return; // HDR light levels, mastering display and scalability only describe the picture
        }
        if (metadataType == AV1_METADATA_ITUT_T35 && describesPicture(d, body, bodyLength)) {
            return;
        }
        // T.35 from anyone else, a timecode (which can hold the time of day), and the types left
        // for private use or not yet defined, which a decoder skips and so could hold anything.
        count(counter, d, body, Math.max(bodyLength, 0));
    }

    private static void count(@NonNull Counter counter, @NonNull byte[] d, int pos, int length) {
        counter.messages++;
        counter.bytes += length;
        if (counter.text == null) {
            counter.text = readableText(d, pos, length);
        }
    }

    private static boolean allZero(@NonNull byte[] d, int pos, int length) {
        for (int i = pos; i < pos + length; i++) {
            if (d[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** An unsigned LEB128 value at {@code pos} and the position after it, or null when cut off. */
    @Nullable
    private static long[] leb128(@NonNull byte[] d, int pos) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            if (pos + i >= d.length) {
                return null;
            }
            int b = d[pos + i] & 0xFF;
            value |= (long) (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) {
                return new long[] {value, pos + i + 1};
            }
        }
        return null;
    }

    private static boolean isSei(@NonNull byte[] nal, boolean hevc) {
        if (nal.length < (hevc ? 3 : 2)) {
            return false;
        }
        if (hevc) {
            int type = (nal[0] >> 1) & 0x3F;
            return type == 39 || type == 40;
        }
        return (nal[0] & 0x1F) == 6;
    }

    /**
     * The units of {@code d} whose first byte {@code wanted} accepts, as {@link #nalUnits} splits
     * them. The rest, the picture data that is nearly all of a sample, is never copied, so every
     * frame of a long video can be read.
     */
    @NonNull
    static java.util.List<byte[]> units(@NonNull byte[] d, @NonNull java.util.function.IntPredicate wanted) {
        java.util.List<byte[]> units = new java.util.ArrayList<>();
        int start = nextStartCode(d, 0);
        if (start >= 0) {
            while (start >= 0) {
                int payload = start + (d[start + 2] == 1 ? 3 : 4);
                boolean keep = payload < d.length && wanted.test(d[payload] & 0xFF);
                int next = nextStartCode(d, payload);
                if (keep) {
                    int end = next < 0 ? d.length : next;
                    while (end > payload && d[end - 1] == 0) {
                        end--;
                    }
                    if (end > payload) {
                        units.add(java.util.Arrays.copyOfRange(d, payload, end));
                    }
                }
                start = next;
            }
            return units;
        }
        int pos = 0;
        while (pos + 4 <= d.length) {
            int length = ((d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16)
                    | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            if (length <= 0 || pos + 4L + length > d.length) {
                break;
            }
            if (wanted.test(d[pos + 4] & 0xFF)) {
                units.add(java.util.Arrays.copyOfRange(d, pos + 4, pos + 4 + length));
            }
            pos += 4 + length;
        }
        return units;
    }

    /** Splits Annex B (start codes) or, failing that, 4-byte length-prefixed data into NAL units. */
    @NonNull
    static java.util.List<byte[]> nalUnits(@NonNull byte[] d) {
        java.util.List<byte[]> units = new java.util.ArrayList<>();
        int start = nextStartCode(d, 0);
        if (start >= 0) {
            while (start >= 0) {
                int payload = start + (d[start + 2] == 1 ? 3 : 4);
                int next = nextStartCode(d, payload);
                int end = next < 0 ? d.length : next;
                while (end > payload && d[end - 1] == 0) {
                    end--; // trailing zero bytes belong to the next start code
                }
                if (end > payload) {
                    units.add(java.util.Arrays.copyOfRange(d, payload, end));
                }
                start = next;
            }
            return units;
        }
        int pos = 0;
        while (pos + 4 <= d.length) {
            int length = ((d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16)
                    | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            if (length <= 0 || pos + 4L + length > d.length) {
                break;
            }
            units.add(java.util.Arrays.copyOfRange(d, pos + 4, pos + 4 + length));
            pos += 4 + length;
        }
        return units;
    }

    private static int nextStartCode(@NonNull byte[] d, int from) {
        for (int i = from; i + 3 <= d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0) {
                if (d[i + 2] == 1) {
                    return i;
                }
                if (i + 4 <= d.length && d[i + 2] == 0 && d[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The RBSP after the NAL header, with emulation prevention bytes removed. */
    @NonNull
    private static byte[] unescape(@NonNull byte[] nal, int headerLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = headerLength; i < nal.length; i++) {
            int b = nal[i] & 0xFF;
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    private static void seiMessages(@NonNull byte[] rbsp, @NonNull Counter counter) {
        int pos = 0;
        while (pos < rbsp.length && !(rbsp.length - pos == 1 && (rbsp[pos] & 0xFF) == 0x80)) {
            int type = 0;
            while (pos < rbsp.length && (rbsp[pos] & 0xFF) == 0xFF) {
                type += 255;
                pos++;
            }
            if (pos >= rbsp.length) {
                return;
            }
            type += rbsp[pos++] & 0xFF;
            int size = 0;
            while (pos < rbsp.length && (rbsp[pos] & 0xFF) == 0xFF) {
                size += 255;
                pos++;
            }
            if (pos >= rbsp.length) {
                return;
            }
            size += rbsp[pos++] & 0xFF;
            if (pos + size > rbsp.length) {
                return;
            }
            if (type == SEI_USER_DATA_UNREGISTERED && size >= 16) {
                counter.messages++;
                counter.bytes += size;
                if (counter.text == null) {
                    counter.text = readableText(rbsp, pos + 16, size - 16);
                }
            } else if (type == SEI_USER_DATA_REGISTERED && size >= 1 && !describesPicture(rbsp, pos, size)) {
                counter.messages++;
                counter.bytes += size;
                if (counter.text == null) {
                    counter.text = readableText(rbsp, pos, size);
                }
            }
            pos += size;
        }
    }

    /**
     * Whether a T.35 message is one that only says how to show the picture: HDR10+ (Samsung's
     * provider code), Dolby Vision, or ATSC captions and bar data ({@code GA94}), all registered
     * in the United States (0xB5).
     */
    @VisibleForTesting
    static boolean describesPicture(@NonNull byte[] rbsp, int pos, int size) {
        if (size < 3 || (rbsp[pos] & 0xFF) != 0xB5) {
            return false;
        }
        int provider = ((rbsp[pos + 1] & 0xFF) << 8) | (rbsp[pos + 2] & 0xFF);
        return provider == 0x003C || provider == 0x003B || provider == 0x0031;
    }

    /**
     * The payload as text when it mostly is text, such as an encoder's settings line; otherwise
     * the readable strings inside it, such as a camcorder's model name.
     */
    @Nullable
    private static String readableText(@NonNull byte[] data, int offset, int length) {
        if (length < 4) {
            return null;
        }
        int printable = 0;
        int n = Math.min(length, 2048);
        for (int i = offset; i < offset + n; i++) {
            int c = data[i] & 0xFF;
            if ((c >= 0x20 && c < 0x7F) || c == 0) {
                printable++;
            }
        }
        if (printable < n * 9 / 10) {
            return ReadableText.strings(data, offset, n);
        }
        String text = new String(data, offset, n, StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
        return text.isEmpty() ? null : text;
    }
}
