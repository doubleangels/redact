package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the tags in a photo's EXIF block by number, including the ones {@link
 * androidx.exifinterface.media.ExifInterface} has no name for and so never reports: the author,
 * title, subject, keywords and comment Windows writes from Explorer (XPAuthor and the like), and
 * any vendor's own tags. A clean drops the whole block and writes back only tags ExifInterface
 * knows, so such a tag means the file was not cleaned; Scan lists them too.
 *
 * <p>Every offset and count is checked against the block. A block that cannot be followed throws,
 * and the callers treat it as something they cannot vouch for.
 */
public final class ExifBlock {

    /** IFD0, the EXIF and GPS sub-IFDs, the interoperability IFD, and IFD1 (the thumbnail's). */
    public static final int IFD_0 = 0;
    public static final int IFD_EXIF = 1;
    public static final int IFD_GPS = 2;
    public static final int IFD_INTEROPERABILITY = 3;
    public static final int IFD_1 = 4;

    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_GPS_IFD = 0x8825;
    private static final int TAG_INTEROPERABILITY_IFD = 0xA005;

    /** The tags Windows writes from Explorer's Details tab, as UTF-16LE text. */
    public static final int TAG_XP_TITLE = 0x9C9B;
    public static final int TAG_XP_COMMENT = 0x9C9C;
    public static final int TAG_XP_AUTHOR = 0x9C9D;
    public static final int TAG_XP_KEYWORDS = 0x9C9E;
    public static final int TAG_XP_SUBJECT = 0x9C9F;

    private static final int MAX_ENTRIES = 1000;
    /** More than any EXIF block holds (a JPEG segment is at most 64 KB; PNG and WebP allow more). */
    private static final int MAX_BLOCK = 4 * 1024 * 1024;
    /** A tag's value is kept up to this size for Scan; its full size is still reported. */
    private static final int MAX_VALUE = 64 * 1024;

    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

    /**
     * The tag numbers androidx.exifinterface 1.4.2 defines, in any IFD, read from its own tag
     * table. A tag outside this set is one ExifInterface never reads or writes. If a newer
     * ExifInterface defines more, its new tags show as unnamed in Scan and make a file need
     * cleaning until this list is updated: the safe direction.
     */
    private static final Set<Integer> EXIFINTERFACE_TAGS = new HashSet<>();

    static {
        for (int tag : new int[] {
                0x0000, 0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008, 0x0009,
                0x000A, 0x000B, 0x000C, 0x000D, 0x000E, 0x000F, 0x0010, 0x0011, 0x0012, 0x0013,
                0x0014, 0x0015, 0x0016, 0x0017, 0x0018, 0x0019, 0x001A, 0x001B, 0x001C, 0x001D,
                0x001E, 0x001F, 0x002E, 0x0037, 0x00FE, 0x00FF, 0x0100, 0x0101, 0x0102, 0x0103,
                0x0106, 0x010E, 0x010F, 0x0110, 0x0111, 0x0112, 0x0115, 0x0116, 0x0117, 0x011A,
                0x011B, 0x011C, 0x0128, 0x012D, 0x0131, 0x0132, 0x013B, 0x013E, 0x013F, 0x014A,
                0x0201, 0x0202, 0x0211, 0x0212, 0x0213, 0x0214, 0x02BC, 0x1113, 0x2020, 0x2040,
                0x8298, 0x829A, 0x829D, 0x8769, 0x8822, 0x8824, 0x8825, 0x8827, 0x8828, 0x8830,
                0x8831, 0x8832, 0x8833, 0x8834, 0x8835, 0x9000, 0x9003, 0x9004, 0x9010, 0x9011,
                0x9012, 0x9101, 0x9102, 0x9201, 0x9202, 0x9203, 0x9204, 0x9205, 0x9206, 0x9207,
                0x9208, 0x9209, 0x920A, 0x9214, 0x927C, 0x9286, 0x9290, 0x9291, 0x9292, 0xA000,
                0xA001, 0xA002, 0xA003, 0xA004, 0xA005, 0xA20B, 0xA20C, 0xA20E, 0xA20F, 0xA210,
                0xA214, 0xA215, 0xA217, 0xA300, 0xA301, 0xA302, 0xA401, 0xA402, 0xA403, 0xA404,
                0xA405, 0xA406, 0xA407, 0xA408, 0xA409, 0xA40A, 0xA40B, 0xA40C, 0xA420, 0xA430,
                0xA431, 0xA432, 0xA433, 0xA434, 0xA500, 0xC612, 0xC620
        }) {
            EXIFINTERFACE_TAGS.add(tag);
        }
    }

