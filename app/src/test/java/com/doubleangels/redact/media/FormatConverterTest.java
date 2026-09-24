package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class FormatConverterTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        FormatConverter.testHeicCompressFormatOverride = null;
        FormatConverter.testTreatFormatAsHeic = null;
    }

    @Test
    public void formatAtIndex_mapsStandardIndices() {
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(0));
        assertEquals(Bitmap.CompressFormat.PNG, FormatConverter.formatAtIndex(1));
        assertEquals(Bitmap.CompressFormat.WEBP, FormatConverter.formatAtIndex(2));
    }

    @Test
    public void formatAtIndex_unknownIndexFallsBackToJpeg() {
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(99));
    }

    @Test
    public void effectiveImageFormatIndex_heicFallsBackToJpegBelowApi34() {
        assertEquals(0, FormatConverter.effectiveImageFormatIndex(3));
        assertEquals(0, FormatConverter.effectiveImageFormatIndex(0));
        assertEquals(2, FormatConverter.effectiveImageFormatIndex(2));
    }

    @Test
    public void formatAtIndex_heicBelowApi34_fallsBackToJpeg() {
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(3));
    }

    @Config(sdk = 34)
    @Test
    public void effectiveImageFormatIndex_allowsHeicOnApi34() {
        assertEquals(3, FormatConverter.effectiveImageFormatIndex(3));
    }

    @Test
    public void isHeicProcessingSupported_falseBelowApi34() {
        assertFalse(FormatConverter.isHeicProcessingSupported());
    }

    @Config(sdk = 34)
    @Test
    public void isHeicProcessingSupported_trueOnApi34() {
        assertTrue(FormatConverter.isHeicProcessingSupported());
    }

    @Config(sdk = 34)
    @Test
    public void formatAtIndex_heicUsesOverrideOnApi34() {
        FormatConverter.testHeicCompressFormatOverride = Bitmap.CompressFormat.PNG;
        assertEquals(Bitmap.CompressFormat.PNG, FormatConverter.formatAtIndex(3));
    }

    @Config(sdk = 34)
    @Test
    public void isHeicOutputSupported_detectsHeicFormatOnApi34() {
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.WEBP;
        assertTrue(FormatConverter.isHeicOutputSupported());
    }

    @Config(sdk = 34)
    @Test
    public void heicDetectedViaOverride_qualityTreatedAsWebpOrHeic() {
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.JPEG;
        assertEquals(90, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_HIGH));
        assertEquals(70, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_SMALLER));
    }

    @Test
    public void qualityForFormat_pngIsAlwaysLossless() {
        assertEquals(100, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.PNG, AppPreferences.QUALITY_PRESET_HIGH));
        assertEquals(100, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.PNG, AppPreferences.QUALITY_PRESET_BALANCED));
        assertEquals(100, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.PNG, AppPreferences.QUALITY_PRESET_SMALLER));
    }

    @Test
    public void qualityForFormat_jpegAndWebpMatchPresetDefaults() {
        assertEquals(92, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_HIGH));
        assertEquals(85, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_BALANCED));
        assertEquals(75, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_SMALLER));
        assertEquals(90, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.WEBP, AppPreferences.QUALITY_PRESET_HIGH));
        assertEquals(80, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.WEBP, AppPreferences.QUALITY_PRESET_BALANCED));
        assertEquals(70, FormatConverter.qualityForFormat(
                Bitmap.CompressFormat.WEBP, AppPreferences.QUALITY_PRESET_SMALLER));
    }

    @Test
    public void resolveImageFormat_jpegExtensionsResolveToJpegSpec() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".jpg", null);
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.JPEG, spec.compressFormat);
        assertTrue(spec.bitmapFallbackSupported);

        FormatConverter.ImageFormatSpec fromJpeg = FormatConverter.resolveImageFormat(".JPEG", null);
        assertEquals(".jpg", fromJpeg.extension);
    }

    @Test
    public void resolveImageFormat_pngFallsBackToMimeWhenExtensionUnknown() throws IOException {
        FormatConverter.ImageFormatSpec byExtension = FormatConverter.resolveImageFormat(".png", null);
        assertEquals(".png", byExtension.extension);
        assertEquals("image/png", byExtension.mimeType);

        FormatConverter.ImageFormatSpec byMime = FormatConverter.resolveImageFormat(null, "image/png");
        assertEquals(".png", byMime.extension);
    }

    @Test
    public void resolveImageFormat_unknownSourceFallsBackToJpeg() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(
                ".bin", "application/octet-stream");
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
    }

    @Test
    public void resolveImageFormat_heicBelowApi34_fallsBackToJpeg() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".heic", null);
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
    }

    @Config(sdk = 34)
    @Test
    public void resolveImageFormat_heicOnApi34_withOverrideKeepsHeicSpec() throws IOException {
        FormatConverter.testHeicCompressFormatOverride = Bitmap.CompressFormat.PNG;
        FormatConverter.testTreatFormatAsHeic = Bitmap.CompressFormat.PNG;
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".heif", null);
        assertEquals(".heic", spec.extension);
        assertEquals("image/heic", spec.mimeType);
    }

    @Test
    public void jpegFormatSpec_returnsJpegDefaults() {
        FormatConverter.ImageFormatSpec spec = FormatConverter.jpegFormatSpec();
        assertNotNull(spec);
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.JPEG, spec.compressFormat);
    }

    @Test
    public void calculateInSampleSize_respectsConfiguredMaxBitmapSize() {
        AppPreferences.setMaxBitmapSize(context, 2048);

        BitmapFactory.Options small = new BitmapFactory.Options();
        small.outWidth = 1000;
        small.outHeight = 1000;
        assertEquals(1, FormatConverter.calculateInSampleSize(context, small));

        BitmapFactory.Options large = new BitmapFactory.Options();
        large.outWidth = 5000;
        large.outHeight = 5000;
        assertEquals(2, FormatConverter.calculateInSampleSize(context, large));

        BitmapFactory.Options huge = new BitmapFactory.Options();
        huge.outWidth = 10000;
        huge.outHeight = 10000;
        assertEquals(4, FormatConverter.calculateInSampleSize(context, huge));
    }

    @Test
    public void formatOptionCount_MatchesSelectableChips() {
        assertEquals(4, FormatConverter.FORMAT_OPTION_COUNT);
    }
}