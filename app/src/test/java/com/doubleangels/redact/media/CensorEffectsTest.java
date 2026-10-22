package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CensorEffectsTest {

    /** Black and white single-pixel checks: any averaging turns them grey. */
    private static Bitmap checkerboard(int width, int height) {
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                b.setPixel(x, y, (x + y) % 2 == 0 ? Color.WHITE : Color.BLACK);
            }
        }
        return b;
    }

    private static boolean isGrey(int c) {
        int r = Color.red(c);
        return r > 60 && r < 195 && Math.abs(r - Color.green(c)) < 2 * CensorEffects.NOISE + 2
                && Math.abs(r - Color.blue(c)) < 2 * CensorEffects.NOISE + 2;
    }

    private static int[] pixels(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return px;
    }

    // ---- block colours ----------------------------------------------------------------------------

    @Test
    public void blockColours_areTheAverageWithinTheNoise() {
        int[] px = new int[16 * 8];
        Arrays.fill(px, 0xFF808080);
        Random random = new Random(1);
        int[] colours = CensorEffects.blockColours(px, 16, 8, 8, random);

        assertEquals(2, colours.length);
        for (int c : colours) {
            assertEquals(0xFF, c >>> 24);
            for (int channel : new int[] {Color.red(c), Color.green(c), Color.blue(c)}) {
                assertTrue(Math.abs(channel - 0x80) <= CensorEffects.NOISE);
            }
        }
    }

    @Test
    public void blockColours_addNoise_soTheyCannotBeMatchedExactly() {
        int[] px = new int[64 * 64];
        Arrays.fill(px, 0xFF808080);
        int[] colours = CensorEffects.blockColours(px, 64, 64, 8, new Random(7));

        long exact = Arrays.stream(colours).filter(c -> c == 0xFF808080).count();
        assertTrue("most blocks are moved off the true average", exact < colours.length / 4);
    }

    @Test
    public void blockSize_scalesWithTheImage() {
        assertEquals(8, CensorEffects.blockSize(100, 50));
        assertEquals(83, CensorEffects.blockSize(4000, 3000));
    }

    // ---- nothing finer than a block survives -----------------------------------------------------

    /** Two 96x96 images whose 8x8 block averages match but whose pixels inside each block differ. */
    private static Bitmap[] sameBlockAveragesDifferentDetail() {
        Bitmap a = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                boolean on = (x + y) % 2 == 0;
                // Same counts of each colour per 8x8 block, arranged differently: a checkerboard
                // against vertical stripes, like two different words of the same ink coverage.
                a.setPixel(x, y, on ? Color.WHITE : Color.BLACK);
                b.setPixel(x, y, x % 2 == 0 ? Color.WHITE : Color.BLACK);
            }
        }
        return new Bitmap[] {a, b};
    }

    @Test
    public void pixelate_keepsNothingFinerThanABlock() {
        Bitmap[] pair = sameBlockAveragesDifferentDetail();
        List<CensorRegion> all = List.of(CensorRegion.rect(0f, 0f, 1f, 1f));

        CensorEffects.apply(pair[0], all, CensorEffects.Effect.PIXELATE, new Random(3));
        CensorEffects.apply(pair[1], all, CensorEffects.Effect.PIXELATE, new Random(3));

        assertArrayEquals(pixels(pair[0]), pixels(pair[1]));
    }

    @Test
    public void blur_keepsNothingFinerThanABlock() {
        Bitmap[] pair = sameBlockAveragesDifferentDetail();
        List<CensorRegion> all = List.of(CensorRegion.rect(0f, 0f, 1f, 1f));

        CensorEffects.apply(pair[0], all, CensorEffects.Effect.BLUR, new Random(3));
        CensorEffects.apply(pair[1], all, CensorEffects.Effect.BLUR, new Random(3));

        assertArrayEquals(pixels(pair[0]), pixels(pair[1]));
    }

    @Test
    public void eachSaveGetsFreshNoise() {
        Bitmap a = checkerboard(96, 96);
        Bitmap b = checkerboard(96, 96);
        List<CensorRegion> all = List.of(CensorRegion.rect(0f, 0f, 1f, 1f));

        CensorEffects.apply(a, all, CensorEffects.Effect.PIXELATE);
        CensorEffects.apply(b, all, CensorEffects.Effect.PIXELATE);

        assertFalse(Arrays.equals(pixels(a), pixels(b)));
    }

    @Test
    public void blur_isSmoothAcrossBlocks() {
        Bitmap b = checkerboard(96, 96);
        CensorEffects.apply(b, List.of(CensorRegion.rect(0f, 0f, 1f, 1f)),
                CensorEffects.Effect.BLUR, new Random(5));

        // Neighbouring pixels differ by a little, never by a whole block step.
        int maxStep = 0;
        for (int y = 0; y < 96; y++) {
            for (int x = 1; x < 96; x++) {
                maxStep = Math.max(maxStep, Math.abs(Color.red(b.getPixel(x, y)) - Color.red(b.getPixel(x - 1, y))));
            }
        }
        assertTrue("largest step " + maxStep, maxStep <= CensorEffects.NOISE);
    }

    // ---- regions on a bitmap ----------------------------------------------------------------------

    @Test
    public void pixelate_changesOnlyTheInsideOfTheRectangle() {
        Bitmap b = checkerboard(96, 96);
        CensorEffects.apply(b, List.of(CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f)),
                CensorEffects.Effect.PIXELATE);

        assertTrue(isGrey(b.getPixel(48, 48)));
        assertTrue(isGrey(b.getPixel(25, 25)));
        assertEquals(Color.WHITE, b.getPixel(4, 4));
        assertEquals(Color.BLACK, b.getPixel(90, 91));
        assertEquals("just outside the rectangle", Color.WHITE, b.getPixel(23, 23));
    }

    @Test
    public void black_fillsTheRectangle() {
        Bitmap b = Bitmap.createBitmap(50, 40, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        CensorEffects.apply(b, List.of(CensorRegion.rect(0.8f, 0.5f, 0.2f, 0.25f)),
                CensorEffects.Effect.BLACK);

        assertEquals(Color.BLACK, b.getPixel(25, 15));
        assertEquals(Color.WHITE, b.getPixel(25, 30));
        assertEquals(Color.WHITE, b.getPixel(5, 15));
    }

    @Test
    public void blur_changesOnlyTheInsideOfTheRectangle() {
        Bitmap b = checkerboard(80, 80);
        CensorEffects.apply(b, List.of(CensorRegion.rect(0.5f, 0f, 1f, 1f)),
                CensorEffects.Effect.BLUR);

        assertTrue(isGrey(b.getPixel(60, 40)));
        assertEquals(Color.WHITE, b.getPixel(10, 10));
        assertEquals(Color.BLACK, b.getPixel(10, 11));
    }

    @Test
    public void brushStroke_coversItsPathAndNotTheRest() {
        Bitmap b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        CensorRegion stroke = CensorRegion.stroke(new float[] {0.1f, 0.5f, 0.9f, 0.5f}, 0.1f);
        CensorEffects.apply(b, List.of(stroke), CensorEffects.Effect.BLACK);

        assertEquals(Color.BLACK, b.getPixel(50, 50));
        assertEquals(Color.BLACK, b.getPixel(50, 53));
        assertEquals(Color.WHITE, b.getPixel(50, 20));
        assertEquals(Color.WHITE, b.getPixel(50, 80));
    }

    @Test
    public void aTap_coversADotTheSizeOfTheBrush() {
        Bitmap b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        CensorEffects.apply(b, List.of(CensorRegion.stroke(new float[] {0.5f, 0.5f}, 0.2f)),
                CensorEffects.Effect.BLACK);

        assertEquals(Color.BLACK, b.getPixel(50, 50));
        assertEquals(Color.BLACK, b.getPixel(58, 50));
        assertEquals(Color.WHITE, b.getPixel(70, 50));
    }

    @Test
    public void emptyRectangle_changesNothing() {
        Bitmap b = checkerboard(20, 20);
        int before = b.getPixel(10, 10);
        CensorRegion empty = CensorRegion.rect(0.5f, 0.2f, 0.5f, 0.8f);
        assertTrue(empty.isEmpty());

        CensorEffects.apply(b, List.of(empty), CensorEffects.Effect.BLACK);

        assertEquals(before, b.getPixel(10, 10));
    }

    @Test
    public void rect_isKeptInsideTheImage() {
        CensorRegion r = CensorRegion.rect(-0.5f, 1.5f, 0.5f, 0.2f);
        assertArrayEquals(new float[] {0f, 0.2f, 0.5f, 1f}, r.points(), 0f);
    }
}
