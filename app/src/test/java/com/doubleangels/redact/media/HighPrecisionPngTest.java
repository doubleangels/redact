package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** A picture decoded at more than 8 bits is written as a 16-bit PNG tagged with its colours. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class HighPrecisionPngTest {

    private static Bitmap f16(int width, int height, boolean alpha, ColorSpace space) {
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, alpha, space);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                b.setPixel(x, y, Color.argb(alpha && x == 0 ? 128 : 255, x * 30, y * 40, 200));
            }
        }
        return b;
    }

    private static byte[] write(Bitmap b) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HighPrecisionPng.write(b, out);
        return out.toByteArray();
    }

    private static boolean hasChunk(byte[] png, String type) {
        return new String(png, StandardCharsets.ISO_8859_1).contains(type);
    }

    /** IHDR's bit depth, the 25th byte of the file. */
    private static int bitDepth(byte[] png) {
        return png[24] & 0xFF;
    }

    @Test
    public void isHighPrecision_onlyForMoreThanEightBits() {
        // sRGB, BT.2020 and RGBA_1010102 bitmaps crash Robolectric's native graphics, so only P3.
        assertTrue(HighPrecisionPng.isHighPrecision(f16(2, 2, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))));
        assertFalse(HighPrecisionPng.isHighPrecision(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)));
    }

    @Test
    public void displayP3_withAlpha_isTaggedP3() throws Exception {
        byte[] png = write(f16(5, 3, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3)));

        assertEquals(6, Png16.readHeader(new ByteArrayInputStream(png)).colorType);
        assertTrue(hasChunk(png, "cICP"));
        assertTrue(hasChunk(png, "cHRM"));
    }

    @Test
    public void aSpaceAPngCannotName_isWrittenInDisplayP3() throws Exception {
        byte[] png = write(f16(4, 3, false, ColorSpace.get(ColorSpace.Named.ADOBE_RGB)));

        assertEquals(16, bitDepth(png));
        assertTrue(hasChunk(png, "cICP"));
        assertTrue(hasChunk(png, "gAMA"));
    }

    @Test
    public void turningAHighPrecisionPicture_keepsItsConfigAndColourSpace() throws Exception {
        java.lang.reflect.Method turn = FormatConverter.class.getDeclaredMethod(
                "transformKeepingPrecision", Bitmap.class, android.graphics.Matrix.class);
        turn.setAccessible(true);
        Bitmap wide = f16(4, 2, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        android.graphics.Matrix quarter = new android.graphics.Matrix();
        quarter.setRotate(90);

        Bitmap upright = (Bitmap) turn.invoke(null, wide, quarter);

        assertEquals(2, upright.getWidth());
        assertEquals(4, upright.getHeight());
        assertEquals(Bitmap.Config.RGBA_F16, upright.getConfig());
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), upright.getColorSpace());
    }
}
