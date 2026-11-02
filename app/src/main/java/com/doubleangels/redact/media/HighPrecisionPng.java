package com.doubleangels.redact.media;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.os.Build;
import android.util.Half;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves a picture decoded at more than 8 bits per channel as a 16-bit PNG.
 *
 * <p>Android decodes a 16-bit PNG, or a 10-bit HEIC or AVIF, into an {@code RGBA_F16} or
 * {@code RGBA_1010102} bitmap that keeps its precision, but {@code Bitmap.compress} turns it into
 * an 8-bit picture before it writes a PNG. This writes the bitmap's own values at 16 bits instead
 * ({@link Png16#write}), with the chunks that tell a viewer its colour space: sRGB, Display P3,
 * or BT.2020 with the HLG or PQ curve. A picture in any other space is converted to Display P3,
 * a band of rows at a time, so the memory it takes stays small.
 */
public final class HighPrecisionPng {

    /** Bytes of F16 pixels converted at a time. */
    private static final long BAND_BYTES = 8L * 1024 * 1024;

    private HighPrecisionPng() {
    }

    /** Whether {@code bitmap} holds more than 8 bits per channel. */
    public static boolean isHighPrecision(@NonNull Bitmap bitmap) {
        Bitmap.Config config = bitmap.getConfig();
        return config == Bitmap.Config.RGBA_F16
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && config == Bitmap.Config.RGBA_1010102);
    }

    /** Writes {@code bitmap} to {@code out} as a 16-bit PNG. */
    // The shorts read from the RGBA_F16 pixels are half floats, which Half.toFloat converts; lint
    // can't tell a short read from a buffer is one.
    @android.annotation.SuppressLint("HalfFloat")
    public static void write(@NonNull Bitmap bitmap, @NonNull OutputStream out) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean alpha = bitmap.hasAlpha();
        ColorSpace source = bitmap.getColorSpace() != null
                ? bitmap.getColorSpace() : ColorSpace.get(ColorSpace.Named.SRGB);
        ColorSpace target = supported(source) ? source : ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
        int bandRows = (int) Math.max(1, Math.min(height, BAND_BYTES / (width * 8L)));
        Bitmap band;
        try {
            band = Bitmap.createBitmap(width, bandRows, Bitmap.Config.RGBA_F16, alpha, target);
        } catch (IllegalArgumentException unsupported) {
            target = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
            band = Bitmap.createBitmap(width, bandRows, Bitmap.Config.RGBA_F16, alpha, target);
        }
        List<byte[]> chunks = colourChunks(target);
        try {
            Canvas canvas = new Canvas(band);
            Paint copy = new Paint();
            copy.setFilterBitmap(false);
            copy.setBlendMode(BlendMode.SRC);
            ByteBuffer pixels = ByteBuffer.allocate(band.getByteCount()).order(ByteOrder.nativeOrder());
            int rowBytes = band.getRowBytes();
            boolean premultiplied = band.isPremultiplied();
            int[] bandStart = {-1};
            Bitmap filled = band;
            Png16.write(width, height, alpha, chunks, (y, samples) -> {
                int start = (y / bandRows) * bandRows;
                if (start != bandStart[0]) {
                    filled.eraseColor(0);
                    canvas.drawBitmap(bitmap, 0f, -start, copy);
                    pixels.clear();
                    filled.copyPixelsToBuffer(pixels);
                    bandStart[0] = start;
                }
                int at = (y - start) * rowBytes;
                int i = 0;
                for (int x = 0; x < width; x++, at += 8) {
                    float r = Half.toFloat(pixels.getShort(at));
                    float g = Half.toFloat(pixels.getShort(at + 2));
                    float b = Half.toFloat(pixels.getShort(at + 4));
                    if (alpha) {
                        float a = Half.toFloat(pixels.getShort(at + 6));
                        if (premultiplied && a > 0f) {
                            r /= a;
                            g /= a;
                            b /= a;
                        }
                        samples[i++] = sample(r);
                        samples[i++] = sample(g);
                        samples[i++] = sample(b);
                        samples[i++] = sample(a);
                    } else {
                        samples[i++] = sample(r);
                        samples[i++] = sample(g);
                        samples[i++] = sample(b);
                    }
                }
            }, out);
        } finally {
            band.recycle();
        }
    }

    private static int sample(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 65535f);
    }

    /** Whether a PNG can say what {@code space} is: one of the spaces {@link #colourChunks} names. */
    private static boolean supported(@NonNull ColorSpace space) {
        if (space.equals(ColorSpace.get(ColorSpace.Named.SRGB))
                || space.equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && (space.equals(ColorSpace.get(ColorSpace.Named.BT2020_HLG))
                        || space.equals(ColorSpace.get(ColorSpace.Named.BT2020_PQ)));
    }

    /**
     * The chunks that describe {@code space}: cICP for readers that know it, and cHRM with gAMA
     * (or sRGB) for those that don't. An HDR curve has no gAMA, so those get cICP and cHRM only.
     */
    @NonNull
    private static List<byte[]> colourChunks(@NonNull ColorSpace space) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        int[] srgbPrimaries = {31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000};
        int[] p3Primaries = {31270, 32900, 68000, 32000, 26500, 69000, 15000, 6000};
        int[] bt2020Primaries = {31270, 32900, 70800, 29200, 17000, 79700, 13100, 4600};
        if (space.equals(ColorSpace.get(ColorSpace.Named.SRGB))) {
            chunks.add(Png16.colourChunk("sRGB", new byte[] {0}));
            chunks.add(Png16.colourChunk("gAMA", u32s(45455)));
            chunks.add(Png16.colourChunk("cHRM", u32s(srgbPrimaries)));
        } else if (space.equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))) {
            chunks.add(Png16.colourChunk("cICP", new byte[] {12, 13, 0, 1}));
            chunks.add(Png16.colourChunk("gAMA", u32s(45455)));
            chunks.add(Png16.colourChunk("cHRM", u32s(p3Primaries)));
        } else {
            boolean hlg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && space.equals(ColorSpace.get(ColorSpace.Named.BT2020_HLG));
            chunks.add(Png16.colourChunk("cICP", new byte[] {9, (byte) (hlg ? 18 : 16), 0, 1}));
            chunks.add(Png16.colourChunk("cHRM", u32s(bt2020Primaries)));
        }
        return chunks;
    }

    @NonNull
    private static byte[] u32s(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }
}
