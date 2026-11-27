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
        return r > 60 && r < 195 && Math.abs(r - Color.green(c)) < 2 * CensorBlocks.NOISE + 2
                && Math.abs(r - Color.blue(c)) < 2 * CensorBlocks.NOISE + 2;
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
                assertTrue(Math.abs(channel - 0x80) <= CensorBlocks.NOISE);
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
    public void pixelate_leavesALargeAreaAFewBlocksAcross() {
        Bitmap image = checkerboard(240, 240);
        CensorEffects.apply(image, List.of(CensorRegion.rect(0f, 0f, 1f, 1f)),
                CensorEffects.Effect.PIXELATE, new Random(5));

        int[] px = pixels(image);
        java.util.Set<Integer> rowColours = new java.util.HashSet<>();
        for (int x = 0; x < 240; x++) {
            rowColours.add(px[120 * 240 + x]);
        }
        assertTrue("one row crosses at most " + CensorBlocks.MAX_BLOCKS_PER_AREA + " blocks",
                rowColours.size() <= CensorBlocks.MAX_BLOCKS_PER_AREA);
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
        assertTrue("largest step " + maxStep, maxStep <= CensorBlocks.NOISE);
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

    // ---- an effect per region -------------------------------------------------------------------

    @Test
    public void eachRegion_usesItsOwnEffect_andOneWithoutTakesTheGivenOne() {
        Bitmap b = checkerboard(90, 30);
        CensorEffects.apply(b, List.of(
                        CensorRegion.rect(0f, 0f, 1f / 3f, 1f).withEffect(CensorEffects.Effect.BLACK),
                        CensorRegion.rect(1f / 3f, 0f, 2f / 3f, 1f),
                        CensorRegion.rect(2f / 3f, 0f, 1f, 1f).withEffect(CensorEffects.Effect.PIXELATE)),
                CensorEffects.Effect.BLUR, new Random(4));

        for (int y = 0; y < 30; y += 7) {
            assertEquals("blacked out", Color.BLACK, b.getPixel(12, y));
            assertTrue("the given effect, blur", isGrey(b.getPixel(45, y)));
            assertTrue("pixelated", isGrey(b.getPixel(78, y)));
        }
    }

    @Test
    public void anAreaCoveredTwice_showsNothingOfTheOriginal() {
        Bitmap b = checkerboard(64, 64);
        // Black first, then a blur over half of it: the blur only has black and grey to work from.
        CensorEffects.apply(b, List.of(
                        CensorRegion.rect(0f, 0f, 1f, 1f).withEffect(CensorEffects.Effect.BLACK),
                        CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f).withEffect(CensorEffects.Effect.BLUR)),
                CensorEffects.Effect.PIXELATE, new Random(9));

        for (int y = 16; y < 48; y += 5) {
            for (int x = 16; x < 48; x += 5) {
                assertTrue("dark at " + x + "," + y, Color.red(b.getPixel(x, y)) <= CensorBlocks.NOISE);
            }
        }
    }

    @Test
    public void aRegionsEffect_isPartOfItsEquality_andWithEffectKeepsTheArea() {
        CensorRegion plain = CensorRegion.rect(0.1f, 0.2f, 0.3f, 0.4f);
        CensorRegion blurred = plain.withEffect(CensorEffects.Effect.BLUR);

        assertEquals(null, plain.effect());
        assertEquals(CensorEffects.Effect.BLUR, blurred.effect());
        assertArrayEquals(plain.points(), blurred.points(), 0f);
        assertFalse(plain.equals(blurred));
        assertEquals(plain, blurred.withEffect(null));
        assertEquals(plain.hashCode(), blurred.withEffect(null).hashCode());
        assertTrue("the same effect is the same region", blurred == blurred.withEffect(CensorEffects.Effect.BLUR));
    }

    // ---- output pinned ---------------------------------------------------------------------------

    /** A photo-like image with smooth gradients and fine detail, the same on every run. */
    private static Bitmap goldenImage(int width, int height) {
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255 / width + random.nextInt(32)) & 0xff;
                int g = (y * 255 / height + random.nextInt(32)) & 0xff;
                int bl = ((x ^ y) * 3) & 0xff;
                b.setPixel(x, y, Color.argb(255, r, g, bl));
            }
        }
        return b;
    }

    private static String sha256(int[] pixels) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(pixels.length * 4);
        bytes.asIntBuffer().put(pixels);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest(bytes.array())) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /**
     * Pins every effect's exact output for a fixed image, regions and noise seed, so a change to how
     * blocks are sized or coloured (for one shared with video, say) cannot alter photo results.
     */
    @Test
    public void apply_outputIsPinned() throws Exception {
        List<CensorRegion> regions = List.of(
                CensorRegion.rect(0.05f, 0.05f, 0.30f, 0.25f),
                CensorRegion.rect(0.40f, 0.10f, 0.95f, 0.90f).withEffect(CensorEffects.Effect.BLUR),
                CensorRegion.rect(0.10f, 0.60f, 0.35f, 0.95f).withEffect(CensorEffects.Effect.BLACK),
                CensorRegion.stroke(new float[] {0.1f, 0.4f, 0.5f, 0.5f, 0.9f, 0.95f}, 0.04f),
                CensorRegion.rect(0.25f, 0.20f, 0.50f, 0.45f).withEffect(CensorEffects.Effect.PIXELATE));
        String[][] cases = {
                {"PIXELATE", GOLDEN_PIXELATE},
                {"BLUR", GOLDEN_BLUR},
                {"BLACK", GOLDEN_BLACK},
        };
        StringBuilder mismatches = new StringBuilder();
        for (String[] c : cases) {
            Bitmap image = goldenImage(400, 300);
            CensorEffects.apply(image, regions, CensorEffects.Effect.valueOf(c[0]), new Random(9));
            String actual = sha256(pixels(image));
            if (!actual.equals(c[1])) {
                mismatches.append(c[0]).append('=').append(actual).append(' ');
            }
        }
        assertEquals("", mismatches.toString());
    }

    private static final String GOLDEN_PIXELATE =
            "3a7b9d2147cc9b0847ac4d249a7ac1e156c7d66017998ab2a66d6f612e85c233";
    private static final String GOLDEN_BLUR =
            "de5e699d0cc6bb20dfc6749a2f0f40ac7758a93d4163313d2989aba37c2af82b";
    private static final String GOLDEN_BLACK =
            "55acd7393e23952c439899dddc9b6d0797c4051ef6546a383bac5756397f2ecb";
}
