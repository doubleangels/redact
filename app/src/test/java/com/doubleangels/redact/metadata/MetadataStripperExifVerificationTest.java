package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Regression test for a real bug: verifyMetadataRemoval was widened (commit 3a9376a) to sweep
 * every EXIF TAG_* constant via reflection, mirroring removeAllExifMetadata's own removal sweep.
 * That broke Clean entirely, because androidx.exifinterface.media.ExifInterface cannot actually
 * clear TAG_IMAGE_WIDTH, TAG_IMAGE_LENGTH, TAG_LIGHT_SOURCE, or TAG_ORIENTATION to null --
 * getAttribute() keeps returning "0" for them no matter what removeAllExifMetadata does -- so
 * verification failed on every single image (Orientation only surfaces under strict-clean mode,
 * which is the app's *default*, since that's the only mode where Orientation isn't already in
 * the preserved-tags allowlist).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MetadataStripperExifVerificationTest {

    @Test
    public void verificationPasses_defaultStrictClean_gallerySave() throws Exception {
        assertVerificationPasses(/* strictClean= */ true, /* forSharing= */ false);
    }

    @Test
    public void verificationPasses_nonStrictClean_gallerySave() throws Exception {
        assertVerificationPasses(/* strictClean= */ false, /* forSharing= */ false);
    }

    @Test
    public void verificationPasses_defaultStrictClean_sharing() throws Exception {
        assertVerificationPasses(/* strictClean= */ true, /* forSharing= */ true);
    }

    private void assertVerificationPasses(boolean strictClean, boolean forSharing) throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AppPreferences.setStrictClean(app, strictClean);

        File jpeg = File.createTempFile("exif_verify_", ".jpg");
        jpeg.deleteOnExit();

        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        try (FileOutputStream fos = new FileOutputStream(jpeg)) {
            assertTrue("Bitmap.compress should produce a real JPEG",
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos));
        }

        // Populate every tag the library will let us set, so this exercises the full
        // reflection-based removal/verification sweep exactly as it runs in production
        // against a photo carrying a broad spread of camera/EXIF metadata.
        ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
        for (Field field : ExifInterface.class.getDeclaredFields()) {
            if (field.getType() != String.class || !field.getName().startsWith("TAG_")) {
                continue;
            }
            String tag;
            try {
                tag = (String) field.get(null);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                continue;
            }
            if (tag == null) continue;
            try {
                exif.setAttribute(tag, plausibleValueFor(tag));
            } catch (Exception ignored) {
                // Some tags reject arbitrary values; irrelevant to this test.
            }
        }
        exif.saveAttributes();

        MetadataStripper stripper = new MetadataStripper(app);

        Method removeAll = MetadataStripper.class.getDeclaredMethod(
                "removeAllExifMetadata", ExifInterface.class, boolean.class);
        removeAll.setAccessible(true);
        removeAll.invoke(stripper, exif, forSharing);
        exif.saveAttributes();

        Method verify = MetadataStripper.class.getDeclaredMethod(
                "verifyMetadataRemoval", File.class, boolean.class);
        verify.setAccessible(true);
        boolean passed = (boolean) verify.invoke(stripper, jpeg, forSharing);

        if (!passed) {
            ExifInterface reopened = new ExifInterface(jpeg.getAbsolutePath());
            StringBuilder survivors = new StringBuilder();
            for (Field field : ExifInterface.class.getDeclaredFields()) {
                if (field.getType() != String.class || !field.getName().startsWith("TAG_")) {
                    continue;
                }
                String tag;
                try {
                    tag = (String) field.get(null);
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    continue;
                }
                if (tag == null) continue;
                String value = reopened.getAttribute(tag);
                if (value != null && !value.isEmpty()) {
                    survivors.append(field.getName()).append('=').append(value).append("; ");
                }
            }
            System.out.println("Tags still present after strip (strictClean=" + strictClean
                    + ", forSharing=" + forSharing + "): " + survivors);
        }

        assertTrue(
                "verifyMetadataRemoval must pass after removeAllExifMetadata strips a photo "
                        + "(strictClean=" + strictClean + ", forSharing=" + forSharing + ") -- "
                        + "if this fails, Clean will fail for every image",
                passed);
    }

    private static String plausibleValueFor(String tag) {
        String t = tag.toUpperCase();
        if (t.contains("REF")) {
            if (t.contains("LATITUDE")) return "N";
            if (t.contains("LONGITUDE")) return "E";
            return "N";
        }
        if (t.contains("DATE") || t.contains("TIME")) {
            if (t.contains("OFFSET")) return "+00:00";
            if (t.contains("SUBSEC")) return "00";
            if (t.contains("TIMESTAMP")) return "12:00:00";
            return "2024:01:01 12:00:00";
        }
        if (t.contains("GPS_VERSION_ID")) return "2200";
        if (t.contains("LATITUDE") || t.contains("LONGITUDE") || t.contains("ALTITUDE")
                || t.contains("SPEED") || t.contains("DISTANCE") || t.contains("DOP")
                || t.contains("TRACK") || t.contains("IMG_DIRECTION")) {
            return "10/1";
        }
        if (t.contains("WIDTH") || t.contains("LENGTH") || t.contains("DIMENSION")
                || t.contains("X_RESOLUTION") || t.contains("Y_RESOLUTION")
                || t.contains("FOCAL") || t.contains("APERTURE") || t.contains("F_NUMBER")
                || t.contains("EXPOSURE") || t.contains("ISO") || t.contains("SHUTTER")
                || t.contains("BITS_PER_SAMPLE") || t.contains("COMPRESSION")
                || t.contains("SAMPLES_PER_PIXEL") || t.contains("STRIP_")
                || t.contains("ROWS_PER_STRIP") || t.contains("JPEG_INTERCHANGE")
                || t.contains("PLANAR") || t.contains("PHOTOMETRIC")
                || t.contains("COLOR_SPACE") || t.contains("WHITE_BALANCE")
                || t.contains("FLASH") || t.contains("METERING") || t.contains("PROGRAM")
                || t.contains("ZOOM") || t.contains("SCENE") || t.contains("CONTRAST")
                || t.contains("SATURATION") || t.contains("SHARPNESS")
                || t.contains("SUBJECT_DISTANCE_RANGE") || t.contains("EXIF_VERSION")
                || t.contains("FLASHPIX") || t.contains("COMPRESSED_BITS")
                || t.contains("PIXEL_") || t.contains("LIGHT_SOURCE")
                || t.contains("ORIENTATION")) {
            return "1";
        }
        return "test-value";
    }
}
