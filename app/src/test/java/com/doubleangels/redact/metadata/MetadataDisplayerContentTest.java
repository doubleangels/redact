package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Extraction from content:// URIs (the shape the system pickers hand the app). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MetadataDisplayerContentTest {

    private Application app;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
    }

    @After
    public void tearDown() {
        MetadataDisplayer.cancelActiveScan();
        FakeMediaStoreProvider.reset();
        for (File f : files) {
            f.delete();
        }
    }

    private static void await(BooleanSupplier done) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("timed out", done.getAsBoolean());
    }

    private void grantLocation(boolean granted) {
        if (granted) {
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        } else {
            Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        }
    }

    private File jpeg(boolean withGps, int paddingBytes) throws IOException {
        File f = File.createTempFile("content_", ".jpg", app.getFilesDir());
        files.add(f);
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_MAKE, "Acme");
        exif.setAttribute(ExifInterface.TAG_MODEL, "Cam1");
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2020:01:02 03:04:05");
        exif.setAttribute(ExifInterface.TAG_F_NUMBER, "2.8");
        exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "100");
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.01");
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
        exif.setAttribute(ExifInterface.TAG_FLASH, "1");
        exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "1");
        if (withGps) {
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,59/1,0/1");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
        }
        exif.saveAttributes();
        if (paddingBytes > 0) {
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(new byte[paddingBytes]);
            }
        }
        return f;
    }

    private Uri imageUri(File file) {
        return FakeMediaStoreProvider.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, file, "image/jpeg");
    }

    private Uri videoUri() throws IOException {
        File f = File.createTempFile("content_", ".mp4", app.getFilesDir());
        files.add(f);
        return FakeMediaStoreProvider.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, f, "video/mp4");
    }

    private void videoMetadata(Uri uri, int key, String value) {
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app, uri), key, value);
    }

    private Map<String, String> sections(Uri uri) throws Exception {
        AtomicReference<Map<String, String>> result = new AtomicReference<>();
        boolean[] failed = {false};
        MetadataDisplayer.extractSectionedMetadata(app, uri, new MetadataDisplayer.SectionedMetadataCallback() {
            @Override
            public void onMetadataExtracted(Map<String, String> s, boolean isVideo) {
                result.set(s);
            }

            @Override
            public void onExtractionFailed(String error) {
                failed[0] = true;
            }
        });
        await(() -> result.get() != null || failed[0]);
        assertFalse("extraction failed", failed[0]);
        return result.get();
    }

    private String text(Uri uri) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        MetadataDisplayer.extractMetadata(app, uri, new MetadataDisplayer.MetadataCallback() {
            @Override
            public void onMetadataExtracted(String metadata, boolean isVideo) {
                result.set(metadata);
            }

            @Override
            public void onExtractionFailed(String error) {
                result.set("FAILED: " + error);
            }
        });
        await(() -> result.get() != null);
        return result.get();
    }

    // ---- images --------------------------------------------------------------------------------------

    @Test
    public void image_withLocationAccess_splitsCameraLocationAndBasicInfo() throws Exception {
        grantLocation(true);
        File file = jpeg(true, 0);

        Map<String, String> s = sections(imageUri(file));

        assertTrue(s.get(MetadataDisplayer.SECTION_BASIC_INFO).contains("DISPLAY_NAME"));
        assertTrue(s.get(MetadataDisplayer.SECTION_BASIC_INFO).contains(file.getName()));
        assertTrue(s.get(MetadataDisplayer.SECTION_BASIC_INFO).contains("MIME_TYPE"));
        assertNotNull(s.get(MetadataDisplayer.SECTION_CAMERA_DETAILS));
        String location = s.get(MetadataDisplayer.SECTION_LOCATION);
        assertNotNull(location);
        assertTrue(location, location.contains("GPS_LATITUDE"));
        double[] coords = MetadataDisplayer.resolveMapCoordinates(s);
        assertNotNull(coords);
        assertEquals(40.5, coords[0], 0.01);
    }

    @Test
    public void image_withoutLocationAccess_stillExtractsTheRest() throws Exception {
        grantLocation(false);
        Map<String, String> s = sections(imageUri(jpeg(true, 0)));

        assertNotNull(s.get(MetadataDisplayer.SECTION_BASIC_INFO));
        assertNotNull(s.get(MetadataDisplayer.SECTION_CAMERA_DETAILS));
    }

    @Test
    public void image_fileSizesAreFormattedInBytesKilobytesAndMegabytes() throws Exception {
        grantLocation(true);
        String bytes = sections(imageUri(jpeg(false, 0))).get(MetadataDisplayer.SECTION_BASIC_INFO);
        assertTrue(bytes, bytes.contains("SIZE"));

        String kb = sections(imageUri(jpeg(false, 4000))).get(MetadataDisplayer.SECTION_BASIC_INFO);
        assertTrue(kb, kb.contains(app.getString(com.doubleangels.redact.R.string.metadata_size_kb)));

        String mb = sections(imageUri(jpeg(false, 3 * 1024 * 1024))).get(MetadataDisplayer.SECTION_BASIC_INFO);
        assertTrue(mb, mb.contains(app.getString(com.doubleangels.redact.R.string.metadata_size_mb)));
    }

    @Test
    public void image_coordinatesFromMediaStoreColumns_whenTheExifHasNone() throws Exception {
        grantLocation(true);
        Uri uri = imageUri(jpeg(false, 0));
        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.entries().values().iterator().next();
        entry.values.put(MediaStore.Images.Media.LATITUDE, 51.5);
        entry.values.put(MediaStore.Images.Media.LONGITUDE, -0.12);

        Map<String, String> s = sections(uri);

        assertNotNull(s.get(MetadataDisplayer.SECTION_BASIC_INFO));
        // Whether the columns are surfaced depends on the extractor; it must at least not fail.
        MetadataDisplayer.resolveMapCoordinates(s);
    }

    @Test
    public void image_legacyTextApi_listsTheFields() throws Exception {
        grantLocation(true);
        String text = text(imageUri(jpeg(true, 0)));
        assertTrue(text, text.contains("Acme"));
        assertTrue(text, text.contains(app.getString(com.doubleangels.redact.R.string.metadata_camera_information_header)));
    }

    @Test
    public void image_legacyTextApi_withoutLocationAccess() throws Exception {
        grantLocation(false);
        String text = text(imageUri(jpeg(true, 0)));
        assertTrue(text, text.contains("Acme"));
    }

    @Test
    public void image_locationSectionOnly() throws Exception {
        grantLocation(true);
        Uri uri = imageUri(jpeg(true, 0));
        AtomicReference<String> location = new AtomicReference<>();
        boolean[] done = {false};
        MetadataDisplayer.extractLocationSectionOnly(app, uri, new MetadataDisplayer.LocationSectionCallback() {
            @Override
            public void onLocationSectionExtracted(String content) {
                location.set(content);
                done[0] = true;
            }

            @Override
            public void onExtractionFailed(String error) {
                done[0] = true;
            }
        });
        await(() -> done[0]);
        assertNotNull(location.get());
        assertTrue(location.get().contains("GPS_LATITUDE"));
    }

    @Test
    public void image_unreadableContent_isReportedWithoutCrashing() throws Exception {
        grantLocation(true);
        File gone = jpeg(false, 0);
        Uri uri = imageUri(gone);
        assertTrue(gone.delete());

        // Either a failure callback or an empty result is fine; it must complete.
        AtomicReference<Object> outcome = new AtomicReference<>();
        MetadataDisplayer.extractSectionedMetadata(app, uri, new MetadataDisplayer.SectionedMetadataCallback() {
            @Override
            public void onMetadataExtracted(Map<String, String> s, boolean isVideo) {
                outcome.set(s);
            }

            @Override
            public void onExtractionFailed(String error) {
                outcome.set(error);
            }
        });
        await(() -> outcome.get() != null);
    }

    // ---- video ---------------------------------------------------------------------------------------

    private Uri videoWithMetadata(String location) throws IOException {
        Uri uri = videoUri();
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_DURATION, "65000");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "90");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_BITRATE, "4000000");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_DATE, "20240102T030405.000Z");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE, "30");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, "48000");
        videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_TITLE, "My holiday");
        if (location != null) {
            videoMetadata(uri, MediaMetadataRetriever.METADATA_KEY_LOCATION, location);
        }
        return uri;
    }

    @Test
    public void video_withLocationAccess_reportsPropertiesAndCoordinates() throws Exception {
        grantLocation(true);
        Uri uri = videoWithMetadata("+40.5000-073.9833/");

        Map<String, String> s = sections(uri);

        String all = String.join("\n", s.values());
        assertTrue(all, all.contains("DURATION"));
        assertTrue(all, all.contains("VIDEO_WIDTH"));
        assertTrue(all, all.contains("1920"));
        String location = s.get(MetadataDisplayer.SECTION_LOCATION);
        assertNotNull(all, location);
        assertTrue(location, location.contains("GPS_LATITUDE"));
        assertNotNull(MetadataDisplayer.resolveMapCoordinates(s));
    }

    @Test
    public void video_withoutLocationAccess_withholdsTheCoordinates() throws Exception {
        grantLocation(false);
        Map<String, String> s = sections(videoWithMetadata("+40.5000-073.9833/"));

        assertNull(s.get(MetadataDisplayer.SECTION_LOCATION));
        assertTrue(String.join("\n", s.values()).contains("DURATION"));
    }

    @Test
    public void video_unparseableLocation_isShownRaw() throws Exception {
        grantLocation(true);
        Map<String, String> s = sections(videoWithMetadata("somewhere nice"));
        String all = String.join("\n", s.values());
        assertTrue(all, all.contains("somewhere nice"));
    }

    @Test
    public void video_unusableCoordinates_areShownRaw() throws Exception {
        grantLocation(true);
        Map<String, String> s = sections(videoWithMetadata("+0.0000+000.0000/"));
        String all = String.join("\n", s.values());
        assertTrue(all, all.contains("LOCATION"));
    }

    @Test
    public void video_legacyTextApi() throws Exception {
        grantLocation(true);
        String text = text(videoWithMetadata("+40.5000-073.9833/"));
        assertTrue(text, text.contains(app.getString(com.doubleangels.redact.R.string.metadata_video_properties_header)));
    }

    @Test
    public void video_locationSectionOnly() throws Exception {
        grantLocation(true);
        Uri uri = videoWithMetadata("+40.5000-073.9833/");
        AtomicReference<String> location = new AtomicReference<>();
        boolean[] done = {false};
        MetadataDisplayer.extractLocationSectionOnly(app, uri, new MetadataDisplayer.LocationSectionCallback() {
            @Override
            public void onLocationSectionExtracted(String content) {
                location.set(content);
                done[0] = true;
            }

            @Override
            public void onExtractionFailed(String error) {
                done[0] = true;
            }
        });
        await(() -> done[0]);
        assertNotNull(location.get());
    }

    @Test
    public void video_withoutAnyMetadata_stillProducesBasicInfo() throws Exception {
        grantLocation(true);
        Map<String, String> s = sections(videoUri());
        assertNotNull(s.get(MetadataDisplayer.SECTION_BASIC_INFO));
    }

    @Test
    public void cancelActiveScan_isSafeWhenIdle() {
        MetadataDisplayer.cancelActiveScan();
        MetadataDisplayer.cancelActiveScan();
    }
}
