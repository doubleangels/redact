package com.doubleangels.redact.media;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * One area covered in a video: a {@link CensorRegion} rectangle, in fractions of the upright
 * frame, and the time range it covers, in microseconds of presentation time.
 *
 * <p>Times are inclusive and checked outward, never inward: a frame is covered whenever any part
 * of the time it is on screen overlaps the range, so the frames at either end of a range are never
 * left uncovered by rounding.
 */
public final class VideoCensorRegion {

    /** The end time of an area that lasts to the end of the video. */
    public static final long END_OF_VIDEO = Long.MAX_VALUE;

    @NonNull
    private final CensorRegion area;
    private final long startUs;
    private final long endUs;

    private VideoCensorRegion(@NonNull CensorRegion area, long startUs, long endUs) {
        this.area = area;
        this.startUs = startUs;
        this.endUs = endUs;
    }

    /**
     * {@code area} covered from {@code startUs} to {@code endUs}, both inclusive, in either order.
     * Only rectangles are allowed: a video has no brush tool.
     */
    @NonNull
    public static VideoCensorRegion of(@NonNull CensorRegion area, long startUs, long endUs) {
        if (area.shape() != CensorRegion.Shape.RECT) {
            throw new IllegalArgumentException("A video area must be a rectangle");
        }
        long start = Math.max(0L, Math.min(startUs, endUs));
        long end = Math.max(0L, Math.max(startUs, endUs));
        return new VideoCensorRegion(area, start, end);
    }

    /** {@code area} covered for the whole video. */
    @NonNull
    public static VideoCensorRegion wholeVideo(@NonNull CensorRegion area) {
        return of(area, 0L, END_OF_VIDEO);
    }

    @NonNull
    public CensorRegion area() {
        return area;
    }

    public long startUs() {
        return startUs;
    }

    public long endUs() {
        return endUs;
    }

    public boolean coversWholeVideo() {
        return startUs == 0L && endUs == END_OF_VIDEO;
    }

    /** The same time range over {@code newArea}. */
    @NonNull
    public VideoCensorRegion withArea(@NonNull CensorRegion newArea) {
        return newArea.equals(area) ? this : of(newArea, startUs, endUs);
    }

    /** The same area over a new time range. */
    @NonNull
    public VideoCensorRegion withTimes(long newStartUs, long newEndUs) {
        return of(area, newStartUs, newEndUs);
    }

    /**
     * Whether the frame shown from {@code presentationTimeUs} for up to {@code frameDurationUs}
     * must be covered: whether any of that time falls in this area's range.
     */
    public boolean coversFrame(long presentationTimeUs, long frameDurationUs) {
        long frameEnd = saturatedAdd(presentationTimeUs, Math.max(1L, frameDurationUs));
        return presentationTimeUs <= endUs && frameEnd > startUs;
    }

    /**
     * This range widened to whole frames of {@code frameDurationUs}: the start moved back to the
     * start of its frame and the end forward to the last microsecond of its frame. The end of the
     * video stays the end of the video.
     */
    @NonNull
    public VideoCensorRegion roundedOutward(long frameDurationUs) {
        if (frameDurationUs <= 0L) {
            return this;
        }
        long start = Math.floorDiv(startUs, frameDurationUs) * frameDurationUs;
        long end = endUs == END_OF_VIDEO ? END_OF_VIDEO
                : saturatedAdd(Math.floorDiv(endUs, frameDurationUs) * frameDurationUs,
                        frameDurationUs - 1L);
        return start == startUs && end == endUs ? this : new VideoCensorRegion(area, start, end);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? END_OF_VIDEO : sum;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VideoCensorRegion other)) {
            return false;
        }
        return startUs == other.startUs && endUs == other.endUs && area.equals(other.area);
    }

    @Override
    public int hashCode() {
        return Objects.hash(area, startUs, endUs);
    }
}
