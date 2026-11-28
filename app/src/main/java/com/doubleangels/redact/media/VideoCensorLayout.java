package com.doubleangels.redact.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Where each video area lands on a frame of a given size, and the blocks and noise it is covered
 * with: the plain-Java half of {@link VideoCensorEffect}, kept apart from the GL calls so it can be
 * tested off the device.
 *
 * <p>An area that stays put follows {@link CensorEffects} exactly: block sizes from
 * {@link CensorBlocks}, one grid for the whole frame (so overlapping areas share it), and the area
 * widened to whole blocks before its colours are taken. Each area gets one set of noise for the
 * whole video, so its blocks do not flicker and the noise cannot be averaged away across frames.
 *
 * <p>A moving area ({@link VideoCensorRegion#moves()}) is placed again for every frame. Its block
 * grid moves with it rather than staying on the frame's grid: a grid fixed to the frame would
 * sample whatever the area covers at a slightly different offset in every frame, and enough of
 * those samples together could be combined to recover detail no single frame shows. Its block size
 * is set once from its largest rectangle and its noise drawn once for its largest grid, so each
 * block keeps its noise however the area moves. It is padded by the furthest it travels while one
 * frame shows, so nothing it passes over is left uncovered between keyframes.
 */
public final class VideoCensorLayout {

    private VideoCensorLayout() {
    }

    /** One area laid out on one frame. Pixel coordinates count from the frame's top left. */
    public static final class Area {
        /** The position of this area's region in the list it was laid out from. */
        public final int regionIndex;
        @NonNull
        public final CensorEffects.Effect effect;
        /** Block size in pixels; unused for Black. */
        public final int block;
        /**
         * The block grid: its first block's top left, which may lie off the frame for a moving
         * area, and its right and bottom edges, kept inside the frame.
         */
        public final int gridLeft;
        public final int gridTop;
        public final int gridRight;
        public final int gridBottom;
        /** The blocks this frame uses, counted from the grid's top left. */
        public final int cols;
        public final int rows;
        /**
         * The block and noise textures' size, in blocks: {@link #cols} by {@link #rows} for an area
         * that stays put, and the largest grid a moving area ever needs.
         */
        public final int textureCols;
        public final int textureRows;
        /** The pixels actually covered: the region's own rectangle, padded for a moving one. */
        public final float drawLeft;
        public final float drawTop;
        public final float drawRight;
        public final float drawBottom;
        /**
         * Per block of the texture, row by row, the red, green and blue noise offsets, each from
         * {@code -NOISE} to {@code NOISE}; null for Black.
         */
        @Nullable
        private final int[] noise;

        Area(int regionIndex, @NonNull CensorEffects.Effect effect, int block,
             int gridLeft, int gridTop, int gridRight, int gridBottom,
             float drawLeft, float drawTop, float drawRight, float drawBottom, @Nullable int[] noise) {
            this(regionIndex, effect, block, gridLeft, gridTop, gridRight, gridBottom, -1, -1,
                    drawLeft, drawTop, drawRight, drawBottom, noise);
        }

        Area(int regionIndex, @NonNull CensorEffects.Effect effect, int block,
             int gridLeft, int gridTop, int gridRight, int gridBottom, int textureCols, int textureRows,
             float drawLeft, float drawTop, float drawRight, float drawBottom, @Nullable int[] noise) {
            this.regionIndex = regionIndex;
            this.effect = effect;
            this.block = block;
            this.gridLeft = gridLeft;
            this.gridTop = gridTop;
            this.gridRight = gridRight;
            this.gridBottom = gridBottom;
            boolean black = effect == CensorEffects.Effect.BLACK;
            this.cols = black ? 0 : Math.max(0, ceilDiv(gridRight - gridLeft, block));
            this.rows = black ? 0 : Math.max(0, ceilDiv(gridBottom - gridTop, block));
            this.textureCols = black ? 0 : textureCols >= 0 ? textureCols : cols;
            this.textureRows = black ? 0 : textureRows >= 0 ? textureRows : rows;
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
         * The noise as RGBA bytes, one pixel per texture block in the same order, each channel
         * holding its offset plus {@code NOISE} (so 0 to {@code 2 * NOISE}), ready to upload.
         */
        @NonNull
        public byte[] noiseRgba() {
            if (noise == null) {
                return new byte[0];
            }
            int blocks = textureCols * textureRows;
            byte[] rgba = new byte[blocks * 4];
            for (int i = 0; i < blocks; i++) {
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

    /** One area for the whole video: where it is on each frame. */
    public static final class Track {
        public final int regionIndex;
        @NonNull
        public final CensorEffects.Effect effect;
        /** Set for an area that stays put: the same placement on every frame. */
        @Nullable
        private final Area fixed;
        // A moving area's placement rules.
        @Nullable
        private final VideoCensorRegion region;
        private final int width;
        private final int height;
        private final int block;
        private final float padX;
        private final float padY;
        private final int textureCols;
        private final int textureRows;
        @Nullable
        private final int[] noise;

        private Track(@NonNull Area fixed) {
            this.regionIndex = fixed.regionIndex;
            this.effect = fixed.effect;
            this.fixed = fixed;
            this.region = null;
            this.width = 0;
            this.height = 0;
            this.block = fixed.block;
            this.padX = 0f;
            this.padY = 0f;
            this.textureCols = fixed.textureCols;
            this.textureRows = fixed.textureRows;
            this.noise = null;
        }

        private Track(int regionIndex, @NonNull CensorEffects.Effect effect, @NonNull VideoCensorRegion region,
                      int width, int height, int block, float padX, float padY,
                      int textureCols, int textureRows, @Nullable int[] noise) {
            this.regionIndex = regionIndex;
            this.effect = effect;
            this.fixed = null;
            this.region = region;
            this.width = width;
            this.height = height;
            this.block = block;
            this.padX = padX;
            this.padY = padY;
            this.textureCols = textureCols;
            this.textureRows = textureRows;
            this.noise = noise;
        }

        /** Whether the area moves, so it is placed again for every frame. */
        public boolean moves() {
            return fixed == null;
        }

        /** The block and noise textures' size, in blocks, which every frame's placement fits. */
        public int textureCols() {
            return textureCols;
        }

        public int textureRows() {
            return textureRows;
        }

        /** The noise as RGBA bytes for the texture; see {@link Area#noiseRgba()}. */
        @NonNull
        public byte[] noiseRgba() {
            return at(0L).noiseRgba();
        }

        /** Where the area is on the frame at {@code presentationTimeUs}. */
        @NonNull
        public Area at(long presentationTimeUs) {
            if (fixed != null) {
                return fixed;
            }
            float[] p = region.areaAt(presentationTimeUs).points();
            float left = p[0] * width - padX;
            float top = p[1] * height - padY;
            float right = p[2] * width + padX;
            float bottom = p[3] * height + padY;
            // The grid starts at the padded area's own corner, so it moves with the area.
            int gridLeft = (int) Math.floor(left);
            int gridTop = (int) Math.floor(top);
            int gridRight = Math.min(width, Math.min(gridLeft + textureCols * block, (int) Math.ceil(right)));
            int gridBottom = Math.min(height, Math.min(gridTop + textureRows * block, (int) Math.ceil(bottom)));
            return new Area(regionIndex, effect, block, gridLeft, gridTop, gridRight, gridBottom,
                    textureCols, textureRows,
                    Math.max(0f, left), Math.max(0f, top), Math.min(width, right), Math.min(height, bottom),
                    noise);
        }
    }

    /**
     * Lays out every non-empty region that stays put on a {@code width} x {@code height} frame, in
     * order, each with its own effect or {@code defaultEffect}, at the start of the video. Noise
     * comes from {@code random}.
     */
    @VisibleForTesting
    @NonNull
    public static List<Area> layOut(@NonNull List<VideoCensorRegion> regions,
                                    @NonNull CensorEffects.Effect defaultEffect,
                                    int width, int height, @NonNull Random random) {
        List<Area> areas = new ArrayList<>();
        for (Track track : tracks(regions, defaultEffect, width, height, 0L, random)) {
            areas.add(track.at(0L));
        }
        return Collections.unmodifiableList(areas);
    }

    /**
     * One track for every non-empty region, in order, on a {@code width} x {@code height} frame.
     * A moving region is padded by how far it travels in {@code frameDurationUs}.
     */
    @NonNull
    public static List<Track> tracks(@NonNull List<VideoCensorRegion> regions,
                                     @NonNull CensorEffects.Effect defaultEffect,
                                     int width, int height, long frameDurationUs, @NonNull Random random) {
        if (width <= 0 || height <= 0) {
            return Collections.emptyList();
        }
        int imageBlock = CensorBlocks.imageBlock(width, height);
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            VideoCensorRegion video = regions.get(i);
            CensorRegion region = video.area();
            CensorEffects.Effect effect = region.effect() != null ? region.effect() : defaultEffect;
            if (video.moves()) {
                tracks.add(movingTrack(i, video, effect, width, height, frameDurationUs, imageBlock, random));
                continue;
            }
            if (region.isEmpty()) {
                continue;
            }
            Area area = fixedArea(i, region, effect, width, height, imageBlock, random);
            if (area != null) {
                tracks.add(new Track(area));
            }
        }
        return Collections.unmodifiableList(tracks);
    }

    @Nullable
    private static Area fixedArea(int index, @NonNull CensorRegion region, @NonNull CensorEffects.Effect effect,
                                  int width, int height, int imageBlock, @NonNull Random random) {
        float[] p = region.points();
        float left = p[0] * width;
        float top = p[1] * height;
        float right = p[2] * width;
        float bottom = p[3] * height;
        if (effect == CensorEffects.Effect.BLACK) {
            return new Area(index, effect, 0, (int) Math.floor(left), (int) Math.floor(top),
                    (int) Math.ceil(right), (int) Math.ceil(bottom), left, top, right, bottom, null);
        }
        int block = CensorBlocks.areaBlock(imageBlock, right - left, bottom - top);
        int gridLeft = Math.max(0, Math.floorDiv((int) Math.floor(left), block) * block);
        int gridTop = Math.max(0, Math.floorDiv((int) Math.floor(top), block) * block);
        int gridRight = Math.min(width, ceilTo((int) Math.ceil(right), block));
        int gridBottom = Math.min(height, ceilTo((int) Math.ceil(bottom), block));
        if (gridRight <= gridLeft || gridBottom <= gridTop) {
            return null;
        }
        int blocks = ceilDiv(gridRight - gridLeft, block) * ceilDiv(gridBottom - gridTop, block);
        return new Area(index, effect, block, gridLeft, gridTop, gridRight, gridBottom,
                left, top, right, bottom, noise(blocks, random));
    }

    @NonNull
    private static Track movingTrack(int index, @NonNull VideoCensorRegion video, @NonNull CensorEffects.Effect effect,
                                     int width, int height, long frameDurationUs, int imageBlock,
                                     @NonNull Random random) {
        double[] speed = video.maxSpeed();
        long window = Math.max(1L, frameDurationUs);
        // A pixel more than the travel, for the rounding of the frame's own pixels.
        float padX = (float) (speed[0] * window * width) + 1f;
        float padY = (float) (speed[1] * window * height) + 1f;
        float maxWidth = 0f;
        float maxHeight = 0f;
        for (VideoCensorRegion.Keyframe keyframe : video.keyframes()) {
            float[] p = keyframe.area().points();
            maxWidth = Math.max(maxWidth, (p[2] - p[0]) * width + 2 * padX);
            maxHeight = Math.max(maxHeight, (p[3] - p[1]) * height + 2 * padY);
        }
        if (effect == CensorEffects.Effect.BLACK) {
            return new Track(index, effect, video, width, height, 0, padX, padY, 0, 0, null);
        }
        int block = CensorBlocks.areaBlock(imageBlock, maxWidth, maxHeight);
        // One more block each way than the largest rectangle needs, as the grid's corner lands on
        // a whole pixel while the area does not.
        int cols = ceilDiv((int) Math.ceil(maxWidth), block) + 1;
        int rows = ceilDiv((int) Math.ceil(maxHeight), block) + 1;
        return new Track(index, effect, video, width, height, block, padX, padY, cols, rows,
                noise(cols * rows, random));
    }

    @NonNull
    private static int[] noise(int blocks, @NonNull Random random) {
        int[] noise = new int[blocks * 3];
        for (int n = 0; n < noise.length; n++) {
            noise[n] = CensorBlocks.noiseOffset(random);
        }
        return noise;
    }

    private static int ceilDiv(int value, int divisor) {
        return divisor <= 0 ? 0 : (value + divisor - 1) / divisor;
    }

    private static int ceilTo(int value, int multiple) {
        return Math.floorDiv(value + multiple - 1, multiple) * multiple;
    }
}
