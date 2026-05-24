package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class FormatConverterImageFormatTest {

    @Test
    public void resolveImageFormat_pngFromExtension() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".png", null);
        assertEquals(".png", spec.extension);
        assertEquals("image/png", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.PNG, spec.compressFormat);
        assertTrue(spec.bitmapFallbackSupported);
    }

    @Test
    public void resolveImageFormat_jpegFromExtension() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".jpg", null);
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.JPEG, spec.compressFormat);
    }

    @Test
    public void resolveImageFormat_webpFromMimeWhenExtensionMissing() throws IOException {
        FormatConverter.ImageFormatSpec spec =
                FormatConverter.resolveImageFormat(null, "image/webp");
        assertEquals(".webp", spec.extension);
        assertEquals("image/webp", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.WEBP, spec.compressFormat);
    }

    @Test
    public void resolveImageFormat_defaultsToJpeg() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(null, null);
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.JPEG, spec.compressFormat);
    }

    @Test
    public void effectiveImageFormatIndex_mapsHeicToJpegBelowApi34() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(3, FormatConverter.effectiveImageFormatIndex(3));
            return;
        }
        assertEquals(0, FormatConverter.effectiveImageFormatIndex(3));
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(3));
    }

    @Test
    public void resolveImageFormat_heicRejectedBelowApi34() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return;
        }
        try {
            FormatConverter.resolveImageFormat(".heic", "image/heic");
            fail("Expected IOException for HEIC below API 34");
        } catch (IOException e) {
            assertEquals(FormatConverter.HEIC_REQUIRES_API_34, e.getMessage());
        }
    }

    @Test
    public void resolveImageFormat_heicAllowedOnApi34() throws IOException {
        if (!FormatConverter.isHeicProcessingSupported()) {
            return;
        }
        FormatConverter.ImageFormatSpec spec =
                FormatConverter.resolveImageFormat(".heic", "image/heic");
        assertEquals(".heic", spec.extension);
        assertEquals("image/heic", spec.mimeType);
        if (FormatConverter.isHeicOutputSupported()) {
            assertTrue(spec.bitmapFallbackSupported);
        }
    }
}
