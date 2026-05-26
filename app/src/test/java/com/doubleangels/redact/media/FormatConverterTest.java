package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.doubleangels.redact.AppPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import org.mockito.Mockito;

@RunWith(RobolectricTestRunner.class)
public class FormatConverterTest {

    @Test
    public void formatRouting_andDefaultFallbacks_areStable() {
        assertEquals(4, FormatConverter.FORMAT_OPTION_COUNT);
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(0));
        assertEquals(Bitmap.CompressFormat.PNG, FormatConverter.formatAtIndex(1));
        assertEquals(Bitmap.CompressFormat.WEBP, FormatConverter.formatAtIndex(2));
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(99));
        assertNotNull(FormatConverter.formatAtIndex(3));
        assertEquals(2, FormatConverter.effectiveImageFormatIndex(2));
    }

    @Test
    @Config(sdk = 33)
    public void heicRouting_belowApi34_fallsBackToJpeg() {
        assertFalse(FormatConverter.isHeicProcessingSupported());
        assertFalse(FormatConverter.isHeicOutputSupported());
        assertEquals(0, FormatConverter.effectiveImageFormatIndex(3));
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(3));
    }

    @Test
    @Config(sdk = 34)
    public void resolveImageFormat_heifMimeHitsHeicSpecBranch() throws Exception {
        FormatConverter.ImageFormatSpec byHeif =
                FormatConverter.resolveImageFormat(null, "image/heif");
        assertEquals(".heic", byHeif.extension);
        FormatConverter.ImageFormatSpec byHeic =
                FormatConverter.resolveImageFormat(null, "image/heic");
        assertEquals("image/heic", byHeic.mimeType);
    }

    @Test
    @Config(sdk = 34)
    public void heicValueOfPaths_executeWhenRuntimeSupportsHeic() {
        try {
            Bitmap.CompressFormat.valueOf("HEIC");
            assertTrue(FormatConverter.isHeicOutputSupported());
            assertEquals("HEIC", FormatConverter.formatAtIndex(3).name());
        } catch (IllegalArgumentException ignored) {
            // Runtime lacks HEIC; hook tests cover HEIC branches instead.
        }
    }

    @Test
    @Config(sdk = 34)
    public void heicRouting_onApi34_keepsRequestedIndex() {
        assertTrue(FormatConverter.isHeicProcessingSupported());
        assertEquals(3, FormatConverter.effectiveImageFormatIndex(3));
        assertNotNull(FormatConverter.formatAtIndex(3));
        FormatConverter.isHeicOutputSupported();
    }

    @Test
    @Config(sdk = 33)
    public void heicCompressFormatOrJpeg_returnsJpegBelowApi34() throws Exception {
        Method method = FormatConverter.class.getDeclaredMethod("heicCompressFormatOrJpeg");
        method.setAccessible(true);
        assertEquals(Bitmap.CompressFormat.JPEG, method.invoke(null));
    }

    @Test
    @Config(sdk = 34)
    public void heicCompressFormatOrJpeg_usesHeicWhenSupportedOnApi34() throws Exception {
        Method method = FormatConverter.class.getDeclaredMethod("heicCompressFormatOrJpeg");
        method.setAccessible(true);
        Bitmap.CompressFormat format = (Bitmap.CompressFormat) method.invoke(null);
        if (FormatConverter.isHeicOutputSupported()) {
            assertEquals("HEIC", format.name());
        } else {
            assertEquals(Bitmap.CompressFormat.JPEG, format);
        }
    }

    @Test
    @Config(sdk = 34)
    public void heicCompressFormatOrJpeg_scansCompressFormats() throws Exception {
        FormatConverter.testHeicCompressFormatOverride = null;
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.JPEG;
        try {
            Method method = FormatConverter.class.getDeclaredMethod("heicCompressFormatOrJpeg");
            method.setAccessible(true);
            assertEquals(Bitmap.CompressFormat.JPEG, method.invoke(null));
        } finally {
            FormatConverter.testTreatFormatAsHeic = null;
        }
    }

    @Test
    @Config(sdk = 34)
    public void heicBranches_exercisedWithTestHookWhenEnumMissing() throws Exception {
        Bitmap.CompressFormat standIn = Bitmap.CompressFormat.JPEG;
        FormatConverter.testHeicCompressFormatOverride = standIn;
        FormatConverter.testTreatFormatAsHeic = standIn;
        try {
            Context context = ApplicationProvider.getApplicationContext();
            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                assertTrue(FormatConverter.compressBitmapToStream(context, bitmap, standIn, out));
            } finally {
                bitmap.recycle();
            }
            assertEquals(".heic", invokeFormatStringHelper("extensionForFormat", standIn));
            assertEquals("image/heic", invokeFormatStringHelper("mimeForFormat", standIn));
            assertEquals(standIn, FormatConverter.formatAtIndex(3));
            FormatConverter.ImageFormatSpec spec =
                    FormatConverter.resolveImageFormat(".heif", "image/heif");
            assertEquals(".heic", spec.extension);
        } finally {
            FormatConverter.testHeicCompressFormatOverride = null;
            FormatConverter.testTreatFormatAsHeic = null;
        }
    }

    @Test
    @Config(sdk = 34)
    public void heicHelpers_exerciseOutputSupportAndMimeBranches() throws Exception {
        assertTrue(FormatConverter.isHeicProcessingSupported());
        FormatConverter.isHeicOutputSupported();
        if (!FormatConverter.isHeicOutputSupported()) {
            return;
        }
        Bitmap.CompressFormat heic = Bitmap.CompressFormat.valueOf("HEIC");
        assertEquals(".heic", invokeFormatStringHelper("extensionForFormat", heic));
        assertEquals("image/heic", invokeFormatStringHelper("mimeForFormat", heic));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try {
            Context context = ApplicationProvider.getApplicationContext();
            assertTrue(FormatConverter.compressBitmapToStream(context, bitmap, heic, out));
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void qualityForFormat_usesPresetAndContextPreferences() {
        Context context = ApplicationProvider.getApplicationContext();

        assertEquals(100, FormatConverter.qualityForFormat(Bitmap.CompressFormat.PNG));
        assertEquals(90, FormatConverter.qualityForFormat(Bitmap.CompressFormat.WEBP));
        assertEquals(92, FormatConverter.qualityForFormat(Bitmap.CompressFormat.JPEG));
        assertEquals(
                85,
                FormatConverter.qualityForFormat(
                        Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_BALANCED));
        assertEquals(
                80,
                FormatConverter.qualityForFormat(
                        Bitmap.CompressFormat.WEBP, AppPreferences.QUALITY_PRESET_BALANCED));
        assertEquals(
                75,
                FormatConverter.qualityForFormat(
                        Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_SMALLER));

        AppPreferences.setImageQualityPreset(context, AppPreferences.QUALITY_PRESET_SMALLER);
        assertEquals(70, FormatConverter.qualityForFormat(Bitmap.CompressFormat.WEBP, context));
        assertEquals(75, FormatConverter.qualityForFormat(Bitmap.CompressFormat.JPEG, context));
    }

    @Test
    public void compressBitmapToStream_writesBytesForCommonFormats() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF224466);
        try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ByteArrayOutputStream webp = new ByteArrayOutputStream();

            assertTrue(
                    FormatConverter.compressBitmapToStream(
                            context, bitmap, Bitmap.CompressFormat.JPEG, jpeg));
            assertTrue(
                    FormatConverter.compressBitmapToStream(
                            context, bitmap, Bitmap.CompressFormat.PNG, png));
            assertTrue(
                    FormatConverter.compressBitmapToStream(
                            context, bitmap, Bitmap.CompressFormat.WEBP, webp));

            assertTrue(jpeg.size() > 0);
            assertTrue(png.size() > 0);
            assertTrue(webp.size() > 0);
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    @Config(sdk = 34)
    public void compressBitmapToStream_supportsHeicWhenAvailable() {
        if (!FormatConverter.isHeicOutputSupported()) {
            return;
        }

        Context context = ApplicationProvider.getApplicationContext();
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF112233);
        try {
            ByteArrayOutputStream heic = new ByteArrayOutputStream();
            Bitmap.CompressFormat format = Bitmap.CompressFormat.valueOf("HEIC");

            assertTrue(FormatConverter.compressBitmapToStream(context, bitmap, format, heic));
            assertTrue(heic.size() > 0);
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void privateHelpers_coverFilenameAndSamplingBranches() throws Exception {
        assertEquals("converted", invokeStringHelper("stripExtension", null));
        assertEquals("converted", invokeStringHelper("stripExtension", ""));
        assertEquals("holiday", invokeStringHelper("stripExtension", "holiday.jpg"));
        assertEquals("holiday", invokeStringHelper("stripExtension", "holiday"));

        assertEquals("bad_name_", invokeStringHelper("sanitizeFileName", "bad name!"));
        assertEquals("converted", invokeStringHelper("sanitizeFileName", ""));
        assertEquals(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                invokeStringHelper("sanitizeFileName", repeat('a', 81)));

        assertEquals(".png", invokeFormatStringHelper("extensionForFormat", Bitmap.CompressFormat.PNG));
        assertEquals(".webp", invokeFormatStringHelper("extensionForFormat", Bitmap.CompressFormat.WEBP));
        assertEquals(".jpg", invokeFormatStringHelper("extensionForFormat", Bitmap.CompressFormat.JPEG));
        assertEquals("image/png", invokeFormatStringHelper("mimeForFormat", Bitmap.CompressFormat.PNG));
        assertEquals("image/webp", invokeFormatStringHelper("mimeForFormat", Bitmap.CompressFormat.WEBP));
        assertEquals("image/jpeg", invokeFormatStringHelper("mimeForFormat", Bitmap.CompressFormat.JPEG));

        try {
            Bitmap.CompressFormat heic = Bitmap.CompressFormat.valueOf("HEIC");
            assertEquals(".heic", invokeFormatStringHelper("extensionForFormat", heic));
            assertEquals("image/heic", invokeFormatStringHelper("mimeForFormat", heic));
        } catch (IllegalArgumentException ignored) {
            // HEIC enum is not present on all test runtimes.
        }

        BitmapFactory.Options small = new BitmapFactory.Options();
        small.outWidth = 1024;
        small.outHeight = 768;
        assertEquals(1, invokeSampleSizeHelper(small));

        BitmapFactory.Options large = new BitmapFactory.Options();
        large.outWidth = 10000;
        large.outHeight = 9000;
        assertEquals(2, invokeSampleSizeHelper(large));
    }

    @Test
    @Config(sdk = 33)
    public void convertImageToPictures_heicBelowApi34_fallsBackToJpeg() throws Exception {
        Context base = ApplicationProvider.getApplicationContext();
        try {
            Bitmap.CompressFormat.valueOf("HEIC");
        } catch (IllegalArgumentException ignored) {
            // Some JVM test runtimes do not include the HEIC enum constant.
            return;
        }

        byte[] jpegBytes;
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos));
            jpegBytes = bos.toByteArray();
        } finally {
            bitmap.recycle();
        }

        ContentResolver resolver = Mockito.mock(ContentResolver.class);
        Uri src = Uri.parse("content://test/src");
        Uri out = Uri.parse("content://test/out");

        Mockito.when(resolver.getType(src)).thenReturn("image/jpeg");
        Mockito.when(resolver.openInputStream(src)).thenReturn(new ByteArrayInputStream(jpegBytes));
        Mockito.when(resolver.insert(Mockito.any(), Mockito.any())).thenReturn(out);
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        Mockito.when(resolver.openOutputStream(out)).thenReturn(outStream);

        Context context = new ContextWrapper(base) {
            @Override
            public ContentResolver getContentResolver() {
                return resolver;
            }
        };

        Uri result = FormatConverter.convertImageToPictures(
                context, src, Bitmap.CompressFormat.valueOf("HEIC"), "name");
        assertEquals(out, result);
        assertTrue(outStream.size() > 0);
    }

    private static String invokeStringHelper(String methodName, String input) throws Exception {
        Method method = FormatConverter.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, input);
    }

    private static String invokeFormatStringHelper(String methodName, Bitmap.CompressFormat format)
            throws Exception {
        Method method = FormatConverter.class.getDeclaredMethod(
                methodName, Bitmap.CompressFormat.class);
        method.setAccessible(true);
        return (String) method.invoke(null, format);
    }

    private static int invokeSampleSizeHelper(BitmapFactory.Options options) throws Exception {
        Method method =
                FormatConverter.class.getDeclaredMethod(
                        "calculateInSampleSize", BitmapFactory.Options.class);
        method.setAccessible(true);
        return (int) method.invoke(null, options);
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
