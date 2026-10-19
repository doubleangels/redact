package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CleanStatsTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private ExifInterface exifWith(Map<String, String> tags) throws Exception {
        File f = tmp.newFile("photo.jpg");
        Bitmap bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        for (Map.Entry<String, String> t : tags.entrySet()) {
            e.setAttribute(t.getKey(), t.getValue());
        }
        e.saveAttributes();
        return new ExifInterface(f.getAbsolutePath());
    }

    // ---- photos ---------------------------------------------------------------------------------

    @Test
    public void photo_sortsItsTagsIntoDatesDevicesAndNames() throws Exception {
        CleanStats s = CleanStats.fromExif(exifWith(Map.of(
                ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:18 10:00:00",
                ExifInterface.TAG_MODEL, "Pixel 10 Pro",
                ExifInterface.TAG_ARTIST, "Sam")), Collections.emptySet());

        // Saving can add a related tag of its own, so this is at least the three set here.
        assertTrue(String.valueOf(s.removedFields), s.removedFields >= 3);
        assertTrue(s.hadDate);
        assertTrue(s.hadDevice);
        assertTrue(s.hadName);
        assertFalse(s.hadLocation);
    }

    @Test
    public void photo_withOnlyAnOrientation_carriedNoneOfThem() throws Exception {
        CleanStats s = CleanStats.fromExif(exifWith(Map.of(
                ExifInterface.TAG_ORIENTATION, "6")), Collections.emptySet());

        assertFalse(s.hadDate);
        assertFalse(s.hadDevice);
        assertFalse(s.hadName);
    }

    @Test
    public void photo_detailsTheSettingsKeep_areNotCounted() throws Exception {
        CleanStats s = CleanStats.fromExif(exifWith(Map.of(
                ExifInterface.TAG_MAKE, "Acme",
                ExifInterface.TAG_DATETIME, "2026:10:18 10:00:00")),
                Set.of(ExifInterface.TAG_MAKE));

        assertFalse(s.hadDevice);
        assertTrue(s.hadDate);
    }

    @Test
    public void photo_lensSerialAndOwner_countAsDeviceAndName() throws Exception {
        CleanStats s = CleanStats.fromExif(exifWith(Map.of(
                ExifInterface.TAG_BODY_SERIAL_NUMBER, "A1B2C3",
                ExifInterface.TAG_CAMERA_OWNER_NAME, "Sam")), Collections.emptySet());

        assertTrue(s.hadDevice);
        assertTrue(s.hadName);
        assertFalse(s.hadDate);
    }

    // ---- videos ---------------------------------------------------------------------------------

    @Test
    public void video_containerFields_areSortedByName() {
        CleanStats s = CleanStats.withVideoFields(4, true, false, false,
                List.of("©mak", "©mod", "©day", "©ART"));

        assertEquals(4, s.removedFields);
        assertTrue(s.hadLocation);
        assertTrue(s.hadDevice);
        assertTrue(s.hadDate);
        assertTrue(s.hadName);
    }

    @Test
    public void video_quickTimeKeys_areSortedByName() {
        CleanStats s = CleanStats.withVideoFields(3, false, false, false,
                List.of("com.android.manufacturer", "creationdate", "author"));

        assertTrue(s.hadDevice);
        assertTrue(s.hadDate);
        assertTrue(s.hadName);
    }

    @Test
    public void video_whatTheRetrieverSaw_countsToo() {
        CleanStats s = CleanStats.withVideoFields(2, false, true, true, Collections.emptyList());

        assertTrue(s.hadDate);
        assertTrue(s.hadName);
        assertFalse(s.hadDevice);
    }

    @Test
    public void video_locationAndCommentBoxes_areNoneOfTheThree() {
        CleanStats s = CleanStats.withVideoFields(3, true, false, false,
                List.of("location", "comment", "loci"));

        assertFalse(s.hadDate);
        assertFalse(s.hadDevice);
        assertFalse(s.hadName);
    }

    @Test
    public void theOlderConstructor_carriesNoDetails() {
        CleanStats s = new CleanStats(5, true);

        assertTrue(s.hadLocation);
        assertFalse(s.hadDate);
        assertFalse(s.hadDevice);
        assertFalse(s.hadName);
    }
}
