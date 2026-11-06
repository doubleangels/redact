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

    @Test
    public void containerDisplayKey_namesQuickTimeAndVendorTags() {
        String[][] cases = {
                {"©mak", "MAKE"}, {"©mod", "MODEL"}, {"©nam", "TITLE"}, {"©cmt", "COMMENT"},
                {"©ART", "ARTIST"}, {"©aut", "AUTHOR"}, {"©swr", "SOFTWARE"}, {"©too", "SOFTWARE"},
                {"©des", "DESCRIPTION"}, {"©inf", "INFORMATION"},
        };
        for (String[] c : cases) {
            org.junit.Assert.assertEquals(c[0], c[1], MetadataDisplayer.containerDisplayKey(c[0]));
        }
        org.junit.Assert.assertTrue(MetadataDisplayer.containerDisplayKey("com.apple.quicktime.model")
                .toUpperCase(java.util.Locale.ROOT).contains("MODEL"));
    }

    @Test
    public void addImageContainerFindings_placesEachKindOfFinding() {
        java.util.List<ImageContainerMetadata.Finding> findings = new java.util.ArrayList<>();
        findings.add(new ImageContainerMetadata.Finding("XMP",
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description/></rdf:RDF></x:xmpmeta>", 80,
                ImageContainerMetadata.Kind.OTHER));
        findings.add(new ImageContainerMetadata.Finding("LOCATION_CITY", "Amsterdam", 9, ImageContainerMetadata.Kind.LOCATION));
        findings.add(new ImageContainerMetadata.Finding("EMBEDDED_IMAGES", "several", 4096, ImageContainerMetadata.Kind.OTHER));
        java.util.List<ImageContainerMetadata.Finding> kept = new java.util.ArrayList<>();
        kept.add(new ImageContainerMetadata.Finding("ULTRA_HDR_GAIN_MAP", null, 2048, ImageContainerMetadata.Kind.OTHER));
        ImageContainerMetadata.Report report = new ImageContainerMetadata.Report(
                ImageContainerMetadata.Format.JPEG, true, findings, kept);
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();

        // Without the media location permission a place is left out; XMP read as EXIF is shown once.
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("XMP", "from EXIF");
        java.util.Map<String, String> embedded = new java.util.LinkedHashMap<>();
        MetadataDisplayer.addImageContainerFindings(context, report, false, map, embedded);
        assertFalse(map.containsKey("LOCATION_CITY"));
        assertFalse(embedded.containsKey("XMP"));
        assertEquals("several", embedded.get("EMBEDDED_IMAGES"));
        assertNotNull("a kept part with no text shows its size", map.get("ULTRA_HDR_GAIN_MAP"));

        // With it, the place is listed, and XMP not read as EXIF is shown formatted.
        java.util.Map<String, String> withPermission = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> embedded2 = new java.util.LinkedHashMap<>();
        MetadataDisplayer.addImageContainerFindings(context, report, true, withPermission, embedded2);
        assertEquals("Amsterdam", withPermission.get("LOCATION_CITY"));
        assertNotNull(embedded2.get("XMP"));
    }
}
