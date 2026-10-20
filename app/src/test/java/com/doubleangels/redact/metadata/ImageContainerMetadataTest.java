package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FileProviderTestSupport;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Every place an image can keep metadata outside EXIF: found by a scan, shown in Scan, and gone
 * after a clean, while the picture itself is kept bit for bit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImageContainerMetadataTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FileProviderTestSupport.makeUsable(app);
        AppPreferences.setStrictClean(app, true);
    }

    // ---- building test files ------------------------------------------------------------------

    private static byte[] encode(Bitmap.CompressFormat format) {
        Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(format, 90, out);
        return out.toByteArray();
    }

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

    private static byte[] segment(int marker, byte[] payload) {
        int length = payload.length + 2;
        return cat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, payload);
    }

    private File write(String name, byte[] data) throws IOException {
        File f = folder.newFile(name);
        Files.write(f.toPath(), data);
        return f;
    }

    private static String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
    }

    private static byte[] iptc() {
        return cat(a("Photoshop 3.0\0"), a("8BIM"), new byte[] {0x04, 0x04, 0, 0}, new byte[] {0, 0, 0, 42},
                new byte[] {0x1C, 0x02, 0x5A, 0, 11}, a("SECRET_CITY"),
                new byte[] {0x1C, 0x02, 0x50, 0, 13}, a("SECRET_BYLINE"),
                new byte[] {0x1C, 0x02, 0x19, 0, 3}, a("one"));
    }

    /** A second JPEG with its own EXIF location, like an MPF preview or a camera's large thumbnail. */
    private byte[] secondImageWithLocation() throws IOException {
        File f = write("second.jpg", encode(Bitmap.CompressFormat.JPEG));
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
        e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,59/1,0/1");
        e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
        e.setAttribute(ExifInterface.TAG_MAKE, "SECRET_TRAILER_MAKE");
        e.saveAttributes();
        return Files.readAllBytes(f.toPath());
    }

    private static byte[] samsungTrailer() {
        return cat(a("SEFH"), a("....Image_UTC_Data1767225600000....MCC_Data310"), a("SEFT"));
    }

    /** A JPEG carrying every kind of metadata outside EXIF, plus EXIF itself. */
    private File everythingJpeg(byte[] plain) throws IOException {
        byte[] body = Arrays.copyOfRange(plain, 2, plain.length);
        byte[] jpeg = cat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                segment(0xED, iptc()),
                segment(0xFE, a("SECRET_COMMENT")),
                segment(0xE2, cat(a("MPF\0"), a("SECRET_MPF"))),
                segment(0xEB, cat(a("JP"), new byte[] {0, 0, 0, 0, 0, 1}, a("jumbc2pa SECRET_C2PA"))),
                segment(0xEC, cat(a("Ducky"), new byte[] {0, 1, 0, 4}, a("SECRET_DUCKY"))),
                segment(0xE1, cat(a("http://ns.adobe.com/xmp/extension/\0"), new byte[40], a("SECRET_EXT_XMP"))),
                body,
                secondImageWithLocation(),
                a("\0\0\0\u0018ftypmp42SECRET_MOTION_VIDEO"),
                samsungTrailer());
        File f = write("everything.jpg", jpeg);
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        e.setAttribute(ExifInterface.TAG_MAKE, "SECRET_MAKE");
        e.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
        e.saveAttributes();
        return f;
    }

    private static final String[] JPEG_SECRETS = {"SECRET_MAKE", "SECRET_CITY", "SECRET_BYLINE", "SECRET_COMMENT",
            "SECRET_MPF", "SECRET_C2PA", "SECRET_DUCKY", "SECRET_EXT_XMP", "SECRET_TRAILER_MAKE",
            "SECRET_MOTION_VIDEO", "Image_UTC_Data", "MCC_Data"};

    private static Map<String, ImageContainerMetadata.Finding> byKey(ImageContainerMetadata.Report r) {
        Map<String, ImageContainerMetadata.Finding> map = new HashMap<>();
        for (ImageContainerMetadata.Finding f : r.findings) {
            map.putIfAbsent(f.key, f);
        }
        return map;
    }

    // ---- JPEG ---------------------------------------------------------------------------------

    @Test
    public void jpegScan_findsEverythingOutsideExif() throws Exception {
        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(everythingJpeg(encode(Bitmap.CompressFormat.JPEG)));

        assertTrue(report.recognized);
        Map<String, ImageContainerMetadata.Finding> found = byKey(report);
        assertEquals("SECRET_CITY", found.get("LOCATION_CITY").text);
        assertEquals("SECRET_BYLINE", found.get("IPTC_BYLINE").text);
        assertEquals("one", found.get("IPTC_KEYWORDS").text);
        assertEquals("SECRET_COMMENT", found.get("JPEG_COMMENT").text);
        assertTrue(found.containsKey("MULTI_PICTURE_INDEX"));
        assertTrue(found.containsKey("CONTENT_CREDENTIALS"));
        assertTrue(found.containsKey("APP12_DUCKY"));
        assertTrue(found.containsKey("XMP_EXTENDED"));
        assertEquals("1", found.get("EMBEDDED_IMAGES").text);
        assertTrue(found.get("LOCATION_EMBEDDED_IMAGE").text.startsWith("40.5"));
        assertEquals("SECRET_TRAILER_MAKE", found.get("EMBEDDED_IMAGE_DEVICE").text);
        assertTrue(found.containsKey("EMBEDDED_VIDEO"));
        assertTrue(found.get("SAMSUNG_TRAILER").text.contains("Image_UTC_Data"));
        assertTrue(report.has(ImageContainerMetadata.Kind.LOCATION));
        assertTrue(report.has(ImageContainerMetadata.Kind.NAME));
        assertFalse(report.isClean());
    }

    @Test
    public void jpegSanitize_keepsOnlyThePicture() throws Exception {
        byte[] plain = encode(Bitmap.CompressFormat.JPEG);
        File out = folder.newFile("out.jpg");

        ImageContainerMetadata.sanitize(everythingJpeg(plain), out);

        String bytes = text(out);
        for (String secret : JPEG_SECRETS) {
            assertFalse(secret + " survived", bytes.contains(secret));
        }
        assertTrue(ImageContainerMetadata.scan(out).isClean());
        Bitmap decoded = BitmapFactory.decodeFile(out.getAbsolutePath());
        assertNotNull(decoded);
        assertEquals(64, decoded.getWidth());
        assertEquals(48, decoded.getHeight());
    }

    @Test
    public void jpegSanitize_isLosslessForAPlainJpeg() throws Exception {
        byte[] plain = encode(Bitmap.CompressFormat.JPEG);
        File out = folder.newFile("out.jpg");

        ImageContainerMetadata.sanitize(write("plain.jpg", plain), out);

        assertArrayEquals(plain, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void jpegWithoutAnEnd_isNotSanitizedAndNeverCountsAsClean() throws Exception {
        byte[] plain = encode(Bitmap.CompressFormat.JPEG);
        File truncated = write("truncated.jpg", Arrays.copyOf(plain, plain.length - 2));

        try {
            ImageContainerMetadata.sanitize(truncated, folder.newFile("out.jpg"));
            fail("a JPEG without its end must be re-encoded instead");
        } catch (IOException expected) {
            // The stripper re-encodes it from its pixels.
        }
        assertFalse(ImageContainerMetadata.scan(truncated).isClean());
    }

    @Test
    public void iccProfile_isKeptWithItsIdentifyingHeaderCleared() throws Exception {
        byte[] profile = new byte[200];
        profile[3] = (byte) 200;
        System.arraycopy(a("appl"), 0, profile, 4, 4);       // CMM
        profile[24] = 0x07;                                   // year 2026
        profile[25] = (byte) 0xEA;
        System.arraycopy(a("SAMS"), 0, profile, 48, 4);      // device maker
        System.arraycopy(a("SM-X"), 0, profile, 52, 4);      // device model
        System.arraycopy(a("acsp"), 0, profile, 36, 4);
        profile[131] = 1;                                     // one tag
        System.arraycopy(a("desc"), 0, profile, 132, 4);
        profile[139] = (byte) 144;                            // offset 144
        profile[143] = 40;                                    // size 40
        System.arraycopy(a("desc"), 0, profile, 144, 4);
        profile[155] = 10;
        System.arraycopy(a("Display P3"), 0, profile, 156, 10);
        byte[] plain = encode(Bitmap.CompressFormat.JPEG);
        File source = write("icc.jpg", cat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                segment(0xE2, cat(a("ICC_PROFILE\0"), new byte[] {1, 1}, profile)),
                Arrays.copyOfRange(plain, 2, plain.length)));

        ImageContainerMetadata.Report before = ImageContainerMetadata.scan(source);
        // Stock profiles fill these header fields in too, so they never make a file uncleaned.
        assertTrue(before.isClean());
        assertEquals("Display P3", before.kept.get(0).text);

        File out = folder.newFile("out.jpg");
        ImageContainerMetadata.sanitize(source, out);
        ImageContainerMetadata.Report after = ImageContainerMetadata.scan(out);
        assertTrue(after.isClean());
        assertEquals("Display P3", after.kept.get(0).text);
        assertFalse(text(out).contains("SAMS"));
        assertFalse(text(out).contains("appl"));
    }

    @Test
    public void photoshopResourceLargerThanItsSegment_isStillCleaned() throws Exception {
        // A resource size of 0x80000000 once overflowed into a negative index, which ended the
        // clean with an error instead of producing a clean file.
        byte[] resources = cat(a("Photoshop 3.0\0"), a("8BIM"), new byte[] {0x04, 0x04, 0, 0},
                new byte[] {(byte) 0x80, 0, 0, 0}, a("SECRET_HUGE"));
        byte[] plain = encode(Bitmap.CompressFormat.JPEG);
        File source = write("huge.jpg", cat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                segment(0xED, resources), Arrays.copyOfRange(plain, 2, plain.length)));

        assertTrue(ImageContainerMetadata.scan(source).recognized);
        File out = folder.newFile("out.jpg");
        ImageContainerMetadata.sanitize(source, out);
        assertFalse(text(out).contains("SECRET_HUGE"));
        assertTrue(ImageContainerMetadata.scan(out).isClean());
    }

    @Test
    public void embeddedImage_withAThumbnailInside_countsOnce_andAVideoIsNotAnImage() throws Exception {
        byte[] plain = encode(Bitmap.CompressFormat.JPEG);
        byte[] thumbnail = encode(Bitmap.CompressFormat.JPEG);
        // An appended JPEG whose own APP segment holds a whole thumbnail, as EXIF thumbnails do.
        byte[] preview = cat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                segment(0xE3, cat(a("THUMB\0"), thumbnail)),
                Arrays.copyOfRange(plain, 2, plain.length));
        File source = write("preview.jpg", cat(plain, preview,
                a("\0\0\0\u0018ftypmp42"), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}));

        Map<String, ImageContainerMetadata.Finding> found = byKey(ImageContainerMetadata.scan(source));

        assertEquals("1", found.get("EMBEDDED_IMAGES").text);
        assertEquals(preview.length, found.get("EMBEDDED_IMAGES").bytes);
        assertTrue(found.containsKey("EMBEDDED_VIDEO"));
    }

    @Test
    public void xmpKind_readsPropertiesNotNamespaceDeclarations() {
        String declarationsOnly = "<x:xmpmeta><rdf:RDF><rdf:Description"
                + " xmlns:xmpRights='http://ns.adobe.com/xap/1.0/rights/'"
                + " xmlns:Iptc4xmpCore='http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/'/></rdf:RDF></x:xmpmeta>";
        assertEquals(ImageContainerMetadata.Kind.OTHER, ImageContainerMetadata.xmpKind(declarationsOnly));
        assertEquals(ImageContainerMetadata.Kind.NAME, ImageContainerMetadata.xmpKind(
                "<rdf:Description><dc:creator><rdf:Seq><rdf:li>Jo</rdf:li></rdf:Seq></dc:creator></rdf:Description>"));
        assertEquals(ImageContainerMetadata.Kind.LOCATION, ImageContainerMetadata.xmpKind(
                "<rdf:Description exif:GPSLatitude='40,30N'/>"));
        assertEquals(ImageContainerMetadata.Kind.DATE, ImageContainerMetadata.xmpKind(
                "<rdf:Description xmp:CreateDate='2026-10-19T10:00:00'/>"));
    }

    @Test
    public void xmpPacket_isFoundAcrossBufferEdges_andOnlyWithinTheLimit() throws Exception {
        // The 64 KB read buffer would split a marker placed across its end.
        byte[] data = new byte[200 * 1024];
        byte[] marker = a("<x:xmpmeta");
        int at = 64 * 1024 - 4;
        System.arraycopy(marker, 0, data, at, marker.length);

        assertTrue(ImageContainerMetadata.containsXmpPacket(new ByteArrayInputStream(data), Long.MAX_VALUE));
        assertTrue(ImageContainerMetadata.containsXmpPacket(new ByteArrayInputStream(data), at + marker.length));
        assertFalse(ImageContainerMetadata.containsXmpPacket(new ByteArrayInputStream(data), at + marker.length - 1));
        assertFalse(ImageContainerMetadata.containsXmpPacket(new ByteArrayInputStream(new byte[200 * 1024]), Long.MAX_VALUE));
        assertTrue(ImageContainerMetadata.containsXmpPacket(
                new ByteArrayInputStream(a("..<?xpacket begin='x'?>")), Long.MAX_VALUE));
    }

    // ---- PNG ----------------------------------------------------------------------------------

    private static byte[] chunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(a(type));
        crc.update(data);
        long c = crc.getValue();
        int n = data.length;
        return cat(new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n}, a(type), data,
                new byte[] {(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
    }

    private static byte[] deflate(byte[] in) {
        Deflater d = new Deflater();
        d.setInput(in);
        d.finish();
        byte[] buf = new byte[4096];
        int n = d.deflate(buf);
        d.end();
        return Arrays.copyOf(buf, n);
    }

    private File everythingPng() throws IOException {
        byte[] base = encode(Bitmap.CompressFormat.PNG);
        int afterHeader = 8 + 8 + 13 + 4;
        String xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF><rdf:Description exif:GPSLatitude='40,30N'/>"
                + "SECRET_PNG_XMP</rdf:RDF></x:xmpmeta>";
        return write("everything.png", cat(Arrays.copyOfRange(base, 0, afterHeader),
                chunk("tEXt", a("Author\0SECRET_PNG_AUTHOR")),
                chunk("tEXt", a("parameters\0SECRET_PROMPT")),
                chunk("zTXt", cat(a("Comment\0\0"), deflate(a("SECRET_ZTXT")))),
                chunk("tIME", new byte[] {0x07, (byte) 0xEA, 10, 19, 12, 0, 0}),
                chunk("caBX", a("SECRET_C2PA_PNG")),
                Arrays.copyOfRange(base, afterHeader, base.length - 12),
                chunk("iTXt", cat(a("XML:com.adobe.xmp\0\1\0\0\0"), deflate(a(xmp)))),
                Arrays.copyOfRange(base, base.length - 12, base.length),
                a("SECRET_AFTER_IEND")));
    }

    @Test
    public void pngScan_findsTextChunksTimesAndCredentials() throws Exception {
        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(everythingPng());

        Map<String, ImageContainerMetadata.Finding> found = byKey(report);
        assertEquals("SECRET_PNG_AUTHOR", found.get("PNG_TEXT_AUTHOR").text);
        assertEquals(ImageContainerMetadata.Kind.NAME, found.get("PNG_TEXT_AUTHOR").kind);
        assertEquals("SECRET_PROMPT", found.get("PNG_TEXT_PARAMETERS").text);
        assertEquals("SECRET_ZTXT", found.get("PNG_TEXT_COMMENT").text);
        assertEquals("2026-10-19 12:00:00 UTC", found.get("PNG_MODIFIED_TIME").text);
        assertTrue(found.containsKey("CONTENT_CREDENTIALS"));
        assertTrue(found.get("XMP").text.contains("SECRET_PNG_XMP"));
        assertEquals(ImageContainerMetadata.Kind.LOCATION, found.get("XMP").kind);
        assertTrue(found.containsKey("DATA_AFTER_IMAGE"));
    }

    @Test
    public void pngSanitize_keepsOnlyThePicture() throws Exception {
        File out = folder.newFile("out.png");

        ImageContainerMetadata.sanitize(everythingPng(), out);

        String bytes = text(out);
        for (String secret : new String[] {"SECRET", "tEXt", "zTXt", "iTXt", "tIME", "caBX"}) {
            assertFalse(secret + " survived", bytes.contains(secret));
        }
        assertTrue(ImageContainerMetadata.scan(out).isClean());
        assertNotNull(BitmapFactory.decodeFile(out.getAbsolutePath()));
    }

    // ---- WebP ---------------------------------------------------------------------------------

    private static byte[] webpChunk(String type, byte[] data) {
        int n = data.length;
        byte[] header = cat(a(type), new byte[] {(byte) n, (byte) (n >>> 8), (byte) (n >>> 16), (byte) (n >>> 24)});
        return (n & 1) == 1 ? cat(header, data, new byte[] {0}) : cat(header, data);
    }

    private File everythingWebp() throws IOException {
        byte[] simple = encode(Bitmap.CompressFormat.WEBP_LOSSLESS);
        int at = new String(simple, StandardCharsets.ISO_8859_1).indexOf("VP8L");
        byte[] bitstream = Arrays.copyOfRange(simple, at, simple.length); // the VP8L chunk
        byte[] vp8x = new byte[10];
        vp8x[0] = 0x0C; // EXIF and XMP flags
        vp8x[4] = 63;   // canvas width - 1
        vp8x[7] = 47;   // canvas height - 1
        byte[] chunks = cat(webpChunk("VP8X", vp8x), bitstream,
                webpChunk("ABCD", a("SECRET_UNKNOWN")),
                webpChunk("XMP ", a("<x:xmpmeta>SECRET_WEBP_XMP</x:xmpmeta>")));
        int riff = chunks.length + 4;
        return write("everything.webp", cat(a("RIFF"),
                new byte[] {(byte) riff, (byte) (riff >>> 8), (byte) (riff >>> 16), (byte) (riff >>> 24)},
                a("WEBP"), chunks, a("SECRET_AFTER_RIFF")));
    }

    @Test
    public void webpScanAndSanitize_dropXmpUnknownChunksAndTrailingData() throws Exception {
        File source = everythingWebp();
        Map<String, ImageContainerMetadata.Finding> found = byKey(ImageContainerMetadata.scan(source));
        assertTrue(found.get("XMP").text.contains("SECRET_WEBP_XMP"));
        assertTrue(found.containsKey("WEBP_CHUNK_ABCD"));
        assertTrue(found.containsKey("DATA_AFTER_IMAGE"));

        File out = folder.newFile("out.webp");
        ImageContainerMetadata.sanitize(source, out);

        byte[] bytes = Files.readAllBytes(out.toPath());
        assertFalse(text(out).contains("SECRET"));
        assertEquals(bytes.length - 8, (bytes[4] & 0xFF) | ((bytes[5] & 0xFF) << 8) | ((bytes[6] & 0xFF) << 16));
        assertEquals("the EXIF and XMP flags are cleared", 0, bytes[20] & 0x0C);
        assertTrue(ImageContainerMetadata.scan(out).isClean());
        assertNotNull(BitmapFactory.decodeFile(out.getAbsolutePath()));
    }

    // ---- through the stripper -----------------------------------------------------------------

    @Test
    public void sharingAJpeg_removesEverythingAndKeepsOrientation() throws Exception {
        MetadataStripper stripper = new MetadataStripper(app);
        Uri out = stripper.stripExifDataForSharing(
                Uri.fromFile(everythingJpeg(encode(Bitmap.CompressFormat.JPEG))), "p.jpg");

        assertNotNull(out);
        File file = stripper.getLastProcessedOutputFile();
        String bytes = text(file);
        for (String secret : JPEG_SECRETS) {
            assertFalse(secret + " survived", bytes.contains(secret));
        }
        assertEquals("6", new ExifInterface(file.getAbsolutePath()).getAttribute(ExifInterface.TAG_ORIENTATION));
        CleanStats stats = stripper.getLastCleanStats();
        assertTrue(stats.hadLocation);
        assertTrue(stats.hadName);
        assertTrue(stats.removedFields >= 12);
    }

    @Test
    public void aJpegWithAComment_isCleanedRatherThanRefused() throws Exception {
        byte[] plain = encode(Bitmap.CompressFormat.JPEG);
        File source = write("comment.jpg", cat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                segment(0xFE, a("CREATOR: gd-jpeg v1.0")), Arrays.copyOfRange(plain, 2, plain.length)));
        MetadataStripper stripper = new MetadataStripper(app);

        assertNotNull(stripper.stripExifDataForSharing(Uri.fromFile(source), "c.jpg"));
        assertFalse(text(stripper.getLastProcessedOutputFile()).contains("gd-jpeg"));
    }

    @Test
    public void sharingAPng_removesTextChunks() throws Exception {
        MetadataStripper stripper = new MetadataStripper(app);

        assertNotNull(stripper.stripExifDataForSharing(Uri.fromFile(everythingPng()), "p.png"));
        assertFalse(text(stripper.getLastProcessedOutputFile()).contains("SECRET"));
    }

    @Test
    public void sharingAWebp_removesXmpAndUnknownChunks() throws Exception {
        MetadataStripper stripper = new MetadataStripper(app);

        assertNotNull(stripper.stripExifDataForSharing(Uri.fromFile(everythingWebp()), "p.webp"));
        assertFalse(text(stripper.getLastProcessedOutputFile()).contains("SECRET"));
    }

    @Test
    public void alreadyCleanCheck_seesMetadataOutsideExif() throws Exception {
        byte[] plain = encode(Bitmap.CompressFormat.PNG);
        int afterHeader = 8 + 8 + 13 + 4;
        File png = write("author.png", cat(Arrays.copyOfRange(plain, 0, afterHeader),
                chunk("tEXt", a("Author\0Someone")), Arrays.copyOfRange(plain, afterHeader, plain.length)));

        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(app, Uri.fromFile(png));

        assertTrue(report.recognized);
        assertFalse(report.findings.isEmpty());
    }

    // ---- Scan ---------------------------------------------------------------------------------

    @Test
    public void scanShowsFindingsInTheirSections() throws Exception {
        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(everythingJpeg(encode(Bitmap.CompressFormat.JPEG)));
        Map<String, String> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> embedded = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        MetadataDisplayer.addImageContainerFindings(app, report, true, metadata, embedded);

        assertEquals("SECRET_CITY", metadata.get("LOCATION_CITY"));
        assertTrue(MetadataDisplayer.isLocationMetadataKey("LOCATION_CITY"));
        assertTrue(metadata.containsKey("LOCATION_EMBEDDED_IMAGE"));
        assertEquals("SECRET_COMMENT", embedded.get("JPEG_COMMENT"));
        assertEquals("SECRET_BYLINE", embedded.get("IPTC_BYLINE"));
        assertTrue(embedded.get("EMBEDDED_IMAGES").startsWith("1 image"));
        assertTrue(embedded.get("EMBEDDED_VIDEO").startsWith("Present"));
        assertTrue(embedded.get("SAMSUNG_TRAILER").contains("MCC_Data"));
    }

    @Test
    public void scanWithoutLocationPermission_withholdsPlaces() throws Exception {
        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(everythingJpeg(encode(Bitmap.CompressFormat.JPEG)));
        Map<String, String> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> embedded = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        MetadataDisplayer.addImageContainerFindings(app, report, false, metadata, embedded);

        assertFalse(metadata.containsKey("LOCATION_CITY"));
        assertFalse(metadata.containsKey("LOCATION_EMBEDDED_IMAGE"));
        assertFalse(embedded.containsKey("EMBEDDED_IMAGES"));
        assertEquals("SECRET_COMMENT", embedded.get("JPEG_COMMENT"));
    }
}
