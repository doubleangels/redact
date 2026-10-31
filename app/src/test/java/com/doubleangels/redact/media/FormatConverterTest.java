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
    public void calculateInSampleSize_forAPreviewSize_samplesDownToIt() {
        BitmapFactory.Options small = new BitmapFactory.Options();
        small.outWidth = 1000;
        small.outHeight = 1000;
        assertEquals(1, FormatConverter.calculateInSampleSize(2048, small));

        BitmapFactory.Options large = new BitmapFactory.Options();
        large.outWidth = 5000;
        large.outHeight = 5000;
        assertEquals(2, FormatConverter.calculateInSampleSize(2048, large));

        BitmapFactory.Options huge = new BitmapFactory.Options();
        huge.outWidth = 10000;
        huge.outHeight = 10000;
        assertEquals(4, FormatConverter.calculateInSampleSize(2048, huge));
    }

    @Test
    public void calculateInSampleSize_forASavedPicture_keepsItWhole() {
        BitmapFactory.Options twelveMegapixels = new BitmapFactory.Options();
        twelveMegapixels.outWidth = 4000;
        twelveMegapixels.outHeight = 3000;
        assertEquals(1, FormatConverter.calculateInSampleSize(context, twelveMegapixels));
    }

    @Test
    public void calculateInSampleSize_atFullResolution_keepsEveryPixelThatFits() {
        BitmapFactory.Options fiftyMegapixels = new BitmapFactory.Options();
        fiftyMegapixels.outWidth = 8160;
        fiftyMegapixels.outHeight = 6120;
        assertEquals(1, FormatConverter.calculateInSampleSize(
                FormatConverter.FULL_RESOLUTION, 100_000_000L, fiftyMegapixels));
        // Only when it would not fit in memory is it sampled, by as little as will do.
        assertEquals(2, FormatConverter.calculateInSampleSize(
                FormatConverter.FULL_RESOLUTION, 40_000_000L, fiftyMegapixels));
        assertEquals(4, FormatConverter.calculateInSampleSize(
                FormatConverter.FULL_RESOLUTION, 10_000_000L, fiftyMegapixels));
    }

    @Test
    public void resolveCleanFormat_keepsHeicAndAvifAsTheyAre() throws Exception {
        FormatConverter.ImageFormatSpec heic = FormatConverter.resolveCleanFormat(".heic", "image/heic");
        assertEquals(".heic", heic.extension);
        assertEquals("image/heic", heic.mimeType);

        FormatConverter.ImageFormatSpec avif = FormatConverter.resolveCleanFormat(".avif", "image/avif");
        assertEquals(".avif", avif.extension);
        assertEquals("image/avif", avif.mimeType);
        assertFalse("no AVIF encoder to fall back on", avif.bitmapFallbackSupported);

        assertEquals(".png", FormatConverter.resolveCleanFormat(".png", "image/png").extension);
    }

    private java.io.File bytesFile(byte[] bytes) throws IOException {
        java.io.File f = java.io.File.createTempFile("sniff_", ".bin", context.getCacheDir());
        f.deleteOnExit();
        java.nio.file.Files.write(f.toPath(), bytes);
        return f;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Test
    public void resolveCleanFormat_goesByWhatTheFileIs_notWhatItIsCalled() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        assertEquals(".png", FormatConverter.resolveCleanFormat(bytesFile(png), ".jpg", "image/jpeg").extension);

        byte[] gif = ascii("GIF89a\0\0\0\0\0\0\0");
        assertEquals(".gif", FormatConverter.resolveCleanFormat(bytesFile(gif), ".jpg", null).extension);

        byte[] avif = new byte[32];
        System.arraycopy(new byte[] {0, 0, 0, 28}, 0, avif, 0, 4);
        System.arraycopy(ascii("ftypavif\0\0\0\0mif1miafMA1B"), 0, avif, 4, 24);
        assertEquals(".avif", FormatConverter.resolveCleanFormat(bytesFile(avif), ".jpg", null).extension);

        byte[] heic = new byte[32];
        System.arraycopy(new byte[] {0, 0, 0, 24}, 0, heic, 0, 4);
        System.arraycopy(ascii("ftypheic\0\0\0\0mif1heic"), 0, heic, 4, 20);
        assertEquals(".heic", FormatConverter.resolveCleanFormat(bytesFile(heic), ".jpg", null).extension);

        byte[] bmp = new byte[32];
        bmp[0] = 'B';
        bmp[1] = 'M';
        assertEquals(".png", FormatConverter.resolveCleanFormat(bytesFile(bmp), ".bmp", "image/bmp").extension);

        // Bytes that say nothing known: the name decides.
        assertEquals(".webp", FormatConverter.resolveCleanFormat(bytesFile(new byte[32]), ".webp", null).extension);
    }

    @Test
    public void encodableSpecFor_fallsBackToPngForAGifAndJpegOtherwise() throws Exception {
        assertEquals(".png", FormatConverter.encodableSpecFor(
                FormatConverter.resolveCleanFormat(".gif", "image/gif")).extension);
        assertEquals(".jpg", FormatConverter.encodableSpecFor(
                FormatConverter.resolveCleanFormat(".avif", "image/avif")).extension);
        assertEquals(".png", FormatConverter.encodableSpecFor(FormatConverter.pngSpec()).extension);
    }

    @Test
    public void isLosslessImage_tellsLosslessEncodingsFromLossyOnes() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        assertTrue(FormatConverter.isLosslessImage(new java.io.ByteArrayInputStream(png)));
        assertTrue(FormatConverter.isLosslessImage(new java.io.ByteArrayInputStream(ascii("GIF89a"))));
        assertTrue(FormatConverter.isLosslessImage(
                new java.io.ByteArrayInputStream(ascii("RIFF\0\0\0\0WEBPVP8L\0\0\0\0"))));
        assertFalse(FormatConverter.isLosslessImage(
                new java.io.ByteArrayInputStream(ascii("RIFF\0\0\0\0WEBPVP8 \0\0\0\0"))));
        // An extended WebP decides by its first picture chunk.
        assertTrue(FormatConverter.isLosslessImage(new java.io.ByteArrayInputStream(
                ascii("RIFF\0\0\0\0WEBPVP8X\0\0\0\0\0\0\0\0\0\0\0\0\0\0VP8L\0\0\0\0"))));
        assertFalse(FormatConverter.isLosslessImage(new java.io.ByteArrayInputStream(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})));
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void onWhite_laysATransparentPictureOnWhite_andLeavesAnOpaqueOneAlone() {
        Bitmap clear = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        clear.eraseColor(android.graphics.Color.TRANSPARENT);
        clear.setHasAlpha(true);
        Bitmap flat = FormatConverter.onWhite(clear);
        assertEquals(android.graphics.Color.WHITE, flat.getPixel(0, 0));
        assertFalse(flat.hasAlpha());

        Bitmap opaque = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        opaque.eraseColor(android.graphics.Color.RED);
        opaque.setHasAlpha(false);
        assertTrue(FormatConverter.onWhite(opaque) == opaque);
    }

    /** A JPEG header with libjpeg's luminance table for {@code quality}, then the start of scan. */
    private static byte[] jpegWithQuality(int quality) {
        int[] zigzag = {16, 11, 12, 14, 12, 10, 16, 14, 13, 14, 18, 17, 16, 19, 24, 40, 26, 24, 22, 22,
                24, 49, 35, 37, 29, 40, 58, 51, 61, 60, 57, 51, 56, 55, 64, 72, 92, 78, 64, 68, 87, 69,
                55, 56, 80, 109, 81, 87, 95, 98, 103, 104, 103, 62, 77, 113, 121, 112, 100, 120, 92,
                101, 103, 99};
        int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xDB);
        out.write(0);
        out.write(67);
        out.write(0); // 8-bit table 0
        for (int value : zigzag) {
            out.write(Math.max(1, Math.min(255, (value * scale + 50) / 100)));
        }
        out.write(0xFF);
        out.write(0xDA);
        return out.toByteArray();
    }

    @Test
    public void estimateJpegQuality_readsTheQualityTheJpegWasSavedAt() {
        for (int quality : new int[] {50, 75, 92, 95, 97, 100}) {
            assertEquals(quality, FormatConverter.estimateJpegQuality(
                    new java.io.ByteArrayInputStream(jpegWithQuality(quality))));
        }
        assertEquals(0, FormatConverter.estimateJpegQuality(
                new java.io.ByteArrayInputStream(new byte[] {'n', 'o', 't'})));
    }

    @Test
    public void matchSourceQuality_neverEncodesAJpegBelowItsSourceOnHigh() {
        int high = AppPreferences.QUALITY_PRESET_HIGH;
        assertEquals(96, FormatConverter.matchSourceQuality(Bitmap.CompressFormat.JPEG, high, 92, 96));
        assertEquals(92, FormatConverter.matchSourceQuality(Bitmap.CompressFormat.JPEG, high, 92, 80));
        assertEquals(92, FormatConverter.matchSourceQuality(Bitmap.CompressFormat.JPEG, high, 92, 0));
        // Smaller presets and other formats keep their own quality.
        assertEquals(75, FormatConverter.matchSourceQuality(
                Bitmap.CompressFormat.JPEG, AppPreferences.QUALITY_PRESET_SMALLER, 75, 96));
        assertEquals(90, FormatConverter.matchSourceQuality(Bitmap.CompressFormat.WEBP, high, 90, 96));
    }

    @Test
    public void fullResolutionPixelBudget_isNeverBelowTwentyFourMegapixels() {
        assertTrue(FormatConverter.fullResolutionPixelBudget(context) >= 24_000_000L);
    }

    @Test
    public void decodeSampled_halvesTheSizeWhenADecodeRunsOutOfMemory() throws Exception {
        java.util.List<Integer> tried = new java.util.ArrayList<>();
        Bitmap decoded = FormatConverter.decodeSampled(2, options -> {
            tried.add(options.inSampleSize);
            if (options.inSampleSize < 8) {
                throw new OutOfMemoryError();
            }
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        });

        assertNotNull(decoded);
        assertEquals(java.util.Arrays.asList(2, 4, 8), tried);
    }

    @Test
    public void decodeSampled_givesUpWithAnIOException() {
        try {
            FormatConverter.decodeSampled(1, options -> {
                throw new OutOfMemoryError();
            });
            org.junit.Assert.fail("expected an IOException");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getCause() instanceof OutOfMemoryError);
        }
    }

    @Test
    public void calculateInSampleSize_keepsLongScreenshotsWithinTheEncoderLimit() {
        BitmapFactory.Options fits = new BitmapFactory.Options();
        fits.outWidth = 1080;
        fits.outHeight = 16383;
        assertEquals(1, FormatConverter.calculateInSampleSize(context, fits));

        // Halving 32767 rounds up to 16384, one past the limit, so it must be quartered.
        BitmapFactory.Options odd = new BitmapFactory.Options();
        odd.outWidth = 1080;
        odd.outHeight = 32767;
        assertEquals(4, FormatConverter.calculateInSampleSize(context, odd));

        BitmapFactory.Options even = new BitmapFactory.Options();
        even.outWidth = 1080;
        even.outHeight = 32766;
        assertEquals(2, FormatConverter.calculateInSampleSize(context, even));
    }

    @Test
    public void formatOptionCount_MatchesSelectableChips() {
        assertEquals(4, FormatConverter.FORMAT_OPTION_COUNT);
    }
}