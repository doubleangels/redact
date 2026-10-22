package com.doubleangels.redact.media;

import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * One area the user covered in the censor editor: a rectangle or a brush stroke. Coordinates are
 * fractions of the image (x of its width, y of its height), so the same region lands on the same
 * pixels in the small preview and in the full-size saved photo.
 */
public final class CensorRegion {

    public enum Shape { RECT, STROKE }

    @NonNull
    private final Shape shape;
    /** RECT: left, top, right, bottom. STROKE: x, y pairs along the stroke. */
    @NonNull
    private final float[] points;
    /** Brush width as a fraction of the image's longer side; unused for RECT. */
    private final float strokeWidth;

    private CensorRegion(@NonNull Shape shape, @NonNull float[] points, float strokeWidth) {
        this.shape = shape;
        this.points = points;
        this.strokeWidth = strokeWidth;
    }

    /** A rectangle between two corners, in either order, kept inside the image. */
    @NonNull
    public static CensorRegion rect(float x1, float y1, float x2, float y2) {
        return new CensorRegion(Shape.RECT, new float[] {
                clamp(Math.min(x1, x2)), clamp(Math.min(y1, y2)),
                clamp(Math.max(x1, x2)), clamp(Math.max(y1, y2))}, 0f);
    }

    /** A brush stroke through {@code xyPairs}, {@code width} a fraction of the longer side. */
    @NonNull
    public static CensorRegion stroke(@NonNull float[] xyPairs, float width) {
        if (xyPairs.length < 2 || xyPairs.length % 2 != 0) {
            throw new IllegalArgumentException("A stroke needs at least one x, y pair");
        }
        return new CensorRegion(Shape.STROKE, xyPairs.clone(), width);
    }

    @NonNull
    public Shape shape() {
        return shape;
    }

    @NonNull
    public float[] points() {
        return points.clone();
    }

    public float strokeWidth() {
        return strokeWidth;
    }

    /** Whether the region covers no pixels at all, such as a rectangle with no width. */
    public boolean isEmpty() {
        return shape == Shape.RECT && (points[2] <= points[0] || points[3] <= points[1]);
    }

    /** The covered area as a filled outline on an image of {@code width} x {@code height} pixels. */
    @NonNull
    public Path toPath(int width, int height) {
        Path path = new Path();
        if (shape == Shape.RECT) {
            path.addRect(points[0] * width, points[1] * height,
                    points[2] * width, points[3] * height, Path.Direction.CW);
            return path;
        }
        float strokePx = Math.max(1f, strokeWidth * Math.max(width, height));
        if (points.length == 2) {
            // A tap: one dot the size of the brush.
            path.addCircle(points[0] * width, points[1] * height, strokePx / 2f, Path.Direction.CW);
            return path;
        }
        Path line = new Path();
        line.moveTo(points[0] * width, points[1] * height);
        for (int i = 2; i < points.length; i += 2) {
            line.lineTo(points[i] * width, points[i + 1] * height);
        }
        Paint brush = new Paint();
        brush.setStyle(Paint.Style.STROKE);
        brush.setStrokeWidth(strokePx);
        brush.setStrokeCap(Paint.Cap.ROUND);
        brush.setStrokeJoin(Paint.Join.ROUND);
        brush.getFillPath(line, path);
        return path;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CensorRegion other)) {
            return false;
        }
        return shape == other.shape
                && Float.compare(strokeWidth, other.strokeWidth) == 0
                && Arrays.equals(points, other.points);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * shape.hashCode() + Arrays.hashCode(points)) + Float.hashCode(strokeWidth);
    }
}
