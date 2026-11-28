package com.doubleangels.redact.media;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

/**
 * Covers fixed areas of a video, each for its own time range, with Black, Pixelate or Blur, as
 * firmly as {@link CensorEffects} covers a photo: Pixelate and Blur keep one colour per block (an
 * average of the block plus noise), so no detail finer than a block survives.
 *
 * <p>Frames must reach this effect upright, as {@link VideoCensorRegion} areas are fractions of
 * the upright frame. Areas are drawn in order, each on top of the ones before.
 */
@OptIn(markerClass = UnstableApi.class)
public final class VideoCensorEffect implements GlEffect {

    /**
     * How long a frame is assumed to stay on screen when the real frame rate is unknown: 10 frames
     * a second, longer than almost any video's frames, so a range is never cut short.
     */
    public static final long DEFAULT_FRAME_DURATION_US = 100_000L;

    @NonNull
    private final List<VideoCensorRegion> regions;
    @NonNull
    private final CensorEffects.Effect defaultEffect;
    private final long frameDurationUs;
    @NonNull
    private final Random random;

    /**
     * Covers {@code regions}, each with its own effect or {@code defaultEffect}. A frame counts as
     * on screen for {@code frameDurationUs} after its presentation time when checking whether a
     * range covers it; pass the longest frame duration the video may have.
     */
    public VideoCensorEffect(@NonNull List<VideoCensorRegion> regions,
                             @NonNull CensorEffects.Effect defaultEffect, long frameDurationUs) {
        this(regions, defaultEffect, frameDurationUs, new SecureRandom());
    }

    @VisibleForTesting
    VideoCensorEffect(@NonNull List<VideoCensorRegion> regions, @NonNull CensorEffects.Effect defaultEffect,
                      long frameDurationUs, @NonNull Random random) {
        this.regions = List.copyOf(regions);
        this.defaultEffect = defaultEffect;
        this.frameDurationUs = frameDurationUs > 0L ? frameDurationUs : DEFAULT_FRAME_DURATION_US;
        this.random = random;
    }

    @NonNull
    public List<VideoCensorRegion> regions() {
        return regions;
    }

    @NonNull
    public CensorEffects.Effect defaultEffect() {
        return defaultEffect;
    }

    public long frameDurationUs() {
        return frameDurationUs;
    }

    @NonNull
    @Override
    public GlShaderProgram toGlShaderProgram(@NonNull Context context, boolean useHdr)
            throws VideoFrameProcessingException {
        return new VideoCensorShaderProgram(this, useHdr);
    }

    /** No-op only when there is nothing to cover; any area makes every frame go through it. */
    @Override
    public boolean isNoOp(int inputWidth, int inputHeight) {
        for (VideoCensorRegion region : regions) {
            if (region.moves() || !region.area().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Lays out the areas for frames of {@code width} x {@code height}, with fresh noise. */
    @NonNull
    List<VideoCensorLayout.Area> layOut(int width, int height) {
        return VideoCensorLayout.layOut(regions, defaultEffect, width, height, random);
    }

    /**
     * Tracks every area over frames of {@code width} x {@code height}, with fresh noise; a moving
     * area is padded by how far it travels while a frame shows.
     */
    @NonNull
    List<VideoCensorLayout.Track> tracks(int width, int height) {
        return VideoCensorLayout.tracks(regions, defaultEffect, width, height, frameDurationUs, random);
    }

    /** Whether {@code area} covers the frame at {@code presentationTimeUs}. */
    boolean covers(@NonNull VideoCensorLayout.Area area, long presentationTimeUs) {
        return covers(area.regionIndex, presentationTimeUs);
    }

    /** Whether the area at {@code regionIndex} covers the frame at {@code presentationTimeUs}. */
    boolean covers(int regionIndex, long presentationTimeUs) {
        return regions.get(regionIndex).coversFrame(presentationTimeUs, frameDurationUs);
    }
}
