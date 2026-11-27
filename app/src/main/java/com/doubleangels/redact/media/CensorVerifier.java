package com.doubleangels.redact.media;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.sentry.SentryManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Proves that a censored video really covers its areas, by decoding frames of the saved file and
 * looking at them, so a broken effect fails the save instead of passing uncovered detail along.
 *
 * <p>For each area, three frames from its time range are checked (its start, middle and end).
 * Black must be dark throughout. Pixelate must be one colour within each block. For Blur, the
 * colour at each block centre inside the area is read, the area is rebuilt from those colours
 * alone the way the effect blends them, and every pixel must match the rebuild. Both allow for
 * what encoding changes, and nothing more: detail finer than a block cannot pass, so an area left
 * uncovered, or covered in the wrong place, fails.
 *
 * <p>The check fails closed: a frame that can't be decoded or lined up, or an area with no block
 * large enough to measure, fails it too.
 */
public final class CensorVerifier {

    /** Pixels trimmed from each side of an area, where encoding blends it with what is next to it. */
    @VisibleForTesting
    static final int EDGE_INSET = 3;
    /** Pixels from a block's edges left out, where encoding softens the step between blocks. */
    @VisibleForTesting
    static final float BLOCK_MARGIN = 2f;
    /** The brightest a Black area may be anywhere, in luma (0 to 255), after encoding. */
    @VisibleForTesting
    static final int BLACK_LIMIT = 48;
    /** How far, on average over a block, the frame may be from the rebuild, in luma. */
    @VisibleForTesting
    static final double ERROR_LIMIT = 12.0;
    /** The fewest pixels a block needs for its average to count. */
    private static final int MIN_BLOCK_SAMPLES = 4;

    private CensorVerifier() {
    }

    /** The saved video's upright frames. */
    interface FrameSource extends AutoCloseable {
        /** The video's length, in microseconds; 0 when unknown. */
        long durationUs();

        /** The average time between frames, in microseconds; 0 when unknown. */
        long frameIntervalUs();

        /** The upright frame nearest {@code timeUs}, or null when there is none. */
        @Nullable
        Bitmap frameAt(long timeUs);

        @Override
        void close();
    }

    /**
     * Fails unless every area of {@code regions} is covered in {@code video}.
     *
     * @param sourceWidth  the upright width of the source's frames, which the effect saw
     * @param sourceHeight the upright height of the source's frames
     * @param crop         the part of the source frame kept, in fractions; null for all of it
     * @param frameIntervalUs the source's time between frames, used when the video's is unknown
     * @throws IOException when an area is not covered, or it can't be shown that it is
     */
    public static void requireVideoAreasCovered(
            @NonNull File video, int sourceWidth, int sourceHeight,
            @NonNull List<VideoCensorRegion> regions, @Nullable CensorRegion crop,
            @NonNull CensorEffects.Effect defaultEffect, long frameIntervalUs) throws IOException {
        try (FrameSource frames = RetrieverFrames.open(video)) {
            requireVideoAreasCovered(frames, sourceWidth, sourceHeight, regions, crop, defaultEffect,
                    frameIntervalUs);
        }
    }

    @VisibleForTesting
    static void requireVideoAreasCovered(
            @NonNull FrameSource frames, int sourceWidth, int sourceHeight,
            @NonNull List<VideoCensorRegion> regions, @Nullable CensorRegion crop,
            @NonNull CensorEffects.Effect defaultEffect, long frameIntervalUs) throws IOException {
        // Noise doesn't matter here: only where the blocks are.
        List<VideoCensorLayout.Area> areas = VideoCensorLayout.layOut(
                regions, defaultEffect, sourceWidth, sourceHeight, new Random(0));
        long duration = frames.durationUs();
        long interval = frames.frameIntervalUs() > 0 ? frames.frameIntervalUs() : frameIntervalUs;
        if (duration <= 0 || interval <= 0) {
            throw fail("the saved video's length or frame rate can't be read");
        }
        // Later areas are left out wherever the effect may have drawn them, which is a little
        // longer than their ranges.
        long margin = VideoCensorExporter.frameMarginUs(interval);
        for (int i = 0; i < areas.size(); i++) {
            VideoCensorLayout.Area area = areas.get(i);
            for (long time : sampleTimes(regions.get(area.regionIndex), duration, interval)) {
                Bitmap frame = frames.frameAt(time);
                if (frame == null) {
                    throw fail("a frame of the saved video can't be decoded");
                }
                try {
                    int width = frame.getWidth();
                    int height = frame.getHeight();
                    int[] pixels = new int[width * height];
                    frame.getPixels(pixels, 0, width, 0, 0, width, height);
                    Mapping mapping = Mapping.of(sourceWidth, sourceHeight, crop, width, height);
                    List<float[]> above = new ArrayList<>();
                    for (int j = i + 1; j < areas.size(); j++) {
                        VideoCensorLayout.Area later = areas.get(j);
                        if (regions.get(later.regionIndex).coversFrame(time - margin, 2 * margin)) {
                            above.add(mapping.rect(later.drawLeft, later.drawTop, later.drawRight, later.drawBottom));
                        }
                    }
                    if (!isCovered(luma(pixels), width, height, mapping, area, above)) {
                        throw fail("an area is not covered in the saved video");
                    }
                } finally {
                    frame.recycle();
                }
            }
        }
    }

