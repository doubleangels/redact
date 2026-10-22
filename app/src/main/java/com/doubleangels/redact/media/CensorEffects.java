package com.doubleangels.redact.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

/**
 * Covers the censor editor's regions in a bitmap so that what was there cannot be recovered.
 *
 * <p>Black replaces every pixel. Pixelate and Blur both keep only one colour per large block
 * (the block's average plus random noise), so no detail finer than a block survives: two photos
 * that differ only inside blocks give the same result. The noise stops block averages from being
 * matched against rendered text, which is how plain pixelation is attacked. Blur blends those
 * same block colours smoothly instead of showing squares; unlike an ordinary blur, it cannot be
 * sharpened back, because the fine detail is gone rather than spread out.
 *
 * <p>Block sizes scale with the image, so the small preview and the full-size photo look alike.
 */
public final class CensorEffects {

    public enum Effect { PIXELATE, BLUR, BLACK }

    /** Blocks per longer side: large, so faces and text cannot be read. */
    private static final float BLOCKS_PER_SIDE = 48f;
    private static final int MIN_BLOCK = 8;
    /** Random change to each block's colour, per channel, of up to this much either way. */
    @VisibleForTesting
    static final int NOISE = 10;

    private static final Random SECURE_RANDOM = new SecureRandom();

    private CensorEffects() {
    }

    /** Covers every region in {@code target}, which must be mutable and not a hardware bitmap. */
    public static void apply(
            @NonNull Bitmap target, @NonNull List<CensorRegion> regions, @NonNull Effect effect) {
        apply(target, regions, effect, SECURE_RANDOM);
    }

    @VisibleForTesting
    static void apply(@NonNull Bitmap target, @NonNull List<CensorRegion> regions,
                      @NonNull Effect effect, @NonNull Random random) {
        int width = target.getWidth();
        int height = target.getHeight();
        Canvas canvas = new Canvas(target);
        Paint black = new Paint();
        black.setColor(Color.BLACK);
        black.setStyle(Paint.Style.FILL);
        int block = blockSize(width, height);
        for (CensorRegion region : regions) {
            if (region.isEmpty()) {
                continue;
            }
            Path path = region.toPath(width, height);
            if (effect == Effect.BLACK) {
                canvas.drawPath(path, black);
                continue;
            }
            RectF bounds = new RectF();
            path.computeBounds(bounds, true);
            // Whole blocks on the image's own grid, so overlapping regions share one grid and
            // every block's colour comes from the full block.
            Rect area = new Rect(
                    Math.floorDiv((int) Math.floor(bounds.left), block) * block,
                    Math.floorDiv((int) Math.floor(bounds.top), block) * block,
                    ceilTo((int) Math.ceil(bounds.right), block),
                    ceilTo((int) Math.ceil(bounds.bottom), block));
            if (!area.intersect(0, 0, width, height)) {
                continue;
            }
            int w = area.width();
            int h = area.height();
            int[] pixels = new int[w * h];
            target.getPixels(pixels, 0, w, area.left, area.top, w, h);
            int[] colours = blockColours(pixels, w, h, block, random);
            if (effect == Effect.PIXELATE) {
                fillBlocks(pixels, w, h, block, colours);
            } else {
                blendBlocks(pixels, w, h, block, colours);
            }
            Bitmap patch = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(patch, area.left, area.top, null);
            canvas.restore();
            patch.recycle();
        }
    }

    @VisibleForTesting
    static int blockSize(int width, int height) {
        return Math.max(MIN_BLOCK, Math.round(Math.max(width, height) / BLOCKS_PER_SIDE));
    }

    /**
     * One colour per {@code block} x {@code block} square, row by row: its average with up to
     * {@link #NOISE} added to or taken from each colour channel. Alpha is averaged without noise.
     */
    @VisibleForTesting
    @NonNull
    static int[] blockColours(@NonNull int[] pixels, int width, int height, int block, @NonNull Random random) {
        int cols = (width + block - 1) / block;
        int rows = (height + block - 1) / block;
        int[] colours = new int[cols * rows];
        for (int row = 0; row < rows; row++) {
            int top = row * block;
            int bottom = Math.min(top + block, height);
            for (int col = 0; col < cols; col++) {
                int left = col * block;
                int right = Math.min(left + block, width);
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        int c = pixels[y * width + x];
                        a += c >>> 24;
                        r += (c >> 16) & 0xff;
                        g += (c >> 8) & 0xff;
                        b += c & 0xff;
                    }
                }
                int n = (bottom - top) * (right - left);
                colours[row * cols + col] = (int) (a / n) << 24
                        | noisy(r / n, random) << 16 | noisy(g / n, random) << 8 | noisy(b / n, random);
            }
        }
        return colours;
    }

    private static int noisy(long channel, @NonNull Random random) {
        int value = (int) channel + random.nextInt(2 * NOISE + 1) - NOISE;
        return Math.max(0, Math.min(255, value));
    }

    /** Pixelate: every pixel takes its block's colour. */
    @VisibleForTesting
    static void fillBlocks(@NonNull int[] pixels, int width, int height, int block, @NonNull int[] colours) {
        int cols = (width + block - 1) / block;
        for (int y = 0; y < height; y++) {
            int rowStart = (y / block) * cols;
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = colours[rowStart + x / block];
            }
        }
    }

    /**
     * Blur: every pixel is blended from the colours of the four nearest block centres, so the
     * result changes smoothly but holds nothing finer than the blocks.
     */
    @VisibleForTesting
    static void blendBlocks(@NonNull int[] pixels, int width, int height, int block, @NonNull int[] colours) {
        int cols = (width + block - 1) / block;
        int rows = (height + block - 1) / block;
        for (int y = 0; y < height; y++) {
            float fy = Math.max(0f, Math.min(rows - 1f, (y + 0.5f) / block - 0.5f));
            int r0 = (int) fy;
            int r1 = Math.min(rows - 1, r0 + 1);
            float ty = smooth(fy - r0);
            for (int x = 0; x < width; x++) {
                float fx = Math.max(0f, Math.min(cols - 1f, (x + 0.5f) / block - 0.5f));
                int c0 = (int) fx;
                int c1 = Math.min(cols - 1, c0 + 1);
                float tx = smooth(fx - c0);
                pixels[y * width + x] = mix(
                        mix(colours[r0 * cols + c0], colours[r0 * cols + c1], tx),
                        mix(colours[r1 * cols + c0], colours[r1 * cols + c1], tx), ty);
            }
        }
    }

    /** Eases the blend at block centres, so the grid does not show as creases. */
    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static int mix(int c0, int c1, float t) {
        int a = Math.round((c0 >>> 24) + ((c1 >>> 24) - (c0 >>> 24)) * t);
        int r = Math.round(((c0 >> 16) & 0xff) + (((c1 >> 16) & 0xff) - ((c0 >> 16) & 0xff)) * t);
        int g = Math.round(((c0 >> 8) & 0xff) + (((c1 >> 8) & 0xff) - ((c0 >> 8) & 0xff)) * t);
        int b = Math.round((c0 & 0xff) + ((c1 & 0xff) - (c0 & 0xff)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int ceilTo(int value, int multiple) {
        return Math.floorDiv(value + multiple - 1, multiple) * multiple;
    }
}
