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
 * Finds metadata carried inside an H.264 or H.265 video stream rather than its container: SEI
 * "user data unregistered" messages, where camcorders keep the recording date and GPS (AVCHD's
 * MDPM), drones their telemetry, and encoders their settings. A clean re-encodes the video, so
 * none of it survives: Scan uses this to show that it is there, and the stripper to skip the
 * quick transmux of Faster Video Cleaning, which would copy it, for a file that carries any.
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
    private static final int SEI_USER_DATA_UNREGISTERED = 5;

    private VideoStreamMetadata() {
    }

    /** Reads the codec configuration and the first samples of the video track; null when it cannot. */
    @Nullable
    public static Report scan(@NonNull Context context, @NonNull Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                boolean hevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime);
                if (!hevc && !MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
                    continue;
                }
                Counter counter = new Counter();
                for (String key : new String[] {"csd-0", "csd-1"}) {
                    ByteBuffer csd = format.containsKey(key) ? format.getByteBuffer(key) : null;
                    if (csd != null) {
                        counter.add(csd, hevc);
                    }
                }
                extractor.selectTrack(i);
                ByteBuffer sample = null;
                for (int n = 0; n < MAX_SAMPLES; n++) {
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

        void add(@NonNull ByteBuffer buffer, boolean hevc) {
            byte[] data = new byte[buffer.remaining()];
            buffer.duplicate().get(data);
            for (byte[] nal : nalUnits(data)) {
                if (isSei(nal, hevc)) {
                    seiMessages(unescape(nal, hevc ? 2 : 1), this);
                }
            }
        }
    }

    @VisibleForTesting
    static Report scanBuffer(@NonNull byte[] data, boolean hevc) {
        Counter counter = new Counter();
        counter.add(ByteBuffer.wrap(data), hevc);
        return new Report(counter.messages, counter.bytes, counter.text);
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
            }
            pos += size;
        }
    }

    /** The payload as text when it mostly is text, such as an encoder's settings line. */
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
            return null;
        }
        String text = new String(data, offset, n, StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
        return text.isEmpty() ? null : text;
    }
}
