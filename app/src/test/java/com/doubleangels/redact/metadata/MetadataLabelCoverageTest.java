package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.MediaMetadataRetriever;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.ui.MetadataLabels;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every name Scan can show must have a label that reads well. The EXIF tags and the retriever's
 * keys come from the libraries, so a library upgrade that adds one fails here until its label is
 * reviewed; the names that come from file contents are checked by their patterns.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MetadataLabelCoverageTest {

    /** Every ExifInterface tag as Scan names it, with the label it must get ({@code exif_tag_labels.txt}). */
    @Test
    public void everyExifTag_hasAReviewedLabel() throws Exception {
        Map<String, String> golden = new TreeMap<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("exif_tag_labels.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] parts = line.split("\t", 2);
                if (parts.length == 2) {
                    golden.put(parts[0], parts[1]);
                }
            }
        }

        Map<String, String> actual = new TreeMap<>();
        for (Field field : ExifInterface.class.getDeclaredFields()) {
            // Only the public tags: the app skips fields it cannot read, as a private one is.
            if (field.getType() == String.class && field.getName().startsWith("TAG_")
                    && java.lang.reflect.Modifier.isPublic(field.getModifiers())) {
                String tag = (String) field.get(null);
                if (tag != null) {
                    String raw = MetadataDisplayer.convertToSnakeCase(tag).toUpperCase(Locale.ROOT);
                    actual.put(raw, MetadataLabels.format(raw));
                }
            }
        }

        TreeSet<String> unreviewed = new TreeSet<>(actual.keySet());
        unreviewed.removeAll(golden.keySet());
        assertTrue("EXIF tags with no reviewed label (add them to exif_tag_labels.txt after checking how "
                + "MetadataLabels reads them): " + unreviewed, unreviewed.isEmpty());
        for (Map.Entry<String, String> e : actual.entrySet()) {
            assertEquals("label of " + e.getKey(), golden.get(e.getKey()), e.getValue());
        }
        for (String label : actual.values()) {
            assertTrue(label, label.matches("[A-Za-z0-9]+( [A-Za-z0-9]+)*"));
        }
    }

    private static final Map<String, String> RETRIEVER_LABELS = Map.ofEntries(
            Map.entry("ALBUM", "Album"), Map.entry("ALBUMARTIST", "Album Artist"),
            Map.entry("IS_DRM", "Is DRM"), Map.entry("TIMED_TEXT_LANGUAGES", "Timed Text Languages"),
            Map.entry("VIDEO_CODEC_MIME_TYPE", "Video Codec MIME Type"),
            Map.entry("ARTIST", "Artist"), Map.entry("AUTHOR", "Author"), Map.entry("BITRATE", "Bitrate"),
            Map.entry("BITS_PER_SAMPLE", "Bits Per Sample"), Map.entry("CAPTURE_FRAMERATE", "Capture Frame Rate"),
            Map.entry("CD_TRACK_NUMBER", "CD Track Number"), Map.entry("COLOR_RANGE", "Color Range"),
            Map.entry("COLOR_STANDARD", "Color Standard"), Map.entry("COLOR_TRANSFER", "Color Transfer"),
            Map.entry("COMPILATION", "Compilation"), Map.entry("COMPOSER", "Composer"),
            Map.entry("DATE", "Date"), Map.entry("DISC_NUMBER", "Disc Number"), Map.entry("DURATION", "Duration"),
            Map.entry("EXIF_LENGTH", "EXIF Length"), Map.entry("EXIF_OFFSET", "EXIF Offset"),
            Map.entry("GENRE", "Genre"), Map.entry("HAS_AUDIO", "Has Audio"), Map.entry("HAS_IMAGE", "Has Image"),
            Map.entry("HAS_VIDEO", "Has Video"), Map.entry("IMAGE_COUNT", "Image Count"),
            Map.entry("IMAGE_HEIGHT", "Image Height"), Map.entry("IMAGE_PRIMARY", "Image Primary"),
            Map.entry("IMAGE_ROTATION", "Image Rotation"), Map.entry("IMAGE_WIDTH", "Image Width"),
            Map.entry("LOCATION", "Location"), Map.entry("MIMETYPE", "MIME Type"),
            Map.entry("NUM_TRACKS", "Num Tracks"), Map.entry("SAMPLERATE", "Sample Rate"),
            Map.entry("TITLE", "Title"), Map.entry("VIDEO_FRAME_COUNT", "Video Frame Count"),
            Map.entry("VIDEO_HEIGHT", "Video Height"), Map.entry("VIDEO_ROTATION", "Video Rotation"),
            Map.entry("VIDEO_WIDTH", "Video Width"), Map.entry("WRITER", "Writer"),
            Map.entry("XMP_LENGTH", "XMP Length"), Map.entry("XMP_OFFSET", "XMP Offset"),
            Map.entry("YEAR", "Year"));

    @Test
    public void everyRetrieverKey_hasAReviewedLabel() throws Exception {
        List<String> unreviewed = new ArrayList<>();
        for (Field field : MediaMetadataRetriever.class.getDeclaredFields()) {
            if (field.getType() == int.class && field.getName().startsWith("METADATA_KEY_")) {
                String raw = field.getName().substring("METADATA_KEY_".length());
                String expected = RETRIEVER_LABELS.get(raw);
                if (expected == null) {
                    unreviewed.add(raw);
                } else {
                    assertEquals(raw, expected, MetadataLabels.format(raw));
                }
            }
        }
        assertTrue("MediaMetadataRetriever keys with no reviewed label: " + unreviewed, unreviewed.isEmpty());
    }

    @Test
    public void namesBuiltFromFileContents_readWell() {
        String[][] cases = {
                // keys the image scanners build around a code from the file
                {"PNG_CHUNK_TEXT", "PNG Chunk TEXT"}, {"PNG_CHUNK_ITXT", "PNG Chunk ITXT"},
                {"PNG_CHUNK_PHYS", "PNG Chunk PHYS"}, {"WEBP_CHUNK_EXIF", "WEBP Chunk EXIF"},
                {"HEIF_BOX_FTYP", "HEIF Box FTYP"}, {"HEIF_PROPERTY_HVCC", "HEIF Property HVCC"},
                {"JPEG_SEGMENT_FE", "JPEG Segment FE"}, {"GIF_EXTENSION_FE", "GIF Extension FE"},
                {"GIF_EXTENSION_F9", "GIF Extension F9"}, {"GIF_APP_NETSCAPE2_0", "GIF App Netscape2 0"},
                {"APP1_DATA", "APP1 Data"}, {"APP2_MPF", "APP2 MPF"}, {"APP13_DATA", "APP13 Data"},
                {"PNG_TEXT_RAW_PROFILE_TYPE_EXIF", "PNG Text Raw Profile Type EXIF"},
                {"HEIF_ITEM_IMAGE_JPEG", "HEIF Item Image JPEG"}, {"HEIF_ITEM_HVC1", "HEIF Item HVC1"},
                {"IPTC_12", "IPTC 12"}, {"IPTC_COPYRIGHT", "IPTC Copyright"},
                {"LOCATION_COUNTRY_CODE", "Location Country Code"},
                // the fixed keys
                {"EMBEDDED_IMAGE_DEVICE", "Embedded Image Device"}, {"ULTRA_HDR_GAIN_MAP", "Ultra HDR Gain Map"},
                {"MOTION_PHOTO_VIDEO", "Motion Photo Video"}, {"ICC_PROFILE_DEVICE", "ICC Profile Device"},
                {"VIDEO_STREAM_USER_DATA", "Video Stream User Data"}, {"EXIF_THUMBNAIL", "EXIF Thumbnail"},
                {"UNNAMED_FIELD", "Unnamed Field"}, {"CONTAINER_DATE", "Container Date"},
                {"SCAN_NOTE", "Scan Note"}, {"DISPLAY_NAME", "Display Name"}, {"MIME_TYPE", "MIME Type"},
                // video container names after containerDisplayKey
                {"TRACK_VIDE", "Track Video"}, {"TRACK_SOUN", "Track Sound"}, {"TRACK_META", "Track Meta"},
                {"CREATION_TIME", "Creation Time"}, {"NUMBER_OF_FRAMES_ENG", "Number Of Frames Eng"},
        };
        for (String[] c : cases) {
            assertEquals(c[0], c[1], MetadataLabels.format(c[0]));
        }
    }

    @Test
    public void threeGppAndItunesBoxes_getReadableKeys() {
        String[][] cases = {
                {"cprt", "COPYRIGHT"}, {"auth", "AUTHOR"}, {"perf", "PERFORMER"}, {"albm", "ALBUM"},
                {"yrrc", "YEAR"}, {"titl", "TITLE"}, {"dscp", "DESCRIPTION"}, {"gnre", "GENRE"},
                {"rtng", "RATING"}, {"clsf", "CLASSIFICATION"}, {"kywd", "KEYWORDS"},
                {"aART", "ALBUM_ARTIST"}, {"covr", "COVER_ART"}, {"trkn", "TRACK_NUMBER"},
                {"©alb", "ALBUM"}, {"©gen", "GENRE"}, {"©wrt", "WRITER"}, {"©lyr", "LYRICS"},
        };
        for (String[] c : cases) {
            assertEquals(c[0], c[1], MetadataDisplayer.containerDisplayKey(c[0]));
        }
    }
}
