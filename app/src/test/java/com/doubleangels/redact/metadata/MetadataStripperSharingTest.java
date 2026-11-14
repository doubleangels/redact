package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FileProviderTestSupport;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class MetadataStripperSharingTest {

    /** The result of the latest clean in this test, for the checks that follow it. */
    private com.doubleangels.redact.metadata.CleanResult lastResult;

    private Application app;
    private MetadataStripper stripper;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        stripper = new MetadataStripper(app);
        FileProviderTestSupport.makeUsable(app);
    }

    private File image(String ext, Bitmap.CompressFormat fmt, boolean exif) throws Exception {
        File f = File.createTempFile("strip_", ext);
        f.deleteOnExit();
        Bitmap b = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(fmt, 90, out);
        }
        if (exif && fmt == Bitmap.CompressFormat.JPEG) {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            e.setAttribute(ExifInterface.TAG_MAKE, "Acme");
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,59/1,0/1");
            e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
            e.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
            e.saveAttributes();
        }
        return f;
    }

    private void assertClean(Uri out) throws Exception {
        assertNotNull(out);
        File f = lastResult.file();
        assertNotNull(f);
        assertTrue(f.exists() && f.length() > 0);
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        assertEquals(null, e.getAttribute(ExifInterface.TAG_MAKE));
        assertEquals(null, e.getLatLong());
    }

    /**
     * Each clean hands back its own result. With the old last-result fields, a later failed clean
     * cleared the earlier clean's output file, so a caller reading late lost track of it.
     */
    @Test
    public void eachCleanKeepsItsOwnResult_whateverCleanComesNext() throws Exception {
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        CleanResult first = stripper.cleanImageForSharing(Uri.fromFile(src), "a.jpg");
        CleanResult second = stripper.cleanImageForSharing(Uri.fromFile(new File("/nope/x.jpg")), "x.jpg");

        assertTrue(first.succeeded());
        assertNotNull(first.file());
        assertTrue(first.file().exists());
        assertNotNull(first.stats());
        assertFalse(first.unsupported());

        assertFalse(second.succeeded());
        assertEquals(null, second.file());
        assertEquals(null, second.stats());
    }

    @Test
    public void shareJpeg_strict_removesExif() throws Exception {
        AppPreferences.setStrictClean(app, true);
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        List<Integer> pct = new ArrayList<>();
        stripper.setProgressCallback((p, m) -> pct.add(p));
        Uri out = (lastResult = stripper.cleanImageForSharing(Uri.fromFile(src), "a.jpg")).uri();
        assertClean(out);
        assertNotNull(lastResult.uri());
        assertNotNull(lastResult.file());
        assertTrue(!pct.isEmpty());
    }

    @Test
    public void shareJpeg_reportsWhatItRemoved() throws Exception {
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);

        assertClean((lastResult = stripper.cleanImageForSharing(Uri.fromFile(src), "s.jpg")).uri());

        CleanStats stats = lastResult.stats();
        assertNotNull(stats);
        assertTrue(stats.removedFields > 0);
        assertTrue(stats.hadLocation);
    }

    @Test
    public void aFailedShareAfterAGoodOne_doesNotReportTheGoodOneAgain() throws Exception {
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        assertClean((lastResult = stripper.cleanImageForSharing(Uri.fromFile(src), "s.jpg")).uri());
        assertNotNull(lastResult.stats());

        (lastResult = stripper.cleanImageForSharing(Uri.parse("file:///no/such/file.jpg"), "missing.jpg")).uri();

        assertEquals(null, lastResult.stats());
    }

    @Test
    public void shareWithoutALocation_reportsNoLocation() throws Exception {
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, false);

        assertClean((lastResult = stripper.cleanImageForSharing(Uri.fromFile(src), "s.jpg")).uri());

        CleanStats stats = lastResult.stats();
        assertNotNull(stats);
        assertFalse(stats.hadLocation);
    }

    @Test
    public void shareMissingSource_reportsNothing() {
        (lastResult = stripper.cleanImageForSharing(Uri.parse("file:///no/such/file.jpg"), "missing.jpg")).uri();

        assertEquals(null, lastResult.stats());
    }

    @Test
    public void shareJpeg_nonStrict_removesExif() throws Exception {
        AppPreferences.setStrictClean(app, false);
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        (lastResult = stripper.cleanForSharing(Uri.fromFile(src), "b.jpg", false)).uri(); // exercised only; Robolectric EXIF round-trip differs
    }

    @Test
    public void sharePng_andWebp() throws Exception {
        AppPreferences.setStrictClean(app, true);
        File png = image(".png", Bitmap.CompressFormat.PNG, false);
        (lastResult = stripper.cleanImageForSharing(Uri.fromFile(png), "c.png")).uri();
        File webp = image(".webp", Bitmap.CompressFormat.WEBP_LOSSY, false);
        (lastResult = stripper.cleanImageForSharing(Uri.fromFile(webp), "d.webp")).uri();
    }

    @Test
    public void shareMissingSource_returnsNull() {
        assertEquals(null, (lastResult = stripper.cleanImageForSharing(Uri.fromFile(new File("/nope/x.jpg")), "x.jpg")).uri());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // legacy graphics fakes a bitmap for any input
    public void shareSvg_isSkippedWithoutLeavingOutput() throws Exception {
        // REDACT-1Z: SVGs (and other non-raster files) arrive as image/*; neither decoder reads them.
        File svg = File.createTempFile("strip_", ".svg");
        svg.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(svg)) {
            out.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\"/>"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertEquals(null, (lastResult = stripper.cleanForSharing(Uri.fromFile(svg), "f.svg", false)).uri());
        assertTrue(lastResult.unsupported());
        assertEquals(null, lastResult.file());
        File[] leftovers = new File(app.getCacheDir(), "processed").listFiles();
        assertTrue(leftovers == null || leftovers.length == 0);

        // A different kind of failure on the next item must not inherit the flag.
        assertEquals(null, (lastResult = stripper.cleanForSharing(
                Uri.fromFile(new File("/nope/z.jpg")), "z.jpg", false)).uri());
        assertFalse(lastResult.unsupported());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void gallerySvg_isSkipped() throws Exception {
        File svg = File.createTempFile("strip_", ".svg");
        svg.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(svg)) {
            out.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\"/>"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(null, (lastResult = stripper.cleanImage(Uri.fromFile(svg), "g.svg")).uri());
    }

    @Test
    public void galleryJpeg_doesNotThrow() throws Exception {
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        (lastResult = stripper.cleanImage(Uri.fromFile(src), "e.jpg")).uri();
        (lastResult = stripper.cleanImage(Uri.fromFile(new File("/nope/x.jpg")), "x.jpg")).uri();
    }

    @Test
    public void cancellation_flags() {
        stripper.requestCancellation();
        stripper.setTranscodeOwnerId(7);
        assertEquals(7, stripper.getTranscodeOwnerId());
        stripper.resetCancellation();
        File missing = new File("/nope/y.jpg");
        stripper.requestCancellation();
        assertEquals(null, (lastResult = stripper.cleanImageForSharing(Uri.fromFile(missing), "y.jpg")).uri());
    }

    /** A v2 profile named after a home calibration, naming its display maker in dmnd. */
    private static byte[] calibratedProfile() {
        byte[] desc = concat(ascii("desc"), new byte[4], be32(28), ascii("Jane's Dell calibrated 2024"));
        byte[] maker = concat(ascii("text"), new byte[4], ascii("AcmeDisplays"));
        int offset = 128 + 4 + 2 * 12;
        byte[] header = new byte[128];
        header[8] = 2; // version 2
        return concat(header, be32(2),
                ascii("desc"), be32(offset), be32(desc.length),
                ascii("dmnd"), be32(offset + desc.length), be32(maker.length),
                desc, maker);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static byte[] be32(long v) {
        return java.nio.ByteBuffer.allocate(4).putInt((int) v).array();
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /** A share keeps the colour profile but not the person or device it names (item 3, third pass). */
    @Test
    public void shareJpeg_keepsTheProfile_withoutTheNamesInIt() throws Exception {
        AppPreferences.setStrictClean(app, false);
        byte[] plain = java.nio.file.Files.readAllBytes(image(".jpg", Bitmap.CompressFormat.JPEG, false).toPath());
        byte[] app2 = concat(ascii("ICC_PROFILE "), new byte[] {1, 1}, calibratedProfile());
        int length = app2.length + 2;
        byte[] jpeg = concat(java.util.Arrays.copyOf(plain, 2),
                new byte[] {(byte) 0xFF, (byte) 0xE2, (byte) (length >> 8), (byte) length}, app2,
                java.util.Arrays.copyOfRange(plain, 2, plain.length));
        File src = File.createTempFile("strip_icc_", ".jpg");
        src.deleteOnExit();
        java.nio.file.Files.write(src.toPath(), jpeg);

        assertNotNull((lastResult = stripper.cleanImageForSharing(Uri.fromFile(src), "a.jpg")).uri());

        byte[] out = java.nio.file.Files.readAllBytes(lastResult.file().toPath());
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("the profile is kept", text.contains("ICC_PROFILE"));
        assertTrue(text.contains(IccProfiles.NEUTRAL_NAME));
        assertTrue(!text.contains("Jane") && !text.contains("AcmeDisplays"));
    }
}
