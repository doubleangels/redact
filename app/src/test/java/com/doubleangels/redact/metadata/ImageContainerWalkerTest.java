package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The JPEG, PNG and WebP walk on hand-built files, without Robolectric: every kind of segment and
 * chunk it names, what a clean keeps, and damaged files that must never count as clean.
 */
public class ImageContainerWalkerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ---- building test files ------------------------------------------------------------------

    private static byte[] a(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    private static byte[] b(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] be16(int v) {
        return b(v >> 8, v);
    }

    private static byte[] be32(long v) {
        return ByteBuffer.allocate(4).putInt((int) v).array();
    }

    private static byte[] le32(long v) {
        return b((int) v, (int) (v >> 8), (int) (v >> 16), (int) (v >> 24));
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        byte[] buffer = new byte[data.length + 64];
        int n = d.deflate(buffer);
        d.end();
        return Arrays.copyOf(buffer, n);
    }

    private static byte[] seg(int marker, byte[] payload) {
        return cat(b(0xFF, marker), be16(payload.length + 2), payload);
    }

    private static final byte[] SOF = seg(0xC0, b(8, 0, 16, 0, 16, 1, 1, 0x11, 0));
    private static final byte[] SOS = seg(0xDA, b(1, 1, 0, 0, 0x3F, 0));
    /** Entropy data with a stuffed byte, a restart marker and a fill byte before the EOI. */
    private static final byte[] ENTROPY = b(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0xFF);
    private static final byte[] EOI = b(0xFF, 0xD9);

    /** A JPEG with {@code segments} between its start and its frame. */
    private static byte[] jpeg(byte[]... segments) {
        return cat(b(0xFF, 0xD8), cat(segments), SOF, SOS, ENTROPY, EOI);
    }

    private static byte[] pngChunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(a(type));
        crc.update(data);
        return cat(be32(data.length), a(type), data, be32(crc.getValue()));
    }

    private static final byte[] PNG_SIGNATURE = b(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n');

    private static byte[] png(byte[]... chunks) {
        return cat(PNG_SIGNATURE, pngChunk("IHDR", b(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)), cat(chunks),
                pngChunk("IDAT", b(1, 2, 3, 4)), pngChunk("IEND", new byte[0]));
    }

    private static byte[] webpChunk(String type, byte[] data) {
        return cat(a(type), le32(data.length), data, (data.length & 1) == 1 ? new byte[1] : new byte[0]);
    }

    private static byte[] webp(byte[]... chunks) {
        byte[] body = cat(chunks);
        return cat(a("RIFF"), le32(body.length + 4), a("WEBP"), body);
    }

    private static byte[] iccTag(String type, byte[] body) {
        return cat(a(type), new byte[4], body);
    }

    private static byte[] desc(String text) {
        return iccTag("desc", cat(be32(text.length()), a(text)));
    }

    private static byte[] mluc(String text) {
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16BE);
        return iccTag("mluc", cat(be32(1), be32(12), a("enUS"), be32(utf16.length), be32(28), utf16));
    }

    private static byte[] text(String text) {
        return iccTag("text", a(text));
    }

    /**
     * An ICC profile whose header names a CMM, a date, a device and a creator, with the tags given
     * as signature and data pairs.
     */
    private static byte[] icc(Object... tags) {
        byte[] header = new byte[128];
        System.arraycopy(a("CMMX"), 0, header, 4, 4);
        Arrays.fill(header, 24, 36, (byte) 7);
        System.arraycopy(a("DEVMODEL"), 0, header, 48, 8);
        System.arraycopy(a("CRTR"), 0, header, 80, 4);
        int count = tags.length / 2;
        int offset = 128 + 4 + count * 12;
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            byte[] tag = (byte[]) tags[2 * i + 1];
            byte[] entry = cat(a((String) tags[2 * i]), be32(offset + data.size()), be32(tag.length));
            entries.write(entry, 0, entry.length);
            data.write(tag, 0, tag.length);
        }
        return cat(header, be32(count), entries.toByteArray(), data.toByteArray());
    }

    private File file(byte[] data) throws IOException {
        File f = folder.newFile();
        Files.write(f.toPath(), data);
        return f;
    }

    private ImageContainerMetadata.Report scan(byte[] data) throws IOException {
        return ImageContainerMetadata.scan(file(data));
    }

    /** Sanitizes {@code data} and returns the bytes written. */
    private byte[] sanitize(byte[] data) throws IOException {
        File target = folder.newFile();
        ImageContainerMetadata.sanitize(file(data), target);
        return Files.readAllBytes(target.toPath());
    }

    private static List<String> keys(List<ImageContainerMetadata.Finding> findings) {
        List<String> keys = new ArrayList<>();
        for (ImageContainerMetadata.Finding f : findings) {
            keys.add(f.key);
        }
        return keys;
    }

    private static ImageContainerMetadata.Finding finding(List<ImageContainerMetadata.Finding> findings, String key) {
        for (ImageContainerMetadata.Finding f : findings) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        fail("No " + key + " in " + findings);
        return null;
    }

    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ---- JPEG ---------------------------------------------------------------------------------

    @Test
    public void plainJpeg_isClean_andSanitizesToItself() throws Exception {
        byte[] jpeg = jpeg();

        assertTrue(scan(jpeg).isClean());
        assertArrayEquals(jpeg, sanitize(jpeg));
    }

    @Test
    public void jpegAppSegments_areEachNamed() throws Exception {
        byte[] jfifWithThumbnail = cat(a("JFIF\0"), b(1, 2, 0, 0, 1, 0, 1, 1, 1), b(9, 9, 9));
        byte[] xmpExtended = cat(a("http://ns.adobe.com/xmp/extension/\0"), new byte[40],
                a("<x <exif:GPSLatitude>1</exif:GPSLatitude>"));
        ImageContainerMetadata.Report r = scan(jpeg(
                seg(0xE0, jfifWithThumbnail),
                seg(0xE0, cat(a("JFXX\0"), b(0x10, 1, 2))),
                seg(0xE0, b(1, 2)),
                seg(0xE1, xmpExtended),
                seg(0xE1, cat(a("QVCI\0"), b(1, 2))),
                seg(0xE1, b(1, 2, 3)),
                seg(0xE2, cat(a("MPF\0"), b(1, 2))),
                seg(0xE2, cat(a("FPXR\0"), b(1, 2))),
                seg(0xE2, a("XYZ")),
                seg(0xE2, b(1)),
                seg(0xEC, cat(a("Ducky\0"), b(1, 2))),
                seg(0xEB, cat(a("JP"), b(0, 0), a("jumb c2pa"))),
                seg(0xEB, cat(a("JP"), b(0, 0), a("jumb"))),
                seg(0xFE, a("shot on a phone")),
                seg(0xF0, b(1, 2))));

        assertTrue(r.recognized);
        assertEquals(Arrays.asList("JFIF_THUMBNAIL", "JFIF_THUMBNAIL", "APP0_DATA", "XMP_EXTENDED", "APP1_QVCI",
                "APP1_DATA", "MULTI_PICTURE_INDEX", "FLASHPIX_DATA", "APP2_XYZ", "APP2_DATA", "APP12_DUCKY",
                "CONTENT_CREDENTIALS", "JUMBF_DATA", "JPEG_COMMENT", "JPEG_SEGMENT_F0"), keys(r.findings));
        assertEquals(ImageContainerMetadata.Kind.LOCATION, finding(r.findings, "XMP_EXTENDED").kind);
        assertEquals(ImageContainerMetadata.Kind.NAME, finding(r.findings, "APP12_DUCKY").kind);
        assertEquals("shot on a phone", finding(r.findings, "JPEG_COMMENT").text);
    }

    @Test
    public void jpegSanitize_keepsJfifWithoutItsThumbnail_andAdobeColourFlags() throws Exception {
        byte[] jfif = cat(a("JFIF\0"), b(1, 2, 0, 0, 1, 0, 1, 1, 1), b(9, 9, 9));
        byte[] adobe = cat(a("Adobe"), b(0, 100, 0, 0, 0, 0, 2), a("EXTRA"));
        byte[] out = sanitize(jpeg(seg(0xE0, jfif), seg(0xEE, adobe), seg(0xFE, a("note"))));

        assertTrue(scan(out).isClean());
        assertTrue(contains(out, seg(0xE0, cat(a("JFIF\0"), b(1, 2, 0, 0, 1, 0, 1, 0, 0)))));
        assertTrue(contains(out, seg(0xEE, Arrays.copyOf(adobe, 12))));
        assertFalse(contains(out, a("EXTRA")));
        assertFalse(contains(out, a("note")));
    }

    @Test
    public void jpegIccProfile_isKeptWithItsHeaderCleared_andItsNamesListed() throws Exception {
        byte[] profile = icc("desc", desc("Studio Monitor"), "dmnd", text("Acme"), "dmdd", mluc("Cam 1"));
        byte[] jpeg = jpeg(seg(0xE2, cat(a("ICC_PROFILE\0"), b(1, 1), profile)));

        ImageContainerMetadata.Report r = scan(jpeg);
        assertTrue(r.isClean());
        assertEquals("Studio Monitor", finding(r.kept, "ICC_PROFILE").text);
        assertEquals("Acme Cam 1", finding(r.kept, "ICC_PROFILE_DEVICE").text);

        byte[] out = sanitize(jpeg);
        assertFalse(contains(out, a("CMMX")));
        assertFalse(contains(out, a("DEVMODEL")));
        assertFalse(contains(out, a("CRTR")));
        assertTrue(contains(out, a("Studio Monitor")));
    }

    @Test
    public void photoshopResources_areReadOneByOne() throws Exception {
        byte[] iptc = cat(b(0x1C, 2, 80), be16(4), a("Jane"), b(0x1C, 2, 90), be16(5), a("Paris"),
                b(0x1C, 2, 200), be16(2), a("xy"), b(0x1C, 2, 0), be16(2), b(0, 4));
        byte[] xmp = a("<x <dc:creator>Jane</dc:creator>");
        ImageContainerMetadata.Report r = scan(jpeg(seg(0xED, cat(a("Photoshop 3.0\0"),
                resource(0x0404, iptc), resource(0x0409, b(1, 2, 3)), resource(0x0424, xmp),
                resource(0x03ED, b(1, 2))))));

        assertEquals(Arrays.asList("IPTC_BYLINE", "LOCATION_CITY", "IPTC_200", "PHOTOSHOP_THUMBNAIL", "XMP",
                "PHOTOSHOP_RESOURCES"), keys(r.findings));
        assertEquals("Paris", finding(r.findings, "LOCATION_CITY").text);
        assertEquals(ImageContainerMetadata.Kind.NAME, finding(r.findings, "XMP").kind);
        assertTrue(r.has(ImageContainerMetadata.Kind.LOCATION));
    }

    @Test
    public void photoshopSegmentsWithoutReadableText_stillCountAsMetadata() throws Exception {
        byte[] envelopeOnly = cat(b(0x1C, 1, 90), be16(3), a("abc"));
        byte[] extendedLength = cat(b(0x1C, 2, 80), be16(0x8004), a("Jane"));
        byte[] runsPastSegment = cat(a("8BIM"), be16(0x0409), b(0, 0), be32(1000), b(1, 2));
        ImageContainerMetadata.Report r = scan(jpeg(
                seg(0xED, a("Not Photoshop")),
                seg(0xED, cat(a("Photoshop 3.0\0"), a("junk"))),
                seg(0xED, cat(a("Photoshop 3.0\0"), resource(0x0404, envelopeOnly))),
                seg(0xED, cat(a("Photoshop 3.0\0"), resource(0x0404, extendedLength))),
                seg(0xED, cat(a("Photoshop 3.0\0"), runsPastSegment))));

        assertEquals(Arrays.asList("APP13_DATA", "APP13_DATA", "IPTC", "IPTC", "PHOTOSHOP_THUMBNAIL"),
                keys(r.findings));
        assertEquals(2, finding(r.findings, "PHOTOSHOP_THUMBNAIL").bytes);
    }

    private static byte[] resource(int id, byte[] data) {
        return cat(a("8BIM"), be16(id), b(0, 0), be32(data.length), data,
                (data.length & 1) == 1 ? new byte[1] : new byte[0]);
    }

    @Test
    public void damagedJpegs_areNeverClean() throws Exception {
        byte[][] damaged = {
                cat(b(0xFF, 0xD8), seg(0xFE, a("x")), b(0x12, 0x34)), // no marker
                cat(b(0xFF, 0xD8), SOS, ENTROPY, EOI), // scan before frame
                cat(b(0xFF, 0xD8, 0xFF, 0xE1, 0, 1)), // bad segment length
                cat(b(0xFF, 0xD8, 0xFF, 0xD0), SOF, SOS, ENTROPY, EOI), // restart outside a scan
                cat(b(0xFF, 0xD8), SOF, SOS, b(1, 2, 3)), // no end of image
                cat(b(0xFF, 0xD8), seg(0xFE, a("cut")), b(0xFF, 0xE1, 0, 40, 1)), // truncated segment
        };
        for (byte[] jpeg : damaged) {
            ImageContainerMetadata.Report r = scan(jpeg);
            assertEquals(ImageContainerMetadata.Format.JPEG, r.format);
            assertFalse(r.isClean());
        }
        assertFalse(scan(damaged[5]).recognized);
        assertEquals("JPEG_COMMENT", scan(damaged[5]).findings.get(0).key);
    }

    @Test
    public void manySegments_endInOneMoreMetadataRow() throws Exception {
        byte[][] comments = new byte[405][];
        Arrays.fill(comments, seg(0xFE, a("c")));
        ImageContainerMetadata.Report r = scan(jpeg(comments));

        assertEquals(401, r.findings.size());
        assertEquals("MORE_METADATA", r.findings.get(400).key);
    }

    // ---- JPEG trailers ------------------------------------------------------------------------

    @Test
    public void paddingAfterTheEnd_isNotMetadata() throws Exception {
        assertTrue(scan(cat(jpeg(), new byte[64], b(0xFF, 0xFF))).isClean());
    }

    @Test
    public void unknownDataAfterTheEnd_isFound_andNotWritten() throws Exception {
        byte[] jpeg = cat(jpeg(), a("leftover bytes"));

        assertEquals(Arrays.asList("DATA_AFTER_IMAGE"), keys(scan(jpeg).findings));
        assertArrayEquals(jpeg(), sanitize(jpeg));
    }

    @Test
    public void motionPhotoVideo_isFoundAfterThePicture() throws Exception {
        byte[] video = cat(be32(16), a("ftypmp42"), b(0, 0, 0, 0));

        assertEquals(video.length, finding(scan(cat(jpeg(), video)).findings, "EMBEDDED_VIDEO").bytes);
    }

    @Test
    public void samsungTrailer_listsItsEntries() throws Exception {
        byte[] trailer = cat(a("Image_UTC_Data 1700000000 MCC_Data 310 junk"), a("SEFH"), new byte[8], a("SEFT"));
        ImageContainerMetadata.Finding f = finding(scan(cat(jpeg(), trailer)).findings, "SAMSUNG_TRAILER");

        assertEquals("Image_UTC_Data, MCC_Data", f.text);
        assertEquals(ImageContainerMetadata.Kind.DATE, f.kind);

        ImageContainerMetadata.Finding unnamed =
                finding(scan(cat(jpeg(), a("12345678SEFT"))).findings, "SAMSUNG_TRAILER");
        assertNull(unnamed.text);
        assertEquals(ImageContainerMetadata.Kind.OTHER, unnamed.kind);
    }

    // ---- PNG ----------------------------------------------------------------------------------

    @Test
    public void pngChunks_areEachNamedWithTheirText() throws Exception {
        ImageContainerMetadata.Report r = scan(png(
                pngChunk("pHYs", b(0, 0, 0, 1, 0, 0, 0, 1, 1)),
                pngChunk("eXIf", b(1, 2)),
                pngChunk("tEXt", cat(a("Author\0"), a("Jane"))),
                pngChunk("tEXt", cat(a("\0"), a("no keyword"))),
                pngChunk("zTXt", cat(a("Creation Time\0"), b(0), deflate(a("2024-05-06")))),
                pngChunk("zTXt", cat(a("Comment\0"), b(0), b(1, 2, 3))),
                pngChunk("iTXt", cat(a("Software\0"), b(1, 0), a("en\0\0"), deflate(a("Editor")))),
                pngChunk("iTXt", cat(a("XML:com.adobe.xmp\0"), b(0, 0), a("\0\0"),
                        a("<x <exif:GPSLongitude>2</exif:GPSLongitude>"))),
                pngChunk("iTXt", a("Title\0")),
                pngChunk("tEXt", cat(a("Raw profile type exif\0"), a("\nexif\n  12\n4578"))),
                pngChunk("tEXt", cat(a("GPS Position\0"), a("here"))),
                pngChunk("tEXt", cat(a("Misc\0"), a("x"))),
                pngChunk("tIME", b(0x07, 0xE8, 1, 2, 3, 4, 5)),
                pngChunk("tIME", b(0x07, 0xE8, 1)),
                pngChunk("caBX", b(1, 2)),
                pngChunk("prVt", b(1))));

        assertTrue(r.recognized);
        assertEquals(Arrays.asList("PNG_TEXT_AUTHOR", "PNG_TEXT", "PNG_TEXT_CREATION_TIME", "PNG_TEXT_COMMENT",
                "PNG_TEXT_SOFTWARE", "XMP", "PNG_TEXT_TITLE", "PNG_TEXT_RAW_PROFILE_TYPE_EXIF",
                "PNG_TEXT_GPS_POSITION", "PNG_TEXT_MISC", "PNG_MODIFIED_TIME", "PNG_MODIFIED_TIME",
                "CONTENT_CREDENTIALS", "PNG_CHUNK_PRVT"), keys(r.findings));
        List<ImageContainerMetadata.Finding> f = r.findings;
        assertEquals("Jane", f.get(0).text);
        assertEquals(ImageContainerMetadata.Kind.NAME, f.get(0).kind);
        assertEquals("2024-05-06", f.get(2).text);
        assertEquals(ImageContainerMetadata.Kind.DATE, f.get(2).kind);
        assertNull(f.get(3).text);
        assertEquals("Editor", f.get(4).text);
        assertEquals(ImageContainerMetadata.Kind.DEVICE, f.get(4).kind);
        assertEquals(ImageContainerMetadata.Kind.LOCATION, f.get(5).kind);
        assertNull(f.get(6).text);
        assertNull(f.get(7).text);
        assertEquals(ImageContainerMetadata.Kind.LOCATION, f.get(7).kind);
        assertEquals(ImageContainerMetadata.Kind.LOCATION, f.get(8).kind);
        assertEquals(ImageContainerMetadata.Kind.OTHER, f.get(9).kind);
        assertEquals("2024-01-02 03:04:05 UTC", f.get(10).text);
        assertNull(f.get(11).text);
    }

    @Test
    public void pngSanitize_keepsPictureChunksAndARecompressedProfile() throws Exception {
        byte[] profile = icc("desc", desc("Wide Gamut"));
        byte[] phys = pngChunk("pHYs", b(0, 0, 0, 1, 0, 0, 0, 1, 1));
        byte[] out = sanitize(png(phys, pngChunk("iCCP", cat(a("Mine\0"), b(0), deflate(profile))),
                pngChunk("tEXt", cat(a("Author\0"), a("Jane")))));

        ImageContainerMetadata.Report r = scan(out);
        assertTrue(r.isClean());
        assertEquals("Wide Gamut", finding(r.kept, "ICC_PROFILE").text);
        assertTrue(contains(out, phys));
        assertTrue(contains(out, a("ICC Profile")));
        assertFalse(contains(out, a("Mine")));
        assertFalse(contains(out, a("Jane")));
    }

    @Test
    public void unreadablePngProfiles_areMetadataToRemove() throws Exception {
        ImageContainerMetadata.Report r = scan(png(pngChunk("iCCP", a("abc")),
                pngChunk("iCCP", cat(a("abc\0"), b(0), b(1, 2, 3)))));

        assertEquals(Arrays.asList("PNG_CHUNK_ICCP", "PNG_CHUNK_ICCP"), keys(r.findings));
    }

    @Test
    public void pngTrailingData_isFound_butPaddingIsNot() throws Exception {
        assertEquals(Arrays.asList("DATA_AFTER_IMAGE"), keys(scan(cat(png(), a("tail"))).findings));
        assertTrue(scan(cat(png(), new byte[16])).isClean());
    }

    @Test
    public void damagedPngs_areNeverClean() throws Exception {
        byte[] header = pngChunk("IHDR", new byte[13]);
        byte[][] damaged = {
                cat(PNG_SIGNATURE, pngChunk("tEXt", a("a\0b")), header), // IHDR not first
                cat(PNG_SIGNATURE, header, pngChunk("12ab", b(1))), // bad type
                cat(PNG_SIGNATURE, header, be32(100), a("IDAT"), b(1, 2)), // truncated
                cat(PNG_SIGNATURE, header, be32(0x80000000L), a("IDAT")), // too large
        };
        for (byte[] png : damaged) {
            ImageContainerMetadata.Report r = scan(png);
            assertEquals(ImageContainerMetadata.Format.PNG, r.format);
            assertFalse(r.recognized);
        }
    }

    // ---- WebP ---------------------------------------------------------------------------------

    @Test
    public void webpScan_namesEveryChunkInsideAndOutsideFrames() throws Exception {
        byte[] profile = icc("desc", desc("sRGB odd"), "dmnd", text("Acme"));
        profile = Arrays.copyOf(profile, profile.length + 1); // an odd size, so a pad byte follows
        byte[] frame = cat(new byte[16], webpChunk("ALPH", b(1, 2, 3)), webpChunk("VP8 ", b(4, 5, 6, 7)),
                webpChunk("XTRA", b(8, 9)));
        ImageContainerMetadata.Report r = scan(webp(
                webpChunk("VP8X", b(0x2E, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                webpChunk("ICCP", profile),
                webpChunk("ANIM", b(0, 0, 0, 0, 0, 0)),
                webpChunk("ANMF", frame),
                webpChunk("EXIF", b(1, 2)),
                webpChunk("XMP ", a("<x <xmp:CreateDate>2024</xmp:CreateDate>")),
                webpChunk("ABCD", b(1))));

        assertTrue(r.recognized);
        assertEquals(Arrays.asList("WEBP_CHUNK_XTRA", "XMP", "WEBP_CHUNK_ABCD"), keys(r.findings));
        assertEquals(ImageContainerMetadata.Kind.DATE, finding(r.findings, "XMP").kind);
        assertEquals("sRGB odd", finding(r.kept, "ICC_PROFILE").text);
        assertEquals("Acme", finding(r.kept, "ICC_PROFILE_DEVICE").text);
    }

    @Test
    public void webpSanitize_keepsTheProfileAndFrames_andClearsTheMetadataFlags() throws Exception {
        byte[] profile = icc("desc", desc("Kept"));
        profile = Arrays.copyOf(profile, profile.length + 1);
        byte[] frame = cat(new byte[16], webpChunk("VP8L", b(1, 2, 3)), webpChunk("XTRA", b(8)));
        byte[] out = sanitize(webp(
                webpChunk("VP8X", b(0x2E, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                webpChunk("ICCP", profile),
                webpChunk("ANMF", frame),
                webpChunk("XMP ", a("<x/>"))));

        ImageContainerMetadata.Report r = scan(out);
        assertTrue(r.isClean());
        assertEquals("Kept", finding(r.kept, "ICC_PROFILE").text);
        assertEquals(0x22, out[20] & 0xFF);
        assertFalse(contains(out, a("CMMX")));
        assertFalse(contains(out, a("XTRA")));
        assertTrue(contains(out, webpChunk("VP8L", b(1, 2, 3))));
        assertEquals(out.length - 8, ByteBuffer.wrap(out, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
    }

    @Test
    public void webpSanitize_withoutAProfile_clearsTheIccFlagToo() throws Exception {
        byte[] out = sanitize(webp(webpChunk("VP8X", b(0x2C, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                webpChunk("VP8 ", b(1, 2, 3, 4)), webpChunk("EXIF", b(1, 2))));

        assertEquals(0, out[20]);
        assertTrue(scan(out).isClean());
    }

    @Test
    public void webpTrailingData_isFound() throws Exception {
        byte[] webp = cat(webp(webpChunk("VP8 ", b(1, 2, 3, 4))), a("tail"));

        assertEquals(Arrays.asList("DATA_AFTER_IMAGE"), keys(scan(webp).findings));
        assertFalse(contains(sanitize(webp), a("tail")));
    }

    @Test
    public void damagedWebps_areNeverClean() throws Exception {
        byte[] overflowingFrame = cat(new byte[16], a("VP8 "), le32(100), b(1, 2));
        byte[][] damaged = {
                webp(webpChunk("ANMF", b(1, 2, 3, 4))), // shorter than a frame header
                webp(webpChunk("ANMF", overflowingFrame)),
                webp(cat(a("VP8 "), le32(100), b(1, 2))), // runs past the file
        };
        for (byte[] webp : damaged) {
            ImageContainerMetadata.Report r = scan(webp);
            assertEquals(ImageContainerMetadata.Format.WEBP, r.format);
            assertFalse(r.recognized);
        }
    }

    // ---- what each finding holds --------------------------------------------------------------

    private static byte[] utf16Block(int tag, String text) {
        byte[] chars = text.getBytes(StandardCharsets.UTF_16BE);
        return cat(be16(tag), be16(4 + chars.length), be32(text.length()), chars);
    }

    @Test
    public void duckySegment_showsItsCommentAndCopyright() throws Exception {
        byte[] ducky = cat(a("Ducky"), be16(1), be16(4), be32(80), utf16Block(2, "Jane's holiday"),
                utf16Block(3, "© Jane Doe"), be16(0));
        ImageContainerMetadata.Finding f = finding(scan(jpeg(seg(0xEC, ducky))).findings, "APP12_DUCKY");

        assertEquals("Jane's holiday · © Jane Doe", f.text);
        assertEquals(ImageContainerMetadata.Kind.NAME, f.kind);
    }

    @Test
    public void extendedXmp_isOneFindingWithItsChunksInOrder() throws Exception {
        String guid = "0123456789ABCDEF0123456789ABCDEF";
        byte[] second = cat(a("http://ns.adobe.com/xmp/extension/\0"), a(guid), be32(11), be32(6),
                a("World"));
        byte[] first = cat(a("http://ns.adobe.com/xmp/extension/\0"), a(guid), be32(11), be32(0),
                a("Hello "));
        ImageContainerMetadata.Report r = scan(jpeg(seg(0xE1, second), seg(0xE1, first)));

        assertEquals(List.of("XMP_EXTENDED"), keys(r.findings));
        assertEquals("Hello World", r.findings.get(0).text);
        assertEquals(second.length + first.length, r.findings.get(0).bytes);
    }

    @Test
    public void extendedXmp_findsALocationSplitAcrossChunks() throws Exception {
        String guid = "0123456789ABCDEF0123456789ABCDEF";
        String head = "<x:xmpmeta><exif:GPSLat";
        String tail = "itude>40,41.3N</exif:GPSLatitude></x:xmpmeta>";
        int total = head.length() + tail.length();
        byte[] second = cat(a("http://ns.adobe.com/xmp/extension/\0"), a(guid), be32(total), be32(head.length()),
                a(tail));
        byte[] first = cat(a("http://ns.adobe.com/xmp/extension/\0"), a(guid), be32(total), be32(0), a(head));
        ImageContainerMetadata.Report r = scan(jpeg(seg(0xE1, second), seg(0xE1, first)));

        assertEquals(List.of("XMP_EXTENDED"), keys(r.findings));
        assertEquals(head + tail, r.findings.get(0).text);
        assertEquals(ImageContainerMetadata.Kind.LOCATION, r.findings.get(0).kind);
    }

    @Test
    public void thumbnails_showTheirSize() throws Exception {
        byte[] jfif = cat(a("JFIF\0"), b(1, 2, 0, 0, 1, 0, 1, 2, 3), new byte[18]);
        byte[] photoshopThumb = cat(be32(1), be32(160), be32(120), new byte[16]);
        ImageContainerMetadata.Report r = scan(jpeg(seg(0xE0, jfif),
                seg(0xED, cat(a("Photoshop 3.0\0"), resource(0x040C, photoshopThumb)))));

        assertEquals("2×3", finding(r.findings, "JFIF_THUMBNAIL").text);
        assertEquals("160×120", finding(r.findings, "PHOTOSHOP_THUMBNAIL").text);
    }

    @Test
    public void binarySegments_showTheTextInsideThem_orNothing() throws Exception {
        byte[] c2pa = cat(a("JP"), b(0, 0), a("jumb"), b(0, 0, 1), a("c2pa"), b(0, 0x1F),
                a("claim_generator"), b(0x78), a("Adobe Photoshop 25.1"));
        ImageContainerMetadata.Report r = scan(cat(jpeg(seg(0xEB, c2pa), seg(0xE2, b(1, 2, 3)),
                seg(0xED, cat(a("Photoshop 3.0\0"), resource(0x0408, cat(b(0, 1), a("Guides layer"))))),
                seg(0xE7, cat(a("XYZ1"), b(0), a("Vendor Camera Model"))),
                seg(0xF1, b(0x80, 0x81, 0x82, 0x83, 0x84))), a("leftover bytes")));

        assertTrue(finding(r.findings, "CONTENT_CREDENTIALS").text.contains("Adobe Photoshop 25.1"));
        assertNull(finding(r.findings, "APP2_DATA").text);
        assertEquals("Guides layer", finding(r.findings, "PHOTOSHOP_RESOURCES").text);
        assertEquals("XYZ1 · Vendor Camera Model", finding(r.findings, "APP7_XYZ1").text);
        assertNull(finding(r.findings, "JPEG_SEGMENT_F1").text);
        assertEquals("leftover bytes", finding(r.findings, "DATA_AFTER_IMAGE").text);
    }

    @Test
    public void samsungTrailer_showsWhenThePhotoWasTaken() throws Exception {
        byte[] trailer = cat(a("Image_UTC_Data1767225600000"), new byte[4], a("MCC_Data310"), a("SEFT"));

        assertEquals("Image_UTC_Data (2026-01-01 00:00:00 UTC), MCC_Data",
                finding(scan(cat(jpeg(), trailer)).findings, "SAMSUNG_TRAILER").text);
    }

    @Test
    public void pngRawProfilesAndPrivateChunks_showWhatTheyHold() throws Exception {
        String xmpHex = "3c783a786d706d6574612f3e"; // <x:xmpmeta/>
        ImageContainerMetadata.Report r = scan(png(
                pngChunk("tEXt", cat(a("Raw profile type xmp\0"), a("\nxmp\n      12\n" + xmpHex + "\n"))),
                pngChunk("tEXt", cat(a("Raw profile type exif\0"), a("\nexif\n       8\n4163" + "6d652043\n"))),
                pngChunk("prVt", a("private note"))));

        assertEquals("<x:xmpmeta/>", finding(r.findings, "PNG_TEXT_RAW_PROFILE_TYPE_XMP").text);
        assertEquals("Acme C", finding(r.findings, "PNG_TEXT_RAW_PROFILE_TYPE_EXIF").text);
        assertEquals("private note", finding(r.findings, "PNG_CHUNK_PRVT").text);
    }

    @Test
    public void webpUnknownChunks_showTheirText() throws Exception {
        byte[] frame = cat(new byte[16], webpChunk("VP8 ", b(1, 2, 3, 4)), webpChunk("NOTE", a("inside a frame")));
        ImageContainerMetadata.Report r = scan(webp(webpChunk("ANMF", frame), webpChunk("ABCD", a("outside"))));

        assertEquals("inside a frame", finding(r.findings, "WEBP_CHUNK_NOTE").text);
        assertEquals("outside", finding(r.findings, "WEBP_CHUNK_ABCD").text);
    }

    // ---- profiles and helpers -----------------------------------------------------------------

    @Test
    public void iccText_readsEachTagTypeAndRejectsBadOnes() {
        assertEquals("Desc", ImageContainerMetadata.IccText.read(icc("desc", desc("Desc")), "desc"));
        assertEquals("Multi", ImageContainerMetadata.IccText.read(icc("desc", mluc("Multi")), "desc"));
        assertEquals("Plain", ImageContainerMetadata.IccText.read(icc("desc", text("Plain")), "desc"));

        assertNull(ImageContainerMetadata.IccText.read(icc("desc", desc("x")), "dmdd"));
        assertNull(ImageContainerMetadata.IccText.read(icc("desc", iccTag("XYZ ", new byte[12])), "desc"));
        assertNull(ImageContainerMetadata.IccText.read(icc("desc", iccTag("mluc", cat(be32(0), new byte[16]))), "desc"));
        assertNull(ImageContainerMetadata.IccText.read(
                icc("desc", iccTag("mluc", cat(be32(1), be32(12), a("enUS"), be32(500), be32(28)))), "desc"));
        assertNull(ImageContainerMetadata.IccText.read(icc("desc", new byte[4]), "desc"));

        byte[] tooLong = icc("desc", desc("abc"));
        System.arraycopy(be32(10_000), 0, tooLong, 132 + 8, 4);
        assertNull(ImageContainerMetadata.IccText.read(tooLong, "desc"));
        assertNull(ImageContainerMetadata.IccText.read(new byte[64], "desc"));
    }

    @Test
    public void clearIccHeader_clearsIdentifyingFieldsOnly_andIgnoresShortBuffers() {
        byte[] profile = icc("desc", desc("Name"));
        byte[] original = profile.clone();
        ImageContainerMetadata.clearIccHeader(profile, 0);

        assertEquals(-1, indexOf(profile, a("CMMX")));
        assertEquals(-1, indexOf(profile, a("DEVMODEL")));
        assertEquals(-1, indexOf(profile, a("CRTR")));
        assertArrayEquals(Arrays.copyOfRange(original, 128, original.length),
                Arrays.copyOfRange(profile, 128, profile.length));

        byte[] shortBuffer = a("CMMXCMMX");
        ImageContainerMetadata.clearIccHeader(shortBuffer, 0);
        assertArrayEquals(a("CMMXCMMX"), shortBuffer);
    }

    @Test
    public void sniffAndKeyOf_handleEdgeCases() throws Exception {
        assertEquals(ImageContainerMetadata.Format.JPEG, ImageContainerMetadata.sniff(b(0xFF, 0xD8, 0xFF), 3));
        assertEquals(ImageContainerMetadata.Format.OTHER, ImageContainerMetadata.sniff(b(0xFF, 0xD8, 0xFF), 2));
        assertEquals(ImageContainerMetadata.Format.PNG, ImageContainerMetadata.sniff(PNG_SIGNATURE, 8));
        assertEquals(ImageContainerMetadata.Format.WEBP, ImageContainerMetadata.sniff(a("RIFF0000WEBP"), 12));
        assertEquals(ImageContainerMetadata.Format.OTHER, ImageContainerMetadata.sniff(a("RIFF0000AVI "), 12));
        assertEquals(ImageContainerMetadata.Format.OTHER, ImageContainerMetadata.sniff(new File(folder.getRoot(), "missing")));
        assertEquals(ImageContainerMetadata.Format.PNG, ImageContainerMetadata.sniff(file(png())));

        assertEquals("DATA", ImageContainerMetadata.keyOf("--"));
        assertEquals("RAW_PROFILE_TYPE_EXIF", ImageContainerMetadata.keyOf(" Raw profile type exif "));
        assertEquals(40, ImageContainerMetadata.keyOf("x".repeat(60)).length());
        assertTrue(ImageContainerMetadata.samsungEntries(a("nothing here")).isEmpty());
    }

    @Test
    public void otherFiles_areUnrecognized_andCannotBeSanitized() throws Exception {
        byte[] gif = a("GIF89a-not-walked");
        ImageContainerMetadata.Report r = scan(gif);
        assertFalse(r.recognized);
        assertEquals(ImageContainerMetadata.Format.OTHER, r.format);
        assertFalse(scan(new byte[0]).recognized);

        try {
            sanitize(gif);
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        File same = file(jpeg());
        try {
            ImageContainerMetadata.sanitize(same, same);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("differ"));
        }
    }
}