    /**
     * The failure, with {@code why} (always one of the fixed reasons above, never anything about
     * the file) in the breadcrumb only: the exception's own message stays one plain literal.
     */
    private static IOException fail(@NonNull String why) {
        SentryManager.logEvent("verify", "The censored video failed its coverage check: " + why + ".");
        return new CleanFailedException("Censor check failed");
    }

    /**
     * The times to check {@code region} at: its start, middle and end, kept where the nearest frame
     * is one the effect covers. A frame is covered while it is on screen in the range, so the end
     * is checked a frame early, where the nearest frame cannot be the first one after the range.
     */
    @VisibleForTesting
    @NonNull
    static long[] sampleTimes(@NonNull VideoCensorRegion region, long durationUs, long frameIntervalUs) {
        long lastFrame = Math.max(0L, durationUs - frameIntervalUs);
        long start = region.startUs();
        if (start > lastFrame) {
            return new long[0];
        }
        long end = Math.max(start, Math.min(region.endUs(), lastFrame + frameIntervalUs) - frameIntervalUs);
        long middle = start + (end - start) / 2;
        if (end == start) {
            return new long[] {start};
        }
        return middle == start || middle == end ? new long[] {start, end} : new long[] {start, middle, end};
    }

    /** Where the source frame's pixels land in a decoded frame, after the crop and any downscale. */
    @VisibleForTesting
    static final class Mapping {
        final float offsetX;
        final float offsetY;
        final float scaleX;
        final float scaleY;

        private Mapping(float offsetX, float offsetY, float scaleX, float scaleY) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }

        /**
         * @throws IOException when the frame's shape doesn't match the cropped source: it was
         *     decoded on its side, or isn't this video, so nothing in it can be lined up
         */
        @NonNull
        static Mapping of(int sourceWidth, int sourceHeight, @Nullable CensorRegion crop,
                          int frameWidth, int frameHeight) throws IOException {
            float[] c = crop != null ? crop.points() : new float[] {0f, 0f, 1f, 1f};
            float left = c[0] * sourceWidth;
            float top = c[1] * sourceHeight;
            float width = (c[2] - c[0]) * sourceWidth;
            float height = (c[3] - c[1]) * sourceHeight;
            if (width <= 0f || height <= 0f || frameWidth <= 0 || frameHeight <= 0) {
                throw fail("the crop or the saved frame is empty");
            }
            float expected = width / height;
            float actual = (float) frameWidth / frameHeight;
            // Sizes are rounded to even numbers, and odd crops by a pixel or two.
            if (Math.abs(actual - expected) / expected > 0.05f) {
                throw fail("the saved frame's shape doesn't match the source");
            }
            return new Mapping(left, top, frameWidth / width, frameHeight / height);
        }

        float x(float sourceX) {
            return (sourceX - offsetX) * scaleX;
        }

        float y(float sourceY) {
            return (sourceY - offsetY) * scaleY;
        }