    /**
     * How ExifInterface's tag numbers split between its per-IFD tables: the GPS table runs from
     * 0x0000 to 0x001F, the interoperability table holds only InteroperabilityIndex, the EXIF
     * table starts at ExposureTime (0x829A), and the TIFF tables (IFD 0 and 1) hold everything
     * below that plus the IFD pointers and the DNG tags.
     */
    private static final int LAST_GPS_TAG = 0x001F;
    private static final int TAG_INTEROPERABILITY_INDEX = 0x0001;
    private static final int FIRST_EXIF_TAG = 0x829A;
    private static final Set<Integer> TIFF_TAGS_ABOVE_EXIF_START = Set.of(0x8769, 0x8825, 0xC612, 0xC620);

    /** One tag: the IFD it is in, its number and type, how many values it has, and its bytes. */
    public static final class Entry {
        public final int ifd;
        public final int tag;
        public final int type;
        public final long count;
        /** The value's size in bytes. */
        public final long size;
        /** The value, up to {@link #MAX_VALUE} bytes; empty for a type this reader does not know. */
        @NonNull
        public final byte[] value;

        Entry(int ifd, int tag, int type, long count, long size, @NonNull byte[] value) {
            this.ifd = ifd;
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.size = size;
            this.value = value;
        }

        /**
         * Whether ExifInterface has a name for this tag in the IFD it is in, and so reads and
         * writes it. ExifInterface looks a tag up in its table for that IFD only, so a GPS tag
         * number placed in the EXIF IFD is one it never sees, however well known the number is.
         */
        public boolean knownToExifInterface() {
            if (!EXIFINTERFACE_TAGS.contains(tag)) {
                return false;
            }
            switch (ifd) {
                case IFD_GPS:
                    return tag <= LAST_GPS_TAG;
                case IFD_INTEROPERABILITY:
                    return tag == TAG_INTEROPERABILITY_INDEX;
                case IFD_EXIF:
                    return tag >= FIRST_EXIF_TAG && !TIFF_TAGS_ABOVE_EXIF_START.contains(tag);
                default: // IFD 0 and IFD 1, the TIFF tables
                    return tag < FIRST_EXIF_TAG || TIFF_TAGS_ABOVE_EXIF_START.contains(tag);
            }
        }

        /** The text of an XP tag (UTF-16LE, up to its terminator); null for any other tag. */
        @Nullable
        public String xpText() {
            if (tag < TAG_XP_TITLE || tag > TAG_XP_SUBJECT) {
                return null;
            }
            int end = 0;
            while (end + 1 < value.length && (value[end] != 0 || value[end + 1] != 0)) {
                end += 2;
            }
            return new String(value, 0, end, StandardCharsets.UTF_16LE).trim();
        }
    }

    private ExifBlock() {
    }

    /** How many tag numbers ExifInterface defines, so a test notices when the list changes. */
    @VisibleForTesting
    static int exifInterfaceTagCount() {
        return EXIFINTERFACE_TAGS.size();
    }

    // ---- finding the block ----------------------------------------------------------------------

    /**
     * The TIFF data of the EXIF block in a JPEG, PNG or WebP read from {@code in}: what follows
     * "Exif\0\0" in a JPEG's APP1, or a PNG's eXIf or a WebP's EXIF chunk. Null when the file has
     * none, or is a format this does not read (HEIF keeps EXIF in an item, read elsewhere).
     *
     * @throws IOException when the file's structure cannot be followed
     */
    @Nullable
    public static byte[] find(@NonNull InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int b0 = data.read();
        int b1 = data.read();
        if (b0 < 0 || b1 < 0) {
            return null;
        }
        if (b0 == 0xFF && b1 == 0xD8) {
            return fromJpeg(data);
        }
        byte[] rest = new byte[2];
        if (data.read(rest) != 2) {
            return null;
        }
        if (b0 == 0x89 && b1 == 'P' && rest[0] == 'N' && rest[1] == 'G') {
            skipFully(data, 4); // the rest of the signature
            return fromPng(data);
        }
        if (b0 == 'R' && b1 == 'I' && rest[0] == 'F' && rest[1] == 'F') {
            byte[] sizeAndForm = new byte[8];
            data.readFully(sizeAndForm);
            if (sizeAndForm[4] != 'W' || sizeAndForm[5] != 'E' || sizeAndForm[6] != 'B' || sizeAndForm[7] != 'P') {
                return null;
            }
            return fromWebp(data);
        }
        return null;
    }

