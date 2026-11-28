package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
public class VideoCensorLayoutTest {

    private static VideoCensorRegion whole(float l, float t, float r, float b) {
        return VideoCensorRegion.wholeVideo(CensorRegion.rect(l, t, r, b));
    }

    @Test
    public void layOut_usesThePhotoBlockSizes() {
        // 1920 x 1080: 40 px blocks across the frame; a small area keeps them.
        List<VideoCensorLayout.Area> areas = VideoCensorLayout.layOut(
                List.of(whole(0.1f, 0.1f, 0.2f, 0.2f)), CensorEffects.Effect.PIXELATE, 1920, 1080, new Random(1));
        assertEquals(1, areas.size());
        assertEquals(CensorBlocks.imageBlock(1920, 1080), areas.get(0).block);
        assertEquals(40, areas.get(0).block);
    }

    @Test
    public void layOut_growsBlocksForALargeArea() {
        // Most of a 4K frame: at most six blocks across the shorter side.
        VideoCensorLayout.Area area = VideoCensorLayout.layOut(
                List.of(whole(0f, 0f, 1f, 1f)), CensorEffects.Effect.BLUR, 3840, 2160, new Random(1)).get(0);
        assertEquals(360, area.block);
        assertTrue(area.rows <= CensorBlocks.MAX_BLOCKS_PER_AREA);
    }

    @Test
    public void layOut_widensToWholeBlocksOnTheFrameGrid() {
        // 1920 x 1080, 40 px blocks. The area runs from (240, 135) to (600, 303.75).
        VideoCensorLayout.Area area = VideoCensorLayout.layOut(
                List.of(whole(0.125f, 0.125f, 0.3125f, 0.28125f)),
                CensorEffects.Effect.PIXELATE, 1920, 1080, new Random(1)).get(0);
        assertEquals(40, area.block);
        assertEquals(240, area.gridLeft);
        assertEquals(120, area.gridTop);
        assertEquals(600, area.gridRight);
        assertEquals(320, area.gridBottom);
        assertEquals(9, area.cols);
        assertEquals(5, area.rows);
        // Only the area itself is drawn.
        assertEquals(240f, area.drawLeft, 0.01f);
        assertEquals(135f, area.drawTop, 0.01f);
        assertEquals(600f, area.drawRight, 0.01f);
        assertEquals(303.75f, area.drawBottom, 0.01f);
    }

    @Test
    public void layOut_keepsTheGridInsideTheFrame() {
        // 1000 x 700: 21 px blocks, so the grid's last row and column end at the frame's edge.
        VideoCensorLayout.Area area = VideoCensorLayout.layOut(
                List.of(whole(0.9f, 0.9f, 1f, 1f)), CensorEffects.Effect.PIXELATE, 1000, 700, new Random(1)).get(0);
        assertEquals(21, area.block);
        assertEquals(1000, area.gridRight);
        assertEquals(700, area.gridBottom);
        assertEquals(882, area.gridLeft);
        assertEquals(6, area.cols);
    }

    @Test
    public void layOut_givesEachBlockNoiseWithinTheRange() {
        VideoCensorLayout.Area area = VideoCensorLayout.layOut(
                List.of(whole(0f, 0f, 0.5f, 0.5f)), CensorEffects.Effect.PIXELATE, 640, 480, new Random(2)).get(0);
        int[] noise = area.noise();
        assertEquals(area.cols * area.rows * 3, noise.length);
        boolean anyMoved = false;
        for (int offset : noise) {
            assertTrue(Math.abs(offset) <= CensorBlocks.NOISE);
            anyMoved |= offset != 0;
        }
        assertTrue(anyMoved);

        byte[] rgba = area.noiseRgba();
        assertEquals(area.cols * area.rows * 4, rgba.length);
        for (int i = 0; i < area.cols * area.rows; i++) {
            assertEquals(noise[i * 3] + CensorBlocks.NOISE, rgba[i * 4] & 0xff);
            assertEquals(noise[i * 3 + 1] + CensorBlocks.NOISE, rgba[i * 4 + 1] & 0xff);
            assertEquals(noise[i * 3 + 2] + CensorBlocks.NOISE, rgba[i * 4 + 2] & 0xff);
            assertEquals(0xff, rgba[i * 4 + 3] & 0xff);
        }
    }

