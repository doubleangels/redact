package com.doubleangels.redact.metadata;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Finds and removes everything in a JPEG, PNG or WebP file that is not the picture itself.
 *
 * <p>EXIF is only one of many places an image keeps information about who made it, where and
 * when. Others are IPTC (APP13), comments, Multi-Picture Format images and Ultra HDR gain maps
 * appended after the picture, Motion Photo videos, Samsung's trailer, C2PA Content Credentials, PNG
 * text chunks and unknown vendor segments. Removing the known ones one by one always misses the
 * next, so {@link #sanitize} works the other way round: it rebuilds the file from an allowlist of
 * the segments or chunks a decoder needs and stops at the end of the picture, so whatever else the
 * file carries, known or not, is left behind. A colour profile is kept, as dropping it changes the
 * colours, with the fields that can name a device or a date cleared.
 *
 * <p>{@link #scan} walks the same structure and lists what it finds, with values where they are
 * text, for Scan, the already-clean check and the stripper's verification. EXIF itself is read
 * with {@link androidx.exifinterface.media.ExifInterface} and is not reported here.
 */
public final class ImageContainerMetadata {

    public enum Format { JPEG, PNG, WEBP, OTHER }

    /** What a finding says about the file, for the clean summary. */
    public enum Kind { LOCATION, DATE, NAME, DEVICE, OTHER }

    /** One piece of metadata found outside EXIF. */
    public static final class Finding {
        /** Display key in the style of the other Scan rows, such as {@code IPTC_BYLINE}. */
        @NonNull public final String key;
        /** The text it holds, or null for binary data (see {@link #bytes}). */
        @Nullable public final String text;
        /** How large the data is. */
        public final long bytes;
        @NonNull public final Kind kind;

        Finding(@NonNull String key, @Nullable String text, long bytes, @NonNull Kind kind) {
            this.key = key;
            this.text = text;
            this.bytes = bytes;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return key + "=" + (text != null ? text : bytes + " bytes");
        }
    }

    /** What a scan found. */
    public static final class Report {
        @NonNull public final Format format;
        /** False when the file is not one of these formats or its structure could not be followed. */
        public final boolean recognized;
        /** Identifying metadata; a clean removes all of it. */
        @NonNull public final List<Finding> findings;
        /** What a clean keeps because it describes the picture only, such as a colour profile's name. */
        @NonNull public final List<Finding> kept;

        Report(@NonNull Format format, boolean recognized, @NonNull List<Finding> findings,
               @NonNull List<Finding> kept) {
            this.format = format;
            this.recognized = recognized;
            this.findings = Collections.unmodifiableList(findings);
            this.kept = Collections.unmodifiableList(kept);
        }

        /** True only when the file was read to the end of the picture and nothing identifying was found. */
        public boolean isClean() {
            return recognized && findings.isEmpty();
        }

        public boolean has(@NonNull Kind kind) {
            for (Finding f : findings) {
                if (f.kind == kind) {
                    return true;
                }
            }
            return false;
        }

        static Report unrecognized(@NonNull Format format) {
            return new Report(format, false, new ArrayList<>(), new ArrayList<>());
        }
    }

    /** Longest text value kept from a finding; longer ones are cut, as Scan shows them in a row. */
    private static final int MAX_TEXT = 16 * 1024;
    /** Most text chunks, resources or segments reported from one file. */
    private static final int MAX_FINDINGS = 400;
    /** Furthest into data appended after the picture that is searched for what it holds. */
    private static final long TRAILER_PEEK = 64L * 1024 * 1024;
    private static final int MAX_INFLATED = 4 * 1024 * 1024;

    private ImageContainerMetadata() {
    }

    // ---- public API ---------------------------------------------------------------------------

    /** The format of a file by its first bytes, whatever its name says. */
    @NonNull
    public static Format sniff(@NonNull File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[12];
            int n = 0;
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) {
                n += r;
            }
            return sniff(head, n);
        } catch (IOException e) {
            return Format.OTHER;
        }
    }

    @NonNull
    static Format sniff(@NonNull byte[] head, int length) {
        if (length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Format.JPEG;
        }
        if (length >= 8 && startsWith(head, PNG_SIGNATURE)) {
            return Format.PNG;
        }
        if (length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Format.WEBP;
        }
        return Format.OTHER;
    }

    /** Scans a file; never throws. */
    @NonNull
    public static Report scan(@NonNull File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return scan(in.getChannel());
        } catch (IOException | RuntimeException e) {
            return Report.unrecognized(Format.OTHER);
        }
    }

    /** Scans a content or file URI; never throws. */
    @NonNull
    public static Report scan(@NonNull Context context, @NonNull Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return Report.unrecognized(Format.OTHER);
            }
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                return scan(in.getChannel());
            }
        } catch (IOException | RuntimeException e) {
            return Report.unrecognized(Format.OTHER);
        }
    }

    /**
     * Writes {@code source} to {@code target} with nothing but the picture: every segment or chunk
     * outside the allowlist, and everything after the end of the picture, is left out.
     *
     * @throws IOException when the file is not a JPEG, PNG or WebP this class can follow, so the
     *     caller re-encodes it from its pixels instead
     */
    public static void sanitize(@NonNull File source, @NonNull File target) throws IOException {
        if (source.getCanonicalPath().equals(target.getCanonicalPath())) {
            throw new IOException("Source and target must differ");
        }
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 64 * 1024)) {
            Walker walker = new Walker(in.getChannel(), out);
            if (!walker.run()) {
                throw new IOException("Not a JPEG, PNG or WebP file");
            }
            out.flush();
        }
    }

    @VisibleForTesting
    @NonNull
    static Report scan(@NonNull FileChannel channel) throws IOException {
        Walker walker = new Walker(channel, null);
        try {
            if (!walker.run()) {
                return Report.unrecognized(walker.format);
            }
        } catch (IOException | RuntimeException e) {
            // What was found before the structure broke is still worth showing, but the file can
            // never count as clean.
            return new Report(walker.format, false, walker.findings, walker.kept);
        }
        return new Report(walker.format, true, walker.findings, walker.kept);
    }

    // ---- the walk -----------------------------------------------------------------------------

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private static final class Walker {
        private final Source src;
        /** Null when scanning. */
        @Nullable private final OutputStream out;
        Format format = Format.OTHER;
        final List<Finding> findings = new ArrayList<>();
        final List<Finding> kept = new ArrayList<>();

        Walker(@NonNull FileChannel channel, @Nullable OutputStream out) throws IOException {
            this.src = new Source(channel);
            this.out = out;
        }

        boolean run() throws IOException {
            byte[] head = src.read(0, (int) Math.min(12, src.length));
            format = sniff(head, head.length);
            switch (format) {
                case JPEG:
                    jpeg();
                    return true;
                case PNG:
                    png();
                    return true;
                case WEBP:
                    webp();
                    return true;
                default:
                    return false;
            }
        }

        private void find(@NonNull String key, @Nullable String text, long bytes, @NonNull Kind kind) {
            if (findings.size() < MAX_FINDINGS) {
                findings.add(new Finding(key, text == null ? null : cap(text), bytes, kind));
            } else if (findings.size() == MAX_FINDINGS) {
                // Still more to remove; one row says so rather than hundreds.
                findings.add(new Finding("MORE_METADATA", null, bytes, Kind.OTHER));
            }
        }

        private void write(@NonNull byte[] bytes) throws IOException {
            if (out != null) {
                out.write(bytes);
            }
        }

        private void copy(long pos, long n) throws IOException {
            if (out != null) {
                src.copyTo(pos, n, out);
            }
        }

        // ---- JPEG -----------------------------------------------------------------------------

        private void jpeg() throws IOException {
            write(new byte[] {(byte) 0xFF, (byte) 0xD8});
            long pos = 2;
            boolean sawFrame = false;
            ByteArrayOutputStream icc = new ByteArrayOutputStream();
            while (true) {
                if (src.at(pos) != 0xFF) {
                    throw new IOException("Expected a JPEG marker");
                }
                while (src.at(pos) == 0xFF) {
                    pos++; // fill bytes
                }
                int marker = src.at(pos);
                pos++;
                if (marker == 0xD9) {
                    write(new byte[] {(byte) 0xFF, (byte) 0xD9});
                    trailer(pos);
                    break;
                }
                if (marker == 0x00 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01 || marker == 0xD8) {
                    throw new IOException("Unexpected JPEG marker " + marker);
                }
                int length = src.u16(pos);
                if (length < 2) {
                    throw new IOException("Bad JPEG segment length");
                }
                long body = pos + 2;
                long end = pos + length;
                src.require(end);
                if (marker == 0xDA) {
                    if (!sawFrame) {
                        throw new IOException("Scan before frame");
                    }
                    writeMarker(marker, body, end);
                    long scanEnd = entropyEnd(end);
                    copy(end, scanEnd - end);
                    pos = scanEnd;
                    continue;
                }
                if (isFrameMarker(marker)) {
                    sawFrame = true;
                }
                segment(marker, body, end, icc);
                pos = end;
            }
            if (icc.size() > 0) {
                iccInfo(icc.toByteArray());
            }
        }

        private static boolean isFrameMarker(int marker) {
            return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        }

        /** Where the entropy-coded data that starts at {@code pos} ends: at the next real marker. */
        private long entropyEnd(long pos) throws IOException {
            while (true) {
                pos = src.indexOf(0xFF, pos);
                if (pos < 0) {
                    throw new IOException("JPEG has no end of image");
                }
                int next = src.at(pos + 1);
                if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) {
                    pos += 2; // stuffed byte or restart marker, part of the scan
                } else if (next == 0xFF) {
                    pos += 1; // fill byte; the marker follows
                } else {
                    return pos;
                }
            }
        }

        private void writeMarker(int marker, long body, long end) throws IOException {
            int length = (int) (end - body + 2);
            write(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length});
            copy(body, end - body);
        }

        private void writeSegment(int marker, @NonNull byte[] payload) throws IOException {
            int length = payload.length + 2;
            write(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length});
            write(payload);
        }

        private void segment(int marker, long body, long end, ByteArrayOutputStream icc) throws IOException {
            long size = end - body;
            boolean structural = (marker >= 0xC0 && marker <= 0xCF && marker != 0xC8)
                    || marker == 0xDB || marker == 0xDD || marker == 0xDC || marker == 0xDE || marker == 0xDF;
            if (structural) {
                writeMarker(marker, body, end);
                return;
            }
            byte[] data = src.read(body, (int) Math.min(size, 70_000));
            if (marker == 0xE0) {
                app0(data);
            } else if (marker == 0xE1) {
                app1(data, body, size);
            } else if (marker == 0xE2) {
                app2(data, size, icc);
            } else if (marker == 0xED) {
                photoshop(data, size);
            } else if (marker == 0xEE && startsWith(data, ascii("Adobe"))) {
                // Colour transform flags, needed to decode CMYK and YCCK pictures.
                if (data.length >= 12) {
                    writeSegment(marker, java.util.Arrays.copyOf(data, 12));
                }
            } else if (marker == 0xFE) {
                find("JPEG_COMMENT", printable(data), size, Kind.OTHER);
            } else if (marker == 0xEB) {
                boolean c2pa = indexOf(data, ascii("c2pa")) >= 0;
                find(c2pa ? "CONTENT_CREDENTIALS" : "JUMBF_DATA", null, size, Kind.OTHER);
            } else if (marker >= 0xE0 && marker <= 0xEF) {
                String id = identifier(data);
                find("APP" + (marker - 0xE0) + (id.isEmpty() ? "_DATA" : "_" + keyOf(id)), null, size,
                        id.toLowerCase(Locale.ROOT).contains("ducky") ? Kind.NAME : Kind.OTHER);
            } else {
                find(String.format(Locale.ROOT, "JPEG_SEGMENT_%02X", marker), null, size, Kind.OTHER);
            }
        }

        private void app0(byte[] data) throws IOException {
            if (startsWith(data, ascii("JFIF\0")) && data.length >= 14) {
                // Keep the version and pixel density; drop any thumbnail.
                byte[] jfif = java.util.Arrays.copyOf(data, 14);
                jfif[12] = 0;
                jfif[13] = 0;
                writeSegment(0xE0, jfif);
                if ((data[12] & 0xFF) * (data[13] & 0xFF) > 0) {
                    find("JFIF_THUMBNAIL", null, data.length - 14L, Kind.OTHER);
                }
            } else if (startsWith(data, ascii("JFXX\0"))) {
                find("JFIF_THUMBNAIL", null, data.length, Kind.OTHER);
            } else {
                find("APP0_DATA", null, data.length, Kind.OTHER);
            }
        }

        private void app1(byte[] data, long body, long size) {
            if (startsWith(data, ascii("Exif\0"))) {
                return; // EXIF is read, counted and rewritten by ExifInterface.
            }
            if (startsWith(data, ascii("http://ns.adobe.com/xap/1.0/\0"))) {
                String xmp = new String(data, 29, data.length - 29, StandardCharsets.UTF_8);
                find("XMP", xmp, size, xmpKind(xmp));
            } else if (startsWith(data, ascii("http://ns.adobe.com/xmp/extension/\0"))) {
                int start = Math.min(data.length, 35 + 32 + 8);
                String xmp = new String(data, start, data.length - start, StandardCharsets.UTF_8);
                find("XMP_EXTENDED", null, size, xmpKind(xmp));
            } else {
                String id = identifier(data);
                find(id.isEmpty() ? "APP1_DATA" : "APP1_" + keyOf(id), null, size, Kind.OTHER);
            }
        }

        private void app2(byte[] data, long size, ByteArrayOutputStream icc) throws IOException {
            byte[] iccId = ascii("ICC_PROFILE\0");
            if (startsWith(data, iccId) && data.length >= 14) {
                icc.write(data, 14, data.length - 14);
                byte[] chunk = data.clone();
                if ((chunk[12] & 0xFF) == 1) {
                    clearIccHeader(chunk, 14);
                }
                writeSegment(0xE2, chunk);
                return;
            }
            if (startsWith(data, ascii("MPF\0"))) {
                find("MULTI_PICTURE_INDEX", null, size, Kind.OTHER);
            } else if (startsWith(data, ascii("FPXR\0"))) {
                find("FLASHPIX_DATA", null, size, Kind.OTHER);
            } else {
                String id = identifier(data);
                find(id.isEmpty() ? "APP2_DATA" : "APP2_" + keyOf(id), null, size, Kind.OTHER);
            }
        }

        /** Photoshop image resources (APP13), which hold IPTC among others. */
        private void photoshop(byte[] data, long size) {
            byte[] id = ascii("Photoshop 3.0\0");
            if (!startsWith(data, id)) {
                find("APP13_DATA", null, size, Kind.OTHER);
                return;
            }
            int other = 0;
            int pos = id.length;
            while (pos + 12 <= data.length && data[pos] == '8' && data[pos + 1] == 'B'
                    && data[pos + 2] == 'I' && data[pos + 3] == 'M') {
                int resource = ((data[pos + 4] & 0xFF) << 8) | (data[pos + 5] & 0xFF);
                int nameLength = data[pos + 6] & 0xFF;
                int namePadded = (nameLength + 1 + 1) & ~1;
                int sizeAt = pos + 6 + namePadded;
                if (sizeAt + 4 > data.length) {
                    break;
                }
                long resourceSize = u32(data, sizeAt);
                int start = sizeAt + 4;
                if (resourceSize > data.length - start) {
                    resourceSize = data.length - start; // runs past the segment; read what is there
                }
                int end = start + (int) resourceSize;
                if (resource == 0x0404) {
                    iptc(data, start, end);
                } else if (resource == 0x0409 || resource == 0x040C) {
                    find("PHOTOSHOP_THUMBNAIL", null, end - start, Kind.OTHER);
                } else if (resource == 0x0424) {
                    String xmp = new String(data, start, end - start, StandardCharsets.UTF_8);
                    find("XMP", xmp, end - start, xmpKind(xmp));
                } else {
                    other++;
                }
                pos = start + (int) ((resourceSize + 1) & ~1L);
            }
            if (other > 0) {
                find("PHOTOSHOP_RESOURCES", null, size, Kind.OTHER);
            } else if (pos == id.length) {
                find("APP13_DATA", null, size, Kind.OTHER);
            }
        }

        /** IPTC-IIM datasets, by record and number. */
        private void iptc(byte[] data, int start, int end) {
            int pos = start;
            int found = 0;
            while (pos + 5 <= end && data[pos] == 0x1C) {
                int record = data[pos + 1] & 0xFF;
                int dataset = data[pos + 2] & 0xFF;
                int length = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                if ((length & 0x8000) != 0) {
                    break; // extended length; not used for text
                }
                int valueStart = pos + 5;
                int valueEnd = Math.min(end, valueStart + length);
                if (record == 2 && dataset != 0) {
                    IptcField field = IPTC_FIELDS.get(dataset);
                    String value = new String(data, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8).trim();
                    String key = field != null ? field.key : "IPTC_" + dataset;
                    find(key, value, valueEnd - valueStart, field != null ? field.kind : Kind.OTHER);
                    found++;
                }
                pos = valueEnd;
            }
            if (found == 0 && end > start) {
                find("IPTC", null, end - start, Kind.OTHER);
            }
        }

        /** Data after the end of the picture: extra images, videos, vendor trailers. */
        private void trailer(long start) throws IOException {
            long end = src.length;
            if (start >= end || out != null) {
                return; // a clean writes nothing past the end of the picture, so needs no description
            }
            long peekEnd = Math.min(end, start + TRAILER_PEEK);
            if (src.allPadding(start, peekEnd) && peekEnd == end) {
                return;
            }
            boolean described = false;
            // A Motion Photo's video comes last; the extra images are all in front of it.
            long ftyp = src.indexOf(ascii("ftyp"), start, peekEnd);
            long videoStart = ftyp >= start + 4 ? ftyp - 4 : -1;
            long imagesEnd = videoStart >= 0 ? videoStart : peekEnd;
            // Extra JPEGs: MPF images, Ultra HDR gain maps, depth maps, previews.
            List<long[]> images = new ArrayList<>();
            long pos = start;
            while (pos >= 0 && pos < imagesEnd && images.size() < 16) {
                long soi = src.indexOf3(0xFF, 0xD8, 0xFF, pos, imagesEnd);
                if (soi < 0) {
                    break;
                }
                long eoi = endOfEmbeddedJpeg(soi, imagesEnd);
                images.add(new long[] {soi, eoi});
                pos = eoi;
            }
            int withLocation = 0;
            for (long[] image : images) {
                EmbeddedExif exif = embeddedExif(image[0], image[1]);
                if (exif != null && exif.latLong != null) {
                    withLocation++;
                    find("LOCATION_EMBEDDED_IMAGE",
                            String.format(Locale.ROOT, "%.6f, %.6f", exif.latLong[0], exif.latLong[1]),
                            image[1] - image[0], Kind.LOCATION);
                }
                if (exif != null && exif.dateTime != null) {
                    find("EMBEDDED_IMAGE_DATE", exif.dateTime, image[1] - image[0], Kind.DATE);
                }
                if (exif != null && exif.device != null) {
                    find("EMBEDDED_IMAGE_DEVICE", exif.device, image[1] - image[0], Kind.DEVICE);
                }
            }
            if (!images.isEmpty()) {
                long total = 0;
                for (long[] image : images) {
                    total += image[1] - image[0];
                }
                find("EMBEDDED_IMAGES", String.valueOf(images.size()), total,
                        withLocation > 0 ? Kind.LOCATION : Kind.OTHER);
                described = true;
            }
            if (videoStart >= 0) {
                find("EMBEDDED_VIDEO", null, end - videoStart, Kind.OTHER);
                described = true;
            }
            if (end - start >= 8 && java.util.Arrays.equals(src.read(end - 4, 4), ascii("SEFT"))) {
                long sefStart = Math.max(start, end - Math.min(end - start, 4L * 1024 * 1024));
                Set<String> names = samsungEntries(src.read(sefStart, (int) (end - sefStart)));
                find("SAMSUNG_TRAILER", names.isEmpty() ? null : String.join(", ", names), end - start,
                        names.contains("MCC_Data") || names.contains("Image_UTC_Data") ? Kind.DATE : Kind.OTHER);
                described = true;
            }
            if (!described) {
                find("DATA_AFTER_IMAGE", null, end - start, Kind.OTHER);
            }
        }

        /**
         * The end of an embedded JPEG, just past its EOI, found by walking its segments so the EXIF
         * thumbnail inside it is not taken for another image. When its structure cannot be
         * followed, the next start of image, or the end of the search.
         */
        private long endOfEmbeddedJpeg(long soi, long limit) throws IOException {
            try {
                long pos = soi + 2;
                while (pos < limit && src.at(pos) == 0xFF) {
                    while (src.at(pos) == 0xFF) {
                        pos++;
                    }
                    int marker = src.at(pos++);
                    if (marker == 0xD9) {
                        return pos;
                    }
                    if (marker == 0x00 || marker == 0x01 || marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) {
                        break;
                    }
                    int length = src.u16(pos);
                    long end = pos + length;
                    if (length < 2 || end > limit) {
                        break;
                    }
                    pos = marker == 0xDA ? entropyEnd(end) : end;
                }
            } catch (IOException e) {
                // Fall back to the next start of image below.
            }
            long next = src.indexOf3(0xFF, 0xD8, 0xFF, soi + 3, limit);
            return next < 0 ? limit : next;
        }

        @Nullable
        private EmbeddedExif embeddedExif(long start, long end) {
            try {
                int n = (int) Math.min(end - start, 512 * 1024);
                androidx.exifinterface.media.ExifInterface exif =
                        new androidx.exifinterface.media.ExifInterface(
                                new java.io.ByteArrayInputStream(src.read(start, n)));
                EmbeddedExif result = new EmbeddedExif();
                double[] latLong = exif.getLatLong();
                if (latLong != null && MetadataDisplayer.isUsableMapCoordinate(latLong[0], latLong[1])) {
                    result.latLong = latLong;
                }
                result.dateTime = MetadataStripper.normalizeMetadataValue(exif.getAttribute(
                        androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL));
                String make = MetadataStripper.normalizeMetadataValue(
                        exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE));
                String model = MetadataStripper.normalizeMetadataValue(
                        exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL));
                if (make != null || model != null) {
                    result.device = ((make != null ? make + " " : "") + (model != null ? model : "")).trim();
                }
                return result;
            } catch (Exception e) {
                return null;
            }
        }

        // ---- PNG ------------------------------------------------------------------------------

        private void png() throws IOException {
            write(PNG_SIGNATURE);
            long pos = 8;
            boolean sawHeader = false;
            while (true) {
                long length = src.u32(pos);
                if (length > Integer.MAX_VALUE) {
                    throw new IOException("PNG chunk too large");
                }
                String type = new String(src.read(pos + 4, 4), StandardCharsets.ISO_8859_1);
                if (!type.matches("[A-Za-z]{4}")) {
                    throw new IOException("Bad PNG chunk type");
                }
                long data = pos + 8;
                long end = data + length + 4;
                src.require(end);
                if (!sawHeader && !type.equals("IHDR")) {
                    throw new IOException("PNG does not start with IHDR");
                }
                sawHeader = true;
                pngChunk(type, pos, data, length, end);
                pos = end;
                if (type.equals("IEND")) {
                    break;
                }
            }
            afterEnd(pos, "DATA_AFTER_IMAGE");
        }

        private static final Set<String> PNG_KEEP = Set.of(
                "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "sBIT", "pHYs", "bKGD",
                "cICP", "mDCV", "mDCv", "cLLI", "cLLi", "acTL", "fcTL", "fdAT");

        private void pngChunk(String type, long pos, long data, long length, long end) throws IOException {
            if (PNG_KEEP.contains(type)) {
                copy(pos, end - pos);
                return;
            }
            switch (type) {
                case "iCCP":
                    pngIcc(data, (int) length);
                    return;
                case "eXIf":
                    return; // EXIF is read, counted and rewritten by ExifInterface.
                case "tEXt":
                case "zTXt":
                case "iTXt":
                    pngText(type, src.read(data, (int) Math.min(length, MAX_INFLATED)), length);
                    return;
                case "tIME":
                    byte[] t = src.read(data, (int) Math.min(length, 7));
                    String time = t.length < 7 ? null : String.format(Locale.ROOT,
                            "%04d-%02d-%02d %02d:%02d:%02d UTC", ((t[0] & 0xFF) << 8) | (t[1] & 0xFF),
                            t[2], t[3], t[4], t[5], t[6]);
                    find("PNG_MODIFIED_TIME", time, length, Kind.DATE);
                    return;
                case "caBX":
                    find("CONTENT_CREDENTIALS", null, length, Kind.OTHER);
                    return;
                default:
                    find("PNG_CHUNK_" + keyOf(type), null, length, Kind.OTHER);
            }
        }

        private void pngText(String type, byte[] data, long length) {
            int nul = indexOf(data, new byte[] {0});
            if (nul <= 0) {
                find("PNG_TEXT", null, length, Kind.OTHER);
                return;
            }
            String keyword = new String(data, 0, nul, StandardCharsets.ISO_8859_1);
            String text = null;
            try {
                if (type.equals("tEXt")) {
                    text = new String(data, nul + 1, data.length - nul - 1, StandardCharsets.ISO_8859_1);
                } else if (type.equals("zTXt")) {
                    text = new String(inflate(data, nul + 2), StandardCharsets.ISO_8859_1);
                } else {
                    boolean compressed = data[nul + 1] != 0;
                    int pos = nul + 3;
                    pos = indexOf(data, new byte[] {0}, pos) + 1; // language tag
                    pos = indexOf(data, new byte[] {0}, pos) + 1; // translated keyword
                    if (pos <= 0) {
                        throw new IOException("Bad iTXt");
                    }
                    byte[] value = compressed ? inflate(data, pos)
                            : java.util.Arrays.copyOfRange(data, pos, data.length);
                    text = new String(value, StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException e) {
                text = null;
            }
            if (keyword.equals("XML:com.adobe.xmp")) {
                find("XMP", text, length, text != null ? xmpKind(text) : Kind.OTHER);
                return;
            }
            String lower = keyword.toLowerCase(Locale.ROOT);
            if (lower.startsWith("raw profile type")) {
                // ImageMagick's hex dump of a whole EXIF, IPTC or XMP block.
                find("PNG_TEXT_" + keyOf(keyword), null, length,
                        lower.contains("exif") || lower.contains("xmp") ? Kind.LOCATION : Kind.OTHER);
                return;
            }
            find("PNG_TEXT_" + keyOf(keyword), text, length, textKind(lower));
        }

        private void pngIcc(long data, int length) throws IOException {
            byte[] chunk = src.read(data, length);
            int nul = indexOf(chunk, new byte[] {0});
            if (nul < 0 || nul + 2 > chunk.length) {
                find("PNG_CHUNK_ICCP", null, length, Kind.OTHER);
                return;
            }
            byte[] profile;
            try {
                profile = inflate(chunk, nul + 2);
            } catch (IOException e) {
                find("PNG_CHUNK_ICCP", null, length, Kind.OTHER);
                return;
            }
            iccInfo(profile);
            if (out == null) {
                return;
            }
            clearIccHeader(profile, 0);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(ascii("ICC Profile"));
            body.write(0);
            body.write(0);
            byte[] compressed = deflate(profile);
            body.write(compressed, 0, compressed.length);
            writePngChunk("iCCP", body.toByteArray());
        }

        private void writePngChunk(String type, byte[] data) throws IOException {
            byte[] t = ascii(type);
            CRC32 crc = new CRC32();
            crc.update(t);
            crc.update(data);
            write(be32(data.length));
            write(t);
            write(data);
            write(be32(crc.getValue()));
        }

        // ---- WebP -----------------------------------------------------------------------------

        private void webp() throws IOException {
            long riffEnd = Math.min(src.length, 8 + le32(src.read(4, 4), 0));
            ByteArrayOutputStream body = out != null ? new ByteArrayOutputStream() : null;
            long pos = 12;
            int vp8xAt = -1;
            boolean keptIcc = false;
            while (pos + 8 <= riffEnd) {
                String type = new String(src.read(pos, 4), StandardCharsets.ISO_8859_1);
                long size = le32(src.read(pos + 4, 4), 0);
                long data = pos + 8;
                long end = data + size + (size & 1);
                if (data + size > riffEnd) {
                    throw new IOException("WebP chunk runs past the file");
                }
                switch (type) {
                    case "VP8X":
                        if (body != null && vp8xAt < 0) {
                            vp8xAt = body.size();
                            copyWebpChunk(pos, size, body);
                        }
                        break;
                    case "VP8 ":
                    case "VP8L":
                    case "ALPH":
                    case "ANIM":
                        if (body != null) {
                            copyWebpChunk(pos, size, body);
                        }
                        break;
                    case "ANMF":
                        anmf(pos, data, size, body);
                        break;
                    case "ICCP": {
                        byte[] profile = src.read(data, (int) size);
                        iccInfo(profile);
                        if (body != null) {
                            clearIccHeader(profile, 0);
                            body.write(src.read(pos, 8));
                            body.write(profile);
                            if ((size & 1) == 1) {
                                body.write(0);
                            }
                            keptIcc = true;
                        }
                        break;
                    }
                    case "EXIF":
                        break; // EXIF is read, counted and rewritten by ExifInterface.
                    case "XMP ": {
                        String xmp = new String(src.read(data, (int) Math.min(size, MAX_INFLATED)), StandardCharsets.UTF_8);
                        find("XMP", xmp, size, xmpKind(xmp));
                        break;
                    }
                    default:
                        find("WEBP_CHUNK_" + keyOf(type.trim()), null, size, Kind.OTHER);
                }
                pos = end;
            }
            afterEnd(riffEnd, "DATA_AFTER_IMAGE");
            if (body != null) {
                byte[] chunks = body.toByteArray();
                if (vp8xAt >= 0 && vp8xAt + 9 <= chunks.length) {
                    // Clear the EXIF and XMP flags, and the ICC one when no profile was kept.
                    int flags = chunks[vp8xAt + 8] & 0xFF;
                    flags &= ~0x0C;
                    if (!keptIcc) {
                        flags &= ~0x20;
                    }
                    chunks[vp8xAt + 8] = (byte) flags;
                }
                write(ascii("RIFF"));
                write(le32Bytes(chunks.length + 4));
                write(ascii("WEBP"));
                write(chunks);
            }
        }

        /** Copies a chunk's header and data, and always ends it on an even offset. */
        private void copyWebpChunk(long pos, long size, @NonNull ByteArrayOutputStream to) throws IOException {
            to.write(src.read(pos, (int) (8 + size)));
            if ((size & 1) == 1) {
                to.write(0);
            }
        }

        /** An animation frame keeps its alpha and bitstream; anything else inside it is dropped. */
        private void anmf(long pos, long data, long size, @Nullable ByteArrayOutputStream body) throws IOException {
            if (size < 16) {
                throw new IOException("Short ANMF");
            }
            ByteArrayOutputStream frame = body != null ? new ByteArrayOutputStream() : null;
            if (frame != null) {
                frame.write(src.read(data, 16));
            }
            long inner = data + 16;
            long end = data + size;
            while (inner + 8 <= end) {
                String type = new String(src.read(inner, 4), StandardCharsets.ISO_8859_1);
                long innerSize = le32(src.read(inner + 4, 4), 0);
                long innerEnd = inner + 8 + innerSize + (innerSize & 1);
                if (inner + 8 + innerSize > end) {
                    throw new IOException("ANMF chunk runs past its frame");
                }
                if (type.equals("ALPH") || type.equals("VP8 ") || type.equals("VP8L")) {
                    if (frame != null) {
                        copyWebpChunk(inner, innerSize, frame);
                    }
                } else {
                    find("WEBP_CHUNK_" + keyOf(type.trim()), null, innerSize, Kind.OTHER);
                }
                inner = innerEnd;
            }
            if (body != null) {
                byte[] f = frame.toByteArray();
                body.write(ascii("ANMF"));
                body.write(le32Bytes(f.length));
                body.write(f);
                if ((f.length & 1) == 1) {
                    body.write(0);
                }
            }
        }

        private void afterEnd(long pos, String key) throws IOException {
            if (pos < src.length) {
                long peekEnd = Math.min(src.length, pos + TRAILER_PEEK);
                if (!(src.allPadding(pos, peekEnd) && peekEnd == src.length)) {
                    find(key, null, src.length - pos, Kind.OTHER);
                }
            }
        }

        // ---- colour profiles ------------------------------------------------------------------

        /**
         * Lists a profile's name and any device it names, both kept. The header's date, device
         * signature and creator are cleared by a clean ({@link #clearIccHeader}) but are not
         * findings: the stock sRGB and Display P3 profiles that most pictures carry fill them in
         * too ("IEC" "sRGB", 1998), so counting them would call every such file uncleaned and
         * report a device that is only the profile's publisher.
         */
        private void iccInfo(@NonNull byte[] profile) {
            if (profile.length < 132) {
                return;
            }
            String description = IccText.read(profile, "desc");
            String deviceModel = IccText.read(profile, "dmdd");
            String deviceMaker = IccText.read(profile, "dmnd");
            if (description != null) {
                kept.add(new Finding("ICC_PROFILE", description, profile.length, Kind.OTHER));
            }
            String device = ((deviceMaker != null ? deviceMaker + " " : "")
                    + (deviceModel != null ? deviceModel : "")).trim();
            if (!device.isEmpty()) {
                // Text tags stay, as clearing them would break the profile; say so in Scan.
                kept.add(new Finding("ICC_PROFILE_DEVICE", device, 8, Kind.DEVICE));
            }
        }
    }

    /**
     * Clears the header fields of an ICC profile starting at {@code offset} that can identify a
     * device, a person's software or a date: the CMM, creation date and time, device maker, model
     * and attributes, the creator and the profile ID (zero means "not computed").
     */
    @VisibleForTesting
    static void clearIccHeader(@NonNull byte[] buffer, int offset) {
        if (buffer.length < offset + 128) {
            return;
        }
        java.util.Arrays.fill(buffer, offset + 4, offset + 8, (byte) 0);
        java.util.Arrays.fill(buffer, offset + 24, offset + 36, (byte) 0);
        java.util.Arrays.fill(buffer, offset + 48, offset + 64, (byte) 0);
        java.util.Arrays.fill(buffer, offset + 80, offset + 100, (byte) 0);
    }

    /** Text from an ICC profile's tags. */
    static final class IccText {
        private IccText() {
        }

        @Nullable
        static String read(@NonNull byte[] p, @NonNull String signature) {
            try {
                int count = (int) u32(p, 128);
                for (int i = 0; i < count && 132 + i * 12 + 12 <= p.length; i++) {
                    int entry = 132 + i * 12;
                    if (!new String(p, entry, 4, StandardCharsets.ISO_8859_1).equals(signature)) {
                        continue;
                    }
                    int offset = (int) u32(p, entry + 4);
                    int size = (int) u32(p, entry + 8);
                    if (offset < 0 || size < 12 || offset + size > p.length) {
                        return null;
                    }
                    String type = new String(p, offset, 4, StandardCharsets.ISO_8859_1);
                    if (type.equals("desc")) {
                        int n = (int) u32(p, offset + 8);
                        n = Math.min(n, size - 12);
                        return MetadataStripper.normalizeMetadataValue(
                                printable(java.util.Arrays.copyOfRange(p, offset + 12, offset + 12 + n)));
                    }
                    if (type.equals("mluc")) {
                        int records = (int) u32(p, offset + 8);
                        if (records < 1) {
                            return null;
                        }
                        int length = (int) u32(p, offset + 20);
                        int at = (int) u32(p, offset + 24);
                        if (at + length > size) {
                            return null;
                        }
                        return MetadataStripper.normalizeMetadataValue(
                                new String(p, offset + at, length, StandardCharsets.UTF_16BE));
                    }
                    if (type.equals("text")) {
                        return MetadataStripper.normalizeMetadataValue(
                                printable(java.util.Arrays.copyOfRange(p, offset + 8, offset + size)));
                    }
                    return null;
                }
            } catch (RuntimeException e) {
                return null;
            }
            return null;
        }
    }

    /** The finding keys that hold a place, which Scan lists with the other location rows. */
    public static final Set<String> LOCATION_KEYS = Set.of(
            "LOCATION_CITY", "LOCATION_SUBLOCATION", "LOCATION_STATE", "LOCATION_COUNTRY_CODE",
            "LOCATION_COUNTRY", "LOCATION_EMBEDDED_IMAGE");

    // ---- IPTC names ---------------------------------------------------------------------------

    private static final class IptcField {
        final String key;
        final Kind kind;

        IptcField(String key, Kind kind) {
            this.key = key;
            this.kind = kind;
        }
    }

    private static final Map<Integer, IptcField> IPTC_FIELDS = Map.ofEntries(
            Map.entry(5, new IptcField("IPTC_OBJECT_NAME", Kind.OTHER)),
            Map.entry(25, new IptcField("IPTC_KEYWORDS", Kind.OTHER)),
            Map.entry(40, new IptcField("IPTC_SPECIAL_INSTRUCTIONS", Kind.OTHER)),
            Map.entry(55, new IptcField("IPTC_DATE_CREATED", Kind.DATE)),
            Map.entry(60, new IptcField("IPTC_TIME_CREATED", Kind.DATE)),
            Map.entry(62, new IptcField("IPTC_DIGITAL_CREATION_DATE", Kind.DATE)),
            Map.entry(63, new IptcField("IPTC_DIGITAL_CREATION_TIME", Kind.DATE)),
            Map.entry(65, new IptcField("IPTC_ORIGINATING_PROGRAM", Kind.OTHER)),
            Map.entry(80, new IptcField("IPTC_BYLINE", Kind.NAME)),
            Map.entry(85, new IptcField("IPTC_BYLINE_TITLE", Kind.NAME)),
            Map.entry(90, new IptcField("LOCATION_CITY", Kind.LOCATION)),
            Map.entry(92, new IptcField("LOCATION_SUBLOCATION", Kind.LOCATION)),
            Map.entry(95, new IptcField("LOCATION_STATE", Kind.LOCATION)),
            Map.entry(100, new IptcField("LOCATION_COUNTRY_CODE", Kind.LOCATION)),
            Map.entry(101, new IptcField("LOCATION_COUNTRY", Kind.LOCATION)),
            Map.entry(103, new IptcField("IPTC_TRANSMISSION_REFERENCE", Kind.OTHER)),
            Map.entry(105, new IptcField("IPTC_HEADLINE", Kind.OTHER)),
            Map.entry(110, new IptcField("IPTC_CREDIT", Kind.NAME)),
            Map.entry(115, new IptcField("IPTC_SOURCE", Kind.NAME)),
            Map.entry(116, new IptcField("IPTC_COPYRIGHT", Kind.NAME)),
            Map.entry(118, new IptcField("IPTC_CONTACT", Kind.NAME)),
            Map.entry(120, new IptcField("IPTC_CAPTION", Kind.OTHER)),
            Map.entry(122, new IptcField("IPTC_CAPTION_WRITER", Kind.NAME)));

    // ---- helpers ------------------------------------------------------------------------------

    /*
     * XMP properties by their qualified names, as an element (<exif:GPSLatitude>) or an attribute
     * (exif:GPSLatitude="…"). A namespace declaration such as xmlns:xmpRights="…" says nothing
     * about the picture and matches none of these.
     */
    private static final Pattern XMP_LOCATION = Pattern.compile(
            "[<\\s](exif:gpslatitude|exif:gpslongitude|photoshop:city|photoshop:state|photoshop:country"
                    + "|iptc4xmpcore:location|iptc4xmpcore:countrycode|iptc4xmpext:locationcreated"
                    + "|iptc4xmpext:locationshown)[\\s>=]");
    private static final Pattern XMP_NAME = Pattern.compile(
            "[<\\s](dc:creator|dc:rights|xmprights:owner|photoshop:author|photoshop:authorsposition"
                    + "|photoshop:credit|photoshop:captionwriter|iptc4xmpcore:creatorcontactinfo)[\\s>=]");
    private static final Pattern XMP_DATE = Pattern.compile(
            "[<\\s](xmp:createdate|xmp:modifydate|xmp:metadatadate|photoshop:datecreated"
                    + "|exif:datetimeoriginal|exif:datetimedigitized)[\\s>=]");

    @NonNull
    @VisibleForTesting
    static Kind xmpKind(@NonNull String xmp) {
        String lower = xmp.toLowerCase(Locale.ROOT);
        if (XMP_LOCATION.matcher(lower).find()) {
            return Kind.LOCATION;
        }
        if (XMP_NAME.matcher(lower).find()) {
            return Kind.NAME;
        }
        if (XMP_DATE.matcher(lower).find()) {
            return Kind.DATE;
        }
        return Kind.OTHER;
    }

    @NonNull
    private static Kind textKind(@NonNull String lowerKeyword) {
        if (lowerKeyword.contains("author") || lowerKeyword.contains("copyright")
                || lowerKeyword.contains("artist") || lowerKeyword.contains("owner")) {
            return Kind.NAME;
        }
        if (lowerKeyword.contains("time") || lowerKeyword.contains("date")) {
            return Kind.DATE;
        }
        if (lowerKeyword.contains("location") || lowerKeyword.contains("gps")) {
            return Kind.LOCATION;
        }
        if (lowerKeyword.contains("source") || lowerKeyword.contains("software")
                || lowerKeyword.contains("make") || lowerKeyword.contains("model")) {
            return Kind.DEVICE;
        }
        return Kind.OTHER;
    }

    private static final Pattern SAMSUNG_ENTRY = Pattern.compile("[A-Z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)*_(?:Data|Info)");

    /** The names of the entries in a Samsung trailer, such as {@code Image_UTC_Data}. */
    @NonNull
    static Set<String> samsungEntries(@NonNull byte[] trailer) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = SAMSUNG_ENTRY.matcher(new String(trailer, StandardCharsets.ISO_8859_1));
        while (m.find() && names.size() < 32) {
            names.add(m.group());
        }
        return names;
    }

    private static final class EmbeddedExif {
        @Nullable double[] latLong;
        @Nullable String dateTime;
        @Nullable String device;
    }

    /** The leading run of printable ASCII, as vendor segments usually start with their name. */
    @NonNull
    private static String identifier(@NonNull byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 32); i++) {
            int c = data[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                sb.append((char) c);
            } else {
                break;
            }
        }
        return sb.length() >= 3 ? sb.toString().trim() : "";
    }

    /** {@code "Ducky"} becomes {@code DUCKY}, {@code "Raw profile type exif"} RAW_PROFILE_TYPE_EXIF. */
    @NonNull
    static String keyOf(@NonNull String name) {
        String key = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (key.length() > 40) {
            key = key.substring(0, 40);
        }
        return key.isEmpty() ? "DATA" : key;
    }

    @NonNull
    private static String printable(@NonNull byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7F && c != '�') ? c : ' ');
        }
        return sb.toString().trim();
    }

    @NonNull
    private static String cap(@NonNull String text) {
        String trimmed = text.trim();
        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) + "…" : trimmed;
    }

    @NonNull
    private static byte[] inflate(@NonNull byte[] data, int offset) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, data.length - offset);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_INFLATED) {
                    throw new IOException("Compressed text too large");
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    @NonNull
    private static byte[] deflate(@NonNull byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @NonNull
    private static byte[] ascii(@NonNull String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static boolean startsWith(@NonNull byte[] data, @NonNull byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(@NonNull byte[] data, @NonNull byte[] needle) {
        return indexOf(data, needle, 0);
    }

    private static int indexOf(@NonNull byte[] data, @NonNull byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static long u32(byte[] b, int offset) {
        return ((long) (b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static long le32(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16) | ((long) (b[offset + 3] & 0xFF) << 24);
    }

    @NonNull
    private static byte[] be32(long v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    @NonNull
    private static byte[] le32Bytes(long v) {
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    // ---- reading ------------------------------------------------------------------------------

    /** Positional reads over a channel through a small window, so byte-wise scans stay fast. */
    private static final class Source {
        private final FileChannel ch;
        final long length;
        private final ByteBuffer window = ByteBuffer.allocate(64 * 1024);
        private long windowStart = -1;
        private int windowLength;

        Source(@NonNull FileChannel ch) throws IOException {
            this.ch = ch;
            this.length = ch.size();
        }

        void require(long end) throws IOException {
            if (end > length) {
                throw new IOException("Unexpected end of file");
            }
        }

        int at(long pos) throws IOException {
            fill(pos);
            return window.get((int) (pos - windowStart)) & 0xFF;
        }

        /** Makes the window hold {@code pos}. */
        private void fill(long pos) throws IOException {
            if (pos < 0 || pos >= length) {
                throw new IOException("Read past the end of the file");
            }
            if (windowStart < 0 || pos < windowStart || pos >= windowStart + windowLength) {
                window.clear();
                windowStart = pos;
                windowLength = 0;
                while (window.hasRemaining()) {
                    int n = ch.read(window, pos + window.position());
                    if (n < 0) {
                        break;
                    }
                    if (n == 0 && pos + window.position() >= length) {
                        break;
                    }
                }
                windowLength = window.position();
                if (windowLength == 0) {
                    throw new IOException("Unexpected end of file");
                }
            }
        }

        int u16(long pos) throws IOException {
            return (at(pos) << 8) | at(pos + 1);
        }

        long u32(long pos) throws IOException {
            return ((long) at(pos) << 24) | (at(pos + 1) << 16) | (at(pos + 2) << 8) | at(pos + 3);
        }

        @NonNull
        byte[] read(long pos, int n) throws IOException {
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

        void copyTo(long pos, long n, @NonNull OutputStream out) throws IOException {
            require(pos + n);
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(n, 64 * 1024));
            long done = 0;
            while (done < n) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), n - done));
                int r = ch.read(buffer, pos + done);
                if (r < 0) {
                    throw new IOException("Unexpected end of file");
                }
                out.write(buffer.array(), 0, r);
                done += r;
            }
        }

        long indexOf(int b, long from) throws IOException {
            return indexOf(b, from, length);
        }

        /** The first {@code b} in {@code [from, limit)}, searched in the window's array directly. */
        long indexOf(int b, long from, long limit) throws IOException {
            long p = Math.max(0, from);
            limit = Math.min(limit, length);
            byte[] array = window.array();
            while (p < limit) {
                fill(p);
                int i = (int) (p - windowStart);
                int end = (int) Math.min(windowLength, limit - windowStart);
                for (; i < end; i++) {
                    if ((array[i] & 0xFF) == b) {
                        return windowStart + i;
                    }
                }
                p = windowStart + end;
            }
            return -1;
        }

        long indexOf3(int a, int b, int c, long from, long limit) throws IOException {
            return indexOf(new byte[] {(byte) a, (byte) b, (byte) c}, from, limit);
        }

        long indexOf(@NonNull byte[] needle, long from, long limit) throws IOException {
            int first = needle[0] & 0xFF;
            long p = from;
            outer:
            while (p + needle.length <= limit) {
                p = indexOf(first, p, limit - needle.length + 1);
                if (p < 0) {
                    return -1;
                }
                for (int j = 1; j < needle.length; j++) {
                    if (at(p + j) != (needle[j] & 0xFF)) {
                        p++;
                        continue outer;
                    }
                }
                return p;
            }
            return -1;
        }

        /** True when {@code [from, to)} holds nothing but zero or 0xFF padding. */
        boolean allPadding(long from, long to) throws IOException {
            long p = from;
            to = Math.min(to, length);
            byte[] array = window.array();
            while (p < to) {
                fill(p);
                int i = (int) (p - windowStart);
                int end = (int) Math.min(windowLength, to - windowStart);
                for (; i < end; i++) {
                    if (array[i] != 0 && array[i] != (byte) 0xFF) {
                        return false;
                    }
                }
                p = windowStart + end;
            }
            return true;
        }
    }
}
