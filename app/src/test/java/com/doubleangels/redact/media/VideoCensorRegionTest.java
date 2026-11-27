package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class VideoCensorRegionTest {

    private static final CensorRegion RECT = CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.3f);
    /** 30 frames a second. */
    private static final long FRAME = 33_333L;

    @Test
    public void of_ordersTheTimes_andKeepsThemFromGoingNegative() {
        VideoCensorRegion region = VideoCensorRegion.of(RECT, 5_000_000L, 2_000_000L);
        assertEquals(2_000_000L, region.startUs());
        assertEquals(5_000_000L, region.endUs());

        VideoCensorRegion early = VideoCensorRegion.of(RECT, -10L, 100L);
        assertEquals(0L, early.startUs());
    }

    @Test
    public void of_refusesABrushStroke() {
        CensorRegion stroke = CensorRegion.stroke(new float[] {0.1f, 0.1f, 0.5f, 0.5f}, 0.05f);
        assertThrows(IllegalArgumentException.class, () -> VideoCensorRegion.of(stroke, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> VideoCensorRegion.wholeVideo(stroke));
    }

    @Test
    public void wholeVideo_coversEveryFrame() {
        VideoCensorRegion region = VideoCensorRegion.wholeVideo(RECT);
        assertTrue(region.coversWholeVideo());
        assertTrue(region.coversFrame(0L, FRAME));
        assertTrue(region.coversFrame(3_600_000_000L, FRAME));
        assertTrue("no overflow at the very end", region.coversFrame(Long.MAX_VALUE - 1, FRAME));
        assertFalse(VideoCensorRegion.of(RECT, 0L, 10L).coversWholeVideo());
        assertFalse(VideoCensorRegion.of(RECT, 1L, VideoCensorRegion.END_OF_VIDEO).coversWholeVideo());
    }

    @Test
    public void coversFrame_includesAFrameThatStartsBeforeTheRangeButIsStillShowing() {
        VideoCensorRegion region = VideoCensorRegion.of(RECT, 1_000_000L, 2_000_000L);
        // Shown from 0.98 s to 1.013 s: on screen when the range starts.
        assertTrue(region.coversFrame(980_000L, FRAME));
        // Gone by the time the range starts.
        assertFalse(region.coversFrame(1_000_000L - FRAME, FRAME));
    }

    @Test
    public void coversFrame_includesBothEnds() {
        VideoCensorRegion region = VideoCensorRegion.of(RECT, 1_000_000L, 2_000_000L);
        assertTrue(region.coversFrame(1_000_000L, FRAME));
        assertTrue(region.coversFrame(2_000_000L, FRAME));
        assertFalse(region.coversFrame(2_000_001L, FRAME));
    }

    @Test
    public void coversFrame_countsAFrameAsAtLeastOneMicrosecond() {
        VideoCensorRegion region = VideoCensorRegion.of(RECT, 500L, 500L);
        assertTrue(region.coversFrame(500L, 0L));
        assertFalse(region.coversFrame(499L, 0L));
    }

    @Test
    public void roundedOutward_widensToWholeFrames() {
        VideoCensorRegion region = VideoCensorRegion.of(RECT, 50_000L, 90_000L).roundedOutward(FRAME);
        assertEquals(33_333L, region.startUs());
        assertEquals(99_998L, region.endUs());
        // Already on frame boundaries: the start stays, the end runs to its frame's last microsecond.
        VideoCensorRegion exact = VideoCensorRegion.of(RECT, 0L, 66_666L).roundedOutward(FRAME);
        assertEquals(0L, exact.startUs());
        assertEquals(99_998L, exact.endUs());
    }

    @Test
    public void roundedOutward_neverNarrows() {
        for (long start = 0; start < 400_000L; start += 7_919L) {
            VideoCensorRegion region = VideoCensorRegion.of(RECT, start, start + 123_457L);
            VideoCensorRegion rounded = region.roundedOutward(FRAME);
            assertTrue(rounded.startUs() <= region.startUs());
            assertTrue(rounded.endUs() >= region.endUs());
        }
    }

    @Test
    public void roundedOutward_keepsTheEndOfTheVideo_andIgnoresAnUnknownFrameRate() {
        VideoCensorRegion whole = VideoCensorRegion.wholeVideo(RECT);
        assertSame(whole, whole.roundedOutward(FRAME));
        VideoCensorRegion region = VideoCensorRegion.of(RECT, 5L, 10L);
        assertSame(region, region.roundedOutward(0L));
        VideoCensorRegion aligned = VideoCensorRegion.of(RECT, 0L, 9L);
        assertSame(aligned, aligned.roundedOutward(10L));
    }

    @Test
    public void with_changesOnlyWhatItSays() {
        VideoCensorRegion region = VideoCensorRegion.of(RECT, 10L, 20L);
        CensorRegion other = CensorRegion.rect(0.5f, 0.5f, 0.9f, 0.9f);
        VideoCensorRegion moved = region.withArea(other);
        assertEquals(other, moved.area());
        assertEquals(10L, moved.startUs());
        assertEquals(20L, moved.endUs());
        assertSame(region, region.withArea(CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.3f)));

        VideoCensorRegion retimed = region.withTimes(30L, 40L);
        assertEquals(RECT, retimed.area());
        assertEquals(30L, retimed.startUs());
        assertEquals(40L, retimed.endUs());
    }

    @Test
    public void equality_coversTheAreaAndTimes() {
        VideoCensorRegion a = VideoCensorRegion.of(RECT, 10L, 20L);
        VideoCensorRegion b = VideoCensorRegion.of(CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.3f), 20L, 10L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, VideoCensorRegion.of(RECT, 10L, 21L));
        assertNotEquals(a, VideoCensorRegion.of(RECT, 11L, 20L));
        assertNotEquals(a, VideoCensorRegion.of(RECT.withEffect(CensorEffects.Effect.BLUR), 10L, 20L));
        assertNotEquals(a, "not a region");
    }
}
