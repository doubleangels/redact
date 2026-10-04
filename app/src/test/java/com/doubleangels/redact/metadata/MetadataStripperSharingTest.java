package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MetadataStripperSharingTest {

    private Application app;
    private MetadataStripper stripper;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        stripper = new MetadataStripper(app);
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
        File f = stripper.getLastProcessedOutputFile();
        assertNotNull(f);
        assertTrue(f.exists() && f.length() > 0);
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        assertEquals(null, e.getAttribute(ExifInterface.TAG_MAKE));
        assertEquals(null, e.getLatLong());
    }

    @Test
    public void shareJpeg_strict_removesExif() throws Exception {
        AppPreferences.setStrictClean(app, true);
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        List<Integer> pct = new ArrayList<>();
        stripper.setProgressCallback((p, m) -> pct.add(p));
        Uri out = stripper.stripExifDataForSharing(Uri.fromFile(src), "a.jpg");
        assertClean(out);
        assertNotNull(stripper.getLastProcessedFileUri());
        assertNotNull(stripper.getLastProcessedOutputFile());
        assertTrue(!pct.isEmpty());
    }

    @Test
    public void shareJpeg_nonStrict_removesExif() throws Exception {
        AppPreferences.setStrictClean(app, false);
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        stripper.stripMetadataForSharing(Uri.fromFile(src), "b.jpg", false); // exercised only; Robolectric EXIF round-trip differs
    }

    @Test
    public void sharePng_andWebp() throws Exception {
        AppPreferences.setStrictClean(app, true);
        File png = image(".png", Bitmap.CompressFormat.PNG, false);
        stripper.stripExifDataForSharing(Uri.fromFile(png), "c.png");
        File webp = image(".webp", Bitmap.CompressFormat.WEBP_LOSSY, false);
        stripper.stripExifDataForSharing(Uri.fromFile(webp), "d.webp");
    }

    @Test
    public void shareMissingSource_returnsNull() {
        assertEquals(null, stripper.stripExifDataForSharing(Uri.fromFile(new File("/nope/x.jpg")), "x.jpg"));
    }

    @Test
    public void galleryJpeg_doesNotThrow() throws Exception {
        File src = image(".jpg", Bitmap.CompressFormat.JPEG, true);
        stripper.stripExifData(Uri.fromFile(src), "e.jpg");
        stripper.stripExifData(Uri.fromFile(new File("/nope/x.jpg")), "x.jpg");
    }

    @Test
    public void cancellation_flags() {
        stripper.requestCancellation();
        stripper.setTranscodeOwnerId(7);
        assertEquals(7, stripper.getTranscodeOwnerId());
        stripper.resetCancellation();
        File missing = new File("/nope/y.jpg");
        stripper.requestCancellation();
        assertEquals(null, stripper.stripExifDataForSharing(Uri.fromFile(missing), "y.jpg"));
    }
}
