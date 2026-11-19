package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.doubleangels.redact.media.SecureDelete;
import com.doubleangels.redact.metadata.CleanResult;
import com.doubleangels.redact.metadata.MetadataStripper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Cleans each fixture in assets/media through the real share pipeline (real codecs, MediaMuxer,
 * Media3 and MediaMetadataRetriever, which Robolectric only imitates) and checks the result the
 * way .claude/scripts/check_media.py does. The fixtures come from make_instrumented_media.py.
 */
@RunWith(AndroidJUnit4.class)
public class CleanOnDeviceTest {

    /** Byte strings the fixtures carry that no clean file may hold (as check_media.py MARKERS). */
    private static final String[] MARKERS = {"secret", "SecretSoft", "TestCam", "©xyz", "loci"};
    private static final String[] IDENTIFYING_TAGS = {
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_XMP,
    };

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<File> made = new ArrayList<>();

    @After
    public void tearDown() {
        for (File file : made) {
            if (file.exists()) {
                SecureDelete.secureDelete(file);
            }
        }
    }

    @Test
    public void aJpegWithGpsAndCameraTags_comesOutClean() throws Exception {
        assertCleanImage(clean("gps_camera.jpg", false));
    }

    @Test
    public void aJpegWithAnXmpLocation_comesOutClean() throws Exception {
        assertCleanImage(clean("xmp_location.jpg", false));
    }

    @Test
    public void aPngWithGpsInItsExif_comesOutClean() throws Exception {
        assertCleanImage(clean("gps.png", false));
    }

    @Test
    public void anMp4WithALocationAndAComment_comesOutClean() throws Exception {
        assertCleanVideo(clean("location.mp4", true));
    }

    @Test
    public void aVp9WebmWithATitle_comesOutClean() throws Exception {
        assertCleanVideo(clean("title.webm", true));
    }

    // ---- gallery cleans that keep tags ----------------------------------------------------------
    // Writing kept tags back is where ExifInterface leaves an unreferenced copy of IFD0, which
    // the verification must accept, while still refusing anything else. Each combination of Keep
    // options and precision must come out verified, with what it keeps and nothing more.

    /** Settings for one gallery clean: which Keep options are on, and how precise they keep. */
    private static final class Keep {
        final boolean camera, date, location, dateOnly, approximate;

        Keep(boolean camera, boolean date, boolean location, boolean dateOnly, boolean approximate) {
            this.camera = camera;
            this.date = date;
            this.location = location;
            this.dateOnly = dateOnly;
            this.approximate = approximate;
        }

        @Override
        public String toString() {
            return "camera=" + camera + " date=" + date + (dateOnly ? " (date only)" : "")
                    + " location=" + location + (approximate ? " (approximate)" : "");
        }
    }

    private static final Keep[] KEEP_COMBINATIONS = {
            new Keep(true, true, true, false, false),
            new Keep(true, true, true, true, true),
            new Keep(true, false, false, false, false),
            new Keep(false, true, false, false, false),
            new Keep(false, false, true, false, false),
    };

    @After
    public void restoreSettings() {
        AppPreferences.resetAll(context);
        for (Uri uri : published) {
            context.getContentResolver().delete(uri, null, null);
        }
    }

    private final List<Uri> published = new ArrayList<>();

    @Test
    public void aJpeg_cleanedIntoTheGallery_keepsOnlyWhatEachSettingKeeps() throws Exception {
        for (Keep keep : KEEP_COMBINATIONS) {
            assertKeepsOnly(keep, cleanToGallery("gps_camera.jpg", ".jpg", keep), ".jpg");
        }
    }

    @Test
    public void aPng_cleanedIntoTheGallery_keepsOnlyWhatEachSettingKeeps() throws Exception {
        for (Keep keep : KEEP_COMBINATIONS) {
            assertKeepsOnly(keep, cleanToGallery("gps.png", ".png", keep), ".png");
        }
    }

