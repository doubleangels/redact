package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CensorVerifierTest {

    private static final int W = 320;
    private static final int H = 240;
    private static final CensorRegion CENTRE = CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f);

    /** Squares of three pixels in black and white, and a colour gradient: plenty of fine detail. */
    private static Bitmap detailed(int width, int height) {
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean on = ((x / 3) + (y / 3)) % 2 == 0;
                b.setPixel(x, y, on ? Color.rgb(250, 240 - y / 2, x * 255 / width) : Color.rgb(5, y / 2, 30));
            }
        }
        return b;
    }

    private static Bitmap covered(List<CensorRegion> regions, CensorEffects.Effect effect) {
        Bitmap b = detailed(W, H);
        CensorEffects.apply(b, regions, effect, new Random(3));
        return b;
    }

    private static int[] lumaOf(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return CensorVerifier.luma(px);
    }

    private static List<VideoCensorLayout.Area> areas(List<CensorRegion> regions, CensorEffects.Effect effect) {
        List<VideoCensorRegion> timed = new ArrayList<>();
        for (CensorRegion region : regions) {
            timed.add(VideoCensorRegion.wholeVideo(region));
        }
        return VideoCensorLayout.layOut(timed, effect, W, H, new Random(0));
    }

    private static boolean check(Bitmap frame, CensorRegion crop, CensorEffects.Effect effect) throws IOException {
        CensorVerifier.Mapping mapping = CensorVerifier.Mapping.of(W, H, crop, frame.getWidth(), frame.getHeight());
        return CensorVerifier.isCovered(lumaOf(frame), frame.getWidth(), frame.getHeight(), mapping,
                areas(List.of(CENTRE), effect).get(0), List.of());
    }

    // ---- one frame ----------------------------------------------------------------------------

    @Test
    public void pixelatedAndBlurredAndBlackAreas_pass() throws IOException {
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            assertTrue(effect.name(), check(covered(List.of(CENTRE), effect), null, effect));
        }
    }

    /**
     * On a 240 x 320 frame this strip's first column of blocks runs from x 16 to 32 while the strip
     * starts at x 24, so that block's centre shows the uncovered picture beside the area. It was
     * read as the block's colour once, which failed a correctly covered video filmed on its side.
     */
    @Test
    public void aBlockWhoseCentreIsOutsideTheArea_stillPasses() throws IOException {
        CensorRegion strip = CensorRegion.rect(0.1f, 0.05f, 0.9f, 0.35f);
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            Bitmap frame = detailed(240, 320);
            CensorEffects.apply(frame, List.of(strip), effect, new Random(5));
            VideoCensorLayout.Area area = VideoCensorLayout.layOut(List.of(VideoCensorRegion.wholeVideo(strip)),
                    effect, 240, 320, new Random(0)).get(0);
            CensorVerifier.Mapping mapping = CensorVerifier.Mapping.of(240, 320, null, 240, 320);
            assertTrue(effect.name(), CensorVerifier.isCovered(lumaOf(frame), 240, 320, mapping, area, List.of()));
            assertFalse(effect.name(), CensorVerifier.isCovered(lumaOf(detailed(240, 320)), 240, 320, mapping,
                    area, List.of()));
        }
    }

    @Test
    public void anUncoveredArea_fails() throws IOException {
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            assertFalse(effect.name(), check(detailed(W, H), null, effect));
        }
    }

    @Test
    public void anAreaCoveredInTheWrongPlace_fails() throws IOException {
        // Covered as if upside down: the effect's y flipped.
        CensorRegion flipped = CensorRegion.rect(0.25f, 0.05f, 0.75f, 0.45f);
        CensorRegion meant = CensorRegion.rect(0.25f, 0.55f, 0.75f, 0.95f);
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            Bitmap frame = covered(List.of(flipped), effect);
            CensorVerifier.Mapping mapping = CensorVerifier.Mapping.of(W, H, null, W, H);
            assertFalse(effect.name(), CensorVerifier.isCovered(lumaOf(frame), W, H, mapping,
                    areas(List.of(meant), effect).get(0), List.of()));
        }
    }

    @Test
    public void encodingNoise_isAllowed() throws IOException {
        for (CensorEffects.Effect effect : new CensorEffects.Effect[] {
                CensorEffects.Effect.PIXELATE, CensorEffects.Effect.BLUR}) {
            int[] luma = lumaOf(covered(List.of(CENTRE), effect));
            Random random = new Random(8);
            for (int i = 0; i < luma.length; i++) {
                luma[i] = Math.max(0, Math.min(255, luma[i] + random.nextInt(9) - 4));
            }
            CensorVerifier.Mapping mapping = CensorVerifier.Mapping.of(W, H, null, W, H);
            assertTrue(effect.name(), CensorVerifier.isCovered(luma, W, H, mapping,
                    areas(List.of(CENTRE), effect).get(0), List.of()));
        }
    }

    @Test
    public void aDownscaledVideo_isLinedUp() throws IOException {
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            Bitmap small = Bitmap.createScaledBitmap(covered(List.of(CENTRE), effect), W / 2, H / 2, true);
            assertTrue(effect.name(), check(small, null, effect));
        }
    }

    @Test
    public void aCroppedVideo_isLinedUp() throws IOException {
        CensorRegion rightHalf = CensorRegion.rect(0.5f, 0f, 1f, 1f);
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            Bitmap whole = covered(List.of(CENTRE), effect);
            Bitmap cropped = Bitmap.createBitmap(whole, W / 2, 0, W / 2, H);
            assertTrue(effect.name(), check(cropped, rightHalf, effect));
            assertFalse(effect.name(), check(Bitmap.createBitmap(detailed(W, H), W / 2, 0, W / 2, H),
                    rightHalf, effect));
        }
    }

    @Test
    public void anAreaCroppedAway_hasNothingToCheck() throws IOException {
        CensorRegion leftQuarter = CensorRegion.rect(0f, 0f, 0.2f, 1f);
        CensorRegion rightHalf = CensorRegion.rect(0.5f, 0f, 1f, 1f);
        Bitmap frame = Bitmap.createBitmap(detailed(W, H), W / 2, 0, W / 2, H);
        CensorVerifier.Mapping mapping = CensorVerifier.Mapping.of(W, H, rightHalf, W / 2, H);
        assertTrue(CensorVerifier.isCovered(lumaOf(frame), W / 2, H, mapping,
                areas(List.of(leftQuarter), CensorEffects.Effect.PIXELATE).get(0), List.of()));
    }

    @Test
    public void blocksTooSmallToMeasure_failClosed() throws IOException {
        Bitmap tiny = Bitmap.createScaledBitmap(covered(List.of(CENTRE), CensorEffects.Effect.PIXELATE),
                W / 10, H / 10, true);
        assertFalse(check(tiny, null, CensorEffects.Effect.PIXELATE));
    }

    @Test
    public void aLaterAreaDrawnOnTop_isLeftOutOfTheEarlierOnesCheck() throws IOException {
        CensorRegion inside = CensorRegion.rect(0.4f, 0.4f, 0.6f, 0.6f).withEffect(CensorEffects.Effect.BLACK);
        List<CensorRegion> both = List.of(CENTRE, inside);
        Bitmap frame = covered(both, CensorEffects.Effect.PIXELATE);
        List<VideoCensorLayout.Area> laid = areas(both, CensorEffects.Effect.PIXELATE);
        CensorVerifier.Mapping mapping = CensorVerifier.Mapping.of(W, H, null, W, H);
        VideoCensorLayout.Area top = laid.get(1);
        float[] topRect = mapping.rect(top.drawLeft, top.drawTop, top.drawRight, top.drawBottom);
        assertTrue(CensorVerifier.isCovered(lumaOf(frame), W, H, mapping, laid.get(0), List.of(topRect)));
        assertTrue(CensorVerifier.isCovered(lumaOf(frame), W, H, mapping, top, List.of()));
    }

    // ---- lining up --------------------------------------------------------------------------------

    @Test
    public void mapping_followsTheCropAndScale() throws IOException {
        CensorVerifier.Mapping mapping = CensorVerifier.Mapping.of(
                1920, 1080, CensorRegion.rect(0.25f, 0.5f, 0.75f, 1f), 480, 270);
        assertEquals(0f, mapping.x(480f), 1e-3f);
        assertEquals(0f, mapping.y(540f), 1e-3f);
        assertEquals(480f, mapping.x(1440f), 1e-3f);
        assertEquals(270f, mapping.y(1080f), 1e-3f);
        assertArrayEquals(new float[] {240f, 0f, 480f, 135f},
                mapping.rect(960f, 540f, 1440f, 810f), 1e-3f);
    }

    @Test
    public void mapping_refusesAFrameOnItsSide() {
        assertThrows(CleanFailedException.class, () -> CensorVerifier.Mapping.of(1920, 1080, null, 1080, 1920));
        assertThrows(CleanFailedException.class, () -> CensorVerifier.Mapping.of(1920, 1080, null, 0, 0));
        assertThrows(CleanFailedException.class,
                () -> CensorVerifier.Mapping.of(1920, 1080, CensorRegion.rect(0.5f, 0f, 0.5f, 1f), 10, 10));
    }

    @Test
    public void luma_weighsTheChannels() {
        assertArrayEquals(new int[] {0, 255, 76, 150, 29},
                CensorVerifier.luma(new int[] {Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE}));
    }

    // ---- sample times -----------------------------------------------------------------------------

    @Test
    public void sampleTimes_takeTheStartMiddleAndAFrameBeforeTheEnd() {
        assertArrayEquals(new long[] {1_000_000L, 1_483_333L, 1_966_667L}, CensorVerifier.sampleTimes(
                VideoCensorRegion.of(CENTRE, 1_000_000L, 2_000_000L), 10_000_000L, 33_333L));
    }

    @Test
    public void sampleTimes_ofTheWholeVideo_endAtItsLastFrame() {
        assertArrayEquals(new long[] {0L, 4_983_333L, 9_966_667L}, CensorVerifier.sampleTimes(
                VideoCensorRegion.wholeVideo(CENTRE), 10_000_000L, 33_333L));
    }

    @Test
    public void sampleTimes_ofAShortRange_areFew() {
        assertArrayEquals(new long[] {500_000L}, CensorVerifier.sampleTimes(
                VideoCensorRegion.of(CENTRE, 500_000L, 520_000L), 10_000_000L, 33_333L));
        assertArrayEquals(new long[] {500_000L, 500_001L}, CensorVerifier.sampleTimes(
                VideoCensorRegion.of(CENTRE, 500_000L, 500_002L), 10_000_000L, 1L));
    }

    @Test
    public void sampleTimes_ofARangeAfterTheEnd_areNone() {
        assertEquals(0, CensorVerifier.sampleTimes(
                VideoCensorRegion.of(CENTRE, 20_000_000L, 30_000_000L), 10_000_000L, 33_333L).length);
    }

    // ---- whole videos -----------------------------------------------------------------------------

    /** Frames that are all the same picture, with a set length. */
    private static final class FakeFrames implements CensorVerifier.FrameSource {
        private final Bitmap picture;
        private final long durationUs;
        private final long intervalUs;
        final List<Long> asked = new ArrayList<>();
        boolean closed;

        FakeFrames(Bitmap picture, long durationUs, long intervalUs) {
            this.picture = picture;
            this.durationUs = durationUs;
            this.intervalUs = intervalUs;
        }

        @Override
        public long durationUs() {
            return durationUs;
        }

        @Override
        public long frameIntervalUs() {
            return intervalUs;
        }

        @Override
        public Bitmap frameAt(long timeUs) {
            asked.add(timeUs);
            return picture == null ? null : picture.copy(Bitmap.Config.ARGB_8888, false);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static List<VideoCensorRegion> timed(CensorRegion... regions) {
        List<VideoCensorRegion> timed = new ArrayList<>();
        for (CensorRegion region : regions) {
            timed.add(VideoCensorRegion.of(region, 1_000_000L, 3_000_000L));
        }
        return timed;
    }

    @Test
    public void aCoveredVideo_passes_afterCheckingEachAreasRange() throws IOException {
        FakeFrames frames = new FakeFrames(covered(List.of(CENTRE), CensorEffects.Effect.BLUR), 5_000_000L, 40_000L);
        CensorVerifier.requireVideoAreasCovered(frames, W, H, timed(CENTRE), null, CensorEffects.Effect.BLUR, 0L);
        assertEquals(List.of(1_000_000L, 1_980_000L, 2_960_000L), frames.asked);
    }

    @Test
    public void anUncoveredVideo_fails() {
        FakeFrames frames = new FakeFrames(detailed(W, H), 5_000_000L, 40_000L);
        assertThrows(CleanFailedException.class, () -> CensorVerifier.requireVideoAreasCovered(
                frames, W, H, timed(CENTRE), null, CensorEffects.Effect.PIXELATE, 0L));
    }

    @Test
    public void aFrameThatCantBeDecoded_fails() {
        FakeFrames frames = new FakeFrames(null, 5_000_000L, 40_000L);
        assertThrows(CleanFailedException.class, () -> CensorVerifier.requireVideoAreasCovered(
                frames, W, H, timed(CENTRE), null, CensorEffects.Effect.PIXELATE, 0L));
    }

    @Test
    public void aVideoOfUnknownLengthOrRate_fails() {
        Bitmap picture = covered(List.of(CENTRE), CensorEffects.Effect.PIXELATE);
        assertThrows(CleanFailedException.class, () -> CensorVerifier.requireVideoAreasCovered(
                new FakeFrames(picture, 0L, 40_000L), W, H, timed(CENTRE), null, CensorEffects.Effect.PIXELATE, 0L));
        assertThrows(CleanFailedException.class, () -> CensorVerifier.requireVideoAreasCovered(
                new FakeFrames(picture, 5_000_000L, 0L), W, H, timed(CENTRE), null, CensorEffects.Effect.PIXELATE, 0L));
    }

    @Test
    public void theSourcesFrameRate_standsInForAnUnknownOne() throws IOException {
        FakeFrames frames = new FakeFrames(covered(List.of(CENTRE), CensorEffects.Effect.PIXELATE), 5_000_000L, 0L);
        CensorVerifier.requireVideoAreasCovered(
                frames, W, H, timed(CENTRE), null, CensorEffects.Effect.PIXELATE, 40_000L);
        assertEquals(3, frames.asked.size());
    }

    @Test
    public void overlappingAreas_eachPass() throws IOException {
        CensorRegion inside = CensorRegion.rect(0.4f, 0.4f, 0.6f, 0.6f).withEffect(CensorEffects.Effect.BLACK);
        FakeFrames frames = new FakeFrames(covered(List.of(CENTRE, inside), CensorEffects.Effect.PIXELATE),
                5_000_000L, 40_000L);
        CensorVerifier.requireVideoAreasCovered(
                frames, W, H, timed(CENTRE, inside), null, CensorEffects.Effect.PIXELATE, 0L);
        assertEquals(6, frames.asked.size());
    }

    @Test
    public void unreadableFile_failsClosed() throws IOException {
        java.io.File missing = new java.io.File(
                org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(), "no_such_video.mp4");
        assertThrows(IOException.class, () -> CensorVerifier.requireVideoAreasCovered(
                missing, W, H, timed(CENTRE), null, CensorEffects.Effect.PIXELATE, 40_000L));
    }
}
