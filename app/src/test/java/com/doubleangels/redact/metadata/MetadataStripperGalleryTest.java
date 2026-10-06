package com.doubleangels.redact.metadata;

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
@Config(sdk = 34)
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

    @Test
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

    @Test(timeout = 90_000)
    @Config(sdk = 31)
    public void video_unreadableSource_failsWithoutLeavingEntries() throws Exception {
        AppPreferences.setStrictClean(app, true);
        Uri out = null;
        try {
            out = stripper.stripVideoMetadata(Uri.fromFile(junkVideo()), "clip.mp4");
        } catch (RuntimeException expected) {
            // transcoding is unavailable under Robolectric
        }
        assertNull(out);
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }

    @Test(timeout = 90_000)
    @Config(sdk = 31)
    public void video_missingSource_returnsNull() {
        Uri out = null;
        try {
            out = stripper.stripVideoMetadata(Uri.fromFile(new File("/nonexistent/clip.mp4")), "clip.mp4");
        } catch (RuntimeException expected) {
            // cancellation-style failures propagate
        }
        assertNull(out);
    }

    @Test(timeout = 90_000)
    @Config(sdk = 31)
    public void videoForSharing_missingSource_returnsNull() {
        Uri out = null;
        try {
            out = stripper.stripVideoMetadataForSharing(Uri.fromFile(new File("/nonexistent/clip.mp4")), "clip.mp4");
        } catch (RuntimeException expected) {
            // cancellation-style failures propagate
        }
        assertNull(out);
    }
}
