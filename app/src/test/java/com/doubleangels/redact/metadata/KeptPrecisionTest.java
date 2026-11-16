package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class KeptPrecisionTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveLocation(app, true);
        AppPreferences.setPreserveDateTaken(app, true);
    }

    @After
    public void tearDown() {
        AppPreferences.resetAll(app);
    }

    @Test
    public void round_keepsATenthOfADegree_onBothSidesOfZero() {
        assertEquals(37.8, KeptPrecision.round(37.7749), 1e-12);
        assertEquals(-122.4, KeptPrecision.round(-122.4194), 1e-12);
        assertEquals(0.0, KeptPrecision.round(0.04), 1e-12);
        assertEquals(-0.1, KeptPrecision.round(-0.05), 1e-12);
        assertNull(KeptPrecision.round((double[]) null));
        assertArrayEquals(new double[] {52.4, 4.9}, KeptPrecision.round(new double[] {52.37, 4.89}), 1e-12);
        double[] single = {1.0};
        assertArrayEquals(single, KeptPrecision.round(single), 0);
    }

    @Test
    public void onGrid_acceptsTenthsOnly() {
        assertTrue(KeptPrecision.onGrid(37.8));
        assertTrue(KeptPrecision.onGrid(-122.4));
        assertTrue(KeptPrecision.onGrid(0));
        assertFalse(KeptPrecision.onGrid(37.7749));
        assertFalse(KeptPrecision.onGrid(37.85));
    }

    @Test
    public void dms_readsExifRationals_andWritesTheGrid() {
        assertEquals(37.7749, KeptPrecision.parseDms("37/1,46/1,2964/100"), 1e-4);
        assertNull(KeptPrecision.parseDms(null));
        assertNull(KeptPrecision.parseDms("37/1,46/1"));
        assertNull(KeptPrecision.parseDms("37/1,46,1/1"));
        assertNull(KeptPrecision.parseDms("37/0,46/1,0/1"));
        assertNull(KeptPrecision.parseDms("a/1,46/1,0/1"));
        assertNull(KeptPrecision.parseDms("-1/1,0/1,0/1"));
        assertEquals("37/1,48/1,0/1", KeptPrecision.toDms(37.7749));
        assertEquals("122/1,24/1,0/1", KeptPrecision.toDms(-122.4194));
        assertEquals(37.8, KeptPrecision.parseDms(KeptPrecision.toDms(37.7749)), 1e-9);
    }

    @Test
    public void dateOnly_keepsTheDayAtMidnight() {
        assertEquals("2026:01:17 00:00:00", KeptPrecision.dateOnly("2026:01:17 09:30:12"));
        assertNull(KeptPrecision.dateOnly("yesterday"));
        assertNull(KeptPrecision.dateOnly((String) null));
        assertTrue(KeptPrecision.isDateOnly("2026:01:17 00:00:00"));
        assertFalse(KeptPrecision.isDateOnly("2026:01:17 09:30:12"));
        assertFalse(KeptPrecision.isDateOnly(null));
    }

    @Test
    public void dateOnly_movesMovieTimesToMidnightInTheZone() {
        ZoneId utc = ZoneId.of("UTC");
        long base = 2_082_844_800L; // 1970-01-01 in seconds since 1904
        long morning = base + 86_400L * 3 + 9 * 3600 + 30 * 60;
        long[] times = KeptPrecision.dateOnly(new long[] {morning, 0}, utc);
        assertEquals(base + 86_400L * 3, times[0]);
        assertEquals("an unset time stays unset", 0, times[1]);
        assertNull(KeptPrecision.dateOnly((long[]) null, utc));
        // 23:30 in New York is already the next day in UTC; the day is New York's.
        ZoneId newYork = ZoneId.of("America/New_York");
        long lateEvening = base + 86_400L * 3 + 4 * 3600 + 30 * 60; // 04:30 UTC = 23:30 the day before in NY
        long[] local = KeptPrecision.dateOnly(new long[] {lateEvening}, newYork);
        assertEquals(base + 86_400L * 2 + 5 * 3600, local[0]); // midnight NY = 05:00 UTC
    }

    @Test
    public void apply_roundsTheKeptLocationAndCutsTheDate() {
        AppPreferences.setApproximateLocation(app, true);
        AppPreferences.setDateOnly(app, true);
        Map<String, String> kept = new HashMap<>();
        kept.put(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,2964/100");
        kept.put(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        kept.put(ExifInterface.TAG_GPS_LONGITUDE, "not a coordinate");
        kept.put(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
        kept.put(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:17 09:30:12");
        kept.put(ExifInterface.TAG_DATETIME, "garbled");

        KeptPrecision.apply(app, kept, false);

        assertEquals("37/1,48/1,0/1", kept.get(ExifInterface.TAG_GPS_LATITUDE));
        assertEquals("N", kept.get(ExifInterface.TAG_GPS_LATITUDE_REF));
        assertFalse("an unreadable coordinate is not kept", kept.containsKey(ExifInterface.TAG_GPS_LONGITUDE));
        assertFalse(kept.containsKey(ExifInterface.TAG_GPS_LONGITUDE_REF));
        assertEquals("2026:01:17 00:00:00", kept.get(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertFalse(kept.containsKey(ExifInterface.TAG_DATETIME));
    }

    @Test
    public void apply_leavesExactValues_whenTheSettingsAskForThem_andSharesAlone() {
        AppPreferences.setApproximateLocation(app, false);
        AppPreferences.setDateOnly(app, false);
        Map<String, String> kept = new HashMap<>();
        kept.put(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,2964/100");
        kept.put(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:17 09:30:12");
        KeptPrecision.apply(app, kept, false);
        assertEquals("37/1,46/1,2964/100", kept.get(ExifInterface.TAG_GPS_LATITUDE));
        assertEquals("2026:01:17 09:30:12", kept.get(ExifInterface.TAG_DATETIME_ORIGINAL));

        AppPreferences.setApproximateLocation(app, true);
        KeptPrecision.apply(app, kept, true);
        assertEquals("a share keeps neither, so nothing to round", "37/1,46/1,2964/100",
                kept.get(ExifInterface.TAG_GPS_LATITUDE));
    }

    @Test
    public void tagsToPreserve_dropAltitudeTimeAndZone_withTheCoarserSettings() {
        AppPreferences.setApproximateLocation(app, true);
        AppPreferences.setDateOnly(app, true);
        List<String> coarse = MetadataStripper.tagsToPreserve(app, false);
        assertTrue(coarse.contains(ExifInterface.TAG_GPS_LATITUDE));
        assertFalse(coarse.contains(ExifInterface.TAG_GPS_ALTITUDE));
        assertFalse(coarse.contains(ExifInterface.TAG_GPS_TIMESTAMP));
        assertFalse(coarse.contains(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        assertTrue(coarse.contains(ExifInterface.TAG_DATETIME_ORIGINAL));

        AppPreferences.setApproximateLocation(app, false);
        List<String> exactLocationDateOnly = MetadataStripper.tagsToPreserve(app, false);
        assertTrue(exactLocationDateOnly.contains(ExifInterface.TAG_GPS_ALTITUDE));
        assertFalse("the GPS clock gives the time of day", exactLocationDateOnly.contains(ExifInterface.TAG_GPS_TIMESTAMP));

        AppPreferences.setDateOnly(app, false);
        List<String> exact = MetadataStripper.tagsToPreserve(app, false);
        assertTrue(exact.contains(ExifInterface.TAG_GPS_TIMESTAMP));
        assertTrue(exact.contains(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
    }

    private static File jpegWith(String latitude, String dateTimeOriginal) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.GRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        File file = File.createTempFile("kept_precision_", ".jpg");
        file.deleteOnExit();
        Files.write(file.toPath(), out.toByteArray());
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        if (latitude != null) {
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, latitude);
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        }
        if (dateTimeOriginal != null) {
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTimeOriginal);
        }
        exif.saveAttributes();
        return file;
    }

    private boolean verify(File file) throws Exception {
        Method verify = CleanVerifier.class.getDeclaredMethod("verifyMetadataRemoval", File.class, boolean.class);
        verify.setAccessible(true);
        return (boolean) verify.invoke(new MetadataStripper(app).verifier, file, false);
    }

    @Test
    public void matchesSettings_refusesAnExactLocationOrATime_whenLessIsKept() throws Exception {
        AppPreferences.setApproximateLocation(app, true);
        AppPreferences.setDateOnly(app, true);
        ExifInterface exact = new ExifInterface(jpegWith("37/1,46/1,2964/100", null).getAbsolutePath());
        assertFalse(KeptPrecision.matchesSettings(app, exact, false));
        assertTrue("a share has its own check", KeptPrecision.matchesSettings(app, exact, true));
        ExifInterface rounded = new ExifInterface(jpegWith("37/1,48/1,0/1", "2026:01:17 00:00:00").getAbsolutePath());
        assertTrue(KeptPrecision.matchesSettings(app, rounded, false));
        ExifInterface timed = new ExifInterface(jpegWith(null, "2026:01:17 09:30:12").getAbsolutePath());
        assertFalse(KeptPrecision.matchesSettings(app, timed, false));
        AppPreferences.setStrictClean(app, true);
        assertTrue(KeptPrecision.matchesSettings(app, exact, false));
    }

    @Test
    public void theCheckAfterAClean_failsClosedOnAnUnroundedLocation() throws Exception {
        AppPreferences.setApproximateLocation(app, true);
        assertFalse(verify(jpegWith("37/1,46/1,2964/100", null)));
        assertTrue(verify(jpegWith("37/1,48/1,0/1", null)));
        AppPreferences.setApproximateLocation(app, false);
        assertTrue("exact is what was asked for", verify(jpegWith("37/1,46/1,2964/100", null)));
    }

    @Test
    public void videoLocation_isCheckedAgainstTheGridOnlyWhenApproximate() {
        AppPreferences.setApproximateLocation(app, true);
        assertTrue(KeptPrecision.videoLocationMatchesSettings(app, "+52.4000+004.9000/"));
        assertFalse(KeptPrecision.videoLocationMatchesSettings(app, "+52.3700+004.8900/"));
        assertFalse(KeptPrecision.videoLocationMatchesSettings(app, "somewhere"));
        assertTrue(KeptPrecision.videoLocationMatchesSettings(app, null));
        AppPreferences.setApproximateLocation(app, false);
        assertTrue(KeptPrecision.videoLocationMatchesSettings(app, "+52.3700+004.8900/"));
    }
}
