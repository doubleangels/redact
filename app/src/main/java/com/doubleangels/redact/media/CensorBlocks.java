package com.doubleangels.redact.media;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * The block and noise rules every censor effect follows, for photos and videos alike: how large
 * the blocks are that Pixelate and Blur keep one colour for, and how much random noise each
 * block's colour gets. Kept in one place so a video frame is covered exactly as firmly as a photo.
 *
 * <p>Plain Java with no Android types, so the GPU effect for video can use it too.
 */
public final class CensorBlocks {

    /** Blocks per longer side of the image: large, so faces and text cannot be read. */
    static final float BLOCKS_PER_SIDE = 48f;
    /** The smallest block, in pixels, however small the image. */
    static final int MIN_BLOCK = 8;
    /**
     * Blocks across the shorter side of a covered area, at most. Large text in a close-up stays
     * readable on the image-wide grid alone; at this size a letter that fills the area is only a
     * few blocks tall.
     */
    public static final int MAX_BLOCKS_PER_AREA = 6;
    /** Random change to each block's colour, per channel, of up to this much either way. */
    public static final int NOISE = 10;

    private CensorBlocks() {
    }

    /** The block size for a whole image of {@code width} x {@code height} pixels. */
    public static int imageBlock(int width, int height) {
        return Math.max(MIN_BLOCK, Math.round(Math.max(width, height) / BLOCKS_PER_SIDE));
    }

    /**
     * The block size for one covered area of {@code areaWidth} x {@code areaHeight} pixels: the
     * image's own, or larger when the area is big enough that the image's blocks would leave more
     * than {@link #MAX_BLOCKS_PER_AREA} across its shorter side.
     */
    public static int areaBlock(int imageBlock, float areaWidth, float areaHeight) {
        float shorter = Math.min(areaWidth, areaHeight);
        return Math.max(imageBlock, (int) Math.ceil(shorter / MAX_BLOCKS_PER_AREA));
    }

    /** One colour channel moved by up to {@link #NOISE} either way, kept within 0 to 255. */
    public static int noisy(int channel, @NonNull Random random) {
        int value = channel + random.nextInt(2 * NOISE + 1) - NOISE;
        return Math.max(0, Math.min(255, value));
    }
}