    @Nullable
    private static byte[] fromJpeg(@NonNull DataInputStream in) throws IOException {
        while (true) {
            int marker = in.read();
            if (marker < 0) {
                return null;
            }
            if (marker != 0xFF) {
                throw new IOException("Not a JPEG marker");
            }
            int type = in.read();
            while (type == 0xFF) {
                type = in.read();
            }
            if (type < 0 || type == 0xD9 || type == 0xDA) {
                return null; // the picture starts: EXIF comes before it
            }
            if (type == 0x01 || (type >= 0xD0 && type <= 0xD7)) {
                continue;
            }
            int length = in.readUnsignedShort();
            if (length < 2) {
                throw new IOException("Damaged JPEG segment");
            }
            int bodyLength = length - 2;
            if (type == 0xE1 && bodyLength > EXIF_HEADER.length) {
                byte[] body = new byte[bodyLength];
                in.readFully(body);
                if (startsWith(body, EXIF_HEADER)) {
                    return Arrays.copyOfRange(body, EXIF_HEADER.length, body.length);
                }
            } else {
                skipFully(in, bodyLength);
            }
        }
    }

    @Nullable
    private static byte[] fromPng(@NonNull DataInputStream in) throws IOException {
        while (true) {
            long length;
            try {
                length = in.readInt() & 0xFFFFFFFFL;
            } catch (EOFException e) {
                return null;
            }
            byte[] type = new byte[4];
            in.readFully(type);
            String name = new String(type, StandardCharsets.US_ASCII);
            if (name.equals("eXIf")) {
                return body(in, length);
            }
            if (name.equals("IEND")) {
                return null;
            }
            skipFully(in, length + 4); // data and CRC
        }
    }

