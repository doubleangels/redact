package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.location.Address;
import android.location.Geocoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import com.doubleangels.redact.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowGeocoder;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MetadataDisplayerTest {

    private static final long CALLBACK_TIMEOUT_SECONDS = 5L;

    private Context baseContext;
    private Locale previousLocale;
    private File tempDir;
    private File defaultImageFile;
    private Uri defaultImageUri;
    private Context fileContext;

    @Before
    public void setUp() throws Exception {
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);

        baseContext = ApplicationProvider.getApplicationContext();
        grantLocationPermission();
        ShadowGeocoder.setIsPresent(true);

        tempDir = new File(baseContext.getCacheDir(), "metadata-displayer-tests");
        if (!tempDir.exists()) {
            assertTrue(tempDir.mkdirs() || tempDir.exists());
        }

        defaultImageFile = createImageFile("metadata_fixture.jpg", true, exif -> { });
        defaultImageUri = Uri.fromFile(defaultImageFile);
        fileContext = wrapWithResolver(baseContext, buildFileAwareResolver());
    }

    @After
    public void tearDown() {
        ShadowGeocoder.reset();
        ShadowMediaMetadataRetriever.reset();
        Locale.setDefault(previousLocale);
        deleteRecursively(tempDir);
    }

    @Test
    public void isLocationMetadataKey_matchesExpectedKeys() {
        assertNotNull(new MetadataDisplayer());
        assertTrue(MetadataDisplayer.isLocationMetadataKey("GPS_LATITUDE"));
        assertTrue(MetadataDisplayer.isLocationMetadataKey("LOCATION"));
        assertFalse(MetadataDisplayer.isLocationMetadataKey("MAKE"));
        assertFalse(MetadataDisplayer.isLocationMetadataKey("MODEL"));
        assertFalse(MetadataDisplayer.isLocationMetadataKey(null));
    }

    @Test
    public void extractMetadata_image_readsExifMetadataAndGeocodedAddress() throws Exception {
        try (MockedConstruction<Geocoder> ignored = mockGeocoderReturning("Denver, CO", "USA")) {
            MetadataResult result = awaitMetadata(fileContext, defaultImageUri);

            assertNull(result.error);
            assertFalse(result.isVideo);
            assertTrue(result.metadata.contains("DISPLAY_NAME: metadata_fixture.jpg"));
            assertTrue(result.metadata.contains("MIME_TYPE: image/jpeg"));
            assertTrue(result.metadata.contains("Camera Make: TestMake"));
            assertTrue(result.metadata.contains("Camera Model: TestModel"));
            assertTrue(result.metadata.contains("Exposure Time: 1/2 sec"));
            assertTrue(result.metadata.contains("Flash: Fired"));
            assertTrue(result.metadata.contains("White Balance: Auto"));
            assertTrue(result.metadata.contains("Latitude: 39.739"));
            assertTrue(result.metadata.contains("Longitude: -104.990"));
            assertTrue(result.metadata.contains("Orientation: Rotate 90°"));
        }
    }

    @Test
    public void extractMetadata_image_orientationStringsCoverAllMappings() throws Exception {
        Map<Integer, String> expectedOrientations = new LinkedHashMap<>();
        expectedOrientations.put(ExifInterface.ORIENTATION_NORMAL, "Normal");
        expectedOrientations.put(ExifInterface.ORIENTATION_ROTATE_90, "Rotate 90°");
        expectedOrientations.put(ExifInterface.ORIENTATION_ROTATE_180, "Rotate 180°");
        expectedOrientations.put(ExifInterface.ORIENTATION_ROTATE_270, "Rotate 270°");
        expectedOrientations.put(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, "Flip Horizontal");
        expectedOrientations.put(ExifInterface.ORIENTATION_FLIP_VERTICAL, "Flip Vertical");
        expectedOrientations.put(ExifInterface.ORIENTATION_TRANSPOSE, "Transpose");
        expectedOrientations.put(ExifInterface.ORIENTATION_TRANSVERSE, "Transverse");
        expectedOrientations.put(99, "Unknown (99)");

        int index = 0;
        for (Map.Entry<Integer, String> entry : expectedOrientations.entrySet()) {
            File imageFile = createImageFile("orientation_" + index + ".jpg", true,
                    exif -> exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                            String.valueOf(entry.getKey())));
            MetadataResult result = awaitMetadata(fileContext, Uri.fromFile(imageFile));
            assertNull(result.error);
            assertTrue(result.metadata.contains("Orientation: " + entry.getValue()));
            index++;
        }

        File invalidOrientationFile = createImageFile("orientation_invalid.jpg", true,
                exif -> exif.setAttribute(ExifInterface.TAG_ORIENTATION, "not-a-number"));
        MetadataResult invalidResult = awaitMetadata(fileContext, Uri.fromFile(invalidOrientationFile));
        assertNull(invalidResult.error);
        assertFalse(invalidResult.metadata.contains("Orientation: Unknown"));
    }

    @Test
    public void extractMetadata_image_handlesExposureFlashWhiteBalancePermissionAndMissingLocation()
            throws Exception {
        File imageFile = createImageFile("image_technical.jpg", false, exif -> {
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "2.0");
            exif.setAttribute(ExifInterface.TAG_FLASH, "0");
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "1");
        });
        Context deniedPermissionContext = wrapWithLocationPermission(fileContext,
                PackageManager.PERMISSION_DENIED);

        MetadataResult result = awaitMetadata(deniedPermissionContext, Uri.fromFile(imageFile));

        assertNull(result.error);
        assertTrue(result.metadata.contains("Exposure Time: 2.00000 sec"));
        assertTrue(result.metadata.contains("Flash: Not Fired"));
        assertTrue(result.metadata.contains("White Balance: Manual"));
        assertTrue(result.metadata.contains(
                baseContext.getString(R.string.metadata_location_permission_needed)));

        MetadataResult noLocationResult = awaitMetadata(fileContext, Uri.fromFile(imageFile));
        assertNull(noLocationResult.error);
        assertTrue(noLocationResult.metadata.contains(
                baseContext.getString(R.string.metadata_no_location_data)));
    }

    @Test
    public void extractMetadata_image_invalidExifValuesDoNotCrash() throws Exception {
        File imageFile = createPlainImageFile("image_invalid_values.jpg", true, exif -> {
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "bad-exposure");
            exif.setAttribute(ExifInterface.TAG_FLASH, "bad-flash");
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "bad-orientation");
        });

        MetadataResult result = awaitMetadata(fileContext, Uri.fromFile(imageFile));

        assertNull(result.error);
        assertFalse(result.metadata.contains("Exposure Time:"));
        assertFalse(result.metadata.contains("Flash:"));
    }

    @Test
    public void extractMetadata_image_openInputStreamIOException_appendsError() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.openInputStream(any(Uri.class))).thenThrow(new java.io.FileNotFoundException("boom"));
        Context context = wrapWithResolver(baseContext, resolver);

        String metadata = invokeExtractImageMetadata(context, Uri.parse("content://tests/broken-image"));
        assertTrue(metadata.contains("Error extracting image metadata:"));
    }

    @Test
    public void extractImageMetadataToMap_limitsTags_addsDimensions_andHandlesLatLongSecurityException()
            throws Exception {
        Uri imageUri = Uri.parse("content://tests/exif-many-tags");
        Cursor cursor = mockCursor("many_tags.jpg", 123L, true, true);
        ContentResolver resolver = mockResolver("image/jpeg", cursor, defaultImageFile,
                false, null, null);
        Context context = wrapWithResolver(baseContext, resolver);

        try (MockedConstruction<ExifInterface> ignored = mockConstruction(ExifInterface.class,
                (mock, construction) -> {
                    when(mock.getAttribute(anyString())).thenAnswer(invocation -> {
                        String tag = invocation.getArgument(0);
                        if (ExifInterface.TAG_IMAGE_WIDTH.equals(tag)
                                || ExifInterface.TAG_IMAGE_LENGTH.equals(tag)
                                || tag.startsWith("GPS")) {
                            return null;
                        }
                        // Return non-empty for most tags to hit the max-exif-tags break.
                        return "v";
                    });
                    when(mock.getAttributeInt(anyString(), anyInt())).thenAnswer(invocation -> {
                        String tag = invocation.getArgument(0);
                        if (ExifInterface.TAG_IMAGE_WIDTH.equals(tag)) {
                            return 12;
                        }
                        if (ExifInterface.TAG_IMAGE_LENGTH.equals(tag)) {
                            return 34;
                        }
                        return invocation.getArgument(1);
                    });
                    when(mock.getLatLong()).thenThrow(new SecurityException("nope"));
                })) {
            Map<String, String> map = invokeExtractImageMetadataToMap(context, imageUri);

            assertEquals("12", map.get("IMAGE_WIDTH"));
            assertEquals("34", map.get("IMAGE_LENGTH"));
            assertTrue("Expected tag extraction to be capped", map.size() <= 82);
        }
    }

    @Test
    public void extractImageMetadataToMap_openInputStreamIOException_isCaught() throws Exception {
        Uri imageUri = Uri.parse("content://tests/exif-io");
        Cursor cursor = mockCursor("io.jpg", 1L, true, true);
        ContentResolver resolver = mockResolver("image/jpeg", cursor, defaultImageFile,
                false, null, new IOException("read fail"));
        Context context = wrapWithResolver(baseContext, resolver);

        Map<String, String> map = invokeExtractImageMetadataToMap(context, imageUri);
        assertNotNull(map);
    }

    @Test
    public void extractSectionedMetadata_image_createsCameraTechnicalLocationSectionsAndFormatsXml()
            throws Exception {
        Cursor cursor = mockCursor("image_xmp.jpg", 256L, true, true);
        ContentResolver resolver = mockResolver("image/jpeg", cursor, defaultImageFile,
                false, null, null);

        try (MockedConstruction<ExifInterface> ignored =
                     mockConstruction(ExifInterface.class, (mock, context) -> {
                         when(mock.getAttribute(ExifInterface.TAG_MAKE)).thenReturn("TestMake");
                         when(mock.getAttribute(ExifInterface.TAG_MODEL)).thenReturn("TestModel");
                         when(mock.getAttribute(ExifInterface.TAG_ORIENTATION))
                                 .thenReturn(String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
                         when(mock.getAttribute(ExifInterface.TAG_FLASH)).thenReturn("1");
                         when(mock.getAttribute(ExifInterface.TAG_WHITE_BALANCE)).thenReturn("0");
                         when(mock.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)).thenReturn("0.5");
                         when(mock.getAttribute(ExifInterface.TAG_APERTURE_VALUE)).thenReturn("2.8");
                         when(mock.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                                 .thenReturn("<?xpacket begin=\"id\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                                         + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                                         + "<rdf:Description><title>Hello</title></rdf:Description>"
                                         + "</rdf:RDF></x:xmpmeta>");
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                                 .thenReturn("39/1,44/1,21120/1000");
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)).thenReturn("N");
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
                                 .thenReturn("104/1,59/1,25160/1000");
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)).thenReturn("W");
                     })) {
            SectionedResult result = awaitSectionedMetadata(wrapWithResolver(baseContext, resolver),
                    Uri.parse("content://tests/image-xmp"));

            assertNull(result.error);
            assertFalse(result.isVideo);
            assertTrue(result.sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO));
            assertTrue(result.sections.containsKey(MetadataDisplayer.SECTION_CAMERA_DETAILS));
            assertTrue(result.sections.containsKey(MetadataDisplayer.SECTION_TECHNICAL));
            assertTrue(result.sections.containsKey(MetadataDisplayer.SECTION_LOCATION));

            Map<String, String> basicInfo = parseSerializedSection(
                    result.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
            Map<String, String> cameraInfo = parseSerializedSection(
                    result.sections.get(MetadataDisplayer.SECTION_CAMERA_DETAILS));
            Map<String, String> technicalInfo = parseSerializedSection(
                    result.sections.get(MetadataDisplayer.SECTION_TECHNICAL));
            Map<String, String> locationInfo = parseSerializedSection(
                    result.sections.get(MetadataDisplayer.SECTION_LOCATION));

            assertEquals("image_xmp.jpg", basicInfo.get("DISPLAY_NAME"));
            assertEquals("image/jpeg", basicInfo.get("MIME_TYPE"));
            assertEquals("TestMake", cameraInfo.get("MAKE"));
            assertEquals("TestModel", cameraInfo.get("MODEL"));
            assertEquals(String.valueOf(ExifInterface.ORIENTATION_ROTATE_90),
                    technicalInfo.get("ORIENTATION"));
            assertEquals("1", technicalInfo.get("FLASH"));
            assertEquals("0", technicalInfo.get("WHITE_BALANCE"));
            assertEquals("0.5", technicalInfo.get("EXPOSURE_TIME"));
            assertEquals(39.7392d, Double.parseDouble(locationInfo.get("GPS_LATITUDE")), 0.0001d);
            assertEquals(-104.9903d, Double.parseDouble(locationInfo.get("GPS_LONGITUDE")), 0.0001d);
        }
    }

    @Test
    public void extractSectionedMetadata_image_invalidGpsAndInvalidXmlFallBackGracefully()
            throws Exception {
        Cursor cursor = mockCursor("image_invalid_gps.jpg", 64L, true, true);
        ContentResolver resolver = mockResolver("image/jpeg", cursor, defaultImageFile,
                false, null, null);

        try (MockedConstruction<ExifInterface> ignored =
                     mockConstruction(ExifInterface.class, (mock, context) -> {
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                                 .thenReturn("1/0,2/1,3/1");
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)).thenReturn("N");
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
                                 .thenReturn("4/0,5/1,6/1");
                         when(mock.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)).thenReturn("W");
                         when(mock.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                                 .thenReturn("<?xml version=\"1.0\"?><rdf:RDF><broken></rdf:RDF>");
                     })) {
            SectionedResult result = awaitSectionedMetadata(wrapWithResolver(baseContext, resolver),
                    Uri.parse("content://tests/image-invalid-gps"));

            assertNull(result.error);
            Map<String, String> basicInfo = parseSerializedSection(
                    result.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
            Map<String, String> locationInfo = parseSerializedSection(
                    result.sections.get(MetadataDisplayer.SECTION_LOCATION));

            assertNotNull(result.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
            assertTrue(result.sections.containsKey(MetadataDisplayer.SECTION_LOCATION)
                    || result.sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO));
        }
    }

    @Test
    public void extractMetadata_basicFileInfo_usesMockedCursorFormattingAndNullMimeType()
            throws Exception {
        assertBasicInfoFormatting(512L, "512 B");
        assertBasicInfoFormatting(2L * 1024L, "2.00 KB");
        assertBasicInfoFormatting(3L * 1024L * 1024L, "3.00 MB");

        Cursor unknownCursor = mockCursor(null, -1L, false, true);
        ContentResolver unknownResolver = mockResolver(null, unknownCursor, defaultImageFile,
                false, null, null);
        MetadataResult unknownResult = awaitMetadata(wrapWithResolver(baseContext, unknownResolver),
                Uri.parse("content://tests/basic-info-unknown"));

        assertNull(unknownResult.error);
        assertTrue(unknownResult.metadata.contains("DISPLAY_NAME: Unknown"));
        assertTrue(unknownResult.metadata.contains("MIME_TYPE: Unknown"));
        assertTrue(unknownResult.metadata.contains("SIZE: Unknown"));
    }

    @Test
    public void extractSectionedMetadata_basicFileInfoToMap_formatsSizesAndUnknowns()
            throws Exception {
        Cursor kbCursor = mockCursor("kb.jpg", 2L * 1024L, true, true);
        SectionedResult kbResult = awaitSectionedMetadata(wrapWithResolver(baseContext,
                        mockResolver(null, kbCursor, defaultImageFile, false, null, null)),
                Uri.parse("content://tests/sectioned-basic-kb"));
        Map<String, String> kbInfo = parseSerializedSection(
                kbResult.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
        assertEquals("kb.jpg", kbInfo.get("DISPLAY_NAME"));
        assertEquals("Unknown", kbInfo.get("MIME_TYPE"));
        assertEquals("2.00 KB", kbInfo.get("SIZE"));

        Cursor mbCursor = mockCursor("mb.jpg", 3L * 1024L * 1024L, true, true);
        SectionedResult mbResult = awaitSectionedMetadata(wrapWithResolver(baseContext,
                        mockResolver("image/jpeg", mbCursor, defaultImageFile, false, null, null)),
                Uri.parse("content://tests/sectioned-basic-mb"));
        Map<String, String> mbInfo = parseSerializedSection(
                mbResult.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
        assertEquals("3.00 MB", mbInfo.get("SIZE"));

        Cursor unknownCursor = mockCursor(null, -1L, false, false);
        SectionedResult unknownResult = awaitSectionedMetadata(wrapWithResolver(baseContext,
                        mockResolver(null, unknownCursor, defaultImageFile, false, null, null)),
                Uri.parse("content://tests/sectioned-basic-unknown"));
        Map<String, String> unknownInfo = parseSerializedSection(
                unknownResult.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
        assertEquals("Unknown", unknownInfo.get("DISPLAY_NAME"));
        assertEquals("Unknown", unknownInfo.get("MIME_TYPE"));
        assertEquals("Unknown", unknownInfo.get("SIZE"));
    }

    @Test
    public void extractMetadata_basicFileInfo_queryFailureAndNullStreamAppendErrors() throws Exception {
        ContentResolver resolver = mockResolver(null, null, null,
                true, null, null);
        MetadataResult result = awaitMetadata(wrapWithResolver(baseContext, resolver),
                Uri.parse("content://tests/basic-info-error"));

        assertNull(result.error);
        assertTrue(result.metadata.contains("Error extracting basic info: Query exploded"));
        assertTrue(result.metadata.contains(
                baseContext.getString(R.string.metadata_error_file_open)));
    }

    @Test
    public void extractMetadata_image_apertureFromRealFile() throws Exception {
        File imageFile = createImageFile("image_aperture.jpg", true,
                exif -> exif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, "28/10"));
        MetadataResult apertureResult = awaitMetadata(fileContext, Uri.fromFile(imageFile));
        assertNull(apertureResult.error);
        assertTrue(apertureResult.metadata.contains("Image Properties:"));
        assertTrue(apertureResult.metadata.contains("Aperture:"));
    }

    @Test
    public void extractSectionedMetadata_image_nullInputStreamReturnsBasicInfoOnly() throws Exception {
        ContentResolver resolver = mockResolver("image/jpeg",
                mockCursor("null-stream.jpg", 1L, true, true), null,
                false, null, null);
        SectionedResult result = awaitSectionedMetadata(wrapWithResolver(baseContext, resolver),
                Uri.parse("content://tests/image-null-stream"));

        assertNull(result.error);
        assertTrue(result.sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO));
        assertFalse(result.sections.containsKey(MetadataDisplayer.SECTION_LOCATION));
    }

    @Test
    public void extractMetadata_invalidResolverUri_reportsFailure() throws Exception {
        ContentResolver failingResolver = mock(ContentResolver.class);
        Uri invalidUri = Uri.parse("file:///definitely/missing.jpg");
        when(failingResolver.getType(invalidUri)).thenThrow(new IllegalArgumentException("Bad test URI"));
        Context failingContext = wrapWithResolver(baseContext, failingResolver);

        MetadataResult result = awaitMetadata(failingContext, invalidUri);

        assertNull(result.metadata);
        assertTrue(result.error.contains(
                baseContext.getString(R.string.metadata_error_extraction, "Bad test URI")));
    }

    @Test
    public void extractSectionedMetadata_invalidResolverUri_reportsFailure() throws Exception {
        ContentResolver failingResolver = mock(ContentResolver.class);
        Uri invalidUri = Uri.parse("file:///definitely/missing.jpg");
        when(failingResolver.getType(invalidUri)).thenThrow(new IllegalArgumentException("Bad section URI"));
        Context failingContext = wrapWithResolver(baseContext, failingResolver);

        SectionedResult result = awaitSectionedMetadata(failingContext, invalidUri);

        assertNull(result.sections);
        assertTrue(result.error.contains(
                baseContext.getString(R.string.metadata_error_extraction, "Bad section URI")));
    }

    @Test
    public void extractSectionedMetadata_image_queryFailureAndInputFailureStillReturnsSections()
            throws Exception {
        ContentResolver resolver = mockResolver("image/jpeg", null, null,
                true, null, new FileNotFoundException("missing image"));
        SectionedResult result = awaitSectionedMetadata(wrapWithResolver(baseContext, resolver),
                Uri.parse("content://tests/sectioned-image-errors"));

        assertNull(result.error);
        assertNotNull(result.sections);
        Map<String, String> basicInfo = parseSerializedSection(
                result.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
        assertFalse(basicInfo.containsKey("DISPLAY_NAME"));
    }

    @Test
    public void extractMetadata_video_formatsAllMetadataAndGeocodedAddress() throws Exception {
        Uri videoUri = createVideoUri("video_full.mp4");
        registerVideoMetadata(videoUri, Map.of(
                MediaMetadataRetriever.METADATA_KEY_DURATION, "3723000",
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920",
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080",
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "90",
                MediaMetadataRetriever.METADATA_KEY_BITRATE, "4000000",
                MediaMetadataRetriever.METADATA_KEY_DATE, "2024-05-01T12:34:56.000Z",
                MediaMetadataRetriever.METADATA_KEY_LOCATION, "+39.7392+104.9903/",
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE, "29.97",
                MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, "48000",
                MediaMetadataRetriever.METADATA_KEY_TITLE,
                "<?xml version=\"1.0\"?><rdf:RDF><broken></rdf:RDF>"));

        try (MockedConstruction<Geocoder> ignored = mockGeocoderReturning("Main St", "Denver", "USA")) {
            MetadataResult metadataResult = awaitMetadata(fileContext, videoUri);

            assertNull(metadataResult.error);
            assertTrue(metadataResult.isVideo);
            assertTrue(metadataResult.metadata.contains("Duration: 1:02:03"));
            assertTrue(metadataResult.metadata.contains("Resolution: 1920 x 1080"));
            assertTrue(metadataResult.metadata.contains("Rotation: 90"));
            assertTrue(metadataResult.metadata.contains("Bitrate: 4000 kbps"));
            assertTrue(metadataResult.metadata.contains("Date: 2024-05-01T12:34:56.000Z"));
            assertTrue(metadataResult.metadata.contains("Latitude: 39.7392"));
            assertTrue(metadataResult.metadata.contains("Longitude: 104.9903"));
            assertTrue(metadataResult.metadata.contains("Frame Rate: 29.97 fps"));
            assertTrue(metadataResult.metadata.contains("Sample Rate: 48000 Hz"));
        }

        SectionedResult sectionedResult = awaitSectionedMetadata(fileContext, videoUri);

        assertNull(sectionedResult.error);
        assertTrue(sectionedResult.isVideo);
        assertTrue(sectionedResult.sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO));
        assertTrue(sectionedResult.sections.containsKey(MetadataDisplayer.SECTION_TECHNICAL));
        assertTrue(sectionedResult.sections.containsKey(MetadataDisplayer.SECTION_LOCATION));

        Map<String, String> basicInfo = parseSerializedSection(
                sectionedResult.sections.get(MetadataDisplayer.SECTION_BASIC_INFO));
        Map<String, String> technicalInfo = parseSerializedSection(
                sectionedResult.sections.get(MetadataDisplayer.SECTION_TECHNICAL));
        Map<String, String> locationInfo = parseSerializedSection(
                sectionedResult.sections.get(MetadataDisplayer.SECTION_LOCATION));

        assertTrue(basicInfo.get("TITLE").contains(">\n<"));
        assertEquals("3723000", technicalInfo.get("DURATION"));
        assertEquals("1920", technicalInfo.get("VIDEO_WIDTH"));
        assertEquals("1080", technicalInfo.get("VIDEO_HEIGHT"));
        assertEquals("29.97", technicalInfo.get("CAPTURE_FRAMERATE"));
        assertEquals("48000", technicalInfo.get("SAMPLERATE"));
        assertEquals(39.7392d, Double.parseDouble(locationInfo.get("GPS_LATITUDE")), 0.0001d);
        assertEquals(104.9903d, Double.parseDouble(locationInfo.get("GPS_LONGITUDE")), 0.0001d);
    }

    @Test
    public void extractMetadata_video_handlesInvalidLocationGeocoderFailureAndMissingLocation()
            throws Exception {
        Uri invalidNumberVideo = createVideoUri("video_invalid_number.mp4");
        registerVideoMetadata(invalidNumberVideo, Map.of(
                MediaMetadataRetriever.METADATA_KEY_LOCATION, "+39.7392-bad"));
        MetadataResult invalidNumberResult = awaitMetadata(fileContext, invalidNumberVideo);
        assertNull(invalidNumberResult.error);
        assertFalse(invalidNumberResult.metadata.contains("Latitude:"));

        Uri invalidFormatVideo = createVideoUri("video_invalid_format.mp4");
        registerVideoMetadata(invalidFormatVideo, Map.of(
                MediaMetadataRetriever.METADATA_KEY_LOCATION, "not-a-coordinate"));
        MetadataResult invalidFormatResult = awaitMetadata(fileContext, invalidFormatVideo);
        assertNull(invalidFormatResult.error);
        assertFalse(invalidFormatResult.metadata.contains("Latitude:"));

        Uri geocoderFailureVideo = createVideoUri("video_geocoder_failure.mp4");
        registerVideoMetadata(geocoderFailureVideo, Map.of(
                MediaMetadataRetriever.METADATA_KEY_LOCATION, "+91.0-181.0"));
        MetadataResult geocoderFailureResult = awaitMetadata(fileContext, geocoderFailureVideo);
        assertNull(geocoderFailureResult.error);
        assertTrue(geocoderFailureResult.metadata.contains("Geocoding failed:"));

        Uri noLocationVideo = createVideoUri("video_no_location.mp4");
        registerVideoMetadata(noLocationVideo, Map.of(
                MediaMetadataRetriever.METADATA_KEY_DURATION, "65000"));
        MetadataResult noLocationResult = awaitMetadata(fileContext, noLocationVideo);
        assertNull(noLocationResult.error);
        assertTrue(noLocationResult.metadata.contains("Duration: 1:05"));
        assertTrue(noLocationResult.metadata.contains(
                baseContext.getString(R.string.metadata_no_location_data)));
    }

    @Test
    public void extractMetadata_video_invalidDurationBitrateAndPermissionDeniedPaths()
            throws Exception {
        Uri videoUri = createVideoUri("video_invalid_values.mp4");
        registerVideoMetadata(videoUri, Map.of(
                MediaMetadataRetriever.METADATA_KEY_DURATION, "bad-duration",
                MediaMetadataRetriever.METADATA_KEY_BITRATE, "bad-bitrate",
                MediaMetadataRetriever.METADATA_KEY_LOCATION, "+39.7392-104.9903"));
        Context deniedPermissionContext = wrapWithLocationPermission(fileContext,
                PackageManager.PERMISSION_DENIED);

        MetadataResult result = awaitMetadata(deniedPermissionContext, videoUri);

        assertNull(result.error);
        assertFalse(result.metadata.contains("Duration:"));
        assertFalse(result.metadata.contains("Bitrate:"));
        assertTrue(result.metadata.contains(
                baseContext.getString(R.string.metadata_location_permission_needed)));
    }

    @Test
    public void extractSectionedMetadata_video_fallsBackForRawLocationAndMissingKeys() throws Exception {
        Uri videoUri = createVideoUri("video_sectioned_fallbacks.mp4");
        registerVideoMetadata(videoUri, Map.of(
                MediaMetadataRetriever.METADATA_KEY_DURATION, "bad-duration",
                MediaMetadataRetriever.METADATA_KEY_BITRATE, "bad-bitrate",
                MediaMetadataRetriever.METADATA_KEY_LOCATION, "raw-location"));

        SectionedResult result = awaitSectionedMetadata(fileContext, videoUri);

        assertNull(result.error);
        Map<String, String> locationInfo = parseSerializedSection(
                result.sections.get(MetadataDisplayer.SECTION_LOCATION));
        Map<String, String> technicalInfo = parseSerializedSection(
                result.sections.get(MetadataDisplayer.SECTION_TECHNICAL));

        assertEquals("raw-location", locationInfo.get("LOCATION"));
        assertEquals("bad-duration", technicalInfo.get("DURATION"));
        assertEquals("bad-bitrate", technicalInfo.get("BITRATE"));
    }

    @Test
    public void extractMetadata_videoRetrieverFailure_appendsError() throws Exception {
        Uri videoUri = createVideoUri("video_retriever_failure.mp4");
        DataSource dataSource = DataSource.toDataSource(fileContext, videoUri);
        ShadowMediaMetadataRetriever.addException(dataSource, new RuntimeException("boom"));

        MetadataResult result = awaitMetadata(fileContext, videoUri);

        assertNull(result.error);
        assertTrue(result.metadata.contains("MIME_TYPE: video/mp4"));
        assertTrue(result.metadata.contains("Error extracting video metadata: boom"));
    }

    @Test
    public void extractSectionedMetadata_videoRetrieverFailure_returnsBasicInfoOnly() throws Exception {
        Uri videoUri = createVideoUri("video_retriever_sectioned_failure.mp4");
        DataSource dataSource = DataSource.toDataSource(fileContext, videoUri);
        ShadowMediaMetadataRetriever.addException(dataSource, new RuntimeException("boom"));

        SectionedResult result = awaitSectionedMetadata(fileContext, videoUri);

        assertNull(result.error);
        assertNotNull(result.sections);
        assertTrue(result.sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO));
        assertFalse(result.sections.containsKey(MetadataDisplayer.SECTION_LOCATION));
    }

    @Test
    public void privateHelpers_coverNullKeysSnakeCaseAndLocationParsing() throws Exception {
        assertFalse((boolean) invokePrivateStatic("isCameraMetadataKey", new Class[]{String.class}, (Object) null));
        assertFalse((boolean) invokePrivateStatic("isTechnicalMetadataKey", new Class[]{String.class}, (Object) null));
        assertNull(invokePrivateStatic("convertToSnakeCase", new Class[]{String.class}, (Object) null));
        assertEquals("", invokePrivateStatic("convertToSnakeCase", new Class[]{String.class}, ""));
        assertEquals("GPS_Latitude", invokePrivateStatic("convertToSnakeCase", new Class[]{String.class}, "GPSLatitude"));
        assertNull(invokePrivateStatic("parseVideoLocationCoordinates", new Class[]{String.class}, "39.7392"));
        assertNull(invokePrivateStatic("parseVideoLocationCoordinates", new Class[]{String.class}, (Object) null));

        try {
            invokePrivateStatic("convertGpsRationalToDecimal", new Class[]{String.class}, "1,2");
            assertTrue("Expected IllegalArgumentException", false);
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        try {
            invokePrivateStatic("parseRational", new Class[]{String.class}, "bad");
            assertTrue("Expected IllegalArgumentException", false);
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        try {
            invokePrivateStatic("parseRational", new Class[]{String.class}, "1/0");
            assertTrue("Expected IllegalArgumentException", false);
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void extractImageMetadata_sync_mockedExifAndGeocoderCoverBranches() throws Exception {
        byte[] imageBytes = Files.readAllBytes(defaultImageFile.toPath());
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.openInputStream(any(Uri.class)))
                .thenReturn(new ByteArrayInputStream(imageBytes));
        Context context = wrapWithResolver(baseContext, resolver);
        Uri uri = Uri.parse("content://tests/sync-image");

        try (MockedConstruction<ExifInterface> ignored = mockConstruction(ExifInterface.class, (mock, construction) -> {
            when(mock.getAttribute(ExifInterface.TAG_APERTURE_VALUE)).thenReturn("2.8");
            when(mock.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)).thenReturn("not-a-number");
            when(mock.getAttribute(ExifInterface.TAG_ORIENTATION)).thenReturn("bad-orientation");
            when(mock.getAttribute(ExifInterface.TAG_FLASH)).thenReturn("bad-flash");
            when(mock.getAttribute(ExifInterface.TAG_MAKE)).thenReturn("Make");
            when(mock.getLatLong()).thenReturn(new double[]{39.7392, -104.9903});
        })) {
            try (MockedConstruction<Geocoder> geocoder = mockGeocoderReturning("Street", "City", "USA")) {
                String metadata = invokeExtractImageMetadata(context, uri);
                assertTrue(metadata.contains("Aperture: f/2.8"));
                assertTrue(metadata.contains("Address: Street, City, USA"));
            }
        }

        try (MockedConstruction<ExifInterface> ignored = mockConstruction(ExifInterface.class, (mock, construction) -> {
            when(mock.getAttribute(ExifInterface.TAG_MAKE)).thenReturn("Make");
            when(mock.getLatLong()).thenThrow(new SecurityException("denied"));
        })) {
            String metadata = invokeExtractImageMetadata(context, uri);
            assertTrue(metadata.contains(
                    baseContext.getString(R.string.metadata_no_location_data)));
        }

        try (MockedConstruction<ExifInterface> ignored = mockConstruction(ExifInterface.class, (mock, construction) -> {
            when(mock.getAttribute(ExifInterface.TAG_MAKE)).thenReturn("Make");
            when(mock.getLatLong()).thenReturn(new double[]{39.7392, -104.9903});
        })) {
            try (MockedConstruction<Geocoder> geocoder = mockConstruction(Geocoder.class, (mock, construction) ->
                    when(mock.getFromLocation(anyDouble(), anyDouble(), anyInt()))
                            .thenThrow(new IOException("geo fail")))) {
                String metadata = invokeExtractImageMetadata(context, uri);
                assertTrue(metadata.contains("Geocoding failed: geo fail"));
            }
        }
    }

    @Test
    public void extractImageMetadata_ioExceptionAppendsErrorMessage() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("exif read fail");
            }

            @Override
            public int read(byte[] buffer, int offset, int len) throws IOException {
                throw new IOException("exif read fail");
            }
        };
        when(resolver.openInputStream(any(Uri.class))).thenReturn(failingStream);
        Context context = wrapWithResolver(baseContext, resolver);
        String metadata = invokeExtractImageMetadata(context, Uri.parse("content://tests/sync-image-io"));
        assertNotNull(metadata);
    }

    @Test
    public void extractImageMetadataToMap_realFilesCoverGpsAndXmpBranches() throws Exception {
        File southWestFile = createImageFile("map-south-west.jpg", false, exif -> {
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39/1,44/1,21120/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "104/1,59/1,25160/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, "1920");
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, "1080");
        });
        Map<String, String> southWestMap = invokeExtractImageMetadataToMap(
                fileContext, Uri.fromFile(southWestFile));
        assertTrue(Double.parseDouble(southWestMap.get("GPS_LATITUDE")) < 0);
        assertTrue(Double.parseDouble(southWestMap.get("GPS_LONGITUDE")) < 0);
        assertTrue(southWestMap.containsKey("IMAGE_WIDTH"));

        byte[] imageBytes = Files.readAllBytes(defaultImageFile.toPath());
        ContentResolver mockContext = mock(ContentResolver.class);
        when(mockContext.openInputStream(any(Uri.class)))
                .thenReturn(new ByteArrayInputStream(imageBytes));
        Context resolverContext = wrapWithResolver(baseContext, mockContext);
        try (MockedConstruction<ExifInterface> ignored = mockConstruction(ExifInterface.class, (mock, construction) -> {
            when(mock.getAttribute(ExifInterface.TAG_GPS_LATITUDE)).thenReturn("not-valid");
            when(mock.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)).thenReturn("also-bad");
        })) {
            Map<String, String> badGpsMap = invokeExtractImageMetadataToMap(
                    resolverContext, Uri.parse("content://tests/map-bad-gps"));
            assertEquals("not-valid", badGpsMap.get("GPSLATITUDE"));
            assertEquals("also-bad", badGpsMap.get("GPSLONGITUDE"));
        }

        File xmpFile = createImageFile("map-xmp.jpg", false, exif -> exif.setAttribute(
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                "<?xpacket begin=\"id\"?><x:xmpmeta><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                        + "<rdf:Description/></rdf:RDF></x:xmpmeta>   "));
        Map<String, String> xmpMap = invokeExtractImageMetadataToMap(fileContext, Uri.fromFile(xmpFile));
        assertNotNull(xmpMap.get("IMAGE_DESCRIPTION"));
        assertTrue(xmpMap.get("IMAGE_DESCRIPTION").contains(">\n<"));
    }

    @Test
    public void extractImageMetadataToMap_openInputStreamIOException_hitsCatch() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.openInputStream(any(Uri.class)))
                .thenReturn(new IOExceptionInputStream(new IOException("io fail")));
        Context context = wrapWithResolver(baseContext, resolver);
        Map<String, String> map = invokeExtractImageMetadataToMap(context, Uri.parse("content://tests/io-map"));
        assertNotNull(map);
    }

    @Test
    public void extractImageMetadataToMap_openInputStreamThrowsIOException_hitsCatch() throws Exception {
        Uri imageUri = Uri.parse("content://tests/io-map-direct");
        ContentResolver resolver = mock(ContentResolver.class);
        doAnswer(invocation -> {
                    throw new IOException("direct open fail");
                })
                .when(resolver)
                .openInputStream(eq(imageUri));
        Context context = wrapWithResolver(baseContext, resolver);
        Map<String, String> map = invokeExtractImageMetadataToMap(context, imageUri);
        assertNotNull(map);
    }

    @Test
    public void extractVideoMetadataToMap_invalidBitrate_recordsParseFailure() throws Exception {
        Uri videoUri = createVideoUri("bitrate-invalid.mp4");
        try (MockedConstruction<MediaMetadataRetriever> ignored = mockConstruction(
                MediaMetadataRetriever.class, (mock, construction) -> {
                    doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
                    when(mock.extractMetadata(anyInt())).thenAnswer(invocation -> {
                        int key = invocation.getArgument(0);
                        if (key == MediaMetadataRetriever.METADATA_KEY_BITRATE) {
                            return "not-a-number";
                        }
                        return null;
                    });
                })) {
            Map<String, String> map = invokeExtractVideoMetadataToMap(fileContext, videoUri);
            assertEquals("not-a-number", map.get("BITRATE"));
        }
    }

    @Test
    public void extractSectionedMetadata_image_manyExifTagsAndSouthWestGps() throws Exception {
        File heavyExifFile = createImageFile("heavy-exif.jpg", true, this::populateManyExifTags);
        SectionedResult heavyResult = awaitSectionedMetadata(fileContext, Uri.fromFile(heavyExifFile));
        assertNull(heavyResult.error);
        assertTrue(heavyResult.sections.containsKey(MetadataDisplayer.SECTION_LOCATION));

        File southWestFile = createImageFile("south-west.jpg", false, exif -> {
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39/1,44/1,21120/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "104/1,59/1,25160/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
        });
        Map<String, String> location = parseSerializedSection(
                (awaitSectionedMetadata(fileContext, Uri.fromFile(southWestFile)).sections)
                        .get(MetadataDisplayer.SECTION_LOCATION));
        assertTrue(Double.parseDouble(location.get("GPS_LATITUDE")) < 0);
        assertTrue(Double.parseDouble(location.get("GPS_LONGITUDE")) < 0);
    }

    @Test
    public void extractSectionedMetadata_basicFileInfoToMap_bytesAndQueryFailure() throws Exception {
        Cursor bytesCursor = mockCursor("bytes.jpg", 512L, true, true);
        SectionedResult bytesResult = awaitSectionedMetadata(wrapWithResolver(baseContext,
                        mockResolver("image/jpeg", bytesCursor, defaultImageFile, false, null, null)),
                Uri.parse("content://tests/sectioned-bytes"));
        assertEquals("512 B", parseSerializedSection(
                bytesResult.sections.get(MetadataDisplayer.SECTION_BASIC_INFO)).get("SIZE"));

        ContentResolver failingResolver = mockResolver("image/jpeg", null, defaultImageFile,
                true, null, null);
        SectionedResult failingResult = awaitSectionedMetadata(
                wrapWithResolver(baseContext, failingResolver),
                Uri.parse("content://tests/sectioned-basic-query-fail"));
        assertNull(failingResult.error);
        assertTrue(failingResult.sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO));
    }

    @Test
    public void extractMetadata_video_retrieverReleaseFailureIsIgnored() throws Exception {
        Uri videoUri = createVideoUri("video_release_fail.mp4");

        try (MockedConstruction<MediaMetadataRetriever> ignored =
                     mockVideoRetrieverConstruction(true)) {
            MetadataResult result = awaitMetadata(fileContext, videoUri);

            assertNull(result.error);
            assertTrue(result.metadata.contains("MIME_TYPE: video/mp4"));
        }
    }

    @Test
    public void extractSectionedMetadata_video_whitespaceLocationTrimsTrailingSpaces() throws Exception {
        Uri videoUri = createVideoUri("video_sectioned_extra.mp4");
        DataSource dataSource = DataSource.toDataSource(fileContext, videoUri);
        ShadowMediaMetadataRetriever.addMetadata(dataSource, MediaMetadataRetriever.METADATA_KEY_DURATION,
                "5000");
        ShadowMediaMetadataRetriever.addMetadata(dataSource, MediaMetadataRetriever.METADATA_KEY_LOCATION,
                "+39.7392-104.9903/   ");
        ShadowMediaMetadataRetriever.addMetadata(dataSource, MediaMetadataRetriever.METADATA_KEY_TITLE,
                "<?xml version=\"1.0\"?><rdf:RDF><broken></rdf:RDF>   ");

        SectionedResult shadowResult = awaitSectionedMetadata(fileContext, videoUri);
        assertNull(shadowResult.error);
        Map<String, String> location = parseSerializedSection(
                shadowResult.sections.get(MetadataDisplayer.SECTION_LOCATION));
        assertTrue(location.containsKey("GPS_LATITUDE"));
        assertFalse(location.get("GPS_LATITUDE").endsWith(" "));
    }

    @Test
    public void extractVideoMetadata_geocodedAddressUsesMultipleLines() throws Exception {
        Uri videoUri = createVideoUri("video_geocode_lines.mp4");
        registerVideoMetadata(videoUri, Map.of(
                MediaMetadataRetriever.METADATA_KEY_LOCATION, "+39.7392-104.9903/"));

        try (MockedConstruction<Geocoder> ignored = mockGeocoderReturning("Main St", "Denver", "USA")) {
            StringBuilder metadata = new StringBuilder();
            invokeExtractVideoMetadata(fileContext, videoUri, metadata);
            assertTrue(metadata.toString().contains("Address: Main St, Denver, USA"));
        }
    }

    @Test
    public void extractVideoMetadata_sync_mockedRetrieverReleaseFailureIsIgnored() throws Exception {
        Uri videoUri = Uri.parse("content://tests/sync-video");
        try (MockedConstruction<MediaMetadataRetriever> ignored = mockVideoRetrieverConstruction(true)) {
            StringBuilder metadata = new StringBuilder();
            invokeExtractVideoMetadata(fileContext, videoUri, metadata);
            assertFalse(metadata.toString().isEmpty());
        }
    }

    @Test
    public void extractVideoMetadataToMap_sync_fallbackTechnicalKeysAndRelease() throws Exception {
        Uri videoUri = Uri.parse("content://tests/sync-video-map");

        try (MockedConstruction<MediaMetadataRetriever> ignored =
                     mockVideoRetrieverFallbackKeysConstruction()) {
            Map<String, String> metadataMap = invokeExtractVideoMetadataToMap(fileContext, videoUri);
            assertFalse(metadataMap.isEmpty());
            assertTrue(metadataMap.containsValue("1000"));
            assertTrue(metadataMap.containsValue("24"));
            assertTrue(metadataMap.containsValue("128000"));
            assertTrue(metadataMap.containsValue("44100"));
        }

        try (MockedConstruction<MediaMetadataRetriever> ignored = mockConstruction(
                MediaMetadataRetriever.class, (mock, construction) -> {
                    doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
                    when(mock.extractMetadata(anyInt())).thenAnswer(invocation -> {
                        int key = invocation.getArgument(0);
                        if (key == MediaMetadataRetriever.METADATA_KEY_DURATION) {
                            return "1000";
                        }
                        if (key == MediaMetadataRetriever.METADATA_KEY_LOCATION) {
                            return "not-parsable-location";
                        }
                        return null;
                    });
                })) {
            Map<String, String> metadataMap = invokeExtractVideoMetadataToMap(fileContext, videoUri);
            assertEquals("not-parsable-location", metadataMap.get("LOCATION"));
        }

        try (MockedConstruction<MediaMetadataRetriever> ignored = mockConstruction(
                MediaMetadataRetriever.class, (mock, construction) -> {
                    doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
                    when(mock.extractMetadata(anyInt())).thenAnswer(invocation -> {
                        int key = invocation.getArgument(0);
                        if (key == MediaMetadataRetriever.METADATA_KEY_LOCATION) {
                            return "+39.x-104.9903/"; // triggers parse exception
                        }
                        if (key == MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) {
                            return ""; // first call (reflection) returns empty; fallback will call again
                        }
                        if (key == MediaMetadataRetriever.METADATA_KEY_BITRATE) {
                            return ""; // force fallback path
                        }
                        if (key == MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) {
                            return ""; // force fallback path
                        }
                        return null;
                    });
                    when(mock.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE))
                            .thenReturn("", "29.97");
                    when(mock.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE))
                            .thenReturn("", "bad-bitrate");
                    when(mock.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE))
                            .thenReturn("", "48000");
                })) {
            Map<String, String> metadataMap = invokeExtractVideoMetadataToMap(fileContext, videoUri);
            assertEquals("+39.x-104.9903/", metadataMap.get("LOCATION"));
            assertEquals("29.97", metadataMap.get("CAPTURE_FRAMERATE"));
            assertEquals("bad-bitrate", metadataMap.get("BITRATE"));
            assertEquals("48000", metadataMap.get("SAMPLE_RATE"));
        }

        // Force the generic LOCATION parse catch path (covers the defensive catch block).
        try (MockedConstruction<MediaMetadataRetriever> ignored = mockConstruction(
                MediaMetadataRetriever.class, (mock, construction) -> {
                    doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
                    when(mock.extractMetadata(anyInt())).thenAnswer(invocation -> {
                        int key = invocation.getArgument(0);
                        if (key == MediaMetadataRetriever.METADATA_KEY_LOCATION) {
                            return "__force_exception__";
                        }
                        return null;
                    });
                })) {
            Map<String, String> metadataMap = invokeExtractVideoMetadataToMap(fileContext, videoUri);
            assertEquals("__force_exception__", metadataMap.get("LOCATION"));
        }

        // Force an IllegalArgumentException inside the reflection loop to cover the skip-catch.
        try (MockedConstruction<MediaMetadataRetriever> ignored = mockConstruction(
                MediaMetadataRetriever.class, (mock, construction) -> {
                    doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
                    when(mock.extractMetadata(anyInt())).thenThrow(new IllegalArgumentException("bad key"));
                })) {
            Map<String, String> metadataMap = invokeExtractVideoMetadataToMap(fileContext, videoUri);
            assertNotNull(metadataMap);
        }

        try (MockedConstruction<MediaMetadataRetriever> ignored = mockConstruction(
                MediaMetadataRetriever.class, (mock, construction) -> {
                    doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
                    when(mock.extractMetadata(anyInt())).thenReturn(null);
                    doThrow(new RuntimeException("release fail")).when(mock).release();
                })) {
            invokeExtractVideoMetadataToMap(fileContext, videoUri);
        }
    }

    @Test
    public void extractSectionedMetadata_video_emptyLocationMarksNoGpsData() throws Exception {
        Uri videoUri = createVideoUri("video_no_gps_sectioned.mp4");
        registerVideoMetadata(videoUri, Map.of(
                MediaMetadataRetriever.METADATA_KEY_DURATION, "1000"));

        SectionedResult result = awaitSectionedMetadata(fileContext, videoUri);

        assertNull(result.error);
        assertFalse(result.sections.containsKey(MetadataDisplayer.SECTION_LOCATION));
    }

    private void assertBasicInfoFormatting(long fileSize, String expectedSize) throws Exception {
        Cursor cursor = mockCursor("resolver-name.jpg", fileSize, true, true);
        ContentResolver resolver = mockResolver(null, cursor, defaultImageFile,
                false, null, null);
        MetadataResult result = awaitMetadata(wrapWithResolver(baseContext, resolver),
                Uri.parse("content://tests/basic-info/" + fileSize));

        assertNull(result.error);
        assertTrue(result.metadata.contains("DISPLAY_NAME: resolver-name.jpg"));
        assertTrue(result.metadata.contains("MIME_TYPE: Unknown"));
        assertTrue(result.metadata.contains("SIZE: " + expectedSize));
    }

    private MetadataResult awaitMetadata(Context context, Uri uri) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        MetadataResult result = new MetadataResult();

        MetadataDisplayer.extractMetadata(context, uri, new MetadataDisplayer.MetadataCallback() {
            @Override
            public void onMetadataExtracted(String metadata, boolean isVideo) {
                result.metadata = metadata;
                result.isVideo = isVideo;
                latch.countDown();
            }

            @Override
            public void onExtractionFailed(String error) {
                result.error = error;
                latch.countDown();
            }
        });

        assertTrue("Timed out waiting for metadata callback",
                latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return result;
    }

    private SectionedResult awaitSectionedMetadata(Context context, Uri uri)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        SectionedResult result = new SectionedResult();

        MetadataDisplayer.extractSectionedMetadata(context, uri,
                new MetadataDisplayer.SectionedMetadataCallback() {
                    @Override
                    public void onMetadataExtracted(Map<String, String> metadataSections,
                                                    boolean isVideo) {
                        result.sections = metadataSections;
                        result.isVideo = isVideo;
                        latch.countDown();
                    }

                    @Override
                    public void onExtractionFailed(String error) {
                        result.error = error;
                        latch.countDown();
                    }
                });

        assertTrue("Timed out waiting for sectioned metadata callback",
                latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return result;
    }

    private Context wrapWithResolver(Context base, ContentResolver resolver) {
        return new ContextWrapper(base) {
            @Override
            public ContentResolver getContentResolver() {
                return resolver;
            }
        };
    }

    private ContentResolver buildFileAwareResolver() throws FileNotFoundException {
        ContentResolver resolver = mock(ContentResolver.class);

        when(resolver.getType(any(Uri.class))).thenAnswer(invocation -> {
            Uri uri = invocation.getArgument(0);
            String path = uri.getPath();
            if (path == null) {
                return null;
            }
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            if (lowerPath.endsWith(".mp4")) {
                return "video/mp4";
            }
            return null;
        });

        when(resolver.query(any(Uri.class), any(), any(), any(), any())).thenAnswer(invocation -> {
            Uri uri = invocation.getArgument(0);
            File file = new File(uri.getPath());
            MatrixCursor cursor = new MatrixCursor(
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{file.getName(), file.exists() ? file.length() : -1L});
            return cursor;
        });

        when(resolver.openInputStream(any(Uri.class))).thenAnswer(invocation -> {
            Uri uri = invocation.getArgument(0);
            File file = new File(uri.getPath());
            if (!file.exists()) {
                throw new FileNotFoundException(file.getAbsolutePath());
            }
            return new FileInputStream(file);
        });

        return resolver;
    }

    private ContentResolver mockResolver(String mimeType,
                                         Cursor cursor,
                                         File inputFile,
                                         boolean throwQueryException,
                                         RuntimeException getTypeException,
                                         Exception openInputStreamException) throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);

        if (getTypeException != null) {
            when(resolver.getType(any(Uri.class))).thenThrow(getTypeException);
        } else {
            when(resolver.getType(any(Uri.class))).thenReturn(mimeType);
        }

        if (throwQueryException) {
            when(resolver.query(any(Uri.class), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("Query exploded"));
        } else {
            when(resolver.query(any(Uri.class), any(), any(), any(), any())).thenReturn(cursor);
        }

        if (openInputStreamException instanceof IOException) {
            when(resolver.openInputStream(any(Uri.class))).thenAnswer(invocation ->
                    new IOExceptionInputStream((IOException) openInputStreamException));
        } else if (openInputStreamException != null) {
            when(resolver.openInputStream(any(Uri.class))).thenAnswer(invocation -> {
                throw openInputStreamException;
            });
        } else if (inputFile == null) {
            when(resolver.openInputStream(any(Uri.class))).thenReturn(null);
        } else {
            when(resolver.openInputStream(any(Uri.class))).thenAnswer(invocation ->
                    new ByteArrayInputStream(Files.readAllBytes(inputFile.toPath())));
        }

        return resolver;
    }

    private Cursor mockCursor(String displayName, long size, boolean includeName, boolean includeSize) {
        Cursor cursor = mock(Cursor.class);
        when(cursor.moveToFirst()).thenReturn(true);
        when(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(includeName ? 0 : -1);
        when(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(includeSize ? 1 : -1);
        when(cursor.getString(0)).thenReturn(displayName);
        when(cursor.getLong(1)).thenReturn(size);
        return cursor;
    }

    private File createImageFile(String fileName, boolean includeLocation, ExifCustomizer customizer)
            throws IOException {
        return createImageFile(fileName, includeLocation, true, customizer);
    }

    private File createPlainImageFile(String fileName, boolean includeLocation,
                                      ExifCustomizer customizer) throws IOException {
        return createImageFile(fileName, includeLocation, false, customizer);
    }

    private File createImageFile(String fileName, boolean includeLocation, boolean includeDefaults,
                                 ExifCustomizer customizer)
            throws IOException {
        File file = new File(tempDir, fileName);
        Bitmap bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF00AAFF);
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream));
        }
        bitmap.recycle();

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        if (includeDefaults) {
            exif.setAttribute(ExifInterface.TAG_MAKE, "TestMake");
            exif.setAttribute(ExifInterface.TAG_MODEL, "TestModel");
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.5");
            exif.setAttribute(ExifInterface.TAG_FLASH, "1");
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0");
            exif.setAttribute(ExifInterface.TAG_DATETIME, "2024:05:01 12:34:56");
        }
        if (includeLocation) {
            exif.setLatLong(39.7392, -104.9903);
        }
        customizer.configure(exif);
        exif.saveAttributes();
        return file;
    }

    private Uri createVideoUri(String fileName) throws IOException {
        File videoFile = new File(tempDir, fileName);
        try (FileOutputStream outputStream = new FileOutputStream(videoFile)) {
            outputStream.write(new byte[]{0, 1, 2, 3});
        }
        return Uri.fromFile(videoFile);
    }

    private void registerVideoMetadata(Uri videoUri, Map<Integer, String> values) {
        DataSource dataSource = DataSource.toDataSource(fileContext, videoUri);
        for (Map.Entry<Integer, String> entry : values.entrySet()) {
            ShadowMediaMetadataRetriever.addMetadata(dataSource, entry.getKey(), entry.getValue());
        }
    }

    private MockedConstruction<Geocoder> mockGeocoderReturning(String... lines) throws IOException {
        Address address = new Address(Locale.getDefault());
        for (int i = 0; i < lines.length; i++) {
            address.setAddressLine(i, lines[i]);
        }
        return mockConstruction(Geocoder.class, (mock, context) ->
                when(mock.getFromLocation(anyDouble(), anyDouble(), anyInt()))
                        .thenReturn(List.of(address)));
    }

    private MockedConstruction<MediaMetadataRetriever> mockVideoRetrieverConstruction(
            boolean releaseThrows) {
        return mockConstruction(MediaMetadataRetriever.class, (mock, context) -> {
            doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
            when(mock.extractMetadata(anyInt())).thenAnswer(invocation -> {
                int key = invocation.getArgument(0);
                if (key == MediaMetadataRetriever.METADATA_KEY_DURATION) {
                    return "1000";
                }
                if (key == MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) {
                    return "24";
                }
                if (key == MediaMetadataRetriever.METADATA_KEY_BITRATE) {
                    return "128000";
                }
                if (key == MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) {
                    return "44100";
                }
                if (key == MediaMetadataRetriever.METADATA_KEY_LOCATION) {
                    return "+39.7392-104.9903/";
                }
                return null;
            });
            if (releaseThrows) {
                doThrow(new RuntimeException("release fail")).when(mock).release();
            }
        });
    }

    private MockedConstruction<MediaMetadataRetriever> mockVideoRetrieverFallbackKeysConstruction() {
        return mockConstruction(MediaMetadataRetriever.class, (mock, context) -> {
            doAnswer(invocation -> null).when(mock).setDataSource(any(Context.class), any(Uri.class));
            when(mock.extractMetadata(anyInt())).thenAnswer(invocation -> {
                int key = invocation.getArgument(0);
                if (key == MediaMetadataRetriever.METADATA_KEY_DURATION) {
                    return "1000";
                }
                if (key == MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) {
                    return "24";
                }
                if (key == MediaMetadataRetriever.METADATA_KEY_BITRATE) {
                    return "128000";
                }
                if (key == MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) {
                    return "44100";
                }
                return null;
            });
        });
    }

    private void populateManyExifTags(ExifInterface exif) throws IOException {
        int added = 0;
        for (java.lang.reflect.Field field : ExifInterface.class.getDeclaredFields()) {
            if (added >= 81) {
                break;
            }
            if (field.getType() == String.class && field.getName().startsWith("TAG_")) {
                String tag;
                try {
                    tag = (String) field.get(null);
                } catch (IllegalAccessException e) {
                    continue;
                }
                if (tag != null && !tag.startsWith("GPS")) {
                    String value = ExifInterface.TAG_IMAGE_DESCRIPTION.equals(tag)
                            ? "<?xpacket begin=\"id\"?><x:xmpmeta><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                            + "<rdf:Description/></rdf:RDF></x:xmpmeta>   "
                            : ("tag-" + added + " ");
                    exif.setAttribute(tag, value);
                    added++;
                }
            }
        }
    }

    private String invokeExtractImageMetadata(Context context, Uri uri) throws Exception {
        StringBuilder metadata = new StringBuilder();
        invokePrivateStatic("extractImageMetadata",
                new Class[]{Context.class, Uri.class, StringBuilder.class},
                context, uri, metadata);
        return metadata.toString();
    }

    private Map<String, String> invokeExtractImageMetadataToMap(Context context, Uri uri)
            throws Exception {
        Map<String, String> metadataMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        invokePrivateStatic("extractImageMetadataToMap",
                new Class[]{Context.class, Uri.class, Map.class},
                context, uri, metadataMap);
        return metadataMap;
    }

    private void invokeExtractVideoMetadata(Context context, Uri uri, StringBuilder metadata)
            throws Exception {
        invokePrivateStatic("extractVideoMetadata",
                new Class[]{Context.class, Uri.class, StringBuilder.class},
                context, uri, metadata);
    }

    private Map<String, String> invokeExtractVideoMetadataToMap(Context context, Uri uri)
            throws Exception {
        Map<String, String> metadataMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        invokePrivateStatic("extractVideoMetadataToMap",
                new Class[]{Context.class, Uri.class, Map.class},
                context, uri, metadataMap);
        return metadataMap;
    }

    private static Object invokePrivateStatic(String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = MetadataDisplayer.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static final class IOExceptionInputStream extends InputStream {
        private final IOException failure;

        IOExceptionInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw failure;
        }
    }

    private void grantLocationPermission() {
        Shadows.shadowOf((Application) baseContext)
                .grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
    }

    private Context wrapWithLocationPermission(Context base, int permissionResult) {
        return new ContextWrapper(base) {
            @Override
            public int checkPermission(String permission, int pid, int uid) {
                if (Manifest.permission.ACCESS_MEDIA_LOCATION.equals(permission)) {
                    return permissionResult;
                }
                return super.checkPermission(permission, pid, uid);
            }

            @Override
            public int checkSelfPermission(String permission) {
                if (Manifest.permission.ACCESS_MEDIA_LOCATION.equals(permission)) {
                    return permissionResult;
                }
                return super.checkSelfPermission(permission);
            }
        };
    }

    private Map<String, String> parseSerializedSection(String sectionContent) {
        Map<String, String> values = new LinkedHashMap<>();
        if (sectionContent == null || sectionContent.isEmpty()) {
            return values;
        }

        String[] records = sectionContent.split(String.valueOf('\u001e'));
        for (String record : records) {
            if (record == null || record.isEmpty()) {
                continue;
            }
            int separatorIndex = record.indexOf('\u001f');
            if (separatorIndex <= 0) {
                continue;
            }
            values.put(record.substring(0, separatorIndex),
                    record.substring(separatorIndex + 1));
        }
        return values;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    @FunctionalInterface
    private interface ExifCustomizer {
        void configure(ExifInterface exif) throws IOException;
    }

    private static final class MetadataResult {
        private String metadata;
        private String error;
        private boolean isVideo;
    }

    private static final class SectionedResult {
        private Map<String, String> sections;
        private String error;
        private boolean isVideo;
    }
}
