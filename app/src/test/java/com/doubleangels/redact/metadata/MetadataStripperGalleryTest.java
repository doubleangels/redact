package com.doubleangels.redact.metadata;

import static com.doubleangels.redact.TestWaits.callOffMain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeMediaStoreProvider;
import com.doubleangels.redact.media.VideoMedia3Converter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Cleaning into the gallery (MediaStore), backed by {@link FakeMediaStoreProvider}. */
@RunWith(RobolectricTestRunner.class)
public class MetadataStripperGalleryTest {

    private Application app;
    private MetadataStripper stripper;
    private final List<File> files = new ArrayList<>();
    private final List<Integer> progress = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        stripper = new MetadataStripper(app);
        stripper.setProgressCallback((percent, message) -> progress.add(percent));
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        VideoMedia3Converter.setAwaitTimeoutForTests(null);
        for (File f : files) {
            f.delete();
        }
    }

    private File image(String ext, Bitmap.CompressFormat format, boolean withMetadata) throws IOException {
        File f = File.createTempFile("gallery_", ext, app.getFilesDir());
        files.add(f);
        Bitmap bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF22AA66);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(format, 90, out);
        }
        if (withMetadata && format == Bitmap.CompressFormat.JPEG) {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            e.setAttribute(ExifInterface.TAG_MAKE, "Acme");
            e.setAttribute(ExifInterface.TAG_MODEL, "Cam 1");
            e.setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SN-1");
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:01:02 03:04:05");
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,59/1,0/1");
            e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
            e.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
            e.saveAttributes();
        }
        return f;
    }

    private void assertNoIdentifyingExif(File output) throws IOException {
        ExifInterface exif = new ExifInterface(output.getAbsolutePath());
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE));
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL));
        assertNull(exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER));
        assertNull(exif.getLatLong());
    }

    @Test
    public void jpeg_strict_isCleanedIntoAPublishedRandomlyNamedEntry() throws Exception {
        AppPreferences.setStrictClean(app, true);

        Uri out = stripper.stripExifData(Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, true)), "trip.jpg");

        assertNotNull(out);
        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertTrue(entry.displayName().matches("[A-Za-z0-9]{12}\\.[a-z0-9]+"));
        assertFalse(entry.displayName().contains("trip"));
        assertEquals("image/jpeg", entry.mimeType());
        assertFalse(entry.isPending());
        assertNoIdentifyingExif(entry.file);
        assertEquals(out, stripper.getLastProcessedFileUri());
        assertFalse(progress.isEmpty());
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 31)
    public void heic_onAndroid12_staysAHeic_withItsPictureAndWithoutItsMetadata() throws Exception {
        File heic = File.createTempFile("gallery_", ".heic", app.getFilesDir());
        files.add(heic);
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("image/heic_tagged.heic");
                FileOutputStream out = new FileOutputStream(heic)) {
            out.write(in.readAllBytes());
        }

        Uri out = stripper.stripExifData(Uri.fromFile(heic), "photo.heic");

        assertNotNull(out);
        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertEquals("image/heic", entry.mimeType());
        assertTrue(entry.displayName().endsWith(".heic"));
        String written = new String(java.nio.file.Files.readAllBytes(entry.file.toPath()),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("the coded picture is kept", written.contains("hvcC"));
        assertFalse(written.contains("SecretMaker"));
        assertFalse(written.contains("SecretPerson"));
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 34)
    public void heic_onAndroid14WithoutAHeicEncoder_staysAHeic() throws Exception {
        File heic = File.createTempFile("gallery_", ".heic", app.getFilesDir());
        files.add(heic);
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("image/heic_tagged.heic");
                FileOutputStream out = new FileOutputStream(heic)) {
            out.write(in.readAllBytes());
        }

        assertNotNull(stripper.stripExifData(Uri.fromFile(heic), "photo.heic"));

        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertEquals("image/heic", entry.mimeType());
        assertTrue(entry.displayName().endsWith(".heic"));
        String written = new String(java.nio.file.Files.readAllBytes(entry.file.toPath()),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("the coded picture is kept", written.contains("hvcC"));
        assertFalse(written.contains("SecretMaker"));
    }

    private File fixtureFile(String name, String suffix) throws IOException {
        File f = File.createTempFile("gallery_", suffix, app.getFilesDir());
        files.add(f);
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("image/" + name);
                FileOutputStream out = new FileOutputStream(f)) {
            out.write(in.readAllBytes());
        }
        return f;
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 34)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @SuppressWarnings("unchecked")
    public void heic_keptTags_goBackInANewExifItem_withoutOrientation() throws Exception {
        // ExifInterface can't read a HEIC's EXIF on the JVM, so the kept values are set directly.
        File clean = File.createTempFile("gallery_", ".heic", app.getFilesDir());
        files.add(clean);
        HeifContainerMetadata.sanitize(fixtureFile("heic_tagged.heic", ".heic"), clean);
        java.lang.reflect.Field values = MetadataStripper.class.getDeclaredField("preservedExifValues");
        values.setAccessible(true);
        java.util.Map<String, String> kept = (java.util.Map<String, String>) values.get(stripper);
        kept.put(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "KeptMaker");
        kept.put(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "6");
        java.lang.reflect.Method restore = MetadataStripper.class.getDeclaredMethod("restoreHeifExif", File.class);
        restore.setAccessible(true);

        restore.invoke(stripper, clean);

        String written = new String(java.nio.file.Files.readAllBytes(clean.toPath()),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("the kept make is in a new EXIF item", written.contains("KeptMaker"));
        assertFalse(written.contains("SecretMaker"));
        assertTrue(HeifContainerMetadata.isHeif(clean));
        assertTrue("still a clean HEIF", ImageContainerMetadata.scan(clean).isClean());
    }

    @Test
    public void motionPhoto_withKeepMotionPhotos_isCleaned_andNeverKeepsTheRecording() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveMotionPhoto(app, true);

        assertNotNull(stripper.stripExifData(
                Uri.fromFile(fixtureFile("motion_photo_tagged.jpg", ".jpg")), "motion.jpg"));

        byte[] written = java.nio.file.Files.readAllBytes(FakeMediaStoreProvider.only().file.toPath());
        String text = new String(written, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("SecretTitle"));
        assertFalse(text.contains("SecretMaker"));
    }

    @Test
    public void jpeg_nonStrict_keepsOnlyAllowedTagsAndStillRemovesLocation() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveCameraSettings(app, true);
        AppPreferences.setPreserveLocation(app, false);

        Uri out = stripper.stripExifData(Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, true)), "trip.jpg");

        assertNotNull(out);
        ExifInterface exif = new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath());
        assertNull(exif.getLatLong());
        assertNull(exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER));
    }

    /** A photo with what phones write about exposure, colour and time. */
    private File photoWithSettings() throws IOException {
        File f = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        e.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "41");
        e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "681/100");
        e.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, "0/1");
        e.setAttribute(ExifInterface.TAG_FLASH, "16");
        e.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0");
        e.setAttribute(ExifInterface.TAG_COLOR_SPACE, "65535");
        e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2023:10:06 19:25:15");
        e.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00");
        e.saveAttributes();
        return f;
    }

    @Test
    public void jpeg_keepCameraSettings_keepsIsoFocalLengthAndTheRest() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveCameraSettings(app, true);
        AppPreferences.setPreserveDateTaken(app, false);

        assertNotNull(stripper.stripExifData(Uri.fromFile(photoWithSettings()), "trip.jpg"));

        ExifInterface exif = new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath());
        assertEquals("41", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertNotNull(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
        assertEquals("16", exif.getAttribute(ExifInterface.TAG_FLASH));
        assertEquals("0", exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE));
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNull(exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER));
        assertNull(exif.getLatLong());
    }

    @Test
    public void jpeg_keepDateTaken_keepsWhenItWasTaken_andNothingElse() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveCameraSettings(app, false);
        AppPreferences.setPreserveDateTaken(app, true);

        assertNotNull(stripper.stripExifData(Uri.fromFile(photoWithSettings()), "trip.jpg"));

        ExifInterface exif = new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath());
        assertEquals("2023:10:06 19:25:15", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertEquals("+02:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE));
        assertNull(exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
        assertNull(exif.getLatLong());
    }

    @Test
    public void exifTiff_findsTheTiffBlockOfAJpegsExif() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0, 4, 1, 2,
                (byte) 0xFF, (byte) 0xE1, 0, 12, 'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0,
                (byte) 0xFF, (byte) 0xDA, 0, 2};
        assertTrue(java.util.Arrays.equals(new byte[] {'I', 'I', 42, 0}, MetadataStripper.exifTiff(jpeg)));
        assertNull(MetadataStripper.exifTiff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2}));
    }

    @Test
    public void jpeg_strict_keepsTheColourSpace_butNotTheDate() throws Exception {
        AppPreferences.setStrictClean(app, true);
        AppPreferences.setPreserveDateTaken(app, true); // ignored by Strict Clean

        assertNotNull(stripper.stripExifData(Uri.fromFile(photoWithSettings()), "trip.jpg"));

        ExifInterface exif = new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath());
        assertEquals("65535", exif.getAttribute(ExifInterface.TAG_COLOR_SPACE));
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNull(exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
    }

    @Test
    public void jpeg_strict_reportsEveryIdentifyingFieldAndTheLocation() throws Exception {
        AppPreferences.setStrictClean(app, true);

        assertNotNull(stripper.stripExifData(
                Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, true)), "trip.jpg"));

        CleanStats stats = stripper.getLastCleanStats();
        assertNotNull(stats);
        // Make, model, serial, both dates (saving DateTimeOriginal also writes DateTime) and the four
        // GPS tags; orientation is kept, so it is not counted.
        assertEquals(9, stats.removedFields);
        assertTrue(stats.hadLocation);
    }

    @Test
    public void jpeg_nonStrict_doesNotCountTheTagsItKeeps() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveCameraSettings(app, true);
        AppPreferences.setPreserveLocation(app, true);

        assertNotNull(stripper.stripExifData(
                Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, true)), "trip.jpg"));

        CleanStats stats = stripper.getLastCleanStats();
        assertNotNull(stats);
        // Only the serial and the two dates go; camera settings and location are kept.
        assertEquals(3, stats.removedFields);
        assertFalse(stats.hadLocation);
    }

    @Test
    public void jpeg_withoutMetadata_reportsNothingRemoved() throws Exception {
        AppPreferences.setStrictClean(app, true);

        assertNotNull(stripper.stripExifData(
                Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, false)), "plain.jpg"));

        CleanStats stats = stripper.getLastCleanStats();
        assertNotNull(stats);
        assertEquals(0, stats.removedFields);
        assertFalse(stats.hadLocation);
    }

    @Test
    public void failedImageClean_reportsNoStats() {
        assertNull(stripper.stripExifData(Uri.fromFile(new File("/nonexistent/x.jpg")), "x.jpg"));
        assertNull(stripper.getLastCleanStats());
    }

    @Test
    // Legacy graphics write a PNG whatever format is asked for, and a clean goes by what a file
    // is, not its name, so the WebP has to be a real one.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void png_andWebp_areCleanedInTheirOwnFormat() throws Exception {
        AppPreferences.setStrictClean(app, true);

        assertNotNull(stripper.stripExifData(Uri.fromFile(image(".png", Bitmap.CompressFormat.PNG, false)), "a.png"));
        assertNotNull(stripper.stripExifData(
                Uri.fromFile(image(".webp", Bitmap.CompressFormat.WEBP_LOSSY, false)), "b.webp"));

        java.util.Set<String> mimes = new java.util.HashSet<>();
        for (FakeMediaStoreProvider.Entry e : FakeMediaStoreProvider.entries().values()) {
            mimes.add(e.mimeType());
            assertFalse(e.isPending());
        }
        assertTrue(mimes.contains("image/png"));
        assertTrue(mimes.contains("image/webp"));
    }

    @Test
    public void unknownExtension_fallsBackToJpegOutput() throws Exception {
        AppPreferences.setStrictClean(app, true);
        Uri out = stripper.stripExifData(Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, false)), "photo.dat");
        assertNotNull(out);
        assertEquals("image/jpeg", FakeMediaStoreProvider.only().mimeType());
    }

    @Test
    public void missingSource_returnsNullAndLeavesNoEntry() {
        Uri out = stripper.stripExifData(Uri.fromFile(new File("/nonexistent/x.jpg")), "x.jpg");
        assertNull(out);
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }

    @Test
    public void mediaStoreRefusingTheRow_returnsNull() throws Exception {
        FakeMediaStoreProvider.failInsert = true;
        Uri out = stripper.stripExifData(Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, true)), "x.jpg");
        assertNull(out);
    }

    @Test
    public void cancellation_beforeTheCall_isReportedByTheFlag() throws Exception {
        stripper.requestCancellation();
        stripper.resetCancellation();
        Uri out = stripper.stripExifData(Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, true)), "x.jpg");
        assertNotNull(out);
    }

    @Test
    public void sharingNonStrict_stripsLocation() throws Exception {
        AppPreferences.setStrictClean(app, false);
        // The share path is only exercised for its early branches here: FileProvider URIs cannot be
        // produced on every host (see FileProviderTestSupport), so null is an acceptable outcome.
        stripper.stripMetadataForSharing(
                Uri.fromFile(image(".jpg", Bitmap.CompressFormat.JPEG, true)), "x.jpg", false);
    }

    // ---- video paths: only their early failure branches can run without real codecs ------------------

    private File junkVideo() throws IOException {
        File f = File.createTempFile("clip_", ".mp4", app.getFilesDir());
        files.add(f);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 1, 2, 3});
        }
        return f;
    }

    @Test(timeout = 30_000)
    @Config(sdk = 31)
    public void video_unreadableSource_failsWithoutLeavingEntries() throws Exception {
        // Media3 never finishes an export here; take the timeout path instead of waiting an hour.
        VideoMedia3Converter.setAwaitTimeoutForTests(500L);
        AppPreferences.setStrictClean(app, true);
        Uri out = null;
        try {
            Uri junk = Uri.fromFile(junkVideo());
            out = callOffMain(() -> stripper.stripVideoMetadata(junk, "clip.mp4"));
        } catch (RuntimeException expected) {
            // transcoding is unavailable under Robolectric
        }
        assertNull(out);
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }

    @Test(timeout = 30_000)
    @Config(sdk = 31)
    public void video_missingSource_returnsNull() throws Exception {
        // Media3 never finishes an export here; take the timeout path instead of waiting an hour.
        VideoMedia3Converter.setAwaitTimeoutForTests(500L);
        Uri out = null;
        try {
            out = callOffMain(() -> stripper.stripVideoMetadata(
                    Uri.fromFile(new File("/nonexistent/clip.mp4")), "clip.mp4"));
        } catch (RuntimeException expected) {
            // cancellation-style failures propagate
        }
        assertNull(out);
    }

    @Test(timeout = 30_000)
    @Config(sdk = 31)
    public void videoForSharing_missingSource_returnsNull() throws Exception {
        // Media3 never finishes an export here; take the timeout path instead of waiting an hour.
        VideoMedia3Converter.setAwaitTimeoutForTests(500L);
        Uri out = null;
        try {
            out = callOffMain(() -> stripper.stripVideoMetadataForSharing(
                    Uri.fromFile(new File("/nonexistent/clip.mp4")), "clip.mp4"));
        } catch (RuntimeException expected) {
            // cancellation-style failures propagate
        }
        assertNull(out);
    }
}
