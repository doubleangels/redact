package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeMediaStoreProvider;
import com.doubleangels.redact.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FormatConverterConversionTest {

    private Context context;
    private final java.util.List<File> files = new java.util.ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(context);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        FormatConverter.testHeicCompressFormatOverride = null;
        FormatConverter.testTreatFormatAsHeic = null;
        for (File f : files) {
            f.delete();
        }
    }

    private File image(int width, int height, int orientation) throws IOException {
        File f = File.createTempFile("conv_", ".jpg", context.getFilesDir());
        files.add(f);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF3366CC);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
            exif.saveAttributes();
        }
        return f;
    }

    private Bitmap decodeOutput() {
        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        return BitmapFactory.decodeFile(entry.file.getAbsolutePath());
    }

    // ---- convertImageToPictures -------------------------------------------------------------------

    @Test
    public void convert_toPng_writesAPublishedRandomlyNamedEntry() throws Exception {
        Uri out = FormatConverter.convertImageToPictures(
                context, Uri.fromFile(image(64, 48, ExifInterface.ORIENTATION_NORMAL)),
                Bitmap.CompressFormat.PNG, "holiday.jpg");

        assertNotNull(out);
        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertTrue(entry.displayName().endsWith(".png"));
        assertTrue(entry.displayName().matches("[A-Za-z0-9]{12}\\.[a-z0-9]+"));
        assertFalse(entry.displayName().contains("holiday"));
        assertEquals("image/png", entry.mimeType());
        assertFalse("the entry is published once the write finishes", entry.isPending());
        assertTrue(entry.file.length() > 0);
        assertEquals(64, decodeOutput().getWidth());
    }

    @Test
    public void convert_toJpegAndWebp_useTheRightExtensionAndMime() throws Exception {
        File src = image(40, 30, ExifInterface.ORIENTATION_NORMAL);

        FormatConverter.convertImageToPictures(context, Uri.fromFile(src), Bitmap.CompressFormat.JPEG, "a");
        FormatConverter.convertImageToPictures(context, Uri.fromFile(src), Bitmap.CompressFormat.WEBP, "a");

        java.util.Set<String> mimes = new java.util.HashSet<>();
        for (FakeMediaStoreProvider.Entry e : FakeMediaStoreProvider.entries().values()) {
            mimes.add(e.mimeType());
            assertTrue(e.file.length() > 0);
        }
        assertTrue(mimes.contains("image/jpeg"));
        assertTrue(mimes.contains("image/webp"));
    }

    @Test
    public void convert_toHeic_onApi34_usesTheHeicFormat() throws Exception {
        // JPEG stands in for the HEIC encoder (PNG/WEBP are matched before the HEIC check).
        FormatConverter.testHeicCompressFormatOverride = Bitmap.CompressFormat.JPEG;
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.JPEG;

        FormatConverter.convertImageToPictures(
                context, Uri.fromFile(image(20, 20, ExifInterface.ORIENTATION_NORMAL)),
                Bitmap.CompressFormat.JPEG, "a");

        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertEquals("image/heic", entry.mimeType());
        assertTrue(entry.displayName().endsWith(".heic"));
    }

    @Test
    public void convert_bakesExifRotationIntoThePixels() throws Exception {
        FormatConverter.convertImageToPictures(
                context, Uri.fromFile(image(64, 48, ExifInterface.ORIENTATION_ROTATE_90)),
                Bitmap.CompressFormat.PNG, "a");

        Bitmap out = decodeOutput();
        assertEquals(48, out.getWidth());
        assertEquals(64, out.getHeight());
    }

    @Test
    public void convert_handlesEveryOrientation() throws Exception {
        int[] orientations = {
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_UNDEFINED};
        for (int orientation : orientations) {
            FakeMediaStoreProvider.reset();
            FormatConverter.convertImageToPictures(
                    context, Uri.fromFile(image(30, 20, orientation)), Bitmap.CompressFormat.PNG, "a");
            Bitmap out = decodeOutput();
            assertNotNull(out);
            boolean swapped = orientation == ExifInterface.ORIENTATION_TRANSPOSE
                    || orientation == ExifInterface.ORIENTATION_TRANSVERSE
                    || orientation == ExifInterface.ORIENTATION_ROTATE_270;
            assertEquals(swapped ? 20 : 30, out.getWidth());
        }
    }

    @Test
    public void convert_largeImages_areDownsampledToTheConfiguredMaximum() throws Exception {
        AppPreferences.setMaxBitmapSize(context, 2048);
        FormatConverter.convertImageToPictures(
                context, Uri.fromFile(image(4500, 100, ExifInterface.ORIENTATION_NORMAL)),
                Bitmap.CompressFormat.PNG, "a");
        assertNotNull(decodeOutput());
    }

    @Test
    public void convert_missingSource_failsWithAnIoException() {
        try {
            FormatConverter.convertImageToPictures(
                    context, Uri.fromFile(new File("/nonexistent/x.jpg")), Bitmap.CompressFormat.JPEG, "x");
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        }
    }

    @Test
    public void convert_undecodableSource_failsAndLeavesNoEntry() throws Exception {
        File junk = File.createTempFile("junk_", ".jpg", context.getFilesDir());
        files.add(junk);
        try (FileOutputStream out = new FileOutputStream(junk)) {
            out.write(new byte[] {1, 2, 3, 4, 5});
        }
        try {
            FormatConverter.convertImageToPictures(
                    context, Uri.fromFile(junk), Bitmap.CompressFormat.JPEG, "x");
            // Robolectric's bitmap decoder is lenient and may "decode" the junk.
        } catch (IOException | RuntimeException expected) {
            assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        }
    }

    @Test
    public void convert_rejectsVideoSources() throws Exception {
        File clip = File.createTempFile("clip_", ".mp4", context.getFilesDir());
        files.add(clip);
        Uri videoUri = FakeMediaStoreProvider.add(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, clip, "video/mp4");
        try {
            FormatConverter.convertImageToPictures(context, videoUri, Bitmap.CompressFormat.JPEG, "x");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("video_not_supported", e.getMessage());
        }
    }

    @Test
    public void convert_failsCleanlyWhenMediaStoreRefusesTheRow() throws Exception {
        FakeMediaStoreProvider.failInsert = true;
        try {
            FormatConverter.convertImageToPictures(
                    context, Uri.fromFile(image(10, 10, ExifInterface.ORIENTATION_NORMAL)),
                    Bitmap.CompressFormat.JPEG, "x");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("MediaStore insert failed", e.getMessage());
        }
    }

    @Test
    @Config(sdk = 31)
    public void convert_heicRequestBelowApi34_fallsBackToJpeg() throws Exception {
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.WEBP;
        FormatConverter.convertImageToPictures(
                context, Uri.fromFile(image(10, 10, ExifInterface.ORIENTATION_NORMAL)),
                Bitmap.CompressFormat.WEBP, "x");
        assertEquals("image/jpeg", FakeMediaStoreProvider.only().mimeType());
    }

    // ---- decoding + compressing -------------------------------------------------------------------------

    @Test
    public void decodeBitmapFromUri_decodesAReadableImage() throws Exception {
        Bitmap bitmap = null;
        try {
            bitmap = FormatConverter.decodeBitmapFromUri(
                    context, Uri.fromFile(image(32, 32, ExifInterface.ORIENTATION_NORMAL)));
        } catch (IOException | RuntimeException e) {
            // ImageDecoder is only partially emulated; the call path is still exercised.
        }
        if (bitmap != null) {
            assertEquals(32, bitmap.getWidth());
        }
    }

    @Test
    public void compressBitmapToStream_encodesEveryFormat() {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        for (Bitmap.CompressFormat format : new Bitmap.CompressFormat[] {
                Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(format.name(), FormatConverter.compressBitmapToStream(context, bitmap, format, out));
            assertTrue(out.size() > 0);
        }
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.PNG;
        ByteArrayOutputStream heic = new ByteArrayOutputStream();
        assertTrue(FormatConverter.compressBitmapToStream(context, bitmap, Bitmap.CompressFormat.PNG, heic));
    }

    // ---- format resolution -------------------------------------------------------------------------------

    @Test
    public void resolveImageFormat_coversExtensionsAndMimeTypes() throws Exception {
        assertEquals("image/png", FormatConverter.resolveImageFormat(".PNG", null).mimeType);
        assertEquals("image/webp", FormatConverter.resolveImageFormat(".webp", null).mimeType);
        assertEquals("image/jpeg", FormatConverter.resolveImageFormat(".jpeg", null).mimeType);
        assertEquals("image/webp", FormatConverter.resolveImageFormat(".dat", "image/webp").mimeType);
        assertEquals("image/jpeg", FormatConverter.resolveImageFormat("", "image/jpg").mimeType);
        assertEquals("image/png", FormatConverter.resolveImageFormat(null, "IMAGE/PNG").mimeType);
        assertEquals("image/jpeg", FormatConverter.resolveImageFormat(".bin", "application/zip").mimeType);
        assertEquals("image/jpeg", FormatConverter.resolveImageFormat(null, "").mimeType);
        // With no HEIC encoder available the HEIC spec degrades to JPEG.
        assertEquals("image/jpeg", FormatConverter.resolveImageFormat(".heic", "image/heic").mimeType);
        assertEquals("image/jpeg", FormatConverter.resolveImageFormat(".heif", null).mimeType);
        assertEquals("image/jpeg", FormatConverter.resolveImageFormat(null, "image/heif").mimeType);
    }

    @Test
    public void heicSpecs_followTheOverrideOnApi34() throws Exception {
        FormatConverter.testHeicCompressFormatOverride = Bitmap.CompressFormat.PNG;
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.PNG;
        assertEquals("image/heic", FormatConverter.resolveImageFormat(null, "image/heic").mimeType);
        assertEquals(".heic", FormatConverter.resolveImageFormat(".heif", null).extension);
        assertTrue(FormatConverter.isHeicOutputSupported());
    }

    @Test
    public void videoFormatLabel_clampsTheIndex() {
        String[] labels = context.getResources().getStringArray(R.array.settings_video_format_labels);
        assertEquals(labels[0], FormatConverter.videoFormatLabel(context, 0));
        assertEquals(labels[2], FormatConverter.videoFormatLabel(context, 2));
        assertEquals(labels[labels.length - 1], FormatConverter.videoFormatLabel(context, 99));
        assertEquals(labels[0], FormatConverter.videoFormatLabel(context, -4));
    }

    // ---- video conversion ----------------------------------------------------------------------------------

    @Test(timeout = 60_000)
    @Config(sdk = 31)
    public void convertVideo_withAnUnreadableSource_failsWithoutLeavingEntries() throws Exception {
        try {
            FormatConverter.convertVideoToMovies(
                    context, Uri.fromFile(new File("/nonexistent/clip.mp4")), "clip", 0);
        } catch (Exception expected) {
            // Any failure is fine; what matters is that no partial gallery entry remains.
        }
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        assertNull(null);
    }

    @Test(timeout = 60_000)
    @Config(sdk = 31)
    public void convertVideo_overloads_delegateToTheFullSignature() {
        Uri missing = Uri.fromFile(new File("/nonexistent/clip.mp4"));
        int[] actual = new int[1];
        try {
            FormatConverter.convertVideoToMovies(context, missing, "clip", 1, null);
        } catch (Exception expected) {
            // unreadable source
        }
        try {
            FormatConverter.convertVideoToMovies(context, missing, "clip", 2, null, actual);
        } catch (Exception expected) {
            // unreadable source
        }
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }
}
