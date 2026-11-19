package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeMediaStoreProvider;
import com.doubleangels.redact.FileProviderTestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * End to end: a file with a unique marker in every place metadata can hide is cleaned for real,
 * in every mode (gallery with Strict Clean, without it, with every Keep option on, and a share),
 * and the output's raw bytes are searched for each marker, in ASCII and in UTF-16 both ways.
 *
 * <p>This is a check that does not use the app's own scanners, so a blind spot shared by the
 * cleaner and its verification would still be caught here. The structure of each output is
 * checked against an allowlist of what a picture needs, and the verification itself is checked
 * to refuse a clean file with any one kind of metadata put back.
 */
@RunWith(RobolectricTestRunner.class)
public class CleanLeavesNoTraceTest {

    private Application app;
    private final List<File> files = new ArrayList<>();

    /** The four ways a photo is cleaned, which keep different things. */
    private enum Mode {
        STRICT(true), NON_STRICT(false), KEEP_ALL(false), SHARE(false);

        final boolean galleryStrict;

        Mode(boolean galleryStrict) {
            this.galleryStrict = galleryStrict;
        }
    }

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        FileProviderTestSupport.makeUsable(app);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        AppPreferences.resetAll(app);
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---- the cleans ---------------------------------------------------------------------------

    private void applyMode(Mode mode) {
        AppPreferences.resetAll(app);
        AppPreferences.setStrictClean(app, mode.galleryStrict);
        boolean keep = mode == Mode.KEEP_ALL || mode == Mode.SHARE; // a share must ignore them
        AppPreferences.setPreserveCameraSettings(app, keep);
        AppPreferences.setPreserveDateTaken(app, keep);
        AppPreferences.setPreserveLocation(app, keep);
        AppPreferences.setPreserveMotionPhoto(app, keep);
    }

    /** Cleans {@code source} in {@code mode} and returns the output's bytes, or null if it failed. */
    private byte[] clean(File source, String name, Mode mode) throws IOException {
        applyMode(mode);
        FakeMediaStoreProvider.reset();
        FakeMediaStoreProvider.install(app);
        MetadataStripper stripper = new MetadataStripper(app);
        if (mode == Mode.SHARE) {
            CleanResult result = stripper.cleanImageForSharing(Uri.fromFile(source), name);
            return result.uri() == null || result.file() == null ? null : Files.readAllBytes(result.file().toPath());
        }
        CleanResult result = stripper.cleanImage(Uri.fromFile(source), name);
        if (result.uri() == null) {
            return null;
        }
        return Files.readAllBytes(FakeMediaStoreProvider.only().file.toPath());
    }

