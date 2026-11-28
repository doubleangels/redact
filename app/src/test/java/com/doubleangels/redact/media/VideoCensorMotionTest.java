package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import java.io.IOException;
import java.util.List;
import java.util.Random;

/** Areas that move: keyframes, where the area is on each frame, and the check that covers it. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VideoCensorMotionTest {

    private static final CensorRegion LEFT = CensorRegion.rect(0.1f, 0.4f, 0.3f, 0.6f);
    private static final CensorRegion RIGHT = CensorRegion.rect(0.6f, 0.4f, 0.8f, 0.6f);

    /** From the left at 1 s to the right at 3 s, for the whole video. */
    private static VideoCensorRegion sliding() {
        return VideoCensorRegion.wholeVideo(LEFT).withKeyframes(List.of(
                new VideoCensorRegion.Keyframe(3_000_000L, RIGHT),
                new VideoCensorRegion.Keyframe(1_000_000L, LEFT)));
    }

    // ---- keyframes --------------------------------------------------------------------------------

    @Test
    public void keyframes_areSorted_andMakeTheAreaMove() {
        VideoCensorRegion region = sliding();
        assertTrue(region.moves());
        assertEquals(1_000_000L, region.keyframes().get(0).timeUs());
        assertEquals(3_000_000L, region.keyframes().get(1).timeUs());
        assertEquals(LEFT, region.area());
    }

    @Test
    public void fewerThanTwoKeyframes_keepTheAreaInPlace() {
        VideoCensorRegion none = VideoCensorRegion.wholeVideo(LEFT).withKeyframes(List.of());
        assertFalse(none.moves());
        assertEquals(LEFT, none.area());
        VideoCensorRegion one = VideoCensorRegion.wholeVideo(LEFT)
                .withKeyframes(List.of(new VideoCensorRegion.Keyframe(5L, RIGHT)));
        assertFalse(one.moves());
        assertEquals(RIGHT, one.area());
        // Two at the same time are one: the later wins.
        VideoCensorRegion same = VideoCensorRegion.wholeVideo(LEFT).withKeyframes(List.of(
                new VideoCensorRegion.Keyframe(5L, LEFT), new VideoCensorRegion.Keyframe(5L, RIGHT)));
        assertFalse(same.moves());
        assertEquals(RIGHT, same.area());
    }

    @Test
    public void keyframes_takeTheAreasEffect_andMustBeRectangles() {
        VideoCensorRegion blurred = VideoCensorRegion.wholeVideo(LEFT.withEffect(CensorEffects.Effect.BLUR))
                .withKeyframes(List.of(new VideoCensorRegion.Keyframe(0L, LEFT),
                        new VideoCensorRegion.Keyframe(9L, RIGHT)));
        assertEquals(CensorEffects.Effect.BLUR, blurred.areaAt(4L).effect());
        assertEquals(CensorEffects.Effect.BLUR, blurred.keyframes().get(1).area().effect());
        CensorRegion stroke = CensorRegion.stroke(new float[] {0.1f, 0.1f, 0.2f, 0.2f}, 0.05f);
        assertThrows(IllegalArgumentException.class, () -> VideoCensorRegion.wholeVideo(LEFT)
                .withKeyframes(List.of(new VideoCensorRegion.Keyframe(0L, stroke))));
    }

    @Test
    public void areaAt_slidesBetweenKeyframes_andHoldsBeforeAndAfter() {
        VideoCensorRegion region = sliding();
        assertEquals(LEFT, region.areaAt(0L));
        assertEquals(LEFT, region.areaAt(1_000_000L));
        assertArrayEquals(new float[] {0.35f, 0.4f, 0.55f, 0.6f}, region.areaAt(2_000_000L).points(), 1e-5f);
        assertEquals(RIGHT, region.areaAt(3_000_000L));
        assertEquals(RIGHT, region.areaAt(9_000_000L));
        assertSame("an area that stays put is its own rectangle",
                LEFT, VideoCensorRegion.wholeVideo(LEFT).areaAt(5L));
    }

    @Test
    public void maxSpeed_isTheFastestEdge() {
        double[] speed = sliding().maxSpeed();
        assertEquals(0.5 / 2_000_000.0, speed[0], 1e-12);
        assertEquals(0.0, speed[1], 1e-12);
        assertArrayEquals(new double[] {0, 0}, VideoCensorRegion.wholeVideo(LEFT).maxSpeed(), 0);
    }

    @Test
    public void rounding_keepsTheMotion() {
        VideoCensorRegion region = sliding();
        assertEquals(region.keyframes(), region.roundedOutward(33_333L).keyframes());
        assertTrue(region.roundedOutward(33_333L).moves());
    }

    @Test
    public void equality_coversTheKeyframes() {
        assertEquals(sliding(), sliding());
        assertEquals(sliding().hashCode(), sliding().hashCode());
        assertNotEquals(sliding(), VideoCensorRegion.wholeVideo(LEFT));
        assertNotEquals(sliding(), VideoCensorRegion.wholeVideo(LEFT).withKeyframes(List.of(
                new VideoCensorRegion.Keyframe(1_000_000L, LEFT), new VideoCensorRegion.Keyframe(4_000_000L, RIGHT))));
    }

    // ---- placing a moving area ----------------------------------------------------------------------

    private static VideoCensorLayout.Track track(CensorEffects.Effect effect) {
        return VideoCensorLayout.tracks(List.of(sliding()), effect, 1000, 500, 40_000L, new Random(1)).get(0);
    }

    @Test
    public void aMovingArea_isPlacedOnEachFrame_withItsGridMovingWithIt() {
        VideoCensorLayout.Track track = track(CensorEffects.Effect.PIXELATE);
        assertTrue(track.moves());
        VideoCensorLayout.Area start = track.at(1_000_000L);
        VideoCensorLayout.Area middle = track.at(2_000_000L);
        // 0.5 of 1000 px in 2 s: 10 px in a 40 ms frame, plus a pixel.
        assertEquals(100f - 11f, start.drawLeft, 0.01f);
        assertEquals(350f - 11f, middle.drawLeft, 0.01f);
        assertEquals("the grid starts at the area's own corner", (int) Math.floor(middle.drawLeft), middle.gridLeft);
        assertEquals("the block size never changes", start.block, middle.block);
        assertTrue(start.cols <= start.textureCols && middle.cols <= middle.textureCols);
        assertTrue(start.rows <= start.textureRows);
        assertArrayEquals("each block keeps its noise", start.noise(), middle.noise());
        assertEquals(start.textureCols * start.textureRows * 4, track.noiseRgba().length);
    }

    @Test
    public void aMovingArea_offTheFrameEdge_isKeptOnIt() {
        VideoCensorRegion offEdge = VideoCensorRegion.wholeVideo(CensorRegion.rect(0f, 0f, 0.1f, 0.1f))
                .withKeyframes(List.of(new VideoCensorRegion.Keyframe(0L, CensorRegion.rect(0f, 0f, 0.1f, 0.1f)),
                        new VideoCensorRegion.Keyframe(1_000_000L, CensorRegion.rect(0.9f, 0.9f, 1f, 1f))));
        VideoCensorLayout.Track track = VideoCensorLayout.tracks(List.of(offEdge), CensorEffects.Effect.BLUR,
                1000, 500, 40_000L, new Random(2)).get(0);
        VideoCensorLayout.Area first = track.at(0L);
        assertEquals(0f, first.drawLeft, 0f);
        assertTrue("its grid may start off the frame", first.gridLeft < 0);
        VideoCensorLayout.Area last = track.at(1_000_000L);
        assertEquals(1000f, last.drawRight, 0f);
        assertEquals(1000, last.gridRight);
        assertEquals(500, last.gridBottom);
    }

    @Test
    public void aMovingBlackArea_needsNoBlocks() {
        VideoCensorLayout.Area area = track(CensorEffects.Effect.BLACK).at(2_000_000L);
        assertEquals(CensorEffects.Effect.BLACK, area.effect);
        assertEquals(0, area.cols);
        assertEquals(0, area.textureCols);
        assertEquals(0, track(CensorEffects.Effect.BLACK).noiseRgba().length);
    }

    @Test
    public void anAreaThatStaysPut_isPlacedOnce() {
        VideoCensorLayout.Track track = VideoCensorLayout.tracks(List.of(VideoCensorRegion.wholeVideo(LEFT)),
                CensorEffects.Effect.PIXELATE, 1000, 500, 40_000L, new Random(3)).get(0);
        assertFalse(track.moves());
        assertSame(track.at(0L), track.at(9_000_000L));
        assertEquals(track.at(0L).cols, track.textureCols());
    }

    @Test
    public void aMovingArea_isNeverANoOp() {
        assertFalse(new VideoCensorEffect(List.of(sliding()), CensorEffects.Effect.PIXELATE, 33_333L)
                .isNoOp(1000, 500));
    }

    // ---- checking a moving area ---------------------------------------------------------------------

    /** Fine black and white squares: detail no covered area can still show. */
    private static Bitmap detailed(int width, int height) {
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                b.setPixel(x, y, ((x / 3) + (y / 3)) % 2 == 0 ? Color.WHITE : Color.BLACK);
            }
        }
        return b;
    }

    /** Covers {@code area} the way the effect's Pixelate does: one colour per block of its grid. */
    private static Bitmap pixelated(VideoCensorLayout.Area area, int width, int height, int shift) {
        Bitmap b = detailed(width, height);
        for (int y = (int) area.drawTop; y < (int) Math.ceil(area.drawBottom) && y < height; y++) {
            for (int x = (int) area.drawLeft; x < (int) Math.ceil(area.drawRight) && x < width; x++) {
                int col = Math.floorDiv(x - area.gridLeft - shift, area.block);
                int row = Math.floorDiv(y - area.gridTop, area.block);
                int grey = 40 + Math.floorMod(col * 37 + row * 91, 170);
                b.setPixel(x, y, Color.rgb(grey, grey, grey));
            }
        }
        return b;
    }

    private static final class MovingFrames implements CensorVerifier.FrameSource {
        private final VideoCensorLayout.Track track;
        private final int shift;
        boolean noFrameTimes;
        int asked;

        MovingFrames(VideoCensorLayout.Track track, int shift) {
            this.track = track;
            this.shift = shift;
        }

        @Override
        public long durationUs() {
            return 4_000_000L;
        }

        @Override
        public long frameIntervalUs() {
            return 40_000L;
        }

        @Override
        public long[] frameTimesUs() {
            if (noFrameTimes) {
                return new long[0];
            }
            long[] times = new long[100];
            for (int i = 0; i < times.length; i++) {
                times[i] = i * 40_000L;
            }
            return times;
        }

        @Override
        public Bitmap frameAt(long timeUs) {
            asked++;
            assertEquals("a moving area is checked on exact frame times", 0L, timeUs % 40_000L);
            return pixelated(track.at(timeUs), 1000, 500, shift);
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void aMovingAreaCoveredOnItsOwnGrid_passes_atItsKeyframesAndBetween() throws IOException {
        VideoCensorLayout.Track track = VideoCensorLayout.tracks(List.of(sliding()), CensorEffects.Effect.PIXELATE,
                1000, 500, VideoCensorExporter.frameMarginUs(40_000L), new Random(0)).get(0);
        MovingFrames frames = new MovingFrames(track, 0);
        CensorVerifier.requireVideoAreasCovered(frames, 1000, 500, List.of(sliding()), null,
                CensorEffects.Effect.PIXELATE, 40_000L);
        // Start, middle, end, both keyframes and halfway between them.
        assertEquals(6, frames.asked);
    }

    @Test
    public void aMovingAreaCoveredOnAShiftedGrid_fails() {
        VideoCensorLayout.Track track = VideoCensorLayout.tracks(List.of(sliding()), CensorEffects.Effect.PIXELATE,
                1000, 500, VideoCensorExporter.frameMarginUs(40_000L), new Random(0)).get(0);
        MovingFrames frames = new MovingFrames(track, track.at(0L).block / 2);
        assertThrows(CleanFailedException.class, () -> CensorVerifier.requireVideoAreasCovered(frames, 1000, 500,
                List.of(sliding()), null, CensorEffects.Effect.PIXELATE, 40_000L));
    }

    @Test
    public void aMovingArea_withoutTheVideosFrameTimes_failsClosed() {
        VideoCensorLayout.Track track = VideoCensorLayout.tracks(List.of(sliding()), CensorEffects.Effect.PIXELATE,
                1000, 500, VideoCensorExporter.frameMarginUs(40_000L), new Random(0)).get(0);
        MovingFrames frames = new MovingFrames(track, 0);
        frames.noFrameTimes = true;
        assertThrows(CleanFailedException.class, () -> CensorVerifier.requireVideoAreasCovered(frames, 1000, 500,
                List.of(sliding()), null, CensorEffects.Effect.PIXELATE, 40_000L));
    }

    @Test
    public void sampleTimes_ofAMovingArea_addItsKeyframesAndTheirMiddles() {
        assertArrayEquals(new long[] {0L, 1_000_000L, 1_980_000L, 2_000_000L, 3_000_000L, 3_960_000L},
                CensorVerifier.sampleTimes(sliding(), 4_000_000L, 40_000L));
    }

    @Test
    public void nearest_picksTheClosestFrame() {
        long[] times = {0L, 40L, 80L};
        assertEquals(0L, CensorVerifier.nearest(times, -5L));
        assertEquals(40L, CensorVerifier.nearest(times, 55L));
        assertEquals(80L, CensorVerifier.nearest(times, 70L));
        assertEquals(80L, CensorVerifier.nearest(times, 500L));
        assertEquals(40L, CensorVerifier.nearest(times, 40L));
        assertEquals(0L, CensorVerifier.nearest(times, 20L));
    }
}
