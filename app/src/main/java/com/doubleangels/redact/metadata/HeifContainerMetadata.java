package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds a HEIF image (HEIC) with nothing but its pictures, without re-encoding them.
 *
 * <p>Decoding and encoding a HEIC again costs quality, its 10-bit colour and its HDR gain map,
 * and the decode is capped at the processing resolution, so a 50 MP photo came out at about
 * 12 MP. This copies the coded pictures as they are and, like {@link ImageContainerMetadata},
 * works from an allowlist: only image items (and the derived grids, overlays and gain map tone
 * maps built from them) are kept, so EXIF, XMP and any other metadata item, known or not, is left
 * behind together with its bytes. Item names and the handler name are emptied, item properties
 * outside the allowlist are replaced with empty {@code free} boxes so the property indexes still
 * line up, a colour profile keeps its colours with the fields that can name a device or a date
 * cleared, and SEI messages (HEVC) or metadata OBUs (AV1) that are not about how to show the
 * picture are dropped from the coded data. Every top-level box but {@code ftyp} goes, which also
 * removes a Motion Photo's video.
 */
public final class HeifContainerMetadata {

    /** {@code ftyp} brands of HEIF still images. */
    private static final Set<String> BRANDS = Set.of(
            "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "mif2", "msf1");

    /** Items that are pictures or are built from them. */
    private static final Set<String> IMAGE_ITEM_TYPES = Set.of(
            "hvc1", "av01", "avc1", "vvc1", "jpeg", "j2k1", "unci", "grid", "iovl", "iden", "tmap");

    /** Item properties a decoder needs to show the picture as it was taken. */
    private static final Set<String> PROPERTY_KEEP = Set.of(
            "hvcC", "av1C", "avcC", "vvcC", "jpgC", "ispe", "pixi", "colr", "irot", "imir", "clap",
            "pasp", "auxC", "rloc", "lsel", "a1op", "a1lx", "clli", "mdcv", "cclv", "amve", "rref");

    /**
     * Brands that promise a {@code moov} track (image sequences, and MP4 audio or video next to
     * the image), which is not kept.
     */
    private static final Set<String> SEQUENCE_BRANDS = Set.of(
            "msf1", "hevc", "hevx", "hevm", "hevs", "avis", "isom", "iso2", "iso4", "iso5", "iso6",
            "mp41", "mp42", "M4A ", "M4V ", "qt  ", "avc1", "3gp4", "3gp5", "3gp6", "dash");

    /**
     * HEVC SEI payload types kept: mastering display colour volume (137), content light level
     * (144), alternative transfer characteristics (147), ambient viewing environment (148) and
     * alpha channel information (165). User data and everything else goes.
     */
    private static final Set<Integer> SEI_KEEP = Set.of(137, 144, 147, 148, 165);

    /** AV1 metadata types kept: HDR content light level (1), mastering display (2), scalability (3). */
    private static final Set<Integer> AV1_METADATA_KEEP = Set.of(1, 2, 3);

    private static final int MAX_META_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ITEM_BYTES = 512 * 1024 * 1024;

    private HeifContainerMetadata() {
    }

    /** Whether {@code file} starts with a HEIF still-image {@code ftyp}. */
    public static boolean isHeif(@NonNull File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[64];
            int n = 0;
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) {
                n += r;
            }
            return isHeif(head, n);
        } catch (IOException e) {
            return false;
        }
    }

    @VisibleForTesting
    static boolean isHeif(@NonNull byte[] head, int length) {
        if (length < 16 || !"ftyp".equals(fourCc(head, 4))) {
            return false;
        }
        int size = (int) Math.min(u32(head, 0), length);
        if (BRANDS.contains(fourCc(head, 8))) {
            return true;
        }
        for (int at = 16; at + 4 <= size; at += 4) {
            if (BRANDS.contains(fourCc(head, at))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes {@code source}'s pictures to {@code target} and nothing else.
     *
     * @throws IOException when the file is not a HEIF this class can follow (external data,
     *     protected items, an unknown layout), so the caller re-encodes it instead
     */
    public static void sanitize(@NonNull File source, @NonNull File target) throws IOException {
        if (source.getCanonicalPath().equals(target.getCanonicalPath())) {
            throw new IOException("Source and target must differ");
        }
        File body = File.createTempFile("heif_body_", ".bin", target.getAbsoluteFile().getParentFile());
        try (RandomAccessFile in = new RandomAccessFile(source, "r")) {
            byte[] ftyp = null;
            byte[] meta = null;
            long pos = 0;
            long length = in.length();
            while (pos + 8 <= length) {
                in.seek(pos);
                long size = in.readInt() & 0xFFFFFFFFL;
                byte[] typeBytes = new byte[4];
                in.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                int header = 8;
                if (size == 1) {
                    size = in.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = length - pos;
                }
                if (size < header || pos + size > length) {
                    throw new IOException("Damaged box " + type);
                }
                if ("ftyp".equals(type) || "meta".equals(type)) {
                    if (size > MAX_META_BYTES) {
                        throw new IOException("Box too large: " + type);
                    }
                    byte[] box = new byte[(int) size];
                    in.seek(pos);
                    in.readFully(box);
                    if ("ftyp".equals(type) && ftyp == null) {
                        ftyp = box;
                    } else if ("meta".equals(type)) {
                        if (meta != null) {
                            throw new IOException("Two top-level meta boxes");
                        }
                        meta = box;
                    }
                }
                pos += size;
            }
            if (ftyp == null || meta == null) {
                throw new IOException("Not a HEIF image");
            }
            ftyp = stillImageFtyp(ftyp);
            Rebuild rebuild = new Rebuild(in, meta);
            rebuild.parse();
            try (OutputStream bodyOut = new BufferedOutputStream(new FileOutputStream(body), 64 * 1024)) {
                rebuild.writeItemData(bodyOut);
            }
            long bodyLength = body.length();
            // The pictures follow ftyp, meta and the mdat header; iloc points at them there.
            long mdatHeader = bodyLength + 8 > 0xFFFFFFFFL ? 16 : 8;
            byte[] newMeta = rebuild.writeMeta(ftyp.length, mdatHeader);
            long dataStart = ftyp.length + newMeta.length + mdatHeader;
            if (dataStart + bodyLength > 0xFFFFFFFFL) {
                throw new IOException("Too large for 32-bit item offsets");
            }
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 64 * 1024);
                    InputStream bodyIn = new FileInputStream(body)) {
                out.write(ftyp);
                out.write(newMeta);
                if (mdatHeader == 16) {
                    writeU32(out, 1);
                    out.write(ascii("mdat"));
                    writeU64(out, bodyLength + 16);
                } else {
                    writeU32(out, bodyLength + 8);
                    out.write(ascii("mdat"));
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = bodyIn.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        } finally {
            if (!body.delete()) {
                body.deleteOnExit();
            }
        }
    }

    /**
     * {@code ftyp} without image sequence brands: a burst's or animation's {@code moov} track is
     * not kept, and a reader told the file is a sequence looks for it instead of the still image.
     */
    @VisibleForTesting
    @NonNull
    static byte[] stillImageFtyp(@NonNull byte[] ftyp) throws IOException {
        int header = headerSize(ftyp, 0);
        if (ftyp.length < header + 8) {
            throw new IOException("Damaged ftyp");
        }
        String major = fourCc(ftyp, header);
        List<String> compatible = new ArrayList<>();
        for (int at = header + 8; at + 4 <= ftyp.length; at += 4) {
            String brand = fourCc(ftyp, at);
            if (!SEQUENCE_BRANDS.contains(brand) && !compatible.contains(brand)) {
                compatible.add(brand);
            }
        }
        if (!SEQUENCE_BRANDS.contains(major) && compatible.size() * 4 + header + 8 == ftyp.length) {
            return ftyp;
        }
        if (!compatible.contains("mif1")) {
            compatible.add("mif1");
        }
        if (SEQUENCE_BRANDS.contains(major)) {
            major = compatible.contains("heic") ? "heic" : "mif1";
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(ascii(major));
        b.write(ftyp, header + 4, 4); // minor version
        for (String brand : compatible) {
            b.write(ascii(brand));
        }
        return box("ftyp", b.toByteArray());
    }

    // ---- the rebuild ----------------------------------------------------------------------------

    private static final class Item {
        final long id;
        final String type;
        final int infeVersion;
        final int infeFlags;
        int constructionMethod;
        final List<long[]> extents = new ArrayList<>();
        /** Where the item's data went: in the new mdat body, or in the new idat. */
        long newOffset;
        long newLength;
        boolean hasLocation;

        Item(long id, String type, int infeVersion, int infeFlags) {
            this.id = id;
            this.type = type;
            this.infeVersion = infeVersion;
            this.infeFlags = infeFlags;
        }
    }

    private static final class Rebuild {
        private final RandomAccessFile in;
        private final byte[] meta;
        private final Map<Long, Item> items = new LinkedHashMap<>();
        private final Set<Long> kept = new HashSet<>();
        /** Children of meta in file order, as [type, start, end] of the whole child box. */
        private final List<Object[]> children = new ArrayList<>();
        private int metaVersionFlags;
        private int ilocVersion;
        private int irefVersion;
        private int irefStart = -1;
        private int irefEnd = -1;
        private int idatPayload = -1;
        private int idatEnd = -1;
        private int ipcoStart = -1;
        private int ipcoEnd = -1;
        private int ipmaStart = -1;
        private int ipmaEnd = -1;
        private final List<int[]> properties = new ArrayList<>();
        /** In the source's order, which is by increasing item ID. */
        private final Map<Long, List<Integer>> itemProperties = new LinkedHashMap<>();
        private long primaryId = -1;
        private final ByteArrayOutputStream newIdat = new ByteArrayOutputStream();

        Rebuild(@NonNull RandomAccessFile in, @NonNull byte[] meta) {
            this.in = in;
            this.meta = meta;
        }

        void parse() throws IOException {
            int header = headerSize(meta, 0);
            metaVersionFlags = (int) u32(meta, header);
            int pos = header + 4;
            while (pos + 8 <= meta.length) {
                int size = boxSize(meta, pos);
                String type = fourCc(meta, pos + 4);
                int end = pos + size;
                int payload = pos + headerSize(meta, pos);
                children.add(new Object[] {type, pos, end});
                switch (type) {
                    case "iinf":
                        parseIinf(payload, end);
                        break;
                    case "pitm":
                        primaryId = (meta[payload] & 0xFF) == 0 ? u16(meta, payload + 4) : u32(meta, payload + 4);
                        break;
                    case "iref":
                        irefVersion = meta[payload] & 0xFF;
                        irefStart = payload + 4;
                        irefEnd = end;
                        break;
                    case "iprp":
                        parseIprp(payload, end);
                        break;
                    case "idat":
                        idatPayload = payload;
                        idatEnd = end;
                        break;
                    case "ipro":
                        throw new IOException("Protected items");
                    case "dinf":
                        requireSelfContained(payload, end);
                        break;
                    default:
                        break;
                }
                pos = end;
            }
            // iloc may come before iinf; read it once every item's type is known.
            for (Object[] child : children) {
                if ("iloc".equals(child[0])) {
                    int start = (int) child[1];
                    parseIloc(start + headerSize(meta, start), (int) child[2]);
                }
            }
            for (Item item : items.values()) {
                if (IMAGE_ITEM_TYPES.contains(item.type)) {
                    kept.add(item.id);
                }
            }
            if (!kept.contains(primaryId)) {
                throw new IOException("The primary item is not a picture");
            }
            // A property blanked out must not be one a kept picture can't be shown without.
            for (Map.Entry<Long, List<Integer>> entry : itemProperties.entrySet()) {
                if (!kept.contains(entry.getKey())) {
                    continue;
                }
                for (int index : entry.getValue()) {
                    if (index < 1 || index > properties.size()) {
                        throw new IOException("Damaged property index");
                    }
                    String type = fourCc(meta, properties.get(index - 1)[0] + 4);
                    if (!PROPERTY_KEEP.contains(type)
                            && essential(entry.getKey(), index, ipmaFlags(), ipmaVersion())) {
                        throw new IOException("Essential property " + type);
                    }
                }
            }
        }

        private int ipmaVersion() {
            return meta[ipmaStart] & 0xFF;
        }

        private int ipmaFlags() {
            return (int) (u32(meta, ipmaStart) & 0xFFFFFF);
        }

        private void parseIinf(int payload, int end) throws IOException {
            int version = meta[payload] & 0xFF;
            int pos = payload + 4 + (version == 0 ? 2 : 4);
            while (pos + 8 <= end) {
                int size = boxSize(meta, pos);
                int boxEnd = pos + size;
                if ("infe".equals(fourCc(meta, pos + 4))) {
                    int p = pos + headerSize(meta, pos);
                    int infeVersion = meta[p] & 0xFF;
                    int infeFlags = (int) (u32(meta, p) & 0xFFFFFF);
                    if (infeVersion < 2) {
                        throw new IOException("Old item info");
                    }
                    p += 4;
                    long id;
                    if (infeVersion == 2) {
                        id = u16(meta, p);
                        p += 2;
                    } else {
                        id = u32(meta, p);
                        p += 4;
                    }
                    int protection = u16(meta, p);
                    p += 2;
                    String type = fourCc(meta, p);
                    if (protection != 0 && IMAGE_ITEM_TYPES.contains(type)) {
                        throw new IOException("Protected image item");
                    }
                    items.put(id, new Item(id, type, infeVersion, infeFlags));
                }
                pos = boxEnd;
            }
        }

        private void parseIloc(int payload, int end) throws IOException {
            ilocVersion = meta[payload] & 0xFF;
            int pos = payload + 4;
            int offsetSize = (meta[pos] >> 4) & 0xF;
            int lengthSize = meta[pos] & 0xF;
            int baseOffsetSize = (meta[pos + 1] >> 4) & 0xF;
            int indexSize = ilocVersion >= 1 ? meta[pos + 1] & 0xF : 0;
            pos += 2;
            long count;
            if (ilocVersion < 2) {
                count = u16(meta, pos);
                pos += 2;
            } else {
                count = u32(meta, pos);
                pos += 4;
            }
            for (long i = 0; i < count; i++) {
                long id;
                if (ilocVersion < 2) {
                    id = u16(meta, pos);
                    pos += 2;
                } else {
                    id = u32(meta, pos);
                    pos += 4;
                }
                int method = 0;
                if (ilocVersion >= 1) {
                    method = u16(meta, pos) & 0xF;
                    pos += 2;
                }
                int dataReference = u16(meta, pos);
                pos += 2;
                long base = uN(meta, pos, baseOffsetSize);
                pos += baseOffsetSize;
                int extentCount = u16(meta, pos);
                pos += 2;
                Item item = items.get(id);
                List<long[]> extents = new ArrayList<>();
                for (int e = 0; e < extentCount; e++) {
                    if (indexSize > 0) {
                        pos += indexSize;
                    }
                    long offset = uN(meta, pos, offsetSize);
                    pos += offsetSize;
                    long length = uN(meta, pos, lengthSize);
                    pos += lengthSize;
                    extents.add(new long[] {base + offset, length});
                }
                if (pos > end) {
                    throw new IOException("Damaged iloc");
                }
                if (item == null || !IMAGE_ITEM_TYPES.contains(item.type)) {
                    continue;
                }
                if (dataReference != 0 || method > 1 || (indexSize > 0 && extentCount > 1)) {
                    throw new IOException("Item data this class cannot follow");
                }
                item.constructionMethod = method;
                item.extents.addAll(extents);
                item.hasLocation = true;
            }
        }

        private void parseIprp(int payload, int end) throws IOException {
            int pos = payload;
            while (pos + 8 <= end) {
                int size = boxSize(meta, pos);
                String type = fourCc(meta, pos + 4);
                int boxEnd = pos + size;
                int p = pos + headerSize(meta, pos);
                if ("ipco".equals(type)) {
                    ipcoStart = p;
                    ipcoEnd = boxEnd;
                    int q = p;
                    while (q + 8 <= boxEnd) {
                        int propertySize = boxSize(meta, q);
                        properties.add(new int[] {q, q + propertySize});
                        q += propertySize;
                    }
                } else if ("ipma".equals(type)) {
                    ipmaStart = p;
                    ipmaEnd = boxEnd;
                    int version = meta[p] & 0xFF;
                    int flags = (int) (u32(meta, p) & 0xFFFFFF);
                    int q = p + 4;
                    long count = u32(meta, q);
                    q += 4;
                    for (long i = 0; i < count; i++) {
                        long id;
                        if (version < 1) {
                            id = u16(meta, q);
                            q += 2;
                        } else {
                            id = u32(meta, q);
                            q += 4;
                        }
                        int associations = meta[q++] & 0xFF;
                        List<Integer> indexes = new ArrayList<>();
                        for (int a = 0; a < associations; a++) {
                            int index;
                            if ((flags & 1) != 0) {
                                index = u16(meta, q) & 0x7FFF;
                                q += 2;
                            } else {
                                index = meta[q++] & 0x7F;
                            }
                            indexes.add(index);
                        }
                        itemProperties.put(id, indexes);
                    }
                    if (q > boxEnd) {
                        throw new IOException("Damaged ipma");
                    }
                }
                pos = boxEnd;
            }
            if (ipcoStart < 0 || ipmaStart < 0) {
                throw new IOException("No item properties");
            }
        }

        private void requireSelfContained(int payload, int end) throws IOException {
            // dinf > dref > entries; each must have the self-contained flag.
            int pos = payload;
            while (pos + 8 <= end) {
                int size = boxSize(meta, pos);
                if ("dref".equals(fourCc(meta, pos + 4))) {
                    int p = pos + headerSize(meta, pos) + 8;
                    while (p + 12 <= pos + size) {
                        int entrySize = boxSize(meta, p);
                        long flags = u32(meta, p + headerSize(meta, p)) & 0xFFFFFF;
                        if ((flags & 1) == 0) {
                            throw new IOException("External data");
                        }
                        p += entrySize;
                    }
                }
                pos += size;
            }
        }

        /** Writes each kept item's data, cleaned, to the new mdat body or the new idat. */
        void writeItemData(@NonNull OutputStream body) throws IOException {
            long bodyOffset = 0;
            for (Item item : items.values()) {
                if (!kept.contains(item.id) || !item.hasLocation) {
                    continue;
                }
                byte[] data = cleanItemData(item, readItem(item));
                if (item.constructionMethod == 1) {
                    item.newOffset = newIdat.size();
                    newIdat.write(data);
                } else {
                    item.newOffset = bodyOffset;
                    body.write(data);
                    bodyOffset += data.length;
                }
                item.newLength = data.length;
            }
        }

        @NonNull
        private byte[] readItem(@NonNull Item item) throws IOException {
            long total = 0;
            for (long[] extent : item.extents) {
                total += extent[1];
            }
            if (total > MAX_ITEM_BYTES) {
                throw new IOException("Item too large");
            }
            byte[] data = new byte[(int) total];
            int at = 0;
            for (long[] extent : item.extents) {
                int length = (int) extent[1];
                if (item.constructionMethod == 1) {
                    if (idatPayload < 0 || extent[0] + length > idatEnd - idatPayload) {
                        throw new IOException("Item outside idat");
                    }
                    System.arraycopy(meta, idatPayload + (int) extent[0], data, at, length);
                } else {
                    if (extent[0] + length > in.length()) {
                        throw new IOException("Item outside the file");
                    }
                    in.seek(extent[0]);
                    in.readFully(data, at, length);
                }
                at += length;
            }
            return data;
        }

        @NonNull
        private byte[] cleanItemData(@NonNull Item item, @NonNull byte[] data) throws IOException {
            switch (item.type) {
                case "hvc1": {
                    byte[] hvcC = property(item.id, "hvcC");
                    if (hvcC == null) {
                        throw new IOException("HEVC item without hvcC");
                    }
                    int lengthSize = (hvcC[headerSize(hvcC, 0) + 21] & 3) + 1;
                    return filterHevcNalUnits(data, lengthSize);
                }
                case "av01":
                    return filterAv1Obus(data);
                default:
                    return data;
            }
        }

        @Nullable
        private byte[] property(long itemId, @NonNull String type) {
            List<Integer> indexes = itemProperties.get(itemId);
            if (indexes == null) {
                return null;
            }
            for (int index : indexes) {
                if (index >= 1 && index <= properties.size()) {
                    int[] box = properties.get(index - 1);
                    if (type.equals(fourCc(meta, box[0] + 4))) {
                        return java.util.Arrays.copyOfRange(meta, box[0], box[1]);
                    }
                }
            }
            return null;
        }

        /** The new meta box, with item locations pointing past {@code ftypLength}, meta and mdat's header. */
        @NonNull
        byte[] writeMeta(int ftypLength, long mdatHeader) throws IOException {
            // iloc's size depends only on how many items there are, so meta's size is known
            // before the offsets that depend on it are written.
            byte[] probe = buildMeta(0);
            long dataStart = ftypLength + probe.length + mdatHeader;
            byte[] built = buildMeta(dataStart);
            if (built.length != probe.length) {
                throw new IOException("meta size changed");
            }
            return built;
        }

        @NonNull
        private byte[] buildMeta(long dataStart) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeU32(body, metaVersionFlags);
            for (Object[] child : children) {
                String type = (String) child[0];
                int start = (int) child[1];
                int end = (int) child[2];
                switch (type) {
                    case "hdlr":
                        body.write(hdlr());
                        break;
                    case "pitm":
                    case "dinf":
                        body.write(meta, start, end - start);
                        break;
                    case "iinf":
                        body.write(iinf());
                        break;
                    case "iloc":
                        body.write(iloc(dataStart));
                        break;
                    case "iref":
                        body.write(iref());
                        break;
                    case "iprp":
                        body.write(iprp());
                        break;
                    case "idat":
                        body.write(box("idat", newIdat.toByteArray()));
                        break;
                    case "grpl":
                        body.write(grpl(start, end));
                        break;
                    default:
                        // xml, bxml and anything unknown: not the picture.
                        break;
                }
            }
            return box("meta", body.toByteArray());
        }

        @NonNull
        private byte[] hdlr() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            writeU32(b, 0); // version and flags
            writeU32(b, 0); // pre_defined
            b.write(ascii("pict"));
            writeU32(b, 0);
            writeU32(b, 0);
            writeU32(b, 0);
            b.write(0); // empty name
            return box("hdlr", b.toByteArray());
        }

        @NonNull
        private byte[] iinf() throws IOException {
            boolean wide = false;
            int count = 0;
            for (Item item : items.values()) {
                if (kept.contains(item.id)) {
                    count++;
                    wide |= item.id > 0xFFFF;
                }
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int version = count > 0xFFFF ? 1 : 0;
            writeU32(b, (long) version << 24);
            if (version == 0) {
                writeU16(b, count);
            } else {
                writeU32(b, count);
            }
            for (Item item : items.values()) {
                if (!kept.contains(item.id)) {
                    continue;
                }
                int infeVersion = wide || item.infeVersion == 3 ? 3 : 2;
                ByteArrayOutputStream e = new ByteArrayOutputStream();
                writeU32(e, ((long) infeVersion << 24) | item.infeFlags);
                if (infeVersion == 2) {
                    writeU16(e, (int) item.id);
                } else {
                    writeU32(e, item.id);
                }
                writeU16(e, 0); // protection index
                e.write(ascii(item.type));
                e.write(0); // empty name
                b.write(box("infe", e.toByteArray()));
            }
            return box("iinf", b.toByteArray());
        }

        @NonNull
        private byte[] iloc(long dataStart) throws IOException {
            List<Item> located = new ArrayList<>();
            boolean wide = false;
            boolean usesIdat = false;
            for (Item item : items.values()) {
                if (kept.contains(item.id) && item.hasLocation) {
                    located.add(item);
                    wide |= item.id > 0xFFFF;
                    usesIdat |= item.constructionMethod == 1;
                }
            }
            int version = wide ? 2 : (usesIdat || ilocVersion >= 1 ? 1 : 0);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            writeU32(b, (long) version << 24);
            b.write(0x44); // offset_size 4, length_size 4
            b.write(0x00); // base_offset_size 0, index_size 0
            if (version < 2) {
                writeU16(b, located.size());
            } else {
                writeU32(b, located.size());
            }
            for (Item item : located) {
                if (version < 2) {
                    writeU16(b, (int) item.id);
                } else {
                    writeU32(b, item.id);
                }
                if (version >= 1) {
                    writeU16(b, item.constructionMethod);
                }
                writeU16(b, 0); // data_reference_index: this file
                writeU16(b, 1); // one extent
                long offset = item.constructionMethod == 1 ? item.newOffset : dataStart + item.newOffset;
                writeU32(b, offset);
                writeU32(b, item.newLength);
            }
            return box("iloc", b.toByteArray());
        }

        @NonNull
        private byte[] iref() throws IOException {
            boolean wide = irefVersion == 1;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            writeU32(b, (long) irefVersion << 24);
            int pos = irefStart;
            while (pos + 8 <= irefEnd) {
                int size = boxSize(meta, pos);
                String type = fourCc(meta, pos + 4);
                int p = pos + headerSize(meta, pos);
                long from = wide ? u32(meta, p) : u16(meta, p);
                p += wide ? 4 : 2;
                int count = u16(meta, p);
                p += 2;
                List<Long> to = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    long id = wide ? u32(meta, p) : u16(meta, p);
                    p += wide ? 4 : 2;
                    if (kept.contains(id)) {
                        to.add(id);
                    }
                }
                // A derived image needs every input it names, in order; drop it whole otherwise.
                if (kept.contains(from) && to.size() == count && !to.isEmpty()) {
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    writeId(r, from, wide);
                    writeU16(r, to.size());
                    for (long id : to) {
                        writeId(r, id, wide);
                    }
                    b.write(box(type, r.toByteArray()));
                }
                pos += size;
            }
            return box("iref", b.toByteArray());
        }

        @NonNull
        private byte[] iprp() throws IOException {
            ByteArrayOutputStream ipco = new ByteArrayOutputStream();
            for (int[] property : properties) {
                String type = fourCc(meta, property[0] + 4);
                if (!PROPERTY_KEEP.contains(type)) {
                    // Same index, no content.
                    writeU32(ipco, 8);
                    ipco.write(ascii("free"));
                    continue;
                }
                byte[] copy = java.util.Arrays.copyOfRange(meta, property[0], property[1]);
                if ("colr".equals(type)) {
                    int p = headerSize(copy, 0);
                    String colourType = fourCc(copy, p);
                    if ("prof".equals(colourType) || "rICC".equals(colourType)) {
                        ImageContainerMetadata.clearIccHeader(copy, p + 4);
                    }
                } else if ("hvcC".equals(type)) {
                    copy = filterHvcC(copy);
                } else if ("av1C".equals(type)) {
                    copy = filterAv1C(copy);
                }
                ipco.write(copy);
            }
            ByteArrayOutputStream ipma = new ByteArrayOutputStream();
            int version = meta[ipmaStart] & 0xFF;
            int flags = (int) (u32(meta, ipmaStart) & 0xFFFFFF);
            writeU32(ipma, ((long) version << 24) | flags);
            int count = 0;
            for (Long id : itemProperties.keySet()) {
                if (kept.contains(id)) {
                    count++;
                }
            }
            writeU32(ipma, count);
            for (Map.Entry<Long, List<Integer>> entry : itemProperties.entrySet()) {
                if (!kept.contains(entry.getKey())) {
                    continue;
                }
                writeId(ipma, entry.getKey(), version >= 1);
                ipma.write(entry.getValue().size());
                for (int index : entry.getValue()) {
                    // The essential bit is kept from the source.
                    boolean essential = essential(entry.getKey(), index, flags, version);
                    if ((flags & 1) != 0) {
                        writeU16(ipma, (essential ? 0x8000 : 0) | index);
                    } else {
                        ipma.write((essential ? 0x80 : 0) | index);
                    }
                }
            }
            ByteArrayOutputStream iprp = new ByteArrayOutputStream();
            iprp.write(box("ipco", ipco.toByteArray()));
            iprp.write(box("ipma", ipma.toByteArray()));
            return box("iprp", iprp.toByteArray());
        }

        /** Reads an association's essential bit again from the source ipma. */
        private boolean essential(long itemId, int index, int flags, int version) {
            int q = ipmaStart + 8;
            while (q < ipmaEnd) {
                long id;
                if (version < 1) {
                    id = u16(meta, q);
                    q += 2;
                } else {
                    id = u32(meta, q);
                    q += 4;
                }
                int associations = meta[q++] & 0xFF;
                for (int a = 0; a < associations; a++) {
                    boolean bit;
                    int i;
                    if ((flags & 1) != 0) {
                        bit = (meta[q] & 0x80) != 0;
                        i = u16(meta, q) & 0x7FFF;
                        q += 2;
                    } else {
                        bit = (meta[q] & 0x80) != 0;
                        i = meta[q] & 0x7F;
                        q += 1;
                    }
                    if (id == itemId && i == index) {
                        return bit;
                    }
                }
            }
            return false;
        }

        @NonNull
        private byte[] grpl(int start, int end) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int pos = start + headerSize(meta, start);
            while (pos + 8 <= end) {
                int size = boxSize(meta, pos);
                int p = pos + headerSize(meta, pos) + 4; // version and flags
                long count = u32(meta, p + 4);
                if (p + 8 + count * 4 > pos + size) {
                    throw new IOException("Damaged entity group");
                }
                boolean allKept = true;
                for (long i = 0; i < count; i++) {
                    long id = u32(meta, p + 8 + (int) (i * 4));
                    if (!kept.contains(id)) {
                        allKept = false;
                        break;
                    }
                }
                // A group (alternatives, a gain map's base and tone map) keeps every member or goes.
                if (allKept) {
                    b.write(meta, pos, size);
                }
                pos += size;
            }
            return box("grpl", b.toByteArray());
        }
    }

    // ---- coded data -----------------------------------------------------------------------------

    /** Drops SEI NAL units that carry anything but how to show the picture. */
    @VisibleForTesting
    @NonNull
    static byte[] filterHevcNalUnits(@NonNull byte[] data, int lengthSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int pos = 0;
        while (pos < data.length) {
            if (pos + lengthSize > data.length) {
                throw new IOException("Damaged NAL length");
            }
            long length = uN(data, pos, lengthSize);
            int start = pos + lengthSize;
            if (length < 2 || start + length > data.length) {
                throw new IOException("Damaged NAL unit");
            }
            if (keepHevcNal(data, start, (int) length)) {
                out.write(data, pos, lengthSize + (int) length);
            }
            pos = start + (int) length;
        }
        return out.toByteArray();
    }

    private static boolean keepHevcNal(@NonNull byte[] data, int start, int length) {
        int type = (data[start] >> 1) & 0x3F;
        if (type != 39 && type != 40) {
            return true;
        }
        // Read the SEI messages with the emulation prevention bytes removed.
        byte[] rbsp = unescape(data, start + 2, length - 2);
        int pos = 0;
        while (pos < rbsp.length) {
            if (pos == rbsp.length - 1 && (rbsp[pos] & 0xFF) == 0x80) {
                return true; // rbsp trailing bits
            }
            int payloadType = 0;
            while (pos < rbsp.length && (rbsp[pos] & 0xFF) == 0xFF) {
                payloadType += 255;
                pos++;
            }
            if (pos >= rbsp.length) {
                return false;
            }
            payloadType += rbsp[pos++] & 0xFF;
            int payloadSize = 0;
            while (pos < rbsp.length && (rbsp[pos] & 0xFF) == 0xFF) {
                payloadSize += 255;
                pos++;
            }
            if (pos >= rbsp.length) {
                return false;
            }
            payloadSize += rbsp[pos++] & 0xFF;
            if (!SEI_KEEP.contains(payloadType) || pos + payloadSize > rbsp.length) {
                return false;
            }
            pos += payloadSize;
        }
        return true;
    }

    @NonNull
    private static byte[] unescape(@NonNull byte[] data, int start, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        int zeros = 0;
        for (int i = start; i < start + length; i++) {
            int b = data[i] & 0xFF;
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            zeros = b == 0 ? zeros + 1 : 0;
            out.write(b);
        }
        return out.toByteArray();
    }

    /** hvcC with its SEI arrays filtered the same way as the coded data. */
    @NonNull
    private static byte[] filterHvcC(@NonNull byte[] box) throws IOException {
        int header = headerSize(box, 0);
        int pos = header + 22;
        if (pos >= box.length) {
            throw new IOException("Damaged hvcC");
        }
        int arrays = box[pos] & 0xFF;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(box, header, 22);
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int keptArrays = 0;
        pos++;
        for (int a = 0; a < arrays; a++) {
            int flagsAndType = box[pos] & 0xFF;
            int count = u16(box, pos + 1);
            pos += 3;
            ByteArrayOutputStream units = new ByteArrayOutputStream();
            int keptUnits = 0;
            for (int n = 0; n < count; n++) {
                int length = u16(box, pos);
                if (pos + 2 + length > box.length) {
                    throw new IOException("Damaged hvcC");
                }
                if (length >= 2 && keepHevcNal(box, pos + 2, length)) {
                    units.write(box, pos, 2 + length);
                    keptUnits++;
                }
                pos += 2 + length;
            }
            if (keptUnits > 0) {
                kept.write(flagsAndType);
                writeU16(kept, keptUnits);
                kept.write(units.toByteArray());
                keptArrays++;
            }
        }
        body.write(keptArrays);
        body.write(kept.toByteArray());
        return box("hvcC", body.toByteArray());
    }

    /** Drops AV1 metadata OBUs other than HDR and scalability information. */
    @VisibleForTesting
    @NonNull
    static byte[] filterAv1Obus(@NonNull byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int pos = 0;
        while (pos < data.length) {
            int start = pos;
            int header = data[pos] & 0xFF;
            int type = (header >> 3) & 0xF;
            boolean extension = (header & 0x04) != 0;
            boolean hasSize = (header & 0x02) != 0;
            pos += extension ? 2 : 1;
            long size;
            if (hasSize) {
                long[] leb = leb128(data, pos);
                size = leb[0];
                pos = (int) leb[1];
            } else {
                size = data.length - pos;
            }
            if (pos > data.length || pos + size > data.length) {
                throw new IOException("Damaged OBU");
            }
            int end = pos + (int) size;
            boolean keep = true;
            if (type == 5) {
                long metadataType = size > 0 ? leb128(data, pos)[0] : -1;
                keep = AV1_METADATA_KEEP.contains((int) metadataType);
            }
            if (keep) {
                out.write(data, start, end - start);
            }
            pos = end;
        }
        return out.toByteArray();
    }

    @NonNull
    private static byte[] filterAv1C(@NonNull byte[] box) throws IOException {
        int header = headerSize(box, 0);
        if (box.length < header + 4) {
            throw new IOException("Damaged av1C");
        }
        byte[] configObus = java.util.Arrays.copyOfRange(box, header + 4, box.length);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(box, header, 4);
        body.write(filterAv1Obus(configObus));
        return box("av1C", body.toByteArray());
    }

    /** {value, next position}. */
    @NonNull
    private static long[] leb128(@NonNull byte[] data, int pos) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            if (pos >= data.length) {
                throw new IOException("Damaged leb128");
            }
            int b = data[pos++] & 0xFF;
            value |= (long) (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) {
                return new long[] {value, pos};
            }
        }
        throw new IOException("Damaged leb128");
    }

    // ---- bytes ----------------------------------------------------------------------------------

    @NonNull
    private static byte[] box(@NonNull String type, @NonNull byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 8);
        writeU32(b, payload.length + 8L);
        b.write(ascii(type));
        b.write(payload);
        return b.toByteArray();
    }

    private static int boxSize(@NonNull byte[] data, int pos) throws IOException {
        if (pos + 8 > data.length) {
            throw new IOException("Damaged box");
        }
        long size = u32(data, pos);
        if (size == 1) {
            if (pos + 16 > data.length) {
                throw new IOException("Damaged box");
            }
            size = (u32(data, pos + 8) << 32) | u32(data, pos + 12);
        } else if (size == 0) {
            size = data.length - pos;
        }
        if (size < 8 || pos + size > data.length) {
            throw new IOException("Damaged box");
        }
        return (int) size;
    }

    private static int headerSize(@NonNull byte[] data, int pos) {
        return u32(data, pos) == 1 ? 16 : 8;
    }

    private static void writeId(@NonNull OutputStream out, long id, boolean wide) throws IOException {
        if (wide) {
            writeU32(out, id);
        } else {
            writeU16(out, (int) id);
        }
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

    private static void writeU64(@NonNull OutputStream out, long value) throws IOException {
        writeU32(out, value >>> 32);
        writeU32(out, value & 0xFFFFFFFFL);
    }

    private static int u16(@NonNull byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    }

    private static long u32(@NonNull byte[] data, int pos) {
        return ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
    }

    private static long uN(@NonNull byte[] data, int pos, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | (data[pos + i] & 0xFF);
        }
        return value;
    }

    @NonNull
    private static String fourCc(@NonNull byte[] data, int pos) {
        return new String(data, pos, 4, StandardCharsets.ISO_8859_1);
    }

    @NonNull
    private static byte[] ascii(@NonNull String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
