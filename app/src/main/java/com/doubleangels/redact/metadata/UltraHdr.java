package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps an Ultra HDR JPEG's gain map through a clean.
 *
 * <p>An Ultra HDR photo (Pixel 8 and later, Galaxy S24 and later, and others) is a normal JPEG
 * followed by a second, smaller JPEG, the gain map, that brightens it on an HDR screen. It is
 * found through a multi-picture index (MPF) and described by numbers in XMP ({@code hdrgm}) or in
 * ISO 21496-1 blocks. {@link ImageContainerMetadata#sanitize} stops at the end of the first
 * picture, which turned every such photo into SDR. This puts the gain map back after the clean
 * picture, itself cleaned, with fresh XMP holding only the gain map numbers, the ISO blocks (which
 * hold nothing but numbers) and a new index pointing at it. No text from the source's XMP is
 * copied; only values that parse as numbers or True and False are.
 */
final class UltraHdr {

    static final byte[] ISO_ID = "urn:iso:std:iso:ts:21496:-1\0".getBytes(StandardCharsets.ISO_8859_1);
    /** The primary picture's ISO block is a version number and nothing else. */
    static final int MAX_PRIMARY_ISO = 64;
    /** The gain map's ISO block: version, flags and a few numbers per channel. */
    private static final int MAX_GAIN_MAP_ISO = 4096;
    private static final long MAX_GAIN_MAP_BYTES = 64L * 1024 * 1024;

    private static final byte[] XMP_ID = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final String HDRGM_NS = "http://ns.adobe.com/hdr-gain-map/1.0/";
    private static final String CONTAINER_NS = "http://ns.google.com/photos/1.0/container/";
    private static final String ITEM_NS = "http://ns.google.com/photos/1.0/container/item/";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private static final Set<String> NAMESPACES = Set.of(
            "adobe:ns:meta/", RDF_NS, HDRGM_NS, CONTAINER_NS, ITEM_NS);

    /** The gain map's own numbers, in the order the Ultra HDR format lists them. */
    private static final List<String> HDRGM_FIELDS = List.of(
            "Version", "BaseRenditionIsHDR", "GainMapMin", "GainMapMax", "Gamma", "OffsetSDR",
            "OffsetHDR", "HDRCapacityMin", "HDRCapacityMax");

    private static final Pattern NUMBER_OR_FLAG =
            Pattern.compile("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?|True|False|true|false");
    private static final Pattern XMLNS = Pattern.compile("xmlns:([A-Za-z_][\\w.-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ATTRIBUTE =
            Pattern.compile("\\s([A-Za-z_][\\w.-]*)(?::([A-Za-z_][\\w.-]*))?\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z_][\\w.-]*)(?::([A-Za-z_][\\w.-]*))?");
    private static final Pattern HDRGM_ATTRIBUTE =
            Pattern.compile("hdrgm:([A-Za-z]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern HDRGM_SEQ = Pattern.compile(
            "<hdrgm:([A-Za-z]+)>\\s*<rdf:Seq>(.*?)</rdf:Seq>\\s*</hdrgm:\\1>", Pattern.DOTALL);
    private static final Pattern LI = Pattern.compile("<rdf:li>\\s*([^<]*?)\\s*</rdf:li>");

    private UltraHdr() {
    }

    /** Whether XMP says a gain map follows the picture. */
    static boolean declaresGainMap(@NonNull String xmp) {
        return xmp.contains(HDRGM_NS) && xmp.contains("Version");
    }

    /**
     * Whether XMP holds nothing but what an Ultra HDR file needs: the gain map namespace's numbers
     * and the directory naming the primary picture and gain map, with no other namespace, no
     * text, and no value that is not a number, a flag or one of the directory's fixed words.
     */
    static boolean isGainMapOnlyXmp(@NonNull String xmp) {
        String body = xmp.replaceAll("<\\?xpacket[^>]*\\?>", "").trim();
        if (!body.contains(HDRGM_NS) || body.contains("<!") || body.contains("<?")) {
            return false;
        }
        Map<String, String> prefixes = new LinkedHashMap<>();
        Matcher ns = XMLNS.matcher(body);
        while (ns.find()) {
            if (!NAMESPACES.contains(ns.group(2))) {
                return false;
            }
            prefixes.put(ns.group(1), ns.group(2));
        }
        Matcher tag = TAG.matcher(body);
        while (tag.find()) {
            String prefix = tag.group(3) != null ? tag.group(2) : null;
            if (prefix == null || !prefixes.containsKey(prefix)) {
                return false;
            }
        }
        // Each tag's attributes, read tag by tag so text between tags is never taken for one.
        Matcher whole = Pattern.compile("<[^<>]*>").matcher(body);
        int textFrom = 0;
        while (whole.find()) {
            String between = body.substring(textFrom, whole.start()).trim();
            if (!between.isEmpty() && !NUMBER_OR_FLAG.matcher(between).matches()) {
                return false;
            }
            textFrom = whole.end();
            Matcher attribute = ATTRIBUTE.matcher(whole.group());
            while (attribute.find()) {
                String prefix = attribute.group(2) != null ? attribute.group(1) : null;
                String name = attribute.group(2) != null ? attribute.group(2) : attribute.group(1);
                String value = attribute.group(3);
                if (!attributeAllowed(prefix, name, value, prefixes)) {
                    return false;
                }
            }
        }
        return body.substring(textFrom).trim().isEmpty();
    }

    private static boolean attributeAllowed(@Nullable String prefix, @NonNull String name,
            @NonNull String value, @NonNull Map<String, String> prefixes) {
        if (prefix == null) {
            return false;
        }
        if ("xmlns".equals(prefix)) {
            return true; // checked above
        }
        String namespace = prefixes.get(prefix);
        if (namespace == null) {
            return false;
        }
        switch (namespace) {
            case HDRGM_NS:
                return HDRGM_FIELDS.contains(name) && NUMBER_OR_FLAG.matcher(value).matches();
            case RDF_NS:
                return ("about".equals(name) && value.isEmpty())
                        || ("parseType".equals(name) && "Resource".equals(value));
            case ITEM_NS:
                switch (name) {
                    case "Semantic":
                        return "Primary".equals(value) || "GainMap".equals(value);
                    case "Mime":
                        return "image/jpeg".equals(value);
                    case "Length":
                    case "Padding":
                        return value.matches("\\d{1,12}");
                    default:
                        return false;
                }
            case "adobe:ns:meta/":
                // The XMP toolkit's name and version, such as "XMP Core 5.5.0".
                return "xmptk".equals(name) && value.matches("[A-Za-z0-9 .\\-]{0,64}");
            default:
                return false;
        }
    }

    /**
     * The gain map numbers in XMP, each field to its values (one, or one per colour channel).
     * Values that are not numbers or flags are left out, and so is any field not in the format.
     */
    @VisibleForTesting
    @NonNull
    static Map<String, List<String>> gainMapFields(@NonNull String xmp) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        Matcher attribute = HDRGM_ATTRIBUTE.matcher(xmp);
        while (attribute.find()) {
            String name = attribute.group(1);
            String value = attribute.group(2).trim();
            if (HDRGM_FIELDS.contains(name) && NUMBER_OR_FLAG.matcher(value).matches()) {
                fields.put(name, List.of(value));
            }
        }
        Matcher seq = HDRGM_SEQ.matcher(xmp);
        while (seq.find()) {
            String name = seq.group(1);
            if (!HDRGM_FIELDS.contains(name)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            Matcher li = LI.matcher(seq.group(2));
            while (li.find()) {
                if (!NUMBER_OR_FLAG.matcher(li.group(1)).matches()) {
                    values.clear();
                    break;
                }
                values.add(li.group(1));
            }
            if (!values.isEmpty() && values.size() <= 3) {
                fields.put(name, values);
            }
        }
        return fields;
    }

    /** XMP for the gain map: its numbers and nothing else. */
    @VisibleForTesting
    @NonNull
    static String gainMapXmp(@NonNull Map<String, List<String>> fields) {
        StringBuilder attributes = new StringBuilder();
        StringBuilder elements = new StringBuilder();
        for (String name : HDRGM_FIELDS) {
            List<String> values = fields.get(name);
            if (values == null) {
                continue;
            }
            if (values.size() == 1) {
                attributes.append(" hdrgm:").append(name).append("=\"").append(values.get(0)).append('"');
            } else {
                elements.append("<hdrgm:").append(name).append("><rdf:Seq>");
                for (String value : values) {
                    elements.append("<rdf:li>").append(value).append("</rdf:li>");
                }
                elements.append("</rdf:Seq></hdrgm:").append(name).append('>');
            }
        }
        if (!fields.containsKey("Version")) {
            attributes.insert(0, " hdrgm:Version=\"1.0\"");
        }
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"" + RDF_NS + "\">"
                + "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"" + HDRGM_NS + "\"" + attributes
                + (elements.length() == 0 ? "/>" : ">" + elements + "</rdf:Description>")
                + "</rdf:RDF></x:xmpmeta>";
    }

    /** XMP for the primary picture: the gain map version and the directory that finds it. */
    @VisibleForTesting
    @NonNull
    static String primaryXmp(@NonNull String version, long gainMapLength) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"" + RDF_NS + "\">"
                + "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"" + HDRGM_NS + "\" xmlns:Container=\""
                + CONTAINER_NS + "\" xmlns:Item=\"" + ITEM_NS + "\" hdrgm:Version=\"" + version + "\">"
                + "<Container:Directory><rdf:Seq>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\""
                + gainMapLength + "\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>";
    }

    /**
     * A big-endian multi-picture index for two pictures: the primary of {@code primaryLength}
     * bytes, and the gain map of {@code gainMapLength} bytes at {@code gainMapOffset} from the
     * index's own TIFF header, as the format counts it.
     */
    @VisibleForTesting
    @NonNull
    static byte[] mpfSegment(long primaryLength, long gainMapLength, long gainMapOffset) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[] {'M', 'P', 'F', 0});
        b.write(new byte[] {'M', 'M', 0, 0x2A});
        writeU32(b, 8); // first IFD
        writeU16(b, 3);
        // MPFVersion "0100"
        writeU16(b, 0xB000);
        writeU16(b, 7);
        writeU32(b, 4);
        b.write(new byte[] {'0', '1', '0', '0'});
        // NumberOfImages 2
        writeU16(b, 0xB001);
        writeU16(b, 4);
        writeU32(b, 1);
        writeU32(b, 2);
        // MPEntry: two 16-byte entries right after the IFD
        writeU16(b, 0xB002);
        writeU16(b, 7);
        writeU32(b, 32);
        writeU32(b, 8 + 2 + 3 * 12 + 4);
        writeU32(b, 0); // no next IFD
        // Primary: representative baseline picture at offset 0
        writeU32(b, 0x20030000L);
        writeU32(b, primaryLength);
        writeU32(b, 0);
        writeU16(b, 0);
        writeU16(b, 0);
        // Gain map
        writeU32(b, 0);
        writeU32(b, gainMapLength);
        writeU32(b, gainMapOffset);
        writeU16(b, 0);
        writeU16(b, 0);
        return segment(0xE2, b.toByteArray());
    }

    /**
     * Rebuilds {@code target}, the clean primary picture, as an Ultra HDR JPEG with the gain map at
     * {@code [gainMapStart, gainMapEnd)} of {@code source}, cleaned. Leaves {@code target} as it
     * is, without HDR, when the gain map has no numbers to show it with.
     */
    static void rebuild(@NonNull File source, long gainMapStart, long gainMapEnd,
            @Nullable byte[] primaryIso, @NonNull File target) throws IOException {
        long length = gainMapEnd - gainMapStart;
        if (length <= 4 || length > MAX_GAIN_MAP_BYTES) {
            throw new IOException("Gain map out of range");
        }
        byte[] gainMap = new byte[(int) length];
        try (RandomAccessFile in = new RandomAccessFile(source, "r")) {
            in.seek(gainMapStart);
            in.readFully(gainMap);
        }
        String xmp = null;
        byte[] iso = null;
        for (byte[] segment : appSegments(gainMap)) {
            int marker = segment[0] & 0xFF;
            byte[] data = java.util.Arrays.copyOfRange(segment, 1, segment.length);
            if (marker == 0xE1 && startsWith(data, XMP_ID) && xmp == null) {
                xmp = new String(data, XMP_ID.length, data.length - XMP_ID.length, StandardCharsets.UTF_8);
            } else if (marker == 0xE2 && startsWith(data, ISO_ID) && iso == null
                    && data.length <= MAX_GAIN_MAP_ISO) {
                iso = data;
            }
        }
        Map<String, List<String>> fields = xmp != null ? gainMapFields(xmp) : Map.of();
        boolean hasXmpNumbers = fields.containsKey("GainMapMax");
        if (!hasXmpNumbers && iso == null) {
            throw new IOException("Gain map without metadata");
        }

        // The gain map, cleaned like any other JPEG, with its numbers put back.
        File dir = target.getAbsoluteFile().getParentFile();
        File rawGainMap = File.createTempFile("gainmap_", ".jpg", dir);
        File cleanGainMap = File.createTempFile("gainmap_clean_", ".jpg", dir);
        byte[] clean;
        try {
            Files.write(rawGainMap.toPath(), gainMap);
            ImageContainerMetadata.sanitize(rawGainMap, cleanGainMap);
            clean = Files.readAllBytes(cleanGainMap.toPath());
        } finally {
            rawGainMap.delete();
            cleanGainMap.delete();
        }
        ByteArrayOutputStream finalGainMap = new ByteArrayOutputStream(clean.length + 1024);
        finalGainMap.write(clean, 0, 2);
        if (hasXmpNumbers) {
            finalGainMap.write(segment(0xE1, concat(XMP_ID, gainMapXmp(fields).getBytes(StandardCharsets.UTF_8))));
        }
        if (iso != null) {
            finalGainMap.write(segment(0xE2, iso));
        }
        finalGainMap.write(clean, 2, clean.length - 2);
        byte[] gainMapBytes = finalGainMap.toByteArray();

        // The primary picture, with the directory, ISO version block and index after its header.
        byte[] primary = Files.readAllBytes(target.toPath());
        if (primary.length < 4 || (primary[0] & 0xFF) != 0xFF || (primary[1] & 0xFF) != 0xD8) {
            throw new IOException("Not a JPEG");
        }
        int insertAt = 2;
        if ((primary[2] & 0xFF) == 0xFF && (primary[3] & 0xFF) == 0xE0 && primary.length >= 6) {
            insertAt = 4 + (((primary[4] & 0xFF) << 8) | (primary[5] & 0xFF)); // after JFIF
        }
        String version = fields.containsKey("Version") ? fields.get("Version").get(0) : "1.0";
        byte[] directory = hasXmpNumbers
                ? segment(0xE1, concat(XMP_ID, primaryXmp(version, gainMapBytes.length)
                        .getBytes(StandardCharsets.UTF_8)))
                : new byte[0];
        byte[] isoVersion = primaryIso != null && primaryIso.length <= MAX_PRIMARY_ISO
                ? segment(0xE2, primaryIso) : new byte[0];
        int mpfAt = insertAt + directory.length + isoVersion.length;
        int mpfLength = mpfSegment(0, 0, 0).length;
        long primaryLength = (long) primary.length + directory.length + isoVersion.length + mpfLength;
        long tiffHeader = mpfAt + 4 + 4; // marker, length, "MPF\0"
        byte[] mpf = mpfSegment(primaryLength, gainMapBytes.length, primaryLength - tiffHeader);

        try (OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(target), 64 * 1024)) {
            out.write(primary, 0, insertAt);
            out.write(directory);
            out.write(isoVersion);
            out.write(mpf);
            out.write(primary, insertAt, primary.length - insertAt);
            out.write(gainMapBytes);
        }
    }

    /** The APP segments before a JPEG's first scan, each as its marker byte and then its data. */
    @NonNull
    private static List<byte[]> appSegments(@NonNull byte[] jpeg) {
        List<byte[]> segments = new ArrayList<>();
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (length < 2 || pos + 2 + length > jpeg.length) {
                break;
            }
            if (marker >= 0xE0 && marker <= 0xEF) {
                byte[] segment = new byte[length - 1];
                segment[0] = (byte) marker;
                System.arraycopy(jpeg, pos + 4, segment, 1, length - 2);
                segments.add(segment);
            }
            pos += 2 + length;
        }
        return segments;
    }

    @NonNull
    private static byte[] segment(int marker, @NonNull byte[] payload) throws IOException {
        if (payload.length + 2 > 0xFFFF) {
            throw new IOException("Segment too large");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 4);
        b.write(0xFF);
        b.write(marker);
        writeU16(b, payload.length + 2);
        b.write(payload);
        return b.toByteArray();
    }

    @NonNull
    private static byte[] concat(@NonNull byte[] a, @NonNull byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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

    private static void writeU16(@NonNull OutputStream out, int value) throws IOException {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32(@NonNull OutputStream out, long value) throws IOException {
        out.write((int) (value >> 24) & 0xFF);
        out.write((int) (value >> 16) & 0xFF);
        out.write((int) (value >> 8) & 0xFF);
        out.write((int) value & 0xFF);
    }
}
