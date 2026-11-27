package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class CensorBlocksTest {

    @Test
    public void imageBlock_scalesWithTheImage() {
        assertEquals(8, CensorBlocks.imageBlock(100, 50));
        assertEquals(83, CensorBlocks.imageBlock(4000, 3000));
        // Portrait and landscape get the same blocks.
        assertEquals(CensorBlocks.imageBlock(1920, 1080), CensorBlocks.imageBlock(1080, 1920));
        // A 4K video frame.
        assertEquals(80, CensorBlocks.imageBlock(3840, 2160));
    }

    @Test
    public void imageBlock_neverFallsBelowTheMinimum() {
        assertEquals(CensorBlocks.MIN_BLOCK, CensorBlocks.imageBlock(1, 1));
        assertEquals(CensorBlocks.MIN_BLOCK, CensorBlocks.imageBlock(0, 0));
    }

    @Test
    public void areaBlock_growsWithALargeArea() {
        // A small area keeps the image's blocks.
        assertEquals(83, CensorBlocks.areaBlock(83, 400, 300));
        // A close-up number plate filling most of a 4000x3000 photo: at most six blocks tall.
        assertEquals(400, CensorBlocks.areaBlock(83, 3600, 2400));
        // A thin brush stroke keeps the image's blocks, however long it is.
        assertEquals(83, CensorBlocks.areaBlock(83, 4000, 120));
    }

    @Test
    public void areaBlock_leavesAtMostTheMaximumAcrossTheShorterSide() {
        for (int side = 1; side <= 5000; side += 37) {
            int block = CensorBlocks.areaBlock(CensorBlocks.MIN_BLOCK, side, side * 2f);
            int across = (int) Math.ceil(side / (double) block);
            assertTrue(side + " px is " + across + " blocks across",
                    across <= CensorBlocks.MAX_BLOCKS_PER_AREA);
        }
    }

    @Test
    public void noisy_staysWithinTheNoiseEitherWay() {
        Random random = new Random(3);
        Set<Integer> offsets = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            int offset = CensorBlocks.noisy(128, random) - 128;
            assertTrue("offset " + offset, Math.abs(offset) <= CensorBlocks.NOISE);
            offsets.add(offset);
        }
        assertEquals("every offset in the range turns up", 2 * CensorBlocks.NOISE + 1, offsets.size());
    }

    @Test
    public void noisy_keepsEachChannelInRange() {
        Random random = new Random(4);
        for (int i = 0; i < 1000; i++) {
            int dark = CensorBlocks.noisy(0, random);
            int light = CensorBlocks.noisy(255, random);
            assertTrue(dark >= 0 && dark <= CensorBlocks.NOISE);
            assertTrue(light <= 255 && light >= 255 - CensorBlocks.NOISE);
        }
    }

    @Test
    public void noisy_takesOneRandomDrawPerChannel() {
        // Photo output depends on the draw order, so each call must take exactly one value.
        Random a = new Random(11);
        Random b = new Random(11);
        CensorBlocks.noisy(100, a);
        b.nextInt(2 * CensorBlocks.NOISE + 1);
        assertEquals(b.nextInt(), a.nextInt());
    }
}
