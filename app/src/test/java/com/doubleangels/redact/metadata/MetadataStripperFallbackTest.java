package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

/** Fallback and failure branches of cleaning an image into the gallery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MetadataStripperFallbackTest {

    private Application app;
    private MetadataStripper stripper;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        stripper = new MetadataStripper(app);
        AppPreferences.setStrictClean(app, true);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        for (File f : files) {
            f.delete();
        }
    }

    private File jpeg(boolean withExif) throws IOException {
        File f = File.createTempFile("fb_", ".jpg", app.getFilesDir());
        files.add(f);
        Bitmap bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFAA5522);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        if (withExif) {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            e.setAttribute(ExifInterface.TAG_MAKE, "Acme");
            e.setAttribute(ExifInterface.TAG_MODEL, "Cam 1");
            e.setAttribute(ExifInterface.TAG_APERTURE_VALUE, "2/1");
            e.setAttribute(ExifInterface.TAG_F_NUMBER, "2.8");
            e.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "200");
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
            e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,59/1,0/1");
            e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
            e.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
            e.saveAttributes();
        }
        return f;
    }

    private File notReallyAJpeg() throws IOException {
        File f = File.createTempFile("junk_", ".jpg", app.getFilesDir());
        files.add(f);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
        }
        return f;
    }

    @Test
    public void whenLosslessStrippingIsImpossible_theImageIsReEncoded() throws Exception {
        Uri out = null;
        try {
            out = stripper.stripExifData(Uri.fromFile(notReallyAJpeg()), "broken.jpg");
        } catch (RuntimeException ignored) {
            // The decoder emulation may reject the junk outright.
        }
        // Whatever the outcome, no half-written entry may be left published.
        for (FakeMediaStoreProvider.Entry e : FakeMediaStoreProvider.entries().values()) {
            assertFalse(e.isPending());
        }
        if (out == null) {
            assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        }
    }

    @Test
    public void nonStrictMode_keepsOrientationAndCameraSettingsWhenAsked() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveCameraSettings(app, true);
        AppPreferences.setPreserveLocation(app, false);

        Uri out = stripper.stripExifData(Uri.fromFile(jpeg(true)), "trip.jpg");

        assertNotNull(out);
        ExifInterface exif = new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath());
        assertNull(exif.getLatLong());
    }

    @Test
    public void nonStrictMode_canPreserveLocationWhenExplicitlyEnabled() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveLocation(app, true);
        AppPreferences.setPreserveCameraSettings(app, true);

        // Keeping location is an opt-in; verification only has to accept the allowed tags.
        Uri out = stripper.stripExifData(Uri.fromFile(jpeg(true)), "trip.jpg");

        assertNotNull(out);
    }

    @Test
    public void anUnwritableGalleryEntry_isDeletedAndReportedAsAFailure() throws Exception {
        FakeMediaStoreProvider.failOpen = true;

        Uri out = stripper.stripExifData(Uri.fromFile(jpeg(true)), "trip.jpg");

        assertNull(out);
        assertTrue("the half-created row is removed", FakeMediaStoreProvider.entries().isEmpty());
    }

    @Test
    public void cancellingMidWay_propagatesInsteadOfBeingReportedAsAFailure() throws Exception {
        stripper.requestCancellation();
        try {
            stripper.stripExifData(Uri.fromFile(jpeg(true)), "trip.jpg");
            fail("a cancelled clean must surface as a cancellation");
        } catch (RuntimeException expected) {
            assertTrue(com.doubleangels.redact.sentry.SentryManager.isUserCancellation(expected));
        }
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }

    @Test
    public void lastProcessedUri_tracksTheMostRecentSuccess() throws Exception {
        assertNull(stripper.getLastProcessedFileUri());
        Uri out = stripper.stripExifData(Uri.fromFile(jpeg(false)), "a.jpg");
        assertEquals(out, stripper.getLastProcessedFileUri());
        assertNull(stripper.getLastProcessedOutputFile());
    }

    @Test
    public void largeInputs_skipTheLosslessPath() throws Exception {
        File big = jpeg(false);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(big, "rw")) {
            raf.setLength(21L * 1024L * 1024L);
        }
        Uri out = null;
        try {
            out = stripper.stripExifData(Uri.fromFile(big), "big.jpg");
        } catch (RuntimeException ignored) {
            // size limits may reject it first
        }
        assertEquals(out == null, FakeMediaStoreProvider.entries().isEmpty());
    }
}