        /** A source rectangle in frame pixels, as {left, top, right, bottom}. */
        @NonNull
        float[] rect(float left, float top, float right, float bottom) {
            return new float[] {x(left), y(top), x(right), y(bottom)};
        }
    }

    /** Each pixel's luma (BT.601), 0 to 255. */
    @VisibleForTesting
    @NonNull
    static int[] luma(@NonNull int[] argb) {
        int[] luma = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            luma[i] = (299 * ((c >> 16) & 0xff) + 587 * ((c >> 8) & 0xff) + 114 * (c & 0xff) + 500) / 1000;
        }
        return luma;
    }

    /**
     * Whether {@code area} is covered in a frame's {@code luma}, leaving out pixels inside any of
     * {@code above} (later areas drawn over it, as frame rectangles).
     */
    @VisibleForTesting
    static boolean isCovered(@NonNull int[] luma, int width, int height, @NonNull Mapping mapping,
                             @NonNull VideoCensorLayout.Area area, @NonNull List<float[]> above) {
        float[] draw = mapping.rect(area.drawLeft, area.drawTop, area.drawRight, area.drawBottom);
        int x0 = Math.max(0, (int) Math.ceil(draw[0]) + EDGE_INSET);
        int y0 = Math.max(0, (int) Math.ceil(draw[1]) + EDGE_INSET);
        int x1 = Math.min(width, (int) Math.floor(draw[2]) - EDGE_INSET);
        int y1 = Math.min(height, (int) Math.floor(draw[3]) - EDGE_INSET);
        if (x1 <= x0 || y1 <= y0) {
            // Cropped away, or too thin to hold anything readable.
            return true;
        }
        if (area.effect == CensorEffects.Effect.BLACK) {
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    if (luma[y * width + x] > BLACK_LIMIT && !inAny(above, x, y)) {
                        return false;
                    }
                }
            }
            return true;
        }
        float blockX = area.block * mapping.scaleX;
        float blockY = area.block * mapping.scaleY;
        float gridX = mapping.x(area.gridLeft);
        float gridY = mapping.y(area.gridTop);
        int cols = area.cols;
        int rows = area.rows;
        boolean blur = area.effect == CensorEffects.Effect.BLUR;
        int[] bounds = {x0, y0, x1, y1};
        // Blur's colours are read where it is drawn exactly: at the block centres inside the area.
        // Pixelate's are each block's own average, so its check is that every block is one colour;
        // a widened block's centre can lie outside the area, where the original still shows.
        double[] colours = blur
                ? blockCentres(luma, width, bounds, gridX, gridY, blockX, blockY, cols, rows, above)
                : blockMeans(luma, width, bounds, gridX, gridY, blockX, blockY, cols, rows, above);
        double[] errorSum = new double[cols * rows];
        int[] samples = new int[cols * rows];
        for (int y = y0; y < y1; y++) {
            float fy = (y + 0.5f - gridY) / blockY;
            int row = clamp((int) Math.floor(fy), rows - 1);
            if (Math.min(fy - row, row + 1 - fy) * blockY < BLOCK_MARGIN) {
                continue;
            }
            for (int x = x0; x < x1; x++) {
                float fx = (x + 0.5f - gridX) / blockX;
                int col = clamp((int) Math.floor(fx), cols - 1);
                if (Math.min(fx - col, col + 1 - fx) * blockX < BLOCK_MARGIN || inAny(above, x, y)) {
                    continue;
                }
                double expected = blur ? blend(colours, cols, rows, fx, fy) : colours[row * cols + col];
                if (Double.isNaN(expected)) {
                    continue;
                }
                errorSum[row * cols + col] += Math.abs(luma[y * width + x] - expected);
                samples[row * cols + col]++;
            }
        }
        boolean measured = false;
        for (int i = 0; i < errorSum.length; i++) {
            if (samples[i] < MIN_BLOCK_SAMPLES) {
                continue;
            }
            measured = true;
            if (errorSum[i] / samples[i] > ERROR_LIMIT) {
                return false;
            }
        }
        return measured;
    }

    /**
     * Each block's average luma over its pixels inside {@code bounds} (left, top, right, bottom),
     * away from its edges and outside later areas, row by row; NaN for a block with none.
     */
    @NonNull
    private static double[] blockMeans(@NonNull int[] luma, int width, @NonNull int[] bounds,
                                       float gridX, float gridY, float blockX, float blockY,
                                       int cols, int rows, @NonNull List<float[]> above) {
        double[] sum = new double[cols * rows];
        int[] count = new int[cols * rows];
        for (int y = bounds[1]; y < bounds[3]; y++) {
            float fy = (y + 0.5f - gridY) / blockY;
            int row = clamp((int) Math.floor(fy), rows - 1);
            if (Math.min(fy - row, row + 1 - fy) * blockY < BLOCK_MARGIN) {
                continue;
            }
            for (int x = bounds[0]; x < bounds[2]; x++) {
                float fx = (x + 0.5f - gridX) / blockX;
                int col = clamp((int) Math.floor(fx), cols - 1);
                if (Math.min(fx - col, col + 1 - fx) * blockX < BLOCK_MARGIN || inAny(above, x, y)) {
                    continue;
                }
                sum[row * cols + col] += luma[y * width + x];
                count[row * cols + col]++;
            }
        }
        double[] means = new double[cols * rows];
        for (int i = 0; i < means.length; i++) {
            means[i] = count[i] > 0 ? sum[i] / count[i] : Double.NaN;
        }
        return means;
    }

    /**
     * Each block's luma at its centre (a 3 x 3 average), row by row; NaN where that centre is not
     * inside {@code bounds} (left, top, right, bottom) or is under a later area.
     */
    @NonNull
    private static double[] blockCentres(@NonNull int[] luma, int width, @NonNull int[] bounds,
                                         float gridX, float gridY, float blockX, float blockY,
                                         int cols, int rows, @NonNull List<float[]> above) {
        double[] colours = new double[cols * rows];
        for (int row = 0; row < rows; row++) {
            int cy = (int) Math.floor(gridY + (row + 0.5f) * blockY);
            for (int col = 0; col < cols; col++) {
                int cx = (int) Math.floor(gridX + (col + 0.5f) * blockX);
                if (cx - 1 < bounds[0] || cy - 1 < bounds[1] || cx + 1 >= bounds[2] || cy + 1 >= bounds[3]
                        || inAny(above, cx, cy)) {
                    colours[row * cols + col] = Double.NaN;
                    continue;
                }
                int sum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        sum += luma[(cy + dy) * width + cx + dx];
                    }
                }
                colours[row * cols + col] = sum / 9.0;
            }
        }
        return colours;
    }

    /** Blur's blend of the four nearest block centres, as {@link CensorEffects} draws it. */
    private static double blend(@NonNull double[] colours, int cols, int rows, float fx, float fy) {
        float gx = Math.max(0f, Math.min(cols - 1f, fx - 0.5f));
        float gy = Math.max(0f, Math.min(rows - 1f, fy - 0.5f));
        int c0 = (int) gx;
        int r0 = (int) gy;
        int c1 = Math.min(cols - 1, c0 + 1);
        int r1 = Math.min(rows - 1, r0 + 1);
        double tx = smooth(gx - c0);
        double ty = smooth(gy - r0);
        double upper = mix(colours[r0 * cols + c0], colours[r0 * cols + c1], tx);
        double lower = mix(colours[r1 * cols + c0], colours[r1 * cols + c1], tx);
        return mix(upper, lower, ty);
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static boolean inAny(@NonNull List<float[]> rects, int x, int y) {
        float px = x + 0.5f;
        float py = y + 0.5f;
        for (float[] r : rects) {
            if (px >= r[0] - EDGE_INSET && px < r[2] + EDGE_INSET
                    && py >= r[1] - EDGE_INSET && py < r[3] + EDGE_INSET) {
                return true;
            }
        }
        return false;
    }

    /** Frames from {@link MediaMetadataRetriever}, which turns them upright. */
    @VisibleForTesting
    static final class RetrieverFrames implements FrameSource {
        private final MediaMetadataRetriever retriever;
        private final long durationUs;
        private final long frameIntervalUs;

        private RetrieverFrames(@NonNull MediaMetadataRetriever retriever) {
            this.retriever = retriever;
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            long frames = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            this.durationUs = durationMs * 1000L;
            this.frameIntervalUs = frames > 0 && durationUs > 0 ? durationUs / frames : 0L;
        }

        @NonNull
        static RetrieverFrames open(@NonNull File video) throws IOException {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(video.getAbsolutePath());
            } catch (RuntimeException e) {
                closeQuietly(retriever);
                throw new CleanFailedException("Censor check failed: the saved video can't be read", e);
            }
            return new RetrieverFrames(retriever);
        }

        @Override
        public long durationUs() {
            return durationUs;
        }

        @Override
        public long frameIntervalUs() {
            return frameIntervalUs;
        }

        @Nullable
        @Override
        public Bitmap frameAt(long timeUs) {
            try {
                return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public void close() {
            closeQuietly(retriever);
        }

        private static void closeQuietly(@NonNull MediaMetadataRetriever retriever) {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Nothing more can be done with it.
            }
        }

        private static long parseLong(@Nullable String value) {
            if (value == null) {
                return 0L;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }
}
