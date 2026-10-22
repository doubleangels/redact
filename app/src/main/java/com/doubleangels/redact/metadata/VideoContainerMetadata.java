package com.doubleangels.redact.metadata;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds and removes identifying metadata at the container level of MP4/MOV/3GP (ISO base media)
 * and WebM/Matroska files.
 *
 * <p>{@link android.media.MediaMetadataRetriever} only reports the few tags the platform
 * understands, so a location written as a QuickTime {@code mdta} key, a 3GPP {@code loci} box,
 * an XMP packet or a Matroska tag is invisible to it. This class walks the boxes (or EBML
 * elements) itself, so a file is never called clean while any of them remain.
 *
 * <p>{@link #scrub} works in place and never changes the file's length or the position of
 * anything left in it: a metadata box becomes a {@code free} box (a Matroska element becomes a
 * Void element) of the same size with its contents zeroed, and header timestamps are zeroed.
 * Sample offsets therefore stay valid and the video plays exactly as before.
 */
public final class VideoContainerMetadata {

    /** What a scan found: the box/key names, and for Scan what each holds. */
    public static final class Report {
        /** False when the file is not a container this class can read. */
        public final boolean recognized;
        @NonNull
        public final List<String> fields;
        public final boolean hasLocation;
        /** Field name to what it holds, as text, for the fields that hold something readable. */
        @NonNull
        public final Map<String, String> values;
        /** Field name to how many bytes it takes up. */
        @NonNull
        public final Map<String, Long> sizes;
        /** Latitude and longitude, when a location field holds coordinates. */
        @Nullable
        public final double[] coordinates;
        /** The fields found to hold a location, such as an XMP packet with GPS tags in it. */
        @NonNull
        private final Set<String> locationFields;

        Report(boolean recognized, @NonNull List<String> fields, boolean hasLocation) {
            this(recognized, fields, hasLocation, new LinkedHashMap<>(), new LinkedHashMap<>(), null,
                    new HashSet<>());
        }

        Report(boolean recognized, @NonNull List<String> fields, boolean hasLocation,
               @NonNull Map<String, String> values, @NonNull Map<String, Long> sizes,
               @Nullable double[] coordinates, @NonNull Set<String> locationFields) {
            this.recognized = recognized;
            this.fields = Collections.unmodifiableList(fields);
            this.hasLocation = hasLocation;
            this.values = Collections.unmodifiableMap(values);
            this.sizes = Collections.unmodifiableMap(sizes);
            this.coordinates = coordinates;
            this.locationFields = Collections.unmodifiableSet(locationFields);
        }

        /**
         * Whether {@code name}'s value can give away where the video was made: a location tag, or
         * a field such as XMP that was found to hold GPS data. Scan hides these values without the
         * media location permission.
         */
        public boolean isLocationField(@NonNull String name) {
            return locationFields.contains(name) || isLocationName(name);
        }

        /** True only when the file was read and nothing identifying was found. */
        public boolean isClean() {
            return recognized && fields.isEmpty();
        }

        static Report unrecognized() {
            return new Report(false, new ArrayList<>(), false);
        }
    }

    private static final int MAX_DEPTH = 12;
    private static final int MAX_ELEMENTS = 500_000;
    /** How much of an XMP packet or uuid box is searched for GPS data. */
    private static final int CONTENT_PEEK = 1 << 20;
    /** Longest value kept for Scan. */
    private static final int MAX_VALUE = 16 * 1024;
    /** How many bytes of a text field to read: enough for MAX_VALUE characters and then some. */
    private static final int TEXT_PEEK = MAX_VALUE * 4 + 16;
    /** Seconds from 1904, when QuickTime's clock starts, to 1970. */
    private static final long SECONDS_1904_TO_1970 = 2_082_844_800L;
    /** Milliseconds from 1970 to 2001, when Matroska's clock starts. */
    private static final long MILLIS_1970_TO_2001 = 978_307_200_000L;

    private VideoContainerMetadata() {}

    // ---- public API ---------------------------------------------------------------------------

    /** Scans a file; never throws. An unreadable file is reported as not recognized. */
    @NonNull
    public static Report scan(@NonNull File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return scan(in.getChannel());
        } catch (IOException | RuntimeException e) {
            return Report.unrecognized();
        }
    }

    /** Scans a content or file URI; never throws. */
    @NonNull
    public static Report scan(@NonNull Context context, @NonNull Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return Report.unrecognized();
            }
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                return scan(in.getChannel());
            }
        } catch (IOException | RuntimeException e) {
            return Report.unrecognized();
        }
    }

    /**
     * Removes every identifying box or element from {@code file} in place.
     *
     * @return the number of boxes, elements and timestamps blanked
     * @throws IOException if the file cannot be read or written, or is not a known container
     */
    public static int scrub(@NonNull File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            Walker walker = new Walker(raf.getChannel(), true);
            if (!walker.run()) {
                throw new IOException("Not a recognized video container");
            }
            return walker.scrubbed;
        }
    }

    @VisibleForTesting
    @NonNull
    static Report scan(@NonNull FileChannel channel) throws IOException {
        Walker walker = new Walker(channel, false);
        if (!walker.run()) {
            return Report.unrecognized();
        }
        return new Report(true, walker.fields, walker.location, walker.values, walker.sizes, walker.coordinates,
                walker.locationFields);
    }

    // ---- the walk -----------------------------------------------------------------------------

    private static final class Walker {
        private final FileChannel ch;
        private final boolean scrub;
        private final long length;
        final List<String> fields = new ArrayList<>();
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, Long> sizes = new LinkedHashMap<>();
        final Set<String> locationFields = new HashSet<>();
        @Nullable double[] coordinates;
        boolean location;
        int scrubbed;
        private int elements;
        private boolean dateCounted;
        private int simpleTags;

        Walker(FileChannel ch, boolean scrub) throws IOException {
            this.ch = ch;
            this.scrub = scrub;
            this.length = ch.size();
        }

        boolean run() throws IOException {
            if (length < 8) {
                return false;
            }
            byte[] head = read(0, 8);
            if ((head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
                ebmlTop();
                return true;
            }
            String type = fourCc(head, 4);
            if (BMFF_TOP_LEVEL.contains(type)) {
                boxes(0, length, Level.TOP, 0);
                return true;
            }
            return false;
        }

        private void field(@NonNull String name, boolean isLocation) {
            field(name, isLocation, null, -1);
        }

        /** Records a field, with what it holds and its size for Scan; a scrub keeps neither. */
        private void field(@NonNull String name, boolean isLocation, @Nullable String value, long size) {
            fields.add(name);
            location |= isLocation;
            if (isLocation) {
                locationFields.add(name);
            }
            if (scrub) {
                return;
            }
            String text = value == null ? null : value.replace('\0', ' ').trim();
            if (text != null && !text.isEmpty() && !values.containsKey(name)) {
                values.put(name, text.length() > MAX_VALUE ? text.substring(0, MAX_VALUE) + "…" : text);
                if (isLocation && coordinates == null) {
                    coordinates = ReadableText.iso6709(text);
                }
            }
            if (size >= 0) {
                sizes.merge(name, size, Long::sum);
            }
        }

        /** The bytes in [start, end) as UTF-8 text; null when scrubbing. */
        @Nullable
        private String text(long start, long end) throws IOException {
            if (scrub || end <= start) {
                return null;
            }
            // A field keeps MAX_VALUE characters, at most four bytes each; a little more tells
            // field() that the value was cut short.
            return new String(read(start, (int) Math.min(end - start, TEXT_PEEK)), StandardCharsets.UTF_8);
        }

        /** [start, end) as text when it all is, else the readable strings in it; null when scrubbing. */
        @Nullable
        private String strings(long start, long end) throws IOException {
            if (scrub || end <= start) {
                return null;
            }
            return ReadableText.textOrStrings(read(start, (int) Math.min(end - start, 64 * 1024)));
        }

        private void count() throws IOException {
            if (++elements > MAX_ELEMENTS) {
                throw new IOException("Too many container elements");
            }
        }

        // ---- ISO base media (MP4, MOV, 3GP) ---------------------------------------------------

        private enum Level { TOP, MOOV, TRAK, MDIA, OTHER }

        private static final List<String> BMFF_TOP_LEVEL = List.of(
                "ftyp", "styp", "moov", "mdat", "free", "skip", "wide", "pnot", "moof", "mfra",
                "sidx", "ssix", "prft", "emsg", "pdin", "uuid", "meta", "junk");
        /** Top-level boxes that hold media or structure, not information about the recording. */
        private static final List<String> BMFF_TOP_BENIGN = List.of(
                "ftyp", "styp", "moov", "mdat", "free", "skip", "wide", "moof", "mfra", "sidx",
                "ssix", "prft", "emsg", "pdin");
        /** Keys ffmpeg copies from the ftyp box; they describe the format, not the recording. */
        private static final List<String> BENIGN_KEYS = List.of(
                "major_brand", "minor_version", "compatible_brands");
        private static final String XMP_UUID = "be7acfcb97a942e89c71999491e3afac";

        private void boxes(long start, long end, Level level, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("Boxes nested too deeply");
            }
            long pos = start;
            while (pos + 8 <= end) {
                count();
                byte[] h = read(pos, 8);
                long size = u32(h, 0);
                String type = fourCc(h, 4);
                int header = 8;
                if (size == 1) {
                    if (pos + 16 > end) {
                        throw new IOException("Truncated box header");
                    }
                    size = ByteBuffer.wrap(read(pos + 8, 8)).getLong();
                    header = 16;
                } else if (size == 0) {
                    size = end - pos;
                }
                if (size < header || pos + size > end) {
                    throw new IOException("Malformed box " + type);
                }
                box(pos, size, header, type, level, depth);
                pos += size;
            }
        }

        private void box(long pos, long size, int header, String type, Level level, int depth)
                throws IOException {
            long body = pos + header;
            long end = pos + size;
            switch (type) {
                case "moov":
                    if (level == Level.TOP) {
                        boxes(body, end, Level.MOOV, depth + 1);
                    }
                    return;
                case "trak":
                    if (level == Level.MOOV) {
                        trak(body, end, depth);
                    }
                    return;
                case "mdia":
                    if (level == Level.TRAK) {
                        boxes(body, end, Level.MDIA, depth + 1);
                    }
                    return;
                case "mvhd":
                case "tkhd":
                case "mdhd":
                    timestamps(body, end);
                    return;
                case "udta":
                    if (level != Level.OTHER) {
                        udta(body, end, depth);
                        blank(pos, size, header);
                    }
                    return;
                case "meta":
                    if (level != Level.OTHER) {
                        meta(body, end, depth);
                        blank(pos, size, header);
                    }
                    return;
                case "uuid":
                    if (level != Level.OTHER) {
                        uuid(body, end);
                        blank(pos, size, header);
                    }
                    return;
                default:
                    if (level == Level.TOP && !BMFF_TOP_BENIGN.contains(type)) {
                        field(type, false, strings(body, end), size - header);
                        blank(pos, size, header);
                    }
            }
        }

        private void trak(long body, long end, int depth) throws IOException {
            String handler = trackHandler(body, end);
            if (handler != null && !handler.equals("vide") && !handler.equals("soun")) {
                // A timed-metadata, subtitle or timecode track. It cannot be blanked without
                // leaving its samples behind, so a file with one is never reported clean and
                // verification fails, sending the clean down the full re-encode instead.
                field("track:" + handler, false, null, end - body);
            }
            boxes(body, end, Level.TRAK, depth + 1);
        }

        @Nullable
        private String trackHandler(long body, long end) throws IOException {
            long mdia = child(body, end, "mdia");
            if (mdia < 0) {
                return null;
            }
            long mdiaEnd = mdia + boxSize(mdia);
            long hdlr = child(mdia + 8, mdiaEnd, "hdlr");
            if (hdlr < 0 || hdlr + 20 > mdiaEnd) {
                return null;
            }
            return fourCc(read(hdlr + 16, 4), 0);
        }

        /** Offset of the first direct child of the given type, or -1. */
        private long child(long start, long end, String wanted) throws IOException {
            long pos = start;
            while (pos + 8 <= end) {
                byte[] h = read(pos, 8);
                long size = boxSize(pos);
                if (size < 8 || pos + size > end) {
                    return -1;
                }
                if (fourCc(h, 4).equals(wanted)) {
                    return pos;
                }
                pos += size;
            }
            return -1;
        }

        private long boxSize(long pos) throws IOException {
            byte[] h = read(pos, 8);
            long size = u32(h, 0);
            if (size == 1) {
                size = ByteBuffer.wrap(read(pos + 8, 8)).getLong();
            } else if (size == 0) {
                size = length - pos;
            }
            return size;
        }

        /** Creation and modification times in mvhd/tkhd/mdhd; counted once as the recording date. */
        private void timestamps(long body, long end) throws IOException {
            if (body + 4 > end) {
                return;
            }
            int version = read(body, 1)[0] & 0xFF;
            int width = version == 1 ? 8 : 4;
            if (body + 4 + 2L * width > end) {
                return;
            }
            byte[] times = read(body + 4, 2 * width);
            boolean set = false;
            for (byte b : times) {
                if (b != 0) {
                    set = true;
                    break;
                }
            }
            if (!set) {
                return;
            }
            if (!dateCounted) {
                dateCounted = true;
                field("date", false, scrub ? null : quickTimeDate(times, width), -1);
            }
            if (scrub) {
                write(body + 4, new byte[2 * width]);
                scrubbed++;
            }
        }

        /** The creation time, or the modification time when that is unset, counted from 1904. */
        @NonNull
        private static String quickTimeDate(@NonNull byte[] times, int width) {
            long created = 0;
            long modified = 0;
            for (int i = 0; i < width; i++) {
                created = (created << 8) | (times[i] & 0xFF);
                modified = (modified << 8) | (times[width + i] & 0xFF);
            }
            long seconds = created != 0 ? created : modified;
            return ReadableText.utc((seconds - SECONDS_1904_TO_1970) * 1000);
        }

        /**
         * What a user-data atom holds: an iTunes-style data box, a 3GPP location, a QuickTime
         * text atom (length, language, text), or failing those its readable strings.
         */
        @Nullable
        private String udtaValue(@NonNull String type, long body, long end) throws IOException {
            if (scrub) {
                return null;
            }
            if (body + 8 <= end && fourCc(read(body, 8), 4).equals("data")) {
                return itemValue(body, end);
            }
            if (type.equals("loci")) {
                String place = loci(body, end);
                if (place != null) {
                    return place;
                }
            } else if (type.startsWith("©") && body + 4 <= end) {
                byte[] h = read(body, 4);
                int length = ((h[0] & 0xFF) << 8) | (h[1] & 0xFF);
                if (length > 0 && body + 4 + length <= end) {
                    return text(body + 4, body + 4 + length);
                }
            }
            return strings(body, end);
        }

        /** The value in an ilst item's data box, by its well-known type. */
        @Nullable
        private String itemValue(long body, long end) throws IOException {
            long data = child(body, end, "data");
            if (data < 0) {
                return strings(body, end);
            }
            long dataEnd = data + boxSize(data);
            if (data + 16 > dataEnd) {
                return null;
            }
            int type = (int) (u32(read(data + 8, 4), 0) & 0xFFFFFF);
            long payload = data + 16;
            int n = (int) Math.min(dataEnd - payload, CONTENT_PEEK);
            switch (type) {
                case 1:
                    return text(payload, dataEnd);
                case 2:
                    return new String(read(payload, n), StandardCharsets.UTF_16BE);
                case 21:
                case 22:
                    if (n < 1 || n > 8) {
                        return null;
                    }
                    byte[] b = read(payload, n);
                    long value = type == 21 ? b[0] : b[0] & 0xFF;
                    for (int i = 1; i < n; i++) {
                        value = (value << 8) | (b[i] & 0xFF);
                    }
                    return String.valueOf(value);
                case 13:
                case 14:
                case 27:
                    return null; // cover art
                default:
                    return strings(payload, dataEnd);
            }
        }

        /**
         * A 3GPP {@code loci} box: version and flags, language, the place's name, its role, then
         * longitude, latitude and altitude in 16.16 fixed point. Sets {@link #coordinates}.
         *
         * @return the place's name, or the coordinates when it has none; null when it is not one
         */
        @Nullable
        private String loci(long body, long end) throws IOException {
            byte[] b = read(body, (int) Math.min(end - body, 4096));
            int nameStart = 6;
            int nul = -1;
            for (int i = nameStart; i < b.length; i++) {
                if (b[i] == 0) {
                    nul = i;
                    break;
                }
            }
            if (nul < 0 || nul + 1 + 1 + 8 > b.length) {
                return null;
            }
            String name = new String(b, nameStart, nul - nameStart, StandardCharsets.UTF_8).trim();
            int at = nul + 2; // past the NUL and the role
            double lon = ByteBuffer.wrap(b, at, 4).getInt() / 65536.0;
            double lat = ByteBuffer.wrap(b, at + 4, 4).getInt() / 65536.0;
            String place = String.format(Locale.ROOT, "%+.6f%+.6f/", lat, lon);
            double[] parsed = ReadableText.iso6709(place);
            if (parsed != null && coordinates == null) {
                coordinates = parsed;
            }
            if (name.isEmpty()) {
                return parsed != null ? place : null;
            }
            return name;
        }

        private static final long ID_TAG_STRING = 0x4487L;
        private static final long ID_TAG_BINARY = 0x4485L;
        private static final long ID_FILE_NAME = 0x466EL;
        private static final long ID_FILE_MIME_TYPE = 0x4660L;

        /** A SimpleTag's TagString, or the readable strings in its TagBinary. */
        @Nullable
        private String tagValue(long start, long end) throws IOException {
            long pos = start;
            while (pos < end) {
                Element e = element(pos, end);
                if (e.dataSize < 0) {
                    return null;
                }
                if (e.id == ID_TAG_STRING) {
                    return text(e.dataStart, e.dataStart + e.dataSize);
                }
                if (e.id == ID_TAG_BINARY) {
                    return strings(e.dataStart, e.dataStart + e.dataSize);
                }
                pos = e.dataStart + e.dataSize;
            }
            return null;
        }

        /** An attached file's name, with its type when that is given. */
        @Nullable
        private String attachmentValue(long start, long end) throws IOException {
            String name = null;
            String mime = null;
            long pos = start;
            while (pos < end) {
                Element e = element(pos, end);
                if (e.dataSize < 0) {
                    break;
                }
                if (e.id == ID_FILE_NAME) {
                    name = text(e.dataStart, e.dataStart + Math.min(e.dataSize, 512));
                } else if (e.id == ID_FILE_MIME_TYPE) {
                    mime = text(e.dataStart, e.dataStart + Math.min(e.dataSize, 256));
                }
                pos = e.dataStart + e.dataSize;
            }
            if (name == null) {
                return mime;
            }
            return mime == null ? name : name + " (" + mime + ")";
        }

        /** A Matroska DateUTC: signed nanoseconds since 2001. */
        @Nullable
        private String matroskaDate(long start, long size) throws IOException {
            if (scrub || size < 1 || size > 8) {
                return null;
            }
            byte[] b = read(start, (int) size);
            long nanos = b[0];
            for (int i = 1; i < b.length; i++) {
                nanos = (nanos << 8) | (b[i] & 0xFF);
            }
            return ReadableText.utc(MILLIS_1970_TO_2001 + nanos / 1_000_000);
        }

        private void udta(long body, long end, int depth) throws IOException {
            long pos = body;
            while (pos + 8 <= end) {
                count();
                byte[] h = read(pos, 8);
                long size = u32(h, 0);
                if (size == 0 && u32(h, 4) == 0) {
                    break; // QuickTime ends some udta boxes with zero padding.
                }
                int header = 8;
                if (size == 1) {
                    size = ByteBuffer.wrap(read(pos + 8, 8)).getLong();
                    header = 16;
                } else if (size == 0) {
                    size = end - pos;
                }
                if (size < header || pos + size > end) {
                    // Unparseable contents still count as something to remove.
                    field("udta", false, null, end - pos);
                    return;
                }
                String type = fourCc(h, 4);
                switch (type) {
                    case "meta":
                        meta(pos + header, pos + size, depth + 1);
                        break;
                    case "free":
                    case "skip":
                        break;
                    case "XMP_":
                        field("XMP", containsGps(pos + header, pos + size), text(pos + header, pos + size),
                                size - header);
                        break;
                    case "uuid":
                        uuid(pos + header, pos + size);
                        break;
                    default:
                        field(type, isLocationName(type), udtaValue(type, pos + header, pos + size),
                                size - header);
                }
                pos += size;
            }
        }

        private void meta(long body, long end, int depth) throws IOException {
            if (depth > MAX_DEPTH || body + 8 > end) {
                return;
            }
            // QuickTime meta boxes start straight with a child box; ISO ones with version/flags.
            String second = fourCc(read(body, 8), 4);
            long start = second.equals("hdlr") || second.equals("keys") || second.equals("ilst")
                    ? body : body + 4;
            String handler = null;
            List<String> keys = new ArrayList<>();
            long ilst = -1;
            long pos = start;
            while (pos + 8 <= end) {
                count();
                long size = boxSize(pos);
                if (size < 8 || pos + size > end) {
                    field("meta", false, null, end - start);
                    return;
                }
                String type = fourCc(read(pos, 8), 4);
                switch (type) {
                    case "hdlr":
                        if (pos + 20 <= pos + size) {
                            handler = fourCc(read(pos + 16, 4), 0);
                        }
                        break;
                    case "keys":
                        keys = readKeys(pos + 8, pos + size);
                        break;
                    case "ilst":
                        ilst = pos;
                        break;
                    case "free":
                    case "skip":
                        break;
                    case "uuid":
                        uuid(pos + 8, pos + size);
                        break;
                    case "xml ":
                    case "XMP_":
                        field("XMP", containsGps(pos + 8, pos + size), text(pos + 8, pos + size), size - 8);
                        break;
                    default:
                        field(type, false, strings(pos + 8, pos + size), size - 8);
                }
                pos += size;
            }
            if (ilst >= 0) {
                ilst(ilst + 8, ilst + boxSize(ilst), "mdta".equals(handler) ? keys : null);
            }
        }

        private List<String> readKeys(long body, long end) throws IOException {
            List<String> keys = new ArrayList<>();
            if (body + 8 > end) {
                return keys;
            }
            long pos = body + 8; // version/flags and entry count
            while (pos + 8 <= end) {
                count();
                long size = u32(read(pos, 4), 0);
                if (size < 8 || pos + size > end) {
                    break;
                }
                int nameLength = (int) Math.min(size - 8, 512);
                keys.add(new String(read(pos + 8, nameLength), StandardCharsets.UTF_8));
                pos += size;
            }
            return keys;
        }

        private void ilst(long body, long end, @Nullable List<String> keys) throws IOException {
            long pos = body;
            while (pos + 8 <= end) {
                count();
                byte[] h = read(pos, 8);
                long size = u32(h, 0);
                if (size < 8 || pos + size > end) {
                    field("ilst", false, null, end - pos);
                    return;
                }
                String name = fourCc(h, 4);
                if (keys != null) {
                    long index = u32(h, 4);
                    if (index >= 1 && index <= keys.size()) {
                        name = keys.get((int) index - 1);
                    }
                }
                if (!BENIGN_KEYS.contains(name)) {
                    field(name, isLocationName(name), scrub ? null : itemValue(pos + 8, pos + size), size - 8);
                }
                pos += size;
            }
        }

        private void uuid(long body, long end) throws IOException {
            if (body + 16 > end) {
                field("uuid", false, null, end - body);
                return;
            }
            StringBuilder id = new StringBuilder();
            for (byte b : read(body, 16)) {
                id.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            boolean gps = containsGps(body + 16, end);
            boolean xmp = XMP_UUID.contentEquals(id);
            field(xmp ? "XMP" : "uuid", gps, xmp ? text(body + 16, end) : strings(body + 16, end), end - body);
        }

        private boolean containsGps(long start, long end) throws IOException {
            int n = (int) Math.max(0, Math.min(end - start, CONTENT_PEEK));
            String text = new String(read(start, n), StandardCharsets.ISO_8859_1);
            return text.contains("GPS") || text.toLowerCase(Locale.ROOT).contains("location");
        }

        /** Turns a box into a free box of the same size with nothing left inside it. */
        private void blank(long pos, long size, int header) throws IOException {
            if (!scrub) {
                return;
            }
            write(pos + 4, "free".getBytes(StandardCharsets.US_ASCII));
            zero(pos + header, size - header);
            scrubbed++;
        }

        // ---- EBML (WebM, Matroska) -------------------------------------------------------------

        private static final long ID_SEGMENT = 0x18538067L;
        private static final long ID_INFO = 0x1549A966L;
        private static final long ID_TAGS = 0x1254C367L;
        private static final long ID_TAG = 0x7373L;
        private static final long ID_SIMPLE_TAG = 0x67C8L;
        private static final long ID_TAG_NAME = 0x45A3L;
        private static final long ID_ATTACHMENTS = 0x1941A469L;
        private static final long ID_ATTACHED_FILE = 0x61A7L;
        private static final long ID_TITLE = 0x7BA9L;
        private static final long ID_DATE_UTC = 0x4461L;
        private static final long ID_CLUSTER = 0x1F43B675L;
        /**
         * Tags muxers add to describe the streams themselves. They are not counted, though the
         * Tags element holding them is still voided with the rest.
         */
        private static final List<String> BENIGN_MATROSKA_TAGS = List.of(
                "DURATION", "BPS", "NUMBER_OF_FRAMES", "NUMBER_OF_BYTES", "_STATISTICS_TAGS");
        private static final List<Long> SEGMENT_CHILDREN = List.of(
                0x114D9B74L, ID_INFO, 0x1654AE6BL, ID_CLUSTER, 0x1C53BB6BL, ID_TAGS,
                ID_ATTACHMENTS, 0x1043A770L, 0xECL, 0xBFL);

        /** One element header: where its data starts and how long it is (-1 when unknown). */
        private static final class Element {
            long id;
            long dataStart;
            long dataSize;
            int headerLength;
        }

        @NonNull
        private Element element(long pos, long end) throws IOException {
            Element e = new Element();
            int first = read(pos, 1)[0] & 0xFF;
            int idLength = vintLength(first);
            if (idLength > 4 || pos + idLength > end) {
                throw new IOException("Bad EBML id");
            }
            byte[] idBytes = read(pos, idLength);
            long id = 0;
            for (byte b : idBytes) {
                id = (id << 8) | (b & 0xFF);
            }
            int sizeFirst = read(pos + idLength, 1)[0] & 0xFF;
            int sizeLength = vintLength(sizeFirst);
            if (sizeLength > 8 || pos + idLength + sizeLength > end) {
                throw new IOException("Bad EBML size");
            }
            byte[] sizeBytes = read(pos + idLength, sizeLength);
            long size = sizeBytes[0] & (0xFF >> sizeLength);
            boolean allOnes = size == (0xFF >> sizeLength);
            for (int i = 1; i < sizeLength; i++) {
                size = (size << 8) | (sizeBytes[i] & 0xFF);
                allOnes &= (sizeBytes[i] & 0xFF) == 0xFF;
            }
            e.id = id;
            e.headerLength = idLength + sizeLength;
            e.dataStart = pos + e.headerLength;
            e.dataSize = allOnes ? -1 : size;
            if (e.dataSize >= 0 && e.dataStart + e.dataSize > end) {
                throw new IOException("EBML element runs past its parent");
            }
            return e;
        }

        private static int vintLength(int first) throws IOException {
            if (first == 0) {
                throw new IOException("Bad EBML vint");
            }
            return Integer.numberOfLeadingZeros(first) - 23;
        }

        private void ebmlTop() throws IOException {
            long pos = 0;
            while (pos < length) {
                count();
                Element e = element(pos, length);
                long end = e.dataSize < 0 ? length : e.dataStart + e.dataSize;
                if (e.id == ID_SEGMENT) {
                    segment(e.dataStart, end);
                }
                pos = end;
            }
        }

        private void segment(long start, long end) throws IOException {
            long pos = start;
            while (pos < end) {
                count();
                Element e = element(pos, end);
                long elementEnd = e.dataSize < 0 ? unknownSizeEnd(e.dataStart, end) : e.dataStart + e.dataSize;
                if (e.id == ID_INFO) {
                    info(e.dataStart, elementEnd);
                } else if (e.id == ID_TAGS) {
                    int before = simpleTags;
                    tags(e.dataStart, elementEnd, 0);
                    if (simpleTags == before) {
                        // Nothing readable inside: still something to remove.
                        field("tags", false, null, elementEnd - pos);
                    }
                    voidOut(pos, elementEnd);
                } else if (e.id == ID_ATTACHMENTS) {
                    int before = fields.size();
                    attachments(e.dataStart, elementEnd);
                    if (fields.size() == before) {
                        field("attachments", false, null, elementEnd - pos);
                    }
                    voidOut(pos, elementEnd);
                }
                pos = elementEnd;
            }
        }

        /** The end of an element of unknown size: where the next segment-level element begins. */
        private long unknownSizeEnd(long start, long end) throws IOException {
            long pos = start;
            while (pos < end) {
                count();
                Element e = element(pos, end);
                if (SEGMENT_CHILDREN.contains(e.id) || e.dataSize < 0) {
                    return pos;
                }
                pos = e.dataStart + e.dataSize;
            }
            return end;
        }

        private void info(long start, long end) throws IOException {
            long pos = start;
            while (pos < end) {
                count();
                Element e = element(pos, end);
                if (e.dataSize < 0) {
                    return;
                }
                long elementEnd = e.dataStart + e.dataSize;
                if (e.id == ID_TITLE) {
                    field("title", false, text(e.dataStart, elementEnd), e.dataSize);
                    voidOut(pos, elementEnd);
                } else if (e.id == ID_DATE_UTC) {
                    if (!dateCounted) {
                        dateCounted = true;
                        field("date", false, matroskaDate(e.dataStart, e.dataSize), -1);
                    }
                    voidOut(pos, elementEnd);
                }
                pos = elementEnd;
            }
        }

        private void tags(long start, long end, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("Tags nested too deeply");
            }
            long pos = start;
            while (pos < end) {
                count();
                Element e = element(pos, end);
                if (e.dataSize < 0) {
                    return;
                }
                long elementEnd = e.dataStart + e.dataSize;
                if (e.id == ID_TAG) {
                    tags(e.dataStart, elementEnd, depth + 1);
                } else if (e.id == ID_SIMPLE_TAG) {
                    simpleTags++;
                    String name = tagName(e.dataStart, elementEnd);
                    if (!BENIGN_MATROSKA_TAGS.contains(name.toUpperCase(Locale.ROOT))) {
                        field(name, isLocationName(name), scrub ? null : tagValue(e.dataStart, elementEnd),
                                e.dataSize);
                    }
                    tags(e.dataStart, elementEnd, depth + 1);
                }
                pos = elementEnd;
            }
        }

        @NonNull
        private String tagName(long start, long end) throws IOException {
            long pos = start;
            while (pos < end) {
                Element e = element(pos, end);
                if (e.dataSize < 0) {
                    break;
                }
                if (e.id == ID_TAG_NAME) {
                    return new String(read(e.dataStart, (int) Math.min(e.dataSize, 256)), StandardCharsets.UTF_8);
                }
                pos = e.dataStart + e.dataSize;
            }
            return "tag";
        }

        private void attachments(long start, long end) throws IOException {
            long pos = start;
            while (pos < end) {
                count();
                Element e = element(pos, end);
                if (e.dataSize < 0) {
                    return;
                }
                if (e.id == ID_ATTACHED_FILE) {
                    field("attachment", false, scrub ? null : attachmentValue(e.dataStart, e.dataStart + e.dataSize),
                            e.dataSize);
                }
                pos = e.dataStart + e.dataSize;
            }
        }

        /** Rewrites [pos, end) as one Void element, which every reader skips, with zeroed contents. */
        private void voidOut(long pos, long end) throws IOException {
            if (!scrub) {
                return;
            }
            long total = end - pos;
            if (total < 2) {
                throw new IOException("Element too small to void");
            }
            byte[] header;
            if (total >= 9) {
                long data = total - 9;
                header = new byte[9];
                header[0] = (byte) 0xEC;
                header[1] = 0x01;
                for (int i = 0; i < 7; i++) {
                    header[8 - i] = (byte) (data >>> (8 * i));
                }
            } else {
                header = new byte[]{(byte) 0xEC, (byte) (0x80 | (total - 2))};
            }
            write(pos, header);
            zero(pos + header.length, total - header.length);
            scrubbed++;
        }

        // ---- I/O ------------------------------------------------------------------------------

        @NonNull
        private byte[] read(long pos, int n) throws IOException {
            if (n < 0 || pos < 0 || pos + n > length) {
                throw new IOException("Read past the end of the file");
            }
            ByteBuffer buffer = ByteBuffer.allocate(n);
            while (buffer.hasRemaining()) {
                if (ch.read(buffer, pos + buffer.position()) < 0) {
                    throw new IOException("Unexpected end of file");
                }
            }
            return buffer.array();
        }

        private void write(long pos, @NonNull byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                ch.write(buffer, pos + buffer.position());
            }
        }

        private void zero(long pos, long n) throws IOException {
            byte[] zeros = new byte[(int) Math.min(n, 64 * 1024)];
            long done = 0;
            while (done < n) {
                int chunk = (int) Math.min(zeros.length, n - done);
                write(pos + done, chunk == zeros.length ? zeros : new byte[chunk]);
                done += chunk;
            }
        }

        private static long u32(byte[] b, int offset) {
            return ((long) (b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                    | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
        }

        @NonNull
        private static String fourCc(byte[] b, int offset) {
            return new String(b, offset, 4, StandardCharsets.ISO_8859_1);
        }
    }

    /** True for names that hold a place: ©xyz, loci, and keys such as ...location.ISO6709. */
    static boolean isLocationName(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return name.equals("©xyz") || lower.equals("loci") || lower.contains("location")
                || lower.contains("gps") || lower.contains("latitude") || lower.contains("longitude");
    }
}
