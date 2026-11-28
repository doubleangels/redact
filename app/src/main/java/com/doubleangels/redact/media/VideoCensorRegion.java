package com.doubleangels.redact.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One area covered in a video: a {@link CensorRegion} rectangle, in fractions of the upright
 * frame, and the time range it covers, in microseconds of presentation time.
 *
 * <p>Times are inclusive and checked outward, never inward: a frame is covered whenever any part
 * of the time it is on screen overlaps the range, so the frames at either end of a range are never
 * left uncovered by rounding.
 *
 * <p>An area can move: given two or more {@link Keyframe}s, its rectangle at any moment slides in a
 * straight line from one keyframe's to the next, and holds the first or last one before or after
 * them. With fewer, it stays where it is.
 */
public final class VideoCensorRegion {

    /** The end time of an area that lasts to the end of the video. */
    public static final long END_OF_VIDEO = Long.MAX_VALUE;

    /** Where a moving area is at {@code timeUs}: a rectangle in fractions of the upright frame. */
    public record Keyframe(long timeUs, @NonNull CensorRegion area) {
    }

    @NonNull
    private final CensorRegion area;
    private final long startUs;
    private final long endUs;
    /** In time order, one per time, each with the area's effect; empty for an area that stays put. */
    @NonNull
    private final List<Keyframe> keyframes;

    private VideoCensorRegion(@NonNull CensorRegion area, long startUs, long endUs,
                              @NonNull List<Keyframe> keyframes) {
        this.area = area;
        this.startUs = startUs;
        this.endUs = endUs;
        this.keyframes = keyframes;
    }

    /**
     * {@code area} covered from {@code startUs} to {@code endUs}, both inclusive, in either order.
     * Only rectangles are allowed: a video has no brush tool.
     */
    @NonNull
    public static VideoCensorRegion of(@NonNull CensorRegion area, long startUs, long endUs) {
        requireRect(area);
        long start = Math.max(0L, Math.min(startUs, endUs));
        long end = Math.max(0L, Math.max(startUs, endUs));
        return new VideoCensorRegion(area, start, end, List.of());
    }

    /** {@code area} covered for the whole video. */
    @NonNull
    public static VideoCensorRegion wholeVideo(@NonNull CensorRegion area) {
        return of(area, 0L, END_OF_VIDEO);
    }

    private static void requireRect(@NonNull CensorRegion area) {
        if (area.shape() != CensorRegion.Shape.RECT) {
            throw new IllegalArgumentException("A video area must be a rectangle");
        }
    }

    /**
     * The same area moving through {@code newKeyframes}, in any order (a later one at the same time
     * wins), each taking this area's effect. Two or more make it move; fewer leave it in place at
     * its own rectangle or, with one, at that keyframe's.
     */
    @NonNull
    public VideoCensorRegion withKeyframes(@NonNull List<Keyframe> newKeyframes) {
        TreeMap<Long, CensorRegion> byTime = new TreeMap<>();
        for (Keyframe keyframe : newKeyframes) {
            requireRect(keyframe.area());
            byTime.put(keyframe.timeUs(), keyframe.area().withEffect(area.effect()));
        }
        if (byTime.size() < 2) {
            CensorRegion only = byTime.isEmpty() ? area : byTime.firstEntry().getValue();
            return new VideoCensorRegion(only, startUs, endUs, List.of());
        }
        List<Keyframe> sorted = new ArrayList<>(byTime.size());
        byTime.forEach((time, rect) -> sorted.add(new Keyframe(time, rect)));
        return new VideoCensorRegion(sorted.get(0).area(), startUs, endUs, Collections.unmodifiableList(sorted));
    }

    /** The rectangle, or a moving area's first keyframe's. */
    @NonNull
    public CensorRegion area() {
        return area;
    }

    /** The keyframes a moving area slides through, in time order; empty when it stays put. */
    @NonNull
    public List<Keyframe> keyframes() {
        return keyframes;
    }

    /** Whether the area moves: whether it has two or more keyframes. */
    public boolean moves() {
        return !keyframes.isEmpty();
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

    /** The same time range over {@code newArea}, staying put there. */
    @NonNull
    public VideoCensorRegion withArea(@NonNull CensorRegion newArea) {
        return newArea.equals(area) && !moves() ? this : of(newArea, startUs, endUs);
    }

    /** The same area, moving the same way, over a new time range. */
    @NonNull
    public VideoCensorRegion withTimes(long newStartUs, long newEndUs) {
        return of(area, newStartUs, newEndUs).withKeyframes(keyframes);
    }

    /**
     * Where the area is at {@code timeUs}: its rectangle, or for a moving area the straight-line
     * slide between the keyframes either side, with the area's effect.
     */
    @NonNull
    public CensorRegion areaAt(long timeUs) {
        if (!moves()) {
            return area;
        }
        Keyframe first = keyframes.get(0);
        if (timeUs <= first.timeUs()) {
            return first.area();
        }
        for (int i = 1; i < keyframes.size(); i++) {
            Keyframe next = keyframes.get(i);
            if (timeUs <= next.timeUs()) {
                Keyframe previous = keyframes.get(i - 1);
                float t = (float) ((double) (timeUs - previous.timeUs()) / (next.timeUs() - previous.timeUs()));
                return lerp(previous.area(), next.area(), t);
            }
        }
        return keyframes.get(keyframes.size() - 1).area();
    }

    private CensorRegion lerp(@NonNull CensorRegion from, @NonNull CensorRegion to, float t) {
        float[] a = from.points();
        float[] b = to.points();
        return CensorRegion.rect(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t).withEffect(area.effect());
    }

    /**
     * The fastest any edge of a moving area travels, as {x, y} fractions of the frame per
     * microsecond; {0, 0} for an area that stays put. Padding the area by this times a frame's
     * length keeps it covering everything it passes over while that frame shows.
     */
    @NonNull
    public double[] maxSpeed() {
        double x = 0;
        double y = 0;
        for (int i = 1; i < keyframes.size(); i++) {
            float[] a = keyframes.get(i - 1).area().points();
            float[] b = keyframes.get(i).area().points();
            double dt = keyframes.get(i).timeUs() - keyframes.get(i - 1).timeUs();
            x = Math.max(x, Math.max(Math.abs(b[0] - a[0]), Math.abs(b[2] - a[2])) / dt);
            y = Math.max(y, Math.max(Math.abs(b[1] - a[1]), Math.abs(b[3] - a[3])) / dt);
        }
        return new double[] {x, y};
    }

    /** The keyframe at {@code timeUs}, give or take {@code toleranceUs}, or null. */
    @Nullable
    public Keyframe keyframeNear(long timeUs, long toleranceUs) {
        for (Keyframe keyframe : keyframes) {
            if (Math.abs(keyframe.timeUs() - timeUs) <= toleranceUs) {
                return keyframe;
            }
        }
        return null;
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
        return start == startUs && end == endUs ? this : new VideoCensorRegion(area, start, end, keyframes);
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
        return startUs == other.startUs && endUs == other.endUs && area.equals(other.area)
                && keyframes.equals(other.keyframes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(area, startUs, endUs, keyframes);
    }
}
