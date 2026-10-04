package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MetadataDisplayerExtractionTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_MEDIA_LOCATION);
    }

    static void await(BooleanSupplier done) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("timed out", done.getAsBoolean());
    }

    private Uri jpegWithExif() throws Exception {
        File f = File.createTempFile("disp_", ".jpg");
        f.deleteOnExit();
        Bitmap b = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_MAKE, "Acme");
        exif.setAttribute(ExifInterface.TAG_MODEL, "Cam1");
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2020:01:02 03:04:05");
        exif.setAttribute(ExifInterface.TAG_F_NUMBER, "2.8");
        exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "100");
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.01");
        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "35/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,59/1,0/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Tool");
        exif.saveAttributes();
        return Uri.fromFile(f);
    }

    @Test
    public void extractMetadata_image_returnsText() throws Exception {
        Uri uri = jpegWithExif();
        AtomicReference<String> text = new AtomicReference<>();
        MetadataDisplayer.extractMetadata(app, uri, new MetadataDisplayer.MetadataCallback() {
            @Override public void onMetadataExtracted(String m, boolean v) { assertFalse(v); text.set(m); }
            @Override public void onExtractionFailed(String e) { text.set("FAIL"); }
        });
        await(() -> text.get() != null);
        assertTrue(text.get(), text.get().contains("Acme"));
    }

    @Test
    public void extractSectioned_image_splitsSections() throws Exception {
        Uri uri = jpegWithExif();
        AtomicReference<Map<String, String>> res = new AtomicReference<>();
        MetadataDisplayer.extractSectionedMetadata(app, uri, new MetadataDisplayer.SectionedMetadataCallback() {
            @Override public void onMetadataExtracted(Map<String, String> s, boolean v) { res.set(s); }
            @Override public void onExtractionFailed(String e) { }
        });
        await(() -> res.get() != null);
        Map<String, String> s = res.get();
        assertNotNull(s.get(MetadataDisplayer.SECTION_BASIC_INFO));
        assertNotNull(s.toString().replace((char)0x1e,(char)10).replace((char)0x1f,(char)58), s.get(MetadataDisplayer.SECTION_LOCATION));
        assertNotNull(s.get(MetadataDisplayer.SECTION_CAMERA_DETAILS));
        double[] c = MetadataDisplayer.resolveMapCoordinates(s);
        assertNotNull(c);
        assertEquals(40.5, c[0], 0.01);
        assertEquals(-73.98, c[1], 0.02);
    }

    @Test
    public void extractLocationSectionOnly_image() throws Exception {
        Uri uri = jpegWithExif();
        AtomicReference<String> res = new AtomicReference<>();
        boolean[] done = {false};
        MetadataDisplayer.extractLocationSectionOnly(app, uri, new MetadataDisplayer.LocationSectionCallback() {
            @Override public void onLocationSectionExtracted(String c) { res.set(c); done[0] = true; }
            @Override public void onExtractionFailed(String e) { done[0] = true; }
        });
        await(() -> done[0]);
        assertNotNull(res.get());
    }

    @Test
    public void extract_missingFile_reportsFailureOrEmpty() throws Exception {
        Uri uri = Uri.fromFile(new File("/nonexistent/none.jpg"));
        boolean[] done = {false};
        MetadataDisplayer.extractSectionedMetadata(app, uri, new MetadataDisplayer.SectionedMetadataCallback() {
            @Override public void onMetadataExtracted(Map<String, String> s, boolean v) { done[0] = true; }
            @Override public void onExtractionFailed(String e) { done[0] = true; }
        });
        await(() -> done[0]);
    }

    @Test
    public void extract_video_path() throws Exception {
        File f = File.createTempFile("vid_", ".mp4");
        f.deleteOnExit();
        boolean[] done = {false};
        MetadataDisplayer.extractMetadata(app, Uri.fromFile(f), new MetadataDisplayer.MetadataCallback() {
            @Override public void onMetadataExtracted(String m, boolean v) { done[0] = true; }
            @Override public void onExtractionFailed(String e) { done[0] = true; }
        });
        await(() -> done[0]);
        boolean[] d2 = {false};
        MetadataDisplayer.extractSectionedMetadata(app, Uri.fromFile(f), new MetadataDisplayer.SectionedMetadataCallback() {
            @Override public void onMetadataExtracted(Map<String, String> s, boolean v) { d2[0] = true; }
            @Override public void onExtractionFailed(String e) { d2[0] = true; }
        });
        await(() -> d2[0]);
    }

    @Test
    public void cancelActiveScan_isSafe() {
        MetadataDisplayer.cancelActiveScan();
        MetadataDisplayer.cancelActiveScan();
    }
}
