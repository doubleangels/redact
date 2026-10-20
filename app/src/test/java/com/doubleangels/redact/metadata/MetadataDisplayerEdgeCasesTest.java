package com.doubleangels.redact.metadata;

import static com.doubleangels.redact.TestWaits.awaitMain;
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
import com.doubleangels.redact.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Less common values and failures in what Scan shows: odd EXIF, long videos, unreadable files. */
@RunWith(RobolectricTestRunner.class)
public class MetadataDisplayerEdgeCasesTest {

    private Application app;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
    }

    @After
    public void tearDown() {
        MetadataDisplayer.cancelActiveScan();
        FakeMediaStoreProvider.reset();
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** A JPEG with the given EXIF attributes (pairs of tag, value), padded by {@code padding} bytes. */
    private Uri jpeg(int padding, String... tagsAndValues) throws IOException {
        File f = File.createTempFile("edge_", ".jpg", app.getFilesDir());
        files.add(f);
        try (FileOutputStream out = new FileOutputStream(f)) {
            Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        for (int i = 0; i < tagsAndValues.length; i += 2) {
            exif.setAttribute(tagsAndValues[i], tagsAndValues[i + 1]);
        }
        exif.saveAttributes();
        if (padding > 0) {
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(new byte[padding]);
            }
        }
        return FakeMediaStoreProvider.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, f, "image/jpeg");
    }

    private Uri video(String... keysAndValues) throws IOException {
        File f = File.createTempFile("edge_", ".mp4", app.getFilesDir());
        files.add(f);
        Uri uri = FakeMediaStoreProvider.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, f, "video/mp4");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app, uri),
                    Integer.parseInt(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return uri;
    }

    private static String key(int metadataKey) {
        return Integer.toString(metadataKey);
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
        awaitMain(() -> result.get() != null);
        return result.get();
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
        awaitMain(() -> result.get() != null || failed[0]);
        assertFalse("extraction failed", failed[0]);
        return result.get();
    }

    // ---- images, text view -------------------------------------------------------------------------

    @Test
    public void text_namesEveryOrientation() throws Exception {
        int[] orientations = {
                ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE};
        int[] names = {
                R.string.metadata_orientation_normal, R.string.metadata_orientation_rotate_90,
                R.string.metadata_orientation_rotate_180, R.string.metadata_orientation_rotate_270,
                R.string.metadata_orientation_flip_horizontal, R.string.metadata_orientation_flip_vertical,
                R.string.metadata_orientation_transpose, R.string.metadata_orientation_transverse};
        for (int i = 0; i < orientations.length; i++) {
            String text = text(jpeg(0, ExifInterface.TAG_ORIENTATION, Integer.toString(orientations[i])));
            assertTrue(text, text.contains(app.getString(names[i])));
        }
    }

    @Test
    public void text_longExposureApertureAndNoLocation() throws Exception {
        String text = text(jpeg(0,
                ExifInterface.TAG_APERTURE_VALUE, "4/1",
                ExifInterface.TAG_EXPOSURE_TIME, "2"));
        assertTrue(text, text.contains(app.getString(R.string.metadata_aperture, "4.0"))
                || text.contains(app.getString(R.string.metadata_aperture, "4/1")));
        assertTrue(text, text.contains(app.getString(R.string.metadata_exposure_time_seconds, 2f)));
        assertTrue(text, text.contains(app.getString(R.string.metadata_no_location_data)));
    }

    @Test
    public void text_largeFile_isSizedInMegabytes() throws Exception {
        String text = text(jpeg(2 * 1024 * 1024));
        assertTrue(text, text.contains(app.getString(R.string.metadata_size_mb)));
    }

    @Test
    public void text_unreadableFile_isReported() throws Exception {
        Uri uri = jpeg(0);
        FakeMediaStoreProvider.failOpen = true;
        String text = text(uri);
        assertTrue(text, text.contains(app.getString(R.string.metadata_error_image_generic)));
    }

    // ---- images, sections ---------------------------------------------------------------------------

    @Test
    public void sections_southernLatitude_isNegative() throws Exception {
        Map<String, String> s = sections(jpeg(0,
                ExifInterface.TAG_GPS_LATITUDE, "33/1,51/1,0/1",
                ExifInterface.TAG_GPS_LATITUDE_REF, "S",
                ExifInterface.TAG_GPS_LONGITUDE, "151/1,12/1,0/1",
                ExifInterface.TAG_GPS_LONGITUDE_REF, "E"));
        double[] coords = MetadataDisplayer.resolveMapCoordinates(s);
        assertNotNull(coords);
        assertEquals(-33.85, coords[0], 0.01);
        assertEquals(151.2, coords[1], 0.01);
    }

    @Test
    public void sections_brokenGpsValues_areShownRaw_andZeroedOnesHidden() throws Exception {
        Map<String, String> broken = sections(jpeg(0,
                ExifInterface.TAG_GPS_LATITUDE, "40/0,30/1,0/1",
                ExifInterface.TAG_GPS_LATITUDE_REF, "N",
                ExifInterface.TAG_GPS_LONGITUDE, "73/0,59/1,0/1",
                ExifInterface.TAG_GPS_LONGITUDE_REF, "W"));
        assertNotNull(broken.get(MetadataDisplayer.SECTION_BASIC_INFO));

        Map<String, String> zeroed = sections(jpeg(0,
                ExifInterface.TAG_GPS_LATITUDE, "0/1,0/1,0/1",
                ExifInterface.TAG_GPS_LONGITUDE, "0/1,0/1,0/1"));
        String all = String.join("\n", zeroed.values());
        assertFalse(all, all.contains("GPS_LATITUDE\u001f0/1"));
        assertNull(MetadataDisplayer.resolveMapCoordinates(zeroed));
    }

    @Test
    public void sections_unreadableFile_stillHasBasicInfo() throws Exception {
        Uri uri = jpeg(0);
        FakeMediaStoreProvider.failOpen = true;
        assertNotNull(sections(uri).get(MetadataDisplayer.SECTION_BASIC_INFO));
    }

    // ---- videos -------------------------------------------------------------------------------------

    @Test
    public void text_videoOverAnHour_badNumbersAndAnUnreadableLocation() throws Exception {
        String text = text(video(
                key(MediaMetadataRetriever.METADATA_KEY_DURATION), "3723000",
                key(MediaMetadataRetriever.METADATA_KEY_BITRATE), "fast",
                key(MediaMetadataRetriever.METADATA_KEY_LOCATION), "somewhere"));
        assertTrue(text, text.contains("1:02:03"));
        assertFalse(text, text.contains(app.getString(R.string.metadata_no_location_data)));

        String badDuration = text(video(key(MediaMetadataRetriever.METADATA_KEY_DURATION), "long"));
        assertFalse(badDuration, badDuration.startsWith("FAILED"));
    }

    @Test
    public void text_videoWithoutLocationAccess_asksForIt() throws Exception {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        String text = text(video(key(MediaMetadataRetriever.METADATA_KEY_LOCATION), "+40.5-073.9/"));
        assertTrue(text, text.contains(app.getString(R.string.metadata_location_permission_needed)));
    }

    @Test
    public void text_videoTheRetrieverCannotOpen_isReported() throws Exception {
        Uri uri = video();
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(app, uri),
                new IllegalArgumentException("not a video"));
        String text = text(uri);
        assertTrue(text, text.contains(app.getString(R.string.metadata_error_video_generic)));
    }

    @Test
    public void sections_videoValuesAreTrimmed_andBadNumbersKept() throws Exception {
        Map<String, String> s = sections(video(
                key(MediaMetadataRetriever.METADATA_KEY_TITLE), "Holiday  \n",
                key(MediaMetadataRetriever.METADATA_KEY_DURATION), "long",
                key(MediaMetadataRetriever.METADATA_KEY_BITRATE), "fast",
                key(MediaMetadataRetriever.METADATA_KEY_LOCATION), "+95.0000+010.0000/"));
        String all = String.join("\n", s.values());
        assertTrue(all, all.contains("Holiday"));
        assertFalse(all, all.contains("Holiday  "));
        assertTrue(all, all.contains("long"));
        assertTrue(all, all.contains("fast"));
    }

    @Test
    public void sections_videoTheRetrieverCannotOpen_stillHasBasicInfo() throws Exception {
        Uri uri = video();
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(app, uri),
                new IllegalArgumentException("not a video"));
        assertNotNull(sections(uri).get(MetadataDisplayer.SECTION_BASIC_INFO));
    }

    // ---- failures -----------------------------------------------------------------------------------

    @Test
    public void everyEntryPoint_reportsAProviderThatFails() throws Exception {
        org.robolectric.Robolectric.setupContentProvider(
                com.doubleangels.redact.ScanFragmentTest.BrokenProvider.class, "com.doubleangels.redact.test.broken2");
        Uri broken = Uri.parse("content://com.doubleangels.redact.test.broken2/photo/1");
        String failed = app.getString(R.string.status_extraction_fail);

        assertEquals("FAILED: " + failed, text(broken));

        AtomicReference<String> sectioned = new AtomicReference<>();
        MetadataDisplayer.extractSectionedMetadata(app, broken, new MetadataDisplayer.SectionedMetadataCallback() {
            @Override
            public void onMetadataExtracted(Map<String, String> s, boolean isVideo) {
                sectioned.set("extracted");
            }

            @Override
            public void onExtractionFailed(String error) {
                sectioned.set(error);
            }
        });
        awaitMain(() -> sectioned.get() != null);
        assertEquals(failed, sectioned.get());

        AtomicReference<String> location = new AtomicReference<>();
        MetadataDisplayer.extractLocationSectionOnly(app, broken, new MetadataDisplayer.LocationSectionCallback() {
            @Override
            public void onLocationSectionExtracted(String content) {
                location.set("extracted");
            }

            @Override
            public void onExtractionFailed(String error) {
                location.set(error);
            }
        });
        awaitMain(() -> location.get() != null);
        assertEquals(failed, location.get());
    }

    // ---- helpers ------------------------------------------------------------------------------------

    @Test
    public void resolveMapCoordinates_readsBothBlockFormats_andSkipsMalformedEntries() {
        Map<String, String> records = new HashMap<>();
        records.put(MetadataDisplayer.SECTION_LOCATION,
                "\u001eno separator\u001e\u001fempty key\u001eGPS_LATITUDE\u001f12,5\u001eGPS_LONGITUDE\u001f-3.25");
        double[] fromRecords = MetadataDisplayer.resolveMapCoordinates(records);
        assertNotNull(fromRecords);
        assertEquals(12.5, fromRecords[0], 1e-9);

        Map<String, String> lines = new HashMap<>();
        lines.put(MetadataDisplayer.SECTION_LOCATION, "no colon here\n: no key\nGPS_LATITUDE: 1.5\nGPS_LONGITUDE: 2.5");
        double[] fromLines = MetadataDisplayer.resolveMapCoordinates(lines);
        assertNotNull(fromLines);
        assertEquals(2.5, fromLines[1], 1e-9);

        assertNull(MetadataDisplayer.resolveMapCoordinates(null));
        assertNull(MetadataDisplayer.resolveMapCoordinates(new HashMap<>()));
    }

    @Test
    public void smallHelpers_handleMissingInput() {
        assertFalse(MetadataDisplayer.isLocationMetadataKey(null));
        assertNull(MetadataDisplayer.resolveForLocationMetadataAccess(app, null));
        assertFalse(MetadataDisplayer.isUsableMapCoordinate(95, 10));
        assertFalse(MetadataDisplayer.isUsableMapCoordinate(0, 0));
        assertTrue(MetadataDisplayer.isUsableMapCoordinate(-33.8, 151.2));
    }
}