    @Test
    public void layOut_noiseDiffersBetweenAreas() {
        List<VideoCensorLayout.Area> areas = VideoCensorLayout.layOut(
                List.of(whole(0f, 0f, 0.3f, 0.3f), whole(0f, 0f, 0.3f, 0.3f)),
                CensorEffects.Effect.PIXELATE, 640, 480, new Random(3));
        assertTrue(!java.util.Arrays.equals(areas.get(0).noise(), areas.get(1).noise()));
    }

    @Test
    public void layOut_blackNeedsNoBlocksOrNoise() {
        VideoCensorLayout.Area area = VideoCensorLayout.layOut(
                List.of(whole(0.1f, 0.1f, 0.3f, 0.3f)), CensorEffects.Effect.BLACK, 640, 480, new Random(4)).get(0);
        assertEquals(CensorEffects.Effect.BLACK, area.effect);
        assertEquals(0, area.cols);
        assertEquals(0, area.rows);
        assertEquals(0, area.noise().length);
        assertEquals(0, area.noiseRgba().length);
    }

    @Test
    public void layOut_takesEachRegionsOwnEffect_andSkipsEmptyOnes() {
        List<VideoCensorRegion> regions = List.of(
                whole(0.1f, 0.1f, 0.3f, 0.3f),
                whole(0.5f, 0.5f, 0.5f, 0.9f),
                VideoCensorRegion.wholeVideo(
                        CensorRegion.rect(0.5f, 0.5f, 0.9f, 0.9f).withEffect(CensorEffects.Effect.BLACK)));
        List<VideoCensorLayout.Area> areas = VideoCensorLayout.layOut(
                regions, CensorEffects.Effect.BLUR, 640, 480, new Random(5));
        assertEquals(2, areas.size());
        assertEquals(CensorEffects.Effect.BLUR, areas.get(0).effect);
        assertEquals(0, areas.get(0).regionIndex);
        assertEquals(CensorEffects.Effect.BLACK, areas.get(1).effect);
        assertEquals(2, areas.get(1).regionIndex);
    }

    @Test
    public void layOut_ofAnEmptyFrame_isEmpty() {
        assertTrue(VideoCensorLayout.layOut(List.of(whole(0f, 0f, 1f, 1f)),
                CensorEffects.Effect.PIXELATE, 0, 480, new Random(6)).isEmpty());
        assertTrue(VideoCensorLayout.layOut(List.of(whole(0f, 0f, 1f, 1f)),
                CensorEffects.Effect.PIXELATE, 640, 0, new Random(6)).isEmpty());
    }

    @Test
    public void drawQuad_mapsTheAreaToDeviceCoordinates_withYUp() {
        VideoCensorLayout.Area area = VideoCensorLayout.layOut(
                List.of(whole(0.25f, 0.25f, 0.75f, 0.5f)), CensorEffects.Effect.BLACK, 400, 200, new Random(7)).get(0);
        assertArrayEquals(new float[] {
                -0.5f, 0f, 0f, 1f,
                0.5f, 0f, 0f, 1f,
                -0.5f, 0.5f, 0f, 1f,
                0.5f, 0.5f, 0f, 1f,
        }, area.drawQuad(400, 200), 1e-5f);
    }

    @Test
    public void drawQuad_ofTheWholeFrame_isTheWholeViewport() {
        VideoCensorLayout.Area area = VideoCensorLayout.layOut(
                List.of(whole(0f, 0f, 1f, 1f)), CensorEffects.Effect.PIXELATE, 1080, 1920, new Random(8)).get(0);
        assertArrayEquals(new float[] {
                -1f, -1f, 0f, 1f,
                1f, -1f, 0f, 1f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 0f, 1f,
        }, area.drawQuad(1080, 1920), 1e-5f);
    }
}
