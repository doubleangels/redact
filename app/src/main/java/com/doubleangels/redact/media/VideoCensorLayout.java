package com.doubleangels.redact.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Where each video area lands on a frame of a given size, and the blocks and noise it is covered
 * with: the plain-Java half of {@link VideoCensorEffect}, kept apart from the GL calls so it can be
 * tested off the device.
 *
 * <p>Blocks follow {@link CensorEffects} exactly: their size comes from {@link CensorBlocks}, they
 * sit on one grid for the whole frame (so overlapping areas share it), and every area is widened to
 * whole blocks before its block colours are taken, so each colour comes from a full block. Each
 * area gets one set of noise for the whole video, so its blocks do not flicker from frame to frame
 * and the noise cannot be averaged away across frames.
 */
public final class VideoCensorLayout {

    private VideoCensorLayout() {
    }

    /** One area laid out on a frame. Pixel coordinates count from the frame's top left. */
    public static final class Area {
        /** The position of this area's region in the list it was laid out from. */
        public final int regionIndex;
        @NonNull
        public final CensorEffects.Effect effect;
        /** Block size in pixels; unused for Black. */
        public final int block;
        /** The area widened to whole blocks on the frame's grid, kept inside the frame. */
        public final int gridLeft;
        public final int gridTop;
        public final int gridRight;
        public final int gridBottom;
        public final int cols;
        public final int rows;
        /** The pixels actually covered: the region's own rectangle. */
        public final float drawLeft;
        public final float drawTop;
        public final float drawRight;
        public final float drawBottom;
        /**
         * Per block, row by row, the red, green and blue noise offsets, each from {@code -NOISE}
         * to {@code NOISE}; null for Black.
         */
        @Nullable
        private final int[] noise;

        Area(int regionIndex, @NonNull CensorEffects.Effect effect, int block,
             int gridLeft, int gridTop, int gridRight, int gridBottom,
             float drawLeft, float drawTop, float drawRight, float drawBottom, @Nullable int[] noise) {
            this.regionIndex = regionIndex;
            this.effect = effect;
            this.block = block;
            this.gridLeft = gridLeft;
            this.gridTop = gridTop;
            this.gridRight = gridRight;
            this.gridBottom = gridBottom;
            this.cols = effect == CensorEffects.Effect.BLACK ? 0 : ceilDiv(gridRight - gridLeft, block);
            this.rows = effect == CensorEffects.Effect.BLACK ? 0 : ceilDiv(gridBottom - gridTop, block);
            this.drawLeft = drawLeft;
            this.drawTop = drawTop;
            this.drawRight = drawRight;
            this.drawBottom = drawBottom;
            this.noise = noise;
        }

        /** The noise offsets, or an empty array for Black. */
        @NonNull
        public int[] noise() {
            return noise == null ? new int[0] : noise.clone();
        }

        /**
         * The noise as RGBA bytes, one pixel per block in the same order, each channel holding its
         * offset plus {@code NOISE} (so 0 to {@code 2 * NOISE}), ready to upload as a texture.
         */
        @NonNull
        public byte[] noiseRgba() {
            if (noise == null) {
                return new byte[0];
            }
            byte[] rgba = new byte[cols * rows * 4];
            for (int i = 0; i < cols * rows; i++) {
                rgba[i * 4] = (byte) (noise[i * 3] + CensorBlocks.NOISE);
                rgba[i * 4 + 1] = (byte) (noise[i * 3 + 1] + CensorBlocks.NOISE);
                rgba[i * 4 + 2] = (byte) (noise[i * 3 + 2] + CensorBlocks.NOISE);
                rgba[i * 4 + 3] = (byte) 0xff;
            }
            return rgba;
        }

        /**
         * The covered rectangle as a triangle strip in normalized device coordinates (x, y, z, w
         * per corner: bottom left, bottom right, top left, top right), on a frame of
         * {@code width} x {@code height}. OpenGL's y runs upward, so the frame's top is +1.
         */
        @NonNull
        public float[] drawQuad(int width, int height) {
            float left = 2f * drawLeft / width - 1f;
            float right = 2f * drawRight / width - 1f;
            float top = 1f - 2f * drawTop / height;
            float bottom = 1f - 2f * drawBottom / height;
            return new float[] {
                    left, bottom, 0f, 1f,
                    right, bottom, 0f, 1f,
                    left, top, 0f, 1f,
                    right, top, 0f, 1f,
            };
        }
    }

    /**
     * Lays out every non-empty region on a {@code width} x {@code height} frame, in order, each
     * with its own effect or {@code defaultEffect}. Noise comes from {@code random}.
     */
    @NonNull
    public static List<Area> layOut(@NonNull List<VideoCensorRegion> regions,
                                    @NonNull CensorEffects.Effect defaultEffect,
                                    int width, int height, @NonNull Random random) {
        if (width <= 0 || height <= 0) {
            return Collections.emptyList();
        }
        int imageBlock = CensorBlocks.imageBlock(width, height);
        List<Area> areas = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            CensorRegion region = regions.get(i).area();
            if (region.isEmpty()) {
                continue;
            }
            float[] p = region.points();
            float left = p[0] * width;
            float top = p[1] * height;
            float right = p[2] * width;
            float bottom = p[3] * height;
            CensorEffects.Effect effect = region.effect() != null ? region.effect() : defaultEffect;
            if (effect == CensorEffects.Effect.BLACK) {
                areas.add(new Area(i, effect, 0, (int) Math.floor(left), (int) Math.floor(top),
                        (int) Math.ceil(right), (int) Math.ceil(bottom), left, top, right, bottom, null));
                continue;
            }
            int block = CensorBlocks.areaBlock(imageBlock, right - left, bottom - top);
            int gridLeft = Math.max(0, Math.floorDiv((int) Math.floor(left), block) * block);
            int gridTop = Math.max(0, Math.floorDiv((int) Math.floor(top), block) * block);
            int gridRight = Math.min(width, ceilTo((int) Math.ceil(right), block));
            int gridBottom = Math.min(height, ceilTo((int) Math.ceil(bottom), block));
            if (gridRight <= gridLeft || gridBottom <= gridTop) {
                continue;
            }
            int blocks = ceilDiv(gridRight - gridLeft, block) * ceilDiv(gridBottom - gridTop, block);
            int[] noise = new int[blocks * 3];
            for (int n = 0; n < noise.length; n++) {
                noise[n] = CensorBlocks.noiseOffset(random);
            }
            areas.add(new Area(i, effect, block, gridLeft, gridTop, gridRight, gridBottom,
                    left, top, right, bottom, noise));
        }
        return Collections.unmodifiableList(areas);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int ceilTo(int value, int multiple) {
        return Math.floorDiv(value + multiple - 1, multiple) * multiple;
    }
}
