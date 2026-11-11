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
        Uri cleaned = stripper.stripMetadataForSharing(Uri.fromFile(source), name, video);
        assertNotNull("The clean of " + name + " failed", cleaned);
        File output = stripper.getLastProcessedOutputFile();
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