    /**
     * Cleans {@code source} in every mode and lists every marker found in an output, except the
     * ones {@code keptWithKeepAll} names in the mode that keeps them on purpose.
     */
    private List<String> leaks(File source, String name, List<String> markers, Set<String> keptWithKeepAll,
                               OutputCheck extra) throws IOException {
        List<String> problems = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            byte[] out = clean(source, name, mode);
            if (out == null) {
                problems.add(mode + ": the clean failed");
                continue;
            }
            for (String marker : markers) {
                if (mode == Mode.KEEP_ALL && keptWithKeepAll.contains(marker)) {
                    continue;
                }
                String where = foundAs(out, marker);
                if (where != null) {
                    problems.add(mode + ": \"" + marker + "\" survived (" + where + ")");
                }
            }
            if (extra != null) {
                extra.check(mode, out, problems);
            }
        }
        return problems;
    }

    private interface OutputCheck {
        void check(Mode mode, byte[] out, List<String> problems) throws IOException;
    }

    private static void assertNoLeaks(List<String> problems) {
        assertTrue("Metadata survived a clean:\n  " + String.join("\n  ", problems), problems.isEmpty());
    }

    // ---- JPEG ---------------------------------------------------------------------------------

    private static final String[] JPEG_EXIF_MARKERS = {
            "ZQXartist", "ZQXcopyright", "ZQXdescription", "ZQXsoftware", "ZQXusercomment",
            "ZQXowner", "ZQXbodyserial", "ZQXlensmake", "ZQXlensmodel", "ZQXlensserial",
            "ZQXuniqueid", "ZQXgpsmethod", "ZQXgpsarea"};
    private static final String JPEG_MAKE = "ZQXmake";
    private static final String JPEG_MODEL = "ZQXmodel";
    private static final String[] JPEG_SEGMENT_MARKERS = {
            "ZQXcomment", "ZQXiptc", "ZQXxmp", "ZQXapp11", "ZQXtrailer"};
    private static final String DATE = "2019:03:04 05:06:07";

    /** A JPEG with every EXIF text tag, a location, a date and every other place text can hide. */
    private File loadedJpeg() throws IOException {
        File f = temp(".jpg", compressed(Bitmap.CompressFormat.JPEG));
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        String[] tags = {
                ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_CAMERA_OWNER_NAME,
                ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
                ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_IMAGE_UNIQUE_ID,
                ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION};
        for (int i = 0; i < tags.length; i++) {
            exif.setAttribute(tags[i], JPEG_EXIF_MARKERS[i]);
        }
        exif.setAttribute(ExifInterface.TAG_MAKE, JPEG_MAKE);
        exif.setAttribute(ExifInterface.TAG_MODEL, JPEG_MODEL);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DATE);
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, DATE);
        exif.setLatLong(40.6892, -74.0445);
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "93/1");
        exif.saveAttributes();

        byte[] withExif = Files.readAllBytes(f.toPath());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(withExif, 0, 2); // SOI
        jpegSegment(out, 0xFE, ascii("ZQXcomment"));
        jpegSegment(out, 0xED, photoshopIptc("ZQXiptc"));
        jpegSegment(out, 0xE1, cat(ascii("http://ns.adobe.com/xap/1.0/\0"), xmp("ZQXxmp")));
        jpegSegment(out, 0xEB, ascii("ZQXapp11 vendor data"));
        out.write(withExif, 2, withExif.length - 2);
        out.write(ascii("ZQXtrailer appended after the picture"), 0, 37);
        Files.write(f.toPath(), out.toByteArray());
        return f;
    }

    @Test
    public void aJpeg_keepsNoMarkerFromAnyPlace_inAnyMode() throws IOException {
        List<String> markers = new ArrayList<>(List.of(JPEG_EXIF_MARKERS));
        markers.addAll(List.of(JPEG_SEGMENT_MARKERS));
        markers.add(JPEG_MAKE);
        markers.add(JPEG_MODEL);
        markers.add("2019:03:04");

        List<String> problems = leaks(loadedJpeg(), "photo.jpg", markers,
                Set.of(JPEG_MAKE, JPEG_MODEL, "2019:03:04"), (mode, out, list) -> {
                    checkLocation(mode, out, ".jpg", list);
                    checkJpegStructure(mode, out, list);
                    if (BitmapFactory.decodeByteArray(out, 0, out.length) == null) {
                        list.add(mode + ": the output is not a picture");
                    }
                });

        assertNoLeaks(problems);
    }

    /** Only the segments a decoder needs, the kept EXIF, a colour profile and Adobe's flags. */
    private static void checkJpegStructure(Mode mode, byte[] d, List<String> problems) {
        int pos = 2;
        while (pos + 4 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                problems.add(mode + ": the JPEG's segments cannot be followed");
                return;
            }
            int type = d[pos + 1] & 0xFF;
            if (type == 0xDA) {
                break; // the picture starts
            }
            int length = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            boolean allowed = type == 0xDB || type == 0xC4 || type == 0xDD
                    || (type >= 0xC0 && type <= 0xCF && type != 0xC4 && type != 0xC8 && type != 0xCC)
                    || (type == 0xE0 && startsWith(d, pos + 4, "JFIF"))
                    || (type == 0xE1 && startsWith(d, pos + 4, "Exif\0\0"))
                    || (type == 0xE2 && startsWith(d, pos + 4, "ICC_PROFILE"))
                    || (type == 0xEE && startsWith(d, pos + 4, "Adobe"));
            if (!allowed) {
                problems.add(mode + ": a JPEG segment 0x" + Integer.toHexString(type) + " was kept");
            }
            pos += 2 + length;
        }
        int end = lastIndexOf(d, new byte[] {(byte) 0xFF, (byte) 0xD9});
        if (end < 0 || end + 2 != d.length) {
            problems.add(mode + ": data follows the end of the picture");
        }
    }

    // ---- PNG ----------------------------------------------------------------------------------

    private static final String[] PNG_MARKERS = {
            "ZQXtextkey", "ZQXtextvalue", "ZQXitxtkey", "ZQXitxtvalue", "ZQXztxtkey",
            "ZQXexifartist", "ZQXprivate", "ZQXpngtrailer"};
    private static final String PNG_COMPRESSED_TEXT = "ZQXztxtvalue";

    private File loadedPng() throws IOException {
        byte[] plain = compressed(Bitmap.CompressFormat.PNG);
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterIhdr);
        ExifFixtures.writePngChunk(out, "tEXt", cat(ascii("ZQXtextkey\0"), ascii("ZQXtextvalue")));
        ExifFixtures.writePngChunk(out, "iTXt", cat(ascii("ZQXitxtkey\0"), new byte[] {0, 0}, ascii("en\0\0"),
                ascii("ZQXitxtvalue")));
        ExifFixtures.writePngChunk(out, "zTXt", cat(ascii("ZQXztxtkey\0"), new byte[] {0},
                deflate(ascii(PNG_COMPRESSED_TEXT))));
        ExifFixtures.writePngChunk(out, "eXIf", tiffAscii(0x013B, "ZQXexifartist")); // Artist
        ExifFixtures.writePngChunk(out, "tIME", new byte[] {0x07, (byte) 0xE3, 3, 4, 5, 6, 7});
        ExifFixtures.writePngChunk(out, "prVt", ascii("ZQXprivate vendor chunk"));
        out.write(plain, afterIhdr, plain.length - afterIhdr);
        out.write(ascii("ZQXpngtrailer"), 0, 13);
        return temp(".png", out.toByteArray());
    }

    @Test
    public void aPng_keepsNoTextChunkExifOrTrailer_inAnyMode() throws IOException {
        List<String> problems = leaks(loadedPng(), "picture.png", List.of(PNG_MARKERS), Set.of(),
                (mode, out, list) -> {
                    Set<String> allowed = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM",
                            "sRGB", "sBIT", "pHYs", "bKGD", "cICP", "mDCV", "mDCv", "cLLI", "cLLi", "acTL",
                            "fcTL", "fdAT", "iCCP", "eXIf");
                    for (String chunk : pngChunks(out)) {
                        if (!allowed.contains(chunk)) {
                            list.add(mode + ": a PNG chunk " + chunk + " was kept");
                        }
                    }
                    if (inflatedTextContains(out, PNG_COMPRESSED_TEXT)) {
                        list.add(mode + ": the compressed text survived");
                    }
                    if (BitmapFactory.decodeByteArray(out, 0, out.length) == null) {
                        list.add(mode + ": the output is not a picture");
                    }
                });

        assertNoLeaks(problems);
    }

    // ---- WebP ---------------------------------------------------------------------------------

    /** A 1x1 lossless WebP picture (Pillow's), one VP8L chunk's data. Robolectric cannot encode WebP. */
    static final byte[] VP8L_1X1 = {
            0x2f, 0x00, 0x00, 0x00, 0x00, 0x07, 0x50, (byte) 0x8a, 0x2a, (byte) 0xd4, (byte) 0xa3,
            (byte) 0xff, (byte) 0x81, (byte) 0x88, (byte) 0xe8, 0x7f, 0x00};

    private static final String[] WEBP_MARKERS = {
            "ZQXwebpartist", "ZQXwebpxmp", "ZQXwebpunknown", "ZQXwebptrailer"};

    private File loadedWebp() throws IOException {
        byte[] file = ExifFixtures.webp(
                "VP8X", new byte[] {0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0}, // EXIF and XMP flags, 1x1
                "VP8L", VP8L_1X1,
                "ZQXu", ascii("ZQXwebpunknown"),
                "EXIF", tiffAscii(0x013B, "ZQXwebpartist"),
                "XMP ", xmp("ZQXwebpxmp"));
        return temp(".webp", cat(file, ascii("ZQXwebptrailer")));
    }

    @Test
    public void aWebp_keepsNoExifXmpUnknownChunkOrTrailer_inAnyMode() throws IOException {
        List<String> problems = leaks(loadedWebp(), "picture.webp", List.of(WEBP_MARKERS), Set.of(),
                (mode, out, list) -> {
                    Set<String> allowed = Set.of("VP8X", "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF", "ICCP", "EXIF");
                    for (String chunk : webpChunks(out)) {
                        if (!allowed.contains(chunk)) {
                            list.add(mode + ": a WebP chunk " + chunk + " was kept");
                        }
                    }
                });

        assertNoLeaks(problems);
    }

    // ---- GIF ----------------------------------------------------------------------------------

    /** A 1x1 GIF: header, screen with a two-colour table, one image and the trailer. */
    private static final byte[] GIF_1X1 = {
            'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, (byte) 0x80, 0, 0,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0,
            0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0,
            0x3B};

    private static final String[] GIF_MARKERS = {
            "ZQXgifcomment", "ZQXgifxmp", "ZQXgifapp", "ZQXgifplain", "ZQXgiftrailer"};

    private File loadedGif() throws IOException {
        int beforeImage = 19;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(GIF_1X1, 0, beforeImage);
        gifExtension(out, 0xFE, null, ascii("ZQXgifcomment"));
        gifExtension(out, 0xFF, ascii("XMP DataXMP"), xmp("ZQXgifxmp"));
        gifExtension(out, 0xFF, ascii("ZQXAPP01abc"), ascii("ZQXgifapp vendor block"));
        gifExtension(out, 0x01, new byte[] {0, 0, 0, 0, 1, 0, 1, 0, 1, 1, 0, 1}, ascii("ZQXgifplain"));
        out.write(GIF_1X1, beforeImage, GIF_1X1.length - beforeImage);
        out.write(ascii("ZQXgiftrailer"), 0, 13);
        return temp(".gif", out.toByteArray());
    }

    @Test
    public void aGif_keepsNoCommentApplicationBlockOrTrailer_inAnyMode() throws IOException {
        assertNoLeaks(leaks(loadedGif(), "animation.gif", List.of(GIF_MARKERS), Set.of(), null));
    }

    // ---- real camera files --------------------------------------------------------------------

    @Test
    public void aTaggedHeic_keepsItsMakeOnlyWithKeepCameraSettings_andNeverItsXmp() throws IOException {
        File heic = TestVideos.resource(app.getFilesDir(), "image/heic_tagged.heic");
        files.add(heic);
        assertTrue(contains(Files.readAllBytes(heic.toPath()), "SecretMaker"));

        assertNoLeaks(leaks(heic, "photo.heic", List.of("SecretMaker", "xmpmeta"), Set.of("SecretMaker"), null));
    }

    @Test
    public void anUltraHdrPhoto_keepsNeitherCamerasMake_inTheGainMapAboveAll() throws IOException {
        File jpeg = TestVideos.resource(app.getFilesDir(), "image/ultra_hdr_tagged.jpg");
        files.add(jpeg);

        // The gain map's own EXIF is never kept: its layer keeps only its brightness numbers.
        assertNoLeaks(leaks(jpeg, "hdr.jpg", List.of("SecretPrimaryMake", "SecretGainMapMake"),
                Set.of("SecretPrimaryMake"), null));
    }

    @Test
    public void motionPhotos_neverKeepTheClipsTitleLocationOrDate_evenWhenTheClipIsKept() throws IOException {
        for (String name : new String[] {"motion_photo_tagged.jpg", "motion_photo_samsung.jpg"}) {
            File jpeg = TestVideos.resource(app.getFilesDir(), "image/" + name);
            files.add(jpeg);
            byte[] before = Files.readAllBytes(jpeg.toPath());
            assertTrue(contains(before, "SecretTitle") && contains(before, "+48.8584"));

            assertNoLeaks(leaks(jpeg, name, List.of("SecretTitle", "+48.8584+002.2945", "2024-05-01T10:00:00Z"),
                    Set.of(), null));
        }
    }

    // ---- the verification refuses each kind put back -----------------------------------------

    private boolean verifies(byte[] content, String suffix) throws Exception {
        File f = temp(suffix, content);
        Method verify = CleanVerifier.class.getDeclaredMethod("verifyMetadataRemoval", File.class, boolean.class);
        verify.setAccessible(true);
        return (boolean) verify.invoke(new MetadataStripper(app).verifier, f, true);
    }

    /**
     * {@code clean} must pass, and each variant, the clean file with one kind of metadata put
     * back, must not: otherwise a regression in the cleaner would be saved without complaint.
     */
    private void assertEachIsRefused(byte[] clean, String suffix, Map<String, byte[]> variants) throws Exception {
        AppPreferences.setStrictClean(app, true);
        assertTrue("the clean " + suffix + " itself must pass", verifies(clean, suffix));
        List<String> passed = new ArrayList<>();
        for (Map.Entry<String, byte[]> variant : variants.entrySet()) {
            if (verifies(variant.getValue(), suffix)) {
                passed.add(variant.getKey());
            }
        }
        assertTrue("The verification passed a " + suffix + " still holding: " + passed, passed.isEmpty());
    }

    @Test
    public void verification_refusesAJpegWithAnyOneKindOfMetadataLeft() throws Exception {
        byte[] clean = compressed(Bitmap.CompressFormat.JPEG);
        Map<String, byte[]> variants = new LinkedHashMap<>();
        variants.put("a comment", jpegWith(clean, 0xFE, ascii("ZQX")));
        variants.put("IPTC", jpegWith(clean, 0xED, photoshopIptc("ZQX")));
        variants.put("XMP", jpegWith(clean, 0xE1, cat(ascii("http://ns.adobe.com/xap/1.0/\0"), xmp("ZQX"))));
        variants.put("an unknown APP segment", jpegWith(clean, 0xEB, ascii("ZQX vendor")));
        variants.put("a JFIF thumbnail", jpegWith(clean, 0xE0, cat(ascii("JFIF\0"),
                new byte[] {1, 1, 0, 0, 1, 0, 1, 1, 1}, new byte[3])));
        variants.put("data after the picture", cat(clean, ascii("ZQX trailer")));
        variants.put("an XP author", ExifFixtures.withExif(clean,
                ExifFixtures.tiffWith(ExifBlock.TAG_XP_AUTHOR, ExifFixtures.xpText("Jane Doe"))));
        variants.put("an EXIF artist", ExifFixtures.withExif(clean, tiffAscii(0x013B, "Jane Doe")));
        variants.put("a GPS block", ExifFixtures.withExif(clean, exifOf(e -> e.setLatLong(40.7, -74.0))));
        variants.put("a date taken", ExifFixtures.withExif(clean,
                exifOf(e -> e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DATE))));
        variants.put("EXIF data no tag points to", ExifFixtures.withExif(clean, orientationWithHiddenBytes()));

        assertEachIsRefused(clean, ".jpg", variants);
    }

    @Test
    public void verification_refusesAPngWithAnyOneKindOfMetadataLeft() throws Exception {
        byte[] clean = compressed(Bitmap.CompressFormat.PNG);
        Map<String, byte[]> variants = new LinkedHashMap<>();
        variants.put("tEXt", pngWith(clean, "tEXt", cat(ascii("Author\0"), ascii("ZQX"))));
        variants.put("iTXt", pngWith(clean, "iTXt", cat(ascii("Comment\0"), new byte[] {0, 0, 0, 0}, ascii("ZQX"))));
        variants.put("zTXt", pngWith(clean, "zTXt", cat(ascii("Comment\0"), new byte[] {0}, deflate(ascii("ZQX")))));
        variants.put("tIME", pngWith(clean, "tIME", new byte[] {0x07, (byte) 0xE3, 3, 4, 5, 6, 7}));
        variants.put("a private chunk", pngWith(clean, "prVt", ascii("ZQX")));
        variants.put("an eXIf artist", pngWith(clean, "eXIf", tiffAscii(0x013B, "Jane Doe")));
        variants.put("an eXIf XP author", pngWith(clean, "eXIf",
                ExifFixtures.tiffWith(ExifBlock.TAG_XP_AUTHOR, ExifFixtures.xpText("Jane Doe"))));
        variants.put("data after IEND", cat(clean, ascii("ZQX trailer")));
        variants.put("eXIf data no tag points to", pngWith(clean, "eXIf", orientationWithHiddenBytes()));

        assertEachIsRefused(clean, ".png", variants);
    }

    @Test
    public void verification_refusesAWebpWithAnyOneKindOfMetadataLeft() throws Exception {
        byte[] clean = ExifFixtures.webp("VP8X", new byte[10], "VP8L", VP8L_1X1);
        Map<String, byte[]> variants = new LinkedHashMap<>();
        variants.put("an EXIF artist", ExifFixtures.webp(
                "VP8X", new byte[] {0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "VP8L", VP8L_1X1,
                "EXIF", tiffAscii(0x013B, "Jane Doe")));
        variants.put("an EXIF XP author", ExifFixtures.webp(
                "VP8X", new byte[] {0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "VP8L", VP8L_1X1,
                "EXIF", ExifFixtures.tiffWith(ExifBlock.TAG_XP_AUTHOR, ExifFixtures.xpText("Jane Doe"))));
        variants.put("XMP", ExifFixtures.webp(
                "VP8X", new byte[] {0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "VP8L", VP8L_1X1, "XMP ", xmp("ZQX")));
        variants.put("an unknown chunk", ExifFixtures.webp("VP8X", new byte[10], "VP8L", VP8L_1X1,
                "ZQXu", ascii("ZQX vendor")));
        variants.put("data after the RIFF", cat(clean, ascii("ZQX trailer")));
        variants.put("EXIF data no tag points to", ExifFixtures.webp(
                "VP8X", new byte[] {0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0}, "VP8L", VP8L_1X1,
                "EXIF", orientationWithHiddenBytes()));

        assertEachIsRefused(clean, ".webp", variants);
    }

    @Test
    public void verification_refusesAGifWithAnyOneKindOfMetadataLeft() throws Exception {
        Map<String, byte[]> variants = new LinkedHashMap<>();
        variants.put("a comment", gifWith(0xFE, null, ascii("ZQX")));
        variants.put("XMP", gifWith(0xFF, ascii("XMP DataXMP"), xmp("ZQX")));
        variants.put("an unknown application block", gifWith(0xFF, ascii("ZQXAPP01abc"), ascii("ZQX")));
        variants.put("plain text", gifWith(0x01, new byte[] {0, 0, 0, 0, 1, 0, 1, 0, 1, 1, 0, 1}, ascii("ZQX")));
        variants.put("data after the trailer", cat(GIF_1X1, ascii("ZQX trailer")));

        assertEachIsRefused(GIF_1X1, ".gif", variants);
    }

    // ---- checks -------------------------------------------------------------------------------

    /** Whether {@code marker} is in {@code data} as ASCII or UTF-16, and how; null when it is not. */
    private static String foundAs(byte[] data, String marker) {
        if (contains(data, marker.getBytes(StandardCharsets.ISO_8859_1))) {
            return "ASCII";
        }
        if (contains(data, marker.getBytes(StandardCharsets.UTF_16LE))) {
            return "UTF-16LE";
        }
        if (contains(data, marker.getBytes(StandardCharsets.UTF_16BE))) {
            return "UTF-16BE";
        }
        return null;
    }

    private static boolean contains(byte[] data, String text) {
        return contains(data, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int lastIndexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = data.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, int at, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.ISO_8859_1);
        if (at + p.length > data.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (data[at + i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    /** A location only with Keep Location, and the date taken only with Keep Date Taken. */
    private void checkLocation(Mode mode, byte[] out, String suffix, List<String> problems) throws IOException {
        ExifInterface exif = new ExifInterface(temp(suffix, out).getAbsolutePath());
        boolean located = exif.getLatLong() != null;
        boolean dated = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null;
        if (mode == Mode.KEEP_ALL) {
            if (!located) {
                problems.add(mode + ": Keep Location lost the location");
            }
        } else {
            if (located) {
                problems.add(mode + ": a location survived");
            }
            if (dated) {
                problems.add(mode + ": the date taken survived");
            }
        }
        if (exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null && mode != Mode.KEEP_ALL) {
            problems.add(mode + ": the altitude survived");
        }
    }

    private static List<String> pngChunks(byte[] d) {
        List<String> chunks = new ArrayList<>();
        int pos = 8;
        while (pos + 8 <= d.length) {
            int length = ByteBuffer.wrap(d, pos, 4).getInt();
            String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
            chunks.add(type);
            pos += 12 + length;
            if (type.equals("IEND")) {
                if (pos != d.length) {
                    chunks.add("<data after IEND>");
                }
                break;
            }
        }
        return chunks;
    }

    private static List<String> webpChunks(byte[] d) {
        List<String> chunks = new ArrayList<>();
        int riffEnd = 8 + ByteBuffer.wrap(d, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int pos = 12;
        while (pos + 8 <= riffEnd && pos + 8 <= d.length) {
            String type = new String(d, pos, 4, StandardCharsets.ISO_8859_1);
            int length = ByteBuffer.wrap(d, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            chunks.add(type);
            pos += 8 + length + (length & 1);
        }
        if (riffEnd != d.length) {
            chunks.add("<data after the RIFF>");
        }
        return chunks;
    }

    /** Whether any zTXt, iTXt or iCCP chunk in a PNG inflates to text containing {@code marker}. */
    private static boolean inflatedTextContains(byte[] d, String marker) {
        int pos = 8;
        while (pos + 8 <= d.length) {
            int length = ByteBuffer.wrap(d, pos, 4).getInt();
            String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("zTXt") || type.equals("iTXt") || type.equals("iCCP")) {
                byte[] body = java.util.Arrays.copyOfRange(d, pos + 8, pos + 8 + length);
                for (int i = 0; i < body.length; i++) {
                    try {
                        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                        inflater.setInput(body, i, body.length - i);
                        byte[] buf = new byte[1 << 16];
                        int n = inflater.inflate(buf);
                        inflater.end();
                        if (n > 0 && contains(java.util.Arrays.copyOf(buf, n), marker)) {
                            return true;
                        }
                    } catch (java.util.zip.DataFormatException ignored) {
                        // not where the compressed data starts
                    }
                }
            }
            pos += 12 + length;
        }
        return false;
    }

    // ---- builders -----------------------------------------------------------------------------

    private File temp(String suffix, byte[] content) throws IOException {
        File f = File.createTempFile("trace_", suffix, app.getCacheDir());
        files.add(f);
        Files.write(f.toPath(), content);
        return f;
    }

    private static byte[] compressed(Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF22AA66);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(format, 90, out);
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buf = new byte[data.length + 64];
        int n = deflater.deflate(buf);
        deflater.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    private static byte[] xmp(String marker) {
        return ascii("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:creator=\"" + marker + "\"/>"
                + "</rdf:RDF></x:xmpmeta>");
    }

    /** Photoshop's image resources with one IPTC record: the by-line (author), 2:80. */
    private static byte[] photoshopIptc(String author) {
        byte[] text = ascii(author);
        byte[] iptc = cat(new byte[] {0x1C, 2, 80, 0, (byte) text.length}, text);
        ByteBuffer resource = ByteBuffer.allocate(4 + 2 + 2 + 4 + iptc.length + (iptc.length & 1));
        resource.put(ascii("8BIM")).putShort((short) 0x0404).putShort((short) 0).putInt(iptc.length).put(iptc);
        return cat(ascii("Photoshop 3.0\0"), resource.array());
    }

    /** A little-endian TIFF block whose IFD0 holds one ASCII tag. */
    private static byte[] tiffAscii(int tag, String value) {
        byte[] text = ascii(value + "\0");
        ByteBuffer b = ByteBuffer.allocate(8 + 2 + 12 + 4 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        b.putShort((short) 1);
        b.putShort((short) tag).putShort((short) 2).putInt(text.length).putInt(26);
        b.putInt(0);
        b.put(text);
        return b.array();
    }

    /**
     * An EXIF block holding only an Orientation, which every clean keeps, with 100 bytes after it
     * that no tag points to: nothing ExifInterface reads, but room for anything.
     */
    private static byte[] orientationWithHiddenBytes() {
        ByteBuffer b = ByteBuffer.allocate(8 + 2 + 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        b.putShort((short) 1);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 1).putShort((short) 0);
        b.putInt(0);
        byte[] hidden = new byte[100];
        byte[] text = ascii("ZQX a name and an address nobody points to");
        System.arraycopy(text, 0, hidden, 0, text.length);
        return cat(b.array(), hidden);
    }

    private interface ExifEdit {
        void apply(ExifInterface exif) throws IOException;
    }

    /** The TIFF block of a JPEG whose EXIF {@code edit} sets, as ExifInterface writes it. */
    private byte[] exifOf(ExifEdit edit) throws IOException {
        File f = temp(".jpg", compressed(Bitmap.CompressFormat.JPEG));
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        edit.apply(exif);
        exif.saveAttributes();
        byte[] block;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            block = ExifBlock.find(in);
        }
        assertNotNull(block);
        return block;
    }

    private static void jpegSegment(ByteArrayOutputStream out, int type, byte[] body) {
        int length = body.length + 2;
        out.write(0xFF);
        out.write(type);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(body, 0, body.length);
    }

    /** {@code jpeg} with one segment put right after SOI. */
    private static byte[] jpegWith(byte[] jpeg, int type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        jpegSegment(out, type, body);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** {@code png} with one chunk put right after IHDR. */
    private static byte[] pngWith(byte[] png, String type, byte[] body) {
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        ExifFixtures.writePngChunk(out, type, body);
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    /** One GIF extension: introducer, label, an optional fixed block, data sub-blocks, terminator. */
    private static void gifExtension(ByteArrayOutputStream out, int label, byte[] header, byte[] data) {
        out.write(0x21);
        out.write(label);
        if (header != null) {
            out.write(header.length);
            out.write(header, 0, header.length);
        }
        for (int i = 0; i < data.length; i += 255) {
            int n = Math.min(255, data.length - i);
            out.write(n);
            out.write(data, i, n);
        }
        out.write(0);
    }

    /** The 1x1 GIF with one extension before its image. */
    private static byte[] gifWith(int label, byte[] header, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(GIF_1X1, 0, 19);
        gifExtension(out, label, header, data);
        out.write(GIF_1X1, 19, GIF_1X1.length - 19);
        return out.toByteArray();
    }
}