    @Nullable
    private static byte[] fromWebp(@NonNull DataInputStream in) throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                return null;
            }
            byte[] type = new byte[4];
            type[0] = (byte) first;
            in.readFully(type, 1, 3);
            byte[] size = new byte[4];
            in.readFully(size);
            long length = (size[0] & 0xFFL) | (size[1] & 0xFFL) << 8 | (size[2] & 0xFFL) << 16
                    | (size[3] & 0xFFL) << 24;
            if (new String(type, StandardCharsets.US_ASCII).equals("EXIF")) {
                return body(in, length);
            }
            skipFully(in, length + (length & 1)); // chunks are padded to an even size
        }
    }

    /** A PNG or WebP EXIF chunk's TIFF data; some writers put "Exif\0\0" in front of it. */
    @NonNull
    private static byte[] body(@NonNull DataInputStream in, long length) throws IOException {
        if (length > MAX_BLOCK) {
            throw new IOException("EXIF chunk too large");
        }
        byte[] body = new byte[(int) length];
        in.readFully(body);
        return startsWith(body, EXIF_HEADER) ? Arrays.copyOfRange(body, EXIF_HEADER.length, body.length) : body;
    }

    // ---- reading the block ----------------------------------------------------------------------

    /**
     * Every tag in IFD0, the EXIF, GPS and interoperability IFDs, and IFD1.
     *
     * @throws IOException when an offset or count points outside the block, or IFDs loop
     */
    @NonNull
    public static List<Entry> read(@NonNull byte[] tiff) throws IOException {
        return walk(tiff, littleEndian(tiff), null).entries;
    }

    /** What {@link #walk} found: the entries, and whether anything was left unread. */
    private static final class Walk {
        final List<Entry> entries = new ArrayList<>();
        boolean unfollowed;
    }

    private static boolean littleEndian(@NonNull byte[] tiff) throws IOException {
        if (tiff.length < 8) {
            throw new IOException("EXIF block too short");
        }
        boolean little;
        if (tiff[0] == 'I' && tiff[1] == 'I') {
            little = true;
        } else if (tiff[0] == 'M' && tiff[1] == 'M') {
            little = false;
        } else {
            throw new IOException("Not a TIFF header");
        }
        if (u16(tiff, 2, little) != 42) {
            throw new IOException("Not a TIFF header");
        }
        return little;
    }

    /** Bytes of a block no entry needs to account for: alignment padding and the like. */
    private static final int UNREFERENCED_SLACK = 64;
    private static final int TAG_SUB_IFDS = 0x014A;
    private static final int TAG_THUMBNAIL_OFFSET = 0x0201;
    private static final int TAG_THUMBNAIL_LENGTH = 0x0202;

    /**
     * Whether {@link #read} accounts for the whole block: no IFD chained after IFD1, no SubIFDs,
     * no EXIF or GPS directory hung off IFD1, and no more than a little of the block left that no
     * entry, value or thumbnail points to. The already-clean check calls a file clean only when
     * this holds; anything else could hide data the walk never read, so the file is cleaned.
     */
    static boolean coversWholeBlock(@NonNull byte[] tiff) throws IOException {
        boolean little = littleEndian(tiff);
        List<long[]> ranges = new ArrayList<>();
        ranges.add(new long[] {0, 8});
        Walk walk = walk(tiff, little, ranges);
        if (walk.unfollowed) {
            return false;
        }
        long thumbnailAt = -1;
        long thumbnailLength = -1;
        for (Entry e : walk.entries) {
            if (e.tag == TAG_SUB_IFDS || (e.ifd == IFD_1 && (e.tag == TAG_EXIF_IFD || e.tag == TAG_GPS_IFD))) {
                return false;
            }
            if (e.ifd == IFD_1 && e.tag == TAG_THUMBNAIL_OFFSET && e.value.length >= 4) {
                thumbnailAt = u32(e.value, 0, little);
            } else if (e.ifd == IFD_1 && e.tag == TAG_THUMBNAIL_LENGTH && e.value.length >= 4) {
                thumbnailLength = u32(e.value, 0, little);
            }
        }
        if (thumbnailAt >= 0 && thumbnailLength > 0) {
            ranges.add(new long[] {thumbnailAt, thumbnailAt + thumbnailLength});
        }
        // ExifInterface, writing kept tags back, leaves an unreferenced copy of IFD0 after the
        // values. A gap that is exactly such a copy holds nothing the file doesn't already show,
        // so it is accounted for; any other unreferenced data still leaves the block uncovered.
        List<Entry> ifd0 = new ArrayList<>();
        for (Entry e : walk.entries) {
            if (e.ifd == IFD_0) {
                ifd0.add(e);
            }
        }
        for (int copies = 0; copies < MAX_IFD0_COPIES && uncovered(ranges, tiff.length) > UNREFERENCED_SLACK; copies++) {
            long gap = firstGap(ranges, tiff.length);
            if (gap < 0 || !acceptIfd0Copy(tiff, gap, little, ifd0, ranges)) {
                break;
            }
        }
        return uncovered(ranges, tiff.length) <= UNREFERENCED_SLACK;
    }

    /** How many unreferenced copies of IFD0 one block may hold. */
    private static final int MAX_IFD0_COPIES = 2;

    /** Bytes of the block no range in {@code ranges} covers. */
    private static long uncovered(@NonNull List<long[]> ranges, long length) {
        ranges.sort((x, y) -> Long.compare(x[0], y[0]));
        long covered = 0;
        long end = 0;
        for (long[] r : ranges) {
            long from = Math.max(r[0], end);
            long to = Math.min(r[1], length);
            if (to > from) {
                covered += to - from;
                end = to;
            }
        }
        return length - covered;
    }

    /** Where the first byte no range covers is, past the header; -1 when every byte is covered. */
    private static long firstGap(@NonNull List<long[]> ranges, long length) {
        ranges.sort((x, y) -> Long.compare(x[0], y[0]));
        long end = 0;
        for (long[] r : ranges) {
            if (r[0] > end) {
                return end;
            }
            end = Math.max(end, r[1]);
        }
        return end < length ? end : -1;
    }

    /**
     * Whether an IFD starting at {@code gap} (or a word-aligned byte or two after it) is a copy of
     * IFD0: chained to nothing, pointing to no other directory or thumbnail, and every entry the
     * same tag, type, count and value as one of IFD0's. If it is, its bytes join {@code ranges}.
     */
    private static boolean acceptIfd0Copy(@NonNull byte[] tiff, long gap, boolean little,
                                          @NonNull List<Entry> ifd0, @NonNull List<long[]> ranges) {
        for (long at = gap; at <= gap + 3 && at + 2 + 12 + 4 <= tiff.length; at++) {
            List<Entry> copy = new ArrayList<>();
            List<long[]> used = new ArrayList<>();
            long next;
            try {
                next = ifd(tiff, at, IFD_0, little, copy, new HashSet<>(), used);
            } catch (IOException e) {
                continue;
            }
            if (next != 0 || copy.isEmpty()) {
                continue;
            }
            boolean allCopied = true;
            for (Entry e : copy) {
                boolean pointer = e.tag == TAG_EXIF_IFD || e.tag == TAG_GPS_IFD
                        || e.tag == TAG_INTEROPERABILITY_IFD || e.tag == TAG_SUB_IFDS
                        || e.tag == TAG_THUMBNAIL_OFFSET || e.tag == TAG_THUMBNAIL_LENGTH;
                // A value past MAX_VALUE is only partly read, so it could differ unseen.
                if (pointer || e.size > MAX_VALUE || !sameAsOneOf(e, ifd0)) {
                    allCopied = false;
                    break;
                }
            }
            if (allCopied) {
                ranges.add(new long[] {gap, at}); // the alignment bytes before it
                ranges.addAll(used);
                return true;
            }
        }
        return false;
    }

    private static boolean sameAsOneOf(@NonNull Entry e, @NonNull List<Entry> entries) {
        for (Entry other : entries) {
            if (other.tag == e.tag && other.type == e.type && other.count == e.count
                    && other.size == e.size && Arrays.equals(other.value, e.value)) {
                return true;
            }
        }
        return false;
    }

    /** Reads IFD0, IFD1 and their sub-IFDs, recording the byte ranges they use in {@code ranges}. */
    @NonNull
    private static Walk walk(@NonNull byte[] tiff, boolean little, @Nullable List<long[]> ranges)
            throws IOException {
        Walk walk = new Walk();
        List<Entry> entries = walk.entries;
        Set<Long> visited = new HashSet<>();
        long next = ifd(tiff, u32(tiff, 4, little), IFD_0, little, entries, visited, ranges);
        if (next != 0) {
            long afterIfd1 = ifd(tiff, next, IFD_1, little, entries, visited, ranges);
            walk.unfollowed = afterIfd1 != 0;
        }
        // Sub-IFDs, found from the entries read so far (the list grows as they are read).
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int target = e.tag == TAG_EXIF_IFD && e.ifd == IFD_0 ? IFD_EXIF
                    : e.tag == TAG_GPS_IFD && e.ifd == IFD_0 ? IFD_GPS
                    : e.tag == TAG_INTEROPERABILITY_IFD && e.ifd == IFD_EXIF ? IFD_INTEROPERABILITY
                    : -1;
            if (target >= 0 && e.value.length >= 4) {
                ifd(tiff, u32(e.value, 0, little), target, little, entries, visited, ranges);
            }
        }
        return walk;
    }

    /** Reads one IFD's entries into {@code out}; returns the offset of the next IFD, 0 for none. */
    private static long ifd(byte[] tiff, long offset, int ifd, boolean little, List<Entry> out,
                            Set<Long> visited, @Nullable List<long[]> ranges) throws IOException {
        if (!visited.add(offset)) {
            throw new IOException("EXIF IFDs loop");
        }
        if (offset < 8 || offset + 2 > tiff.length) {
            throw new IOException("EXIF IFD outside the block");
        }
        int count = u16(tiff, (int) offset, little);
        if (count > MAX_ENTRIES || offset + 2 + 12L * count + 4 > tiff.length) {
            throw new IOException("EXIF IFD runs past the block");
        }
        if (ranges != null) {
            ranges.add(new long[] {offset, offset + 2 + 12L * count + 4});
        }
        for (int i = 0; i < count; i++) {
            int at = (int) offset + 2 + 12 * i;
            int tag = u16(tiff, at, little);
            int type = u16(tiff, at + 2, little);
            long values = u32(tiff, at + 4, little);
            int unit = unitSize(type);
            long size = unit * values;
            byte[] value;
            if (unit == 0) {
                value = new byte[0];
            } else if (size <= 4) {
                value = Arrays.copyOfRange(tiff, at + 8, at + 8 + (int) size);
            } else {
                long where = u32(tiff, at + 8, little);
                if (where + size > tiff.length) {
                    throw new IOException("EXIF value outside the block");
                }
                value = Arrays.copyOfRange(tiff, (int) where, (int) (where + Math.min(size, MAX_VALUE)));
                if (ranges != null) {
                    ranges.add(new long[] {where, where + size});
                }
            }
            out.add(new Entry(ifd, tag, type, values, size, value));
        }
        return u32(tiff, (int) offset + 2 + 12 * count, little);
    }

    private static int unitSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7:
                return 1;
            case 3: case 8:
                return 2;
            case 4: case 9: case 11: case 13:
                return 4;
            case 5: case 10: case 12:
                return 8;
            default:
                return 0;
        }
    }

    // ---- bytes ----------------------------------------------------------------------------------

    private static int u16(byte[] b, int at, boolean little) {
        return little ? (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 : (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static long u32(byte[] b, long at, boolean little) {
        int i = (int) at;
        return little
                ? (b[i] & 0xFFL) | (b[i + 1] & 0xFFL) << 8 | (b[i + 2] & 0xFFL) << 16 | (b[i + 3] & 0xFFL) << 24
                : (b[i] & 0xFFL) << 24 | (b[i + 1] & 0xFFL) << 16 | (b[i + 2] & 0xFFL) << 8 | (b[i + 3] & 0xFFL);
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("Ended inside a segment");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }
}
