package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
public class VideoCensorEffectTest {

    private static final CensorRegion RECT = CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.4f);

    @Test
    public void isNoOp_onlyWithNothingToCover() {
        assertTrue(new VideoCensorEffect(List.of(), CensorEffects.Effect.BLACK, 33_333L).isNoOp(640, 480));
        VideoCensorRegion empty = VideoCensorRegion.wholeVideo(CensorRegion.rect(0.5f, 0.1f, 0.5f, 0.9f));
        assertTrue(new VideoCensorEffect(List.of(empty), CensorEffects.Effect.BLACK, 33_333L).isNoOp(640, 480));

        VideoCensorRegion later = VideoCensorRegion.of(RECT, 5_000_000L, 6_000_000L);
        assertFalse("an area that only starts later still needs the effect",
                new VideoCensorEffect(List.of(empty, later), CensorEffects.Effect.BLACK, 33_333L).isNoOp(640, 480));
    }

    @Test
    public void frameDuration_fallsBackWhenUnknown() {
        assertEquals(VideoCensorEffect.DEFAULT_FRAME_DURATION_US,
                new VideoCensorEffect(List.of(), CensorEffects.Effect.BLUR, 0L).frameDurationUs());
        assertEquals(VideoCensorEffect.DEFAULT_FRAME_DURATION_US,
                new VideoCensorEffect(List.of(), CensorEffects.Effect.BLUR, -5L).frameDurationUs());
        assertEquals(16_667L,
                new VideoCensorEffect(List.of(), CensorEffects.Effect.BLUR, 16_667L).frameDurationUs());
    }

    @Test
    public void keepsItsOwnCopyOfTheRegions() {
        List<VideoCensorRegion> regions = new ArrayList<>(List.of(VideoCensorRegion.wholeVideo(RECT)));
        VideoCensorEffect effect = new VideoCensorEffect(regions, CensorEffects.Effect.PIXELATE, 33_333L);
        regions.clear();
        assertEquals(1, effect.regions().size());
        assertEquals(CensorEffects.Effect.PIXELATE, effect.defaultEffect());
        assertThrows(UnsupportedOperationException.class, () -> effect.regions().clear());
    }

    @Test
    public void covers_followsEachAreasTimeRange() {
        VideoCensorRegion first = VideoCensorRegion.of(RECT, 0L, 1_000_000L);
        VideoCensorRegion second = VideoCensorRegion.of(
                CensorRegion.rect(0.5f, 0.5f, 0.9f, 0.9f), 2_000_000L, 3_000_000L);
        VideoCensorEffect effect = new VideoCensorEffect(
                List.of(first, second), CensorEffects.Effect.BLUR, 33_333L, new Random(1));
        List<VideoCensorLayout.Area> areas = effect.layOut(1280, 720);
        assertEquals(2, areas.size());

        assertTrue(effect.covers(areas.get(0), 500_000L));
        assertFalse(effect.covers(areas.get(1), 500_000L));
        // Neither covers the gap between them.
        assertFalse(effect.covers(areas.get(0), 1_500_000L));
        assertFalse(effect.covers(areas.get(1), 1_500_000L));
        // The frame on screen when the second range starts is covered.
        assertTrue(effect.covers(areas.get(1), 1_980_000L));
        assertTrue(effect.covers(areas.get(1), 3_000_000L));
        assertFalse(effect.covers(areas.get(1), 3_000_001L));
    }

    @Test
    public void layOut_drawsFreshNoiseEachTime() {
        VideoCensorEffect effect = new VideoCensorEffect(
                List.of(VideoCensorRegion.wholeVideo(RECT)), CensorEffects.Effect.PIXELATE, 33_333L, new Random(2));
        int[] once = effect.layOut(1280, 720).get(0).noise();
        int[] again = effect.layOut(1280, 720).get(0).noise();
        assertFalse(java.util.Arrays.equals(once, again));
    }

    @Test
    public void defaultConstructor_usesSecureNoise() {
        VideoCensorEffect effect = new VideoCensorEffect(
                List.of(VideoCensorRegion.wholeVideo(RECT)), CensorEffects.Effect.PIXELATE, 33_333L);
        assertEquals(1, effect.layOut(1280, 720).size());
    }
}