    /**
     * Adds a date, exposure settings, a GPS height and time and an artist to a fixture, cleans it
     * into the gallery with {@code keep}, and returns the saved file's bytes in a private copy.
     */
    private File cleanToGallery(String name, String suffix, Keep keep) throws IOException {
        File source = new File(context.getCacheDir(), "inbound_keep_" + name);
        made.add(source);
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("media/" + name);
                OutputStream out = new FileOutputStream(source)) {
            in.transferTo(out);
        }
        ExifInterface exif = new ExifInterface(source);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:03:04 05:06:07");
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+01:00");
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "42");
        exif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.8");
        exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "100");
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008");
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "93/1");
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0");
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2019:03:04");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "04:06:07");
        exif.setAttribute(ExifInterface.TAG_ARTIST, "secret artist");
        exif.saveAttributes();

        AppPreferences.resetAll(context);
        AppPreferences.setStrictClean(context, false);
        AppPreferences.setPreserveCameraSettings(context, keep.camera);
        AppPreferences.setPreserveDateTaken(context, keep.date);
        AppPreferences.setPreserveLocation(context, keep.location);
        AppPreferences.setDateOnly(context, keep.dateOnly);
        AppPreferences.setApproximateLocation(context, keep.approximate);

        CleanResult cleaned = new MetadataStripper(context).cleanImage(Uri.fromFile(source), name);
        assertTrue("The gallery clean of " + name + " failed verification with " + keep, cleaned.succeeded());
        Uri uri = cleaned.uri();
        assertNotNull(uri);
        published.add(uri);
        File copy = File.createTempFile("kept_", suffix, context.getCacheDir());
        made.add(copy);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(copy)) {
            assertNotNull(in);
            in.transferTo(out);
        }
        return copy;
    }

    private static void assertKeepsOnly(Keep keep, File file, String suffix) throws IOException {
        String bytes = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        String where = file.getName() + " (" + suffix + ", " + keep + ")";
        assertFalse(where + " kept the software", bytes.contains("SecretSoft"));
        assertFalse(where + " kept the artist", bytes.contains("secret artist"));
        assertFalse(where + " kept an XMP packet", bytes.contains("adobe:ns:meta/"));

        ExifInterface exif = new ExifInterface(file);
        assertEquals(where + ": make", keep.camera ? "TestCam" : null, exif.getAttribute(ExifInterface.TAG_MAKE));
        assertEquals(where + ": f-number", keep.camera, exif.getAttribute(ExifInterface.TAG_F_NUMBER) != null);
        assertNull(where + ": software", exif.getAttribute(ExifInterface.TAG_SOFTWARE));
        assertNull(where + ": artist", exif.getAttribute(ExifInterface.TAG_ARTIST));

        String taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        if (!keep.date) {
            assertNull(where + ": date taken", taken);
        } else if (keep.dateOnly) {
            assertEquals(where + ": date only", "2019:03:04 00:00:00", taken);
            assertNull(where + ": time zone", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        } else {
            assertEquals(where + ": date taken", "2019:03:04 05:06:07", taken);
        }
        assertFalse(where + " kept the time of day", keep.dateOnly && bytes.contains("05:06:07"));

        double[] latLong = exif.getLatLong();
        if (!keep.location) {
            assertNull(where + ": location", latLong);
            assertNull(where + ": altitude", exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE));
        } else {
            assertNotNull(where + ": Keep Location lost it", latLong);
            assertEquals(where + ": altitude", keep.approximate,
                    exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) == null);
        }
    }

    /** Copies a fixture to the cache, as a share snapshot is, and cleans it for sharing. */
    private File clean(String name, boolean video) throws IOException {
        File source = new File(context.getCacheDir(), "inbound_test_" + name);
        made.add(source);
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("media/" + name);
                OutputStream out = new FileOutputStream(source)) {
            in.transferTo(out);
        }
        assertMarkersPresent(source);

        MetadataStripper stripper = new MetadataStripper(context);
        CleanResult cleaned = stripper.cleanForSharing(Uri.fromFile(source), name, video);
        assertTrue("The clean of " + name + " failed", cleaned.succeeded());
        File output = cleaned.file();
        assertNotNull(output);
        made.add(output);
        assertTrue(output.length() > 0);
        return output;
    }

    private static void assertMarkersPresent(File source) throws IOException {
        String bytes = new String(Files.readAllBytes(source.toPath()), StandardCharsets.ISO_8859_1);
        boolean any = false;
        for (String marker : MARKERS) {
            any |= bytes.contains(marker);
        }
        assertTrue("The fixture " + source.getName() + " no longer carries a marker", any);
    }

    private static void assertNoMarkers(File file) throws IOException {
        String bytes = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        for (String marker : MARKERS) {
            assertFalse(file.getName() + " still holds " + marker, bytes.contains(marker));
        }
        assertFalse(file.getName() + " still holds an XMP packet", bytes.contains("adobe:ns:meta/"));
    }

    private static void assertCleanImage(File file) throws IOException {
        assertNoMarkers(file);
        ExifInterface exif = new ExifInterface(file);
        for (String tag : IDENTIFYING_TAGS) {
            assertNull(file.getName() + " still has " + tag, exif.getAttribute(tag));
        }
        assertNull(exif.getLatLong());
    }

    private static void assertCleanVideo(File file) throws IOException {
        assertNoMarkers(file);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            assertNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION));
            assertNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            assertNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR));
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
        } finally {
            retriever.release();
        }
    }
}
