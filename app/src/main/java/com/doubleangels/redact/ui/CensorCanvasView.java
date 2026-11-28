package com.doubleangels.redact.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.doubleangels.redact.R;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorRegion;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Shows the censor preview and lets the user draw over it: one finger draws a rectangle or a
 * brush stroke, and two fingers pinch to zoom and pan with any tool. A second finger cancels the
 * shape being drawn, so a zoom never leaves a stray mark. The move tool also pans with one finger
 * and zooms on a double-tap. The brush keeps its size on screen, so zooming in covers finer
 * details. With the rectangle tool, tapping a rectangle selects it, and dragging inside any
 * rectangle selects and moves it; the selected one is resized by its corner and edge handles.
 * Finished shapes and changes go to the {@link Listener} in image fractions, new ones with the
 * effect set by {@link #setEffect}; the owner covers them and hands back a new preview with
 * {@link #setImage} and the regions with {@link #setRegions}.
 *
 * <p>Each covered area is also a virtual accessibility node, so TalkBack, Switch Access and a
 * keyboard can reach it without drawing: rectangles can be selected, moved and resized with
 * actions, and any area deleted. {@link #addCentredRegion} adds a rectangle without a drag.
 *
 * <p>The crop tool shows a frame around the part of the photo the saved copy keeps (all of it
 * until the frame is moved): dragging a corner or an edge resizes it, dragging inside moves it,
 * and dragging outside it draws a new one. Whatever the tool, what the crop leaves out is dimmed.
 * The frame is a virtual node too, with actions to move, resize and reset it.
 */
public class CensorCanvasView extends View {

    public enum Tool { RECT, BRUSH, MOVE, CROP }

    public interface Listener {
        void onRegionDrawn(@NonNull CensorRegion region);

        /** The rectangle at {@code index} was moved or resized. */
        void onRegionChanged(int index, @NonNull CensorRegion region);

        /** The area at {@code index} was deleted with an accessibility action. */
        default void onRegionRemoved(int index) {
        }

        /** A rectangle was selected (its index) or the selection was cleared (minus one). */
        default void onSelectionChanged(int index) {
        }

        /** The zoom changed; 1 is the whole photo fitting the view. */
        default void onZoomChanged(float zoom) {
        }

        /** The crop was moved, resized or reset (null): the part of the photo the copy keeps. */
        default void onCropChanged(@Nullable CensorRegion crop) {
        }

        /**
         * Where the selected rectangle is on screen, in this view's coordinates, as it moves,
         * resizes or the photo zooms; null when nothing is selected.
         */
        default void onSelectionBoundsChanged(@Nullable RectF viewBounds) {
        }
    }

    /** What a one-finger touch with the rectangle tool is doing. */
    private enum RectGesture { DRAW, MOVE, RESIZE }

    /** What a one-finger touch with the crop tool is doing. */
    private enum CropGesture { NONE, DRAW, MOVE, RESIZE }

    /** The crop frame's virtual node; far above any area's index, which are the others' ids. */
    @VisibleForTesting
    static final int CROP_NODE_ID = 100_000;
    /** Edges of the crop frame being dragged, as bits. */
    private static final int EDGE_LEFT = 1;
    private static final int EDGE_TOP = 2;
    private static final int EDGE_RIGHT = 4;
    private static final int EDGE_BOTTOM = 8;

    static final float MAX_ZOOM = 16f;
    /** Edges of a selected rectangle with a handle at their middle need this much room on screen. */
    private static final float EDGE_HANDLE_MIN_SIDE_DP = 72f;
    /**
     * A drag this far inside an unselected rectangle's edge, at most, still draws a new one, so the
     * next line of text can be covered starting on the last rectangle's edge.
     */
    private static final float GRAB_INSET_DP = 8f;
    /** How far a double-tap with the move tool zooms in. */
    static final float DOUBLE_TAP_ZOOM = 2f;
    /** A rectangle smaller than this on screen is treated as a stray touch (or a tap). */
    private static final float MIN_RECT_DP = 12f;
    /** Corner handles of the selected rectangle: drawn radius, and how near counts as a grab. */
    private static final float HANDLE_DP = 7f;
    private static final float HANDLE_TOUCH_DP = 24f;
    /** How far a move action shifts a rectangle, as a fraction of the photo's width or height. */
    @VisibleForTesting
    static final float ACTION_MOVE_STEP = 0.05f;
    /** How much a resize action grows or shrinks a rectangle on each side, as a fraction. */
    @VisibleForTesting
    static final float ACTION_RESIZE_STEP = 0.025f;
    /** The smallest side a resize action leaves, as a fraction of the photo. */
    private static final float ACTION_MIN_SIDE = 0.02f;
    /** The side of an added rectangle, as a fraction of the part of the photo on screen. */
    private static final float ADDED_RECT_FRACTION = 0.3f;

    private final Matrix baseMatrix = new Matrix();
    private final Matrix userMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imageBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Over what the crop leaves out. */
    private final Paint dimPaint = new Paint();
    /** The crop frame's thirds, while the crop tool is on. */
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private float reportedZoom = 1f;

    @Nullable
    private Bitmap image;
    @Nullable
    private Listener listener;
    @NonNull
    private Tool tool = Tool.RECT;
    /** Brush width as a fraction of the image's longer side when the photo fits the view. */
    private float brushWidth = 0.04f;
    /** The brush width of the stroke being drawn, fixed when it starts. */
    private float strokeWidth;
    /** Shows a circle the size of the brush in the middle of the view, while its size is chosen. */
    private boolean brushPreview;

    /** The shape being drawn, in image pixels; kept after it is finished until the next preview. */
    @Nullable
    private Path shape;
    private boolean shapeIsStroke;
    private float startX;
    private float startY;
    @Nullable
    private float[] strokePoints;
    private int strokeCount;
    /** A second finger cancelled this touch's shape: draw nothing until all fingers lift. */
    private boolean gestureCancelled;
    private float lastFocusX;
    private float lastFocusY;

    /** The covered regions, to find and outline rectangles. */
    @NonNull
    private List<CensorRegion> regions = new ArrayList<>();
    /** The selected rectangle, found again by value after the regions change. */
    @Nullable
    private CensorRegion selectedRegion;
    @NonNull
    private RectGesture rectGesture = RectGesture.DRAW;
    /** The rectangle being moved or resized, in image pixels, and where it started. */
    private final RectF editRect = new RectF();
    private final RectF editStart = new RectF();
    /** The edges being dragged while resizing a rectangle ({@link #EDGE_LEFT} and the others). */
    private int editEdges;
    /**
     * Whether this touch selected the rectangle it picked up, and what was selected before, so a
     * pinch that starts on a rectangle leaves the selection as it was.
     */
    private boolean selectedOnDown;
    @Nullable
    private CensorRegion selectionBeforeDown;
    /** An unselected rectangle under the finger when it went down, selected if this is a tap. */
    private int tapIndex = -1;
    /** The selection last reported to the listener. */
    private int reportedSelection = -1;

    /** The part of the photo the copy keeps, in fractions; null for all of it. */
    @Nullable
    private CensorRegion crop;
    @NonNull
    private CropGesture cropGesture = CropGesture.NONE;
    /** The crop frame being dragged, in image pixels, and where it started; shown until the edit comes back. */
    private final RectF cropEdit = new RectF();
    private final RectF cropEditStart = new RectF();
    /** The frame's edges being dragged while resizing ({@link #EDGE_LEFT} and the others). */
    private int cropEdges;
    /** The effect new regions are covered with; null for the photo's. */
    @Nullable
    private CensorEffects.Effect newEffect;
    /** The selected rectangle's bounds on screen last reported, or null. */
    @Nullable
    private RectF reportedSelectionBounds;
    /** The handle areas last kept from the system's edge gestures. */
    @NonNull
    private List<Rect> gestureExclusion = new ArrayList<>();

    private final RegionAccessibilityHelper accessibilityHelper;

    public CensorCanvasView(@NonNull Context context) {
        this(context, null);
    }

    public CensorCanvasView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int accent = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary,
                0xFF6750A4);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(accent);
        outlinePaint.setStrokeWidth(dp(2));
        // A shape being drawn is shown dark rather than tinted, closer to how it will be covered.
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x99000000);
        handleFill.setStyle(Paint.Style.FILL);
        handleFill.setColor(0xFFFFFFFF);
        imageBorderPaint.setStyle(Paint.Style.STROKE);
        imageBorderPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOutlineVariant, 0x33888888));
        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(0x99000000);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(0x99FFFFFF);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float current = currentZoom();
                float factor = Math.max(1f / current,
                        Math.min(MAX_ZOOM / current, detector.getScaleFactor()));
                userMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                constrainPan();
                invalidate();
                reportZoom();
                return true;
            }
        });
        // A double-tap and drag zooms only with the move tool; with the others it would draw too.
        scaleDetector.setQuickScaleEnabled(false);
        accessibilityHelper = new RegionAccessibilityHelper(this);
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper);
        setFocusable(true);
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                // Zoomed in, a double-tap goes back to the whole photo; otherwise it zooms on the spot.
                if (currentZoom() > 1.01f) {
                    resetZoom();
                } else {
                    zoomAt(DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                }
                return true;
            }
        });
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setTool(@NonNull Tool tool) {
        this.tool = tool;
        scaleDetector.setQuickScaleEnabled(tool == Tool.MOVE);
        if (tool != Tool.RECT && selectedRegion != null) {
            selectedRegion = null;
        }
        cropGesture = CropGesture.NONE;
        invalidate();
        reportSelection();
        // Only the rectangle tool selects, so the areas' click action comes and goes with it.
        accessibilityHelper.invalidateRoot();
    }

    /** The effect new regions are drawn with, or null for the photo's. */
    public void setEffect(@Nullable CensorEffects.Effect effect) {
        newEffect = effect;
    }

    /** Clears the selected rectangle, if any. */
    public void clearSelection() {
        if (selectedRegion != null) {
            selectedRegion = null;
            invalidate();
        }
        reportSelection();
    }

    /** Tells the listener when the selected rectangle changes. */
    private void reportSelection() {
        int index = selectedIndex();
        if (index != reportedSelection) {
            reportedSelection = index;
            // The new selection's place is reported on the next draw, even if it is where the
            // last one was.
            reportedSelectionBounds = null;
            invalidate();
            if (listener != null) {
                listener.onSelectionChanged(index);
            }
        }
    }

    /**
     * The current regions, after any change; a selected rectangle stays selected if it, or the same
     * area with another effect, is still there.
     */
    public void setRegions(@NonNull List<CensorRegion> current) {
        setRegions(current, false);
    }

    /**
     * {@link #setRegions(List)}, and with {@code keepSelectedIndex} the selected rectangle stays
     * selected when it only moved, as a video's moving area does when another frame shows: same
     * number of areas, and a rectangle at its place in the list.
     */
    public void setRegions(@NonNull List<CensorRegion> current, boolean keepSelectedIndex) {
        int previous = selectedIndex();
        boolean sameCount = current.size() == regions.size();
        regions = new ArrayList<>(current);
        if (keepSelectedIndex && sameCount && previous >= 0 && previous < regions.size()
                && regions.get(previous).shape() == CensorRegion.Shape.RECT) {
            selectedRegion = regions.get(previous);
        }
        if (selectedRegion != null && !regions.contains(selectedRegion)) {
            // Its effect may have changed: it stays selected if the same area is still there.
            CensorRegion area = selectedRegion.withEffect(null);
            selectedRegion = null;
            for (int i = regions.size() - 1; i >= 0; i--) {
                if (regions.get(i).withEffect(null).equals(area)) {
                    selectedRegion = regions.get(i);
                    break;
                }
            }
        }
        invalidate();
        reportSelection();
        accessibilityHelper.invalidateRoot();
    }

    /**
     * The part of the photo the copy keeps, in fractions, or null for all of it; ends any drag of
     * the frame, whose result this is.
     */
    public void setCrop(@Nullable CensorRegion current) {
        crop = current;
        cropGesture = CropGesture.NONE;
        invalidate();
        accessibilityHelper.invalidateRoot();
    }

    /**
     * Adds a rectangle in the middle of the part of the photo on screen and selects it, so an area
     * can be covered without drawing; the accessibility actions then move and resize it.
     */
    public void addCentredRegion() {
        if (image == null || listener == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        updateDrawMatrix();
        float[] centre = toImage(getWidth() / 2f, getHeight() / 2f);
        RectF visible = new RectF(0, 0, getWidth(), getHeight());
        inverse.mapRect(visible);
        if (!visible.intersect(0, 0, image.getWidth(), image.getHeight())) {
            visible.set(0, 0, image.getWidth(), image.getHeight());
        }
        float w = image.getWidth();
        float h = image.getHeight();
        float halfW = visible.width() * ADDED_RECT_FRACTION / 2f;
        float halfH = visible.height() * ADDED_RECT_FRACTION / 2f;
        CensorRegion region = CensorRegion.rect((centre[0] - halfW) / w, (centre[1] - halfH) / h,
                (centre[0] + halfW) / w, (centre[1] + halfH) / h).withEffect(newEffect);
        if (tool == Tool.RECT) {
            selectedRegion = region;
        }
        listener.onRegionDrawn(region);
    }

    /** The index of the selected rectangle, or -1. */
    int selectedIndex() {
        return selectedRegion == null ? -1 : regions.lastIndexOf(selectedRegion);
    }

    /** Zooms about the centre of the view, kept between fitting the view and {@link #MAX_ZOOM}. */
    @VisibleForTesting
    void zoomBy(float factor) {
        zoomAt(factor, getWidth() / 2f, getHeight() / 2f);
    }

    /** Zooms about a view point, kept between fitting the view and {@link #MAX_ZOOM}. */
    private void zoomAt(float factor, float x, float y) {
        if (image == null) {
            return;
        }
        float current = currentZoom();
        float clamped = Math.max(1f / current, Math.min(MAX_ZOOM / current, factor));
        userMatrix.postScale(clamped, clamped, x, y);
        constrainPan();
        invalidate();
        reportZoom();
    }

    /** Shows the whole photo again. */
    public void resetZoom() {
        userMatrix.reset();
        constrainPan();
        invalidate();
        reportZoom();
    }

    private void reportZoom() {
        float zoom = currentZoom();
        if (Math.abs(zoom - reportedZoom) > 0.001f) {
            reportedZoom = zoom;
            if (listener != null) {
                listener.onZoomChanged(zoom);
            }
        }
    }

    @VisibleForTesting
    float getZoom() {
        return currentZoom();
    }

    public void setBrushWidth(float fractionOfLongSide) {
        brushWidth = fractionOfLongSide;
        if (brushPreview) {
            invalidate();
        }
    }

    public void setBrushPreview(boolean show) {
        if (brushPreview != show) {
            brushPreview = show;
            invalidate();
        }
    }

    /**
     * The brush's width on screen. It is the same at any zoom: the brush stays the same size on
     * screen, so it covers less of the photo when zoomed in.
     */
    @VisibleForTesting
    float brushScreenWidth() {
        if (image == null) {
            return 0f;
        }
        return brushWidth * Math.max(image.getWidth(), image.getHeight()) * baseMatrix.mapRadius(1f);
    }

    /** A new preview: keeps the zoom when only the covered regions changed. */
    public void setImage(@Nullable Bitmap bitmap) {
        boolean sizeChanged = image == null || bitmap == null
                || image.getWidth() != bitmap.getWidth() || image.getHeight() != bitmap.getHeight();
        image = bitmap;
        shape = null;
        if (sizeChanged) {
            cropGesture = CropGesture.NONE;
            selectedRegion = null;
            userMatrix.reset();
            updateBaseMatrix();
        }
        invalidate();
        reportSelection();
        reportZoom();
        accessibilityHelper.invalidateRoot();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Keep the photo point at the centre where it was (the view resizes when the brush size
        // or a status line shows), so a zoomed-in photo does not jump.
        float[] centre = null;
        if (image != null && oldw > 0 && oldh > 0) {
            updateDrawMatrix();
            centre = new float[] {oldw / 2f, oldh / 2f};
            inverse.mapPoints(centre);
        }
        updateBaseMatrix();
        if (centre != null) {
            updateDrawMatrix();
            drawMatrix.mapPoints(centre);
            userMatrix.postTranslate(w / 2f - centre[0], h / 2f - centre[1]);
        }
        constrainPan();
    }

    private void updateBaseMatrix() {
        baseMatrix.reset();
        if (image == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        baseMatrix.setRectToRect(new RectF(0, 0, image.getWidth(), image.getHeight()),
                new RectF(0, 0, getWidth(), getHeight()), Matrix.ScaleToFit.CENTER);
    }

    private void updateDrawMatrix() {
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(userMatrix);
        drawMatrix.invert(inverse);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (image == null) {
            return;
        }
        updateDrawMatrix();
        canvas.save();
        canvas.concat(drawMatrix);
        canvas.drawBitmap(image, 0, 0, imagePaint);
        imageBorderPaint.setStrokeWidth(dp(1) / mapScale());
        canvas.drawRect(0, 0, image.getWidth(), image.getHeight(), imageBorderPaint);
        // Keep outlines and handles the same size on screen at any zoom.
        outlinePaint.setStrokeWidth(dp(2) / mapScale());
        drawCrop(canvas);
        if (shape != null) {
            canvas.drawPath(shape, fillPaint);
            if (!shapeIsStroke) {
                canvas.drawPath(shape, outlinePaint);
            }
        }
        boolean editing = shape != null && rectGesture != RectGesture.DRAW;
        int selected = selectedIndex();
        if (editing) {
            drawHandles(canvas, editRect);
        } else if (selected >= 0) {
            RectF r = rectPx(regions.get(selected));
            canvas.drawRect(r, outlinePaint);
            drawHandles(canvas, r);
        }
        canvas.restore();
        updateGestureExclusion();
        reportSelectionBounds();
        if (brushPreview) {
            float radius = brushScreenWidth() / 2f;
            outlinePaint.setStrokeWidth(dp(2));
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, fillPaint);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, outlinePaint);
        }
    }

    /**
     * Where the selected rectangle is on screen, in this view's coordinates, while it is dragged
     * too; null when nothing is selected.
     */
    @Nullable
    public RectF selectedBoundsInView() {
        if (tool != Tool.RECT || image == null) {
            return null;
        }
        RectF bounds = null;
        if (shape != null && rectGesture != RectGesture.DRAW) {
            bounds = new RectF(editRect);
        } else if (selectedIndex() >= 0) {
            bounds = rectPx(regions.get(selectedIndex()));
        }
        if (bounds != null) {
            updateDrawMatrix();
            drawMatrix.mapRect(bounds);
        }
        return bounds;
    }

    /** Tells the listener where the selected rectangle is on screen, when that changed. */
    private void reportSelectionBounds() {
        RectF bounds = selectedBoundsInView();
        if (!Objects.equals(bounds, reportedSelectionBounds)) {
            reportedSelectionBounds = bounds;
            if (listener != null) {
                listener.onSelectionBoundsChanged(bounds == null ? null : new RectF(bounds));
            }
        }
    }

    /**
     * Keeps the handles from the system's Back gesture: near the screen's sides, a drag on a crop
     * frame's corner or edge, or a selected rectangle's corner, would otherwise go back instead.
     * Each side may exclude at most 200dp, so only the handles are excluded.
     */
    private void updateGestureExclusion() {
        List<Rect> rects = new ArrayList<>();
        int half = Math.round(dp(HANDLE_TOUCH_DP));
        RectF frame = null;
        if (tool == Tool.CROP) {
            frame = cropFramePx();
        } else if (tool == Tool.RECT && selectedIndex() >= 0) {
            frame = shape != null && rectGesture != RectGesture.DRAW
                    ? new RectF(editRect) : rectPx(regions.get(selectedIndex()));
        }
        if (frame != null) {
            drawMatrix.mapRect(frame);
            float[] ys = tool == Tool.CROP || frame.height() >= dp(EDGE_HANDLE_MIN_SIDE_DP)
                    ? new float[] {frame.top, frame.centerY(), frame.bottom}
                    : new float[] {frame.top, frame.bottom};
            for (float x : new float[] {frame.left, frame.right}) {
                for (float y : ys) {
                    rects.add(new Rect(Math.round(x) - half, Math.round(y) - half,
                            Math.round(x) + half, Math.round(y) + half));
                }
            }
        }
        if (!rects.equals(gestureExclusion)) {
            gestureExclusion = rects;
            setSystemGestureExclusionRects(rects);
        }
    }

    /**
     * Dims what the crop leaves out, at any tool; with the crop tool, also draws the frame (around
     * the whole photo when there is no crop), its thirds and its corner handles.
     */
    private void drawCrop(@NonNull Canvas canvas) {
        if (image == null) {
            return;
        }
        RectF frame = cropFramePx();
        if (frame == null) {
            return;
        }
        float w = image.getWidth();
        float h = image.getHeight();
        canvas.drawRect(0, 0, w, frame.top, dimPaint);
        canvas.drawRect(0, frame.bottom, w, h, dimPaint);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, dimPaint);
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint);
        if (tool != Tool.CROP) {
            return;
        }
        gridPaint.setStrokeWidth(dp(1) / mapScale());
        for (int i = 1; i < 3; i++) {
            float x = frame.left + frame.width() * i / 3f;
            float y = frame.top + frame.height() * i / 3f;
            canvas.drawLine(x, frame.top, x, frame.bottom, gridPaint);
            canvas.drawLine(frame.left, y, frame.right, y, gridPaint);
        }
        canvas.drawRect(frame, outlinePaint);
        drawHandles(canvas, frame);
    }

    /**
     * The crop frame in image pixels: the one being dragged, else the crop. With the crop tool and
     * no crop, the whole photo; otherwise null when there is nothing to dim.
     */
    @Nullable
    private RectF cropFramePx() {
        if (image == null) {
            return null;
        }
        if (cropGesture != CropGesture.NONE) {
            return new RectF(cropEdit);
        }
        if (crop != null) {
            return rectPx(crop);
        }
        return tool == Tool.CROP ? new RectF(0, 0, image.getWidth(), image.getHeight()) : null;
    }

    private void drawHandles(@NonNull Canvas canvas, @NonNull RectF r) {
        float radius = dp(HANDLE_DP) / mapScale();
        for (float[] c : corners(r)) {
            canvas.drawCircle(c[0], c[1], radius, handleFill);
            canvas.drawCircle(c[0], c[1], radius, outlinePaint);
        }
        // Smaller handles at the middle of each edge with room for one, which resize one way.
        float edge = radius * 0.75f;
        float minSide = dp(EDGE_HANDLE_MIN_SIDE_DP) / mapScale();
        if (r.width() >= minSide) {
            for (float y : new float[] {r.top, r.bottom}) {
                canvas.drawCircle(r.centerX(), y, edge, handleFill);
                canvas.drawCircle(r.centerX(), y, edge, outlinePaint);
            }
        }
        if (r.height() >= minSide) {
            for (float x : new float[] {r.left, r.right}) {
                canvas.drawCircle(x, r.centerY(), edge, handleFill);
                canvas.drawCircle(x, r.centerY(), edge, outlinePaint);
            }
        }
    }

    @NonNull
    private static float[][] corners(@NonNull RectF r) {
        return new float[][] {{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}};
    }

    /** A rectangle region in image pixels. */
    @NonNull
    private RectF rectPx(@NonNull CensorRegion region) {
        float[] p = region.points();
        int w = image == null ? 1 : image.getWidth();
        int h = image == null ? 1 : image.getHeight();
        return new RectF(p[0] * w, p[1] * h, p[2] * w, p[3] * h);
    }

    /** The topmost rectangle containing the image point, or -1. */
    private int rectAt(float x, float y) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            CensorRegion r = regions.get(i);
            if (r.shape() == CensorRegion.Shape.RECT && rectPx(r).contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The edges of a selected rectangle a touch at a view point resizes, as bits; 0 to move it or
     * when the touch misses the handles. Corners come first; an edge's middle handle counts only on
     * an edge long enough to show one.
     */
    private int rectEdgesAt(@NonNull RectF r, float viewX, float viewY) {
        int corner = cornerAt(r, viewX, viewY);
        if (corner >= 0) {
            return switch (corner) {
                case 0 -> EDGE_LEFT | EDGE_TOP;
                case 1 -> EDGE_RIGHT | EDGE_TOP;
                case 2 -> EDGE_RIGHT | EDGE_BOTTOM;
                default -> EDGE_LEFT | EDGE_BOTTOM;
            };
        }
        RectF v = new RectF(r);
        drawMatrix.mapRect(v);
        float reach = dp(HANDLE_TOUCH_DP);
        float minSide = dp(EDGE_HANDLE_MIN_SIDE_DP);
        if (v.width() >= minSide) {
            if (Math.hypot(viewX - v.centerX(), viewY - v.top) <= reach) {
                return EDGE_TOP;
            }
            if (Math.hypot(viewX - v.centerX(), viewY - v.bottom) <= reach) {
                return EDGE_BOTTOM;
            }
        }
        if (v.height() >= minSide) {
            if (Math.hypot(viewX - v.left, viewY - v.centerY()) <= reach) {
                return EDGE_LEFT;
            }
            if (Math.hypot(viewX - v.right, viewY - v.centerY()) <= reach) {
                return EDGE_RIGHT;
            }
        }
        return 0;
    }

    /**
     * The topmost rectangle a drag starting at a view point picks up: the point is inside it and
     * more than {@link #GRAB_INSET_DP} from its edges (less on a small one), or -1.
     */
    private int rectToGrabAt(float viewX, float viewY) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            CensorRegion r = regions.get(i);
            if (r.shape() != CensorRegion.Shape.RECT) {
                continue;
            }
            RectF v = rectPx(r);
            drawMatrix.mapRect(v);
            float inset = Math.min(dp(GRAB_INSET_DP), Math.min(v.width(), v.height()) / 4f);
            v.inset(inset, inset);
            if (v.contains(viewX, viewY)) {
                return i;
            }
        }
        return -1;
    }

    /** The corner of {@code r} within grabbing distance of the view point, or -1. */
    private int cornerAt(@NonNull RectF r, float viewX, float viewY) {
        float reach = dp(HANDLE_TOUCH_DP);
        float[][] cs = corners(r);
        for (int i = 0; i < cs.length; i++) {
            float[] v = {cs[i][0], cs[i][1]};
            drawMatrix.mapPoints(v);
            if (Math.hypot(v[0] - viewX, v[1] - viewY) <= reach) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (image == null || !isEnabled()) {
            return false;
        }
        // Two fingers zoom with any tool; their second finger has already cancelled any shape.
        scaleDetector.onTouchEvent(event);
        if (tool == Tool.MOVE) {
            // A double-tap zooms only with the move tool: with the others, a tap selects.
            tapDetector.onTouchEvent(event);
        }
        updateDrawMatrix();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureCancelled = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastFocusX = event.getX();
                lastFocusY = event.getY();
                rectGesture = RectGesture.DRAW;
                tapIndex = -1;
                selectedOnDown = false;
                if (tool == Tool.CROP) {
                    startCrop(event.getX(), event.getY());
                    return true;
                }
                if (tool == Tool.RECT && startEditing(event.getX(), event.getY())) {
                    return true;
                }
                if (tool != Tool.MOVE) {
                    startShape(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (selectedOnDown) {
                    selectedOnDown = false;
                    selectedRegion = selectionBeforeDown;
                    reportSelection();
                }
                gestureCancelled = true;
                shape = null;
                cropGesture = CropGesture.NONE;
                lastFocusX = focusX(event);
                lastFocusY = focusY(event);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (gestureCancelled || tool == Tool.MOVE) {
                    // Pans with two fingers, or one with the move tool, until every finger lifts.
                    float fx = focusX(event);
                    float fy = focusY(event);
                    userMatrix.postTranslate(fx - lastFocusX, fy - lastFocusY);
                    lastFocusX = fx;
                    lastFocusY = fy;
                    constrainPan();
                    invalidate();
                } else if (tool == Tool.CROP) {
                    continueCrop(event.getX(), event.getY());
                } else if (rectGesture != RectGesture.DRAW) {
                    continueEditing(event.getX(), event.getY());
                } else {
                    extendShape(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() > 1) {
                    // Track the fingers that are left so the image does not jump.
                    int up = event.getActionIndex();
                    float sx = 0;
                    float sy = 0;
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        if (i != up) {
                            sx += event.getX(i);
                            sy += event.getY(i);
                        }
                    }
                    lastFocusX = sx / (event.getPointerCount() - 1);
                    lastFocusY = sy / (event.getPointerCount() - 1);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!gestureCancelled && tool == Tool.CROP) {
                    continueCrop(event.getX(), event.getY());
                    finishCrop();
                    performClick();
                } else if (!gestureCancelled && rectGesture != RectGesture.DRAW) {
                    continueEditing(event.getX(), event.getY());
                    finishEditing();
                    performClick();
                } else if (!gestureCancelled && tool != Tool.MOVE) {
                    extendShape(event.getX(), event.getY());
                    finishShape(event.getX(), event.getY());
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                shape = null;
                cropGesture = CropGesture.NONE;
                invalidate();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean dispatchHoverEvent(@NonNull MotionEvent event) {
        return accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        return accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, @Nullable Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }

    /** The area under a view point, topmost first, for touch exploration; -1 for none. */
    private int regionAtView(float viewX, float viewY) {
        if (image == null) {
            return -1;
        }
        updateDrawMatrix();
        for (int i = regions.size() - 1; i >= 0; i--) {
            if (regionBoundsInView(i).contains((int) viewX, (int) viewY)) {
                return i;
            }
        }
        return -1;
    }

    /** An area's bounds on screen, kept inside the view and never empty (a node needs some). */
    @NonNull
    private Rect regionBoundsInView(int index) {
        RectF r = boundsPx(regions.get(index));
        drawMatrix.mapRect(r);
        Rect bounds = new Rect();
        r.roundOut(bounds);
        if (!bounds.intersect(0, 0, getWidth(), getHeight())) {
            bounds.set(0, 0, 1, 1);
        }
        if (bounds.isEmpty()) {
            bounds.set(bounds.left, bounds.top, bounds.left + 1, bounds.top + 1);
        }
        return bounds;
    }

    /** The image-pixel bounds of any region; a stroke's include half its brush width. */
    @NonNull
    private RectF boundsPx(@NonNull CensorRegion region) {
        if (region.shape() == CensorRegion.Shape.RECT) {
            return rectPx(region);
        }
        float[] p = region.points();
        int w = image == null ? 1 : image.getWidth();
        int h = image == null ? 1 : image.getHeight();
        float half = region.strokeWidth() * Math.max(w, h) / 2f;
        RectF r = new RectF(p[0] * w, p[1] * h, p[0] * w, p[1] * h);
        for (int i = 2; i < p.length; i += 2) {
            r.union(p[i] * w, p[i + 1] * h);
        }
        r.inset(-half, -half);
        return r;
    }

    /** Selects the rectangle at {@code index}, as a tap on it does. */
    private boolean selectRegion(int index) {
        if (index < 0 || index >= regions.size() || regions.get(index).shape() != CensorRegion.Shape.RECT
                || tool != Tool.RECT) {
            return false;
        }
        selectedRegion = regions.get(index);
        invalidate();
        reportSelection();
        return true;
    }

    /**
     * Moves the rectangle at {@code index} by a step in image fractions, kept inside the photo, or
     * grows or shrinks it about its centre; the change goes to the listener as a drag's would.
     */
    @VisibleForTesting
    boolean editRegion(int index, float dx, float dy, float grow) {
        if (listener == null || index < 0 || index >= regions.size()) {
            return false;
        }
        CensorRegion region = regions.get(index);
        if (region.shape() != CensorRegion.Shape.RECT) {
            return false;
        }
        float[] p = region.points();
        float left = p[0];
        float top = p[1];
        float right = p[2];
        float bottom = p[3];
        if (grow != 0f) {
            float halfW = Math.max(ACTION_MIN_SIDE / 2f, (right - left) / 2f + grow);
            float halfH = Math.max(ACTION_MIN_SIDE / 2f, (bottom - top) / 2f + grow);
            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            left = Math.max(0f, cx - halfW);
            top = Math.max(0f, cy - halfH);
            right = Math.min(1f, cx + halfW);
            bottom = Math.min(1f, cy + halfH);
        } else {
            dx = Math.max(-left, Math.min(1f - right, dx));
            dy = Math.max(-top, Math.min(1f - bottom, dy));
            left += dx;
            right += dx;
            top += dy;
            bottom += dy;
        }
        CensorRegion changed = CensorRegion.rect(left, top, right, bottom).withEffect(region.effect());
        if (changed.equals(region)) {
            return false;
        }
        if (tool == Tool.RECT) {
            selectedRegion = changed;
        }
        listener.onRegionChanged(index, changed);
        return true;
    }

    /** Deletes the area at {@code index} through the listener. */
    private boolean removeRegion(int index) {
        if (listener == null || index < 0 || index >= regions.size()) {
            return false;
        }
        if (regions.get(index).equals(selectedRegion)) {
            selectedRegion = null;
            reportSelection();
        }
        listener.onRegionRemoved(index);
        return true;
    }

    /**
     * The covered areas as virtual nodes: each says which area it is, a tap selects a rectangle,
     * and custom actions move, resize or delete it, which is all a drag would do.
     */
    private final class RegionAccessibilityHelper extends ExploreByTouchHelper {

        RegionAccessibilityHelper(@NonNull View host) {
            super(host);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            // With the crop tool, the frame is what a touch works on.
            if (tool == Tool.CROP && image != null && cropBoundsInView().contains((int) x, (int) y)) {
                return CROP_NODE_ID;
            }
            int index = regionAtView(x, y);
            return index >= 0 ? index : HOST_ID;
        }

        @Override
        protected void getVisibleVirtualViews(@NonNull List<Integer> virtualViewIds) {
            if (image == null) {
                return;
            }
            for (int i = 0; i < regions.size(); i++) {
                virtualViewIds.add(i);
            }
            if (tool == Tool.CROP) {
                virtualViewIds.add(CROP_NODE_ID);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
            if (virtualViewId == CROP_NODE_ID && image != null && tool == Tool.CROP) {
                updateDrawMatrix();
                node.setContentDescription(getContext().getString(crop == null
                        ? R.string.censor_crop_whole_description : R.string.censor_crop_description));
                node.setBoundsInParent(cropBoundsInView());
                if (crop != null) {
                    addAction(node, R.id.censor_action_move_left, R.string.censor_action_move_left);
                    addAction(node, R.id.censor_action_move_right, R.string.censor_action_move_right);
                    addAction(node, R.id.censor_action_move_up, R.string.censor_action_move_up);
                    addAction(node, R.id.censor_action_move_down, R.string.censor_action_move_down);
                    addAction(node, R.id.censor_action_larger, R.string.censor_action_larger);
                }
                addAction(node, R.id.censor_action_smaller, R.string.censor_action_smaller);
                if (crop != null) {
                    addAction(node, R.id.censor_action_reset_crop, R.string.censor_reset_crop);
                }
                return;
            }
            if (virtualViewId < 0 || virtualViewId >= regions.size() || image == null) {
                // A node that went away between the system asking for it and this call.
                node.setContentDescription("");
                node.setBoundsInParent(new Rect(0, 0, 1, 1));
                return;
            }
            updateDrawMatrix();
            node.setContentDescription(getContext().getString(
                    R.string.censor_area_description, virtualViewId + 1, regions.size()));
            node.setBoundsInParent(regionBoundsInView(virtualViewId));
            boolean rect = regions.get(virtualViewId).shape() == CensorRegion.Shape.RECT;
            if (rect) {
                if (tool == Tool.RECT) {
                    node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
                    node.setSelected(virtualViewId == selectedIndex());
                }
                addAction(node, R.id.censor_action_move_left, R.string.censor_action_move_left);
                addAction(node, R.id.censor_action_move_right, R.string.censor_action_move_right);
                addAction(node, R.id.censor_action_move_up, R.string.censor_action_move_up);
                addAction(node, R.id.censor_action_move_down, R.string.censor_action_move_down);
                addAction(node, R.id.censor_action_larger, R.string.censor_action_larger);
                addAction(node, R.id.censor_action_smaller, R.string.censor_action_smaller);
            }
            addAction(node, R.id.censor_action_delete, R.string.censor_action_delete);
        }

        private void addAction(@NonNull AccessibilityNodeInfoCompat node, int id, int label) {
            node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    id, getContext().getString(label)));
        }

        @Override
        protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
            if (virtualViewId == CROP_NODE_ID) {
                boolean done = performCropAction(action);
                if (done) {
                    invalidateVirtualView(virtualViewId);
                }
                return done;
            }
            boolean done;
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                done = selectRegion(virtualViewId);
            } else if (action == R.id.censor_action_move_left) {
                done = editRegion(virtualViewId, -ACTION_MOVE_STEP, 0f, 0f);
            } else if (action == R.id.censor_action_move_right) {
                done = editRegion(virtualViewId, ACTION_MOVE_STEP, 0f, 0f);
            } else if (action == R.id.censor_action_move_up) {
                done = editRegion(virtualViewId, 0f, -ACTION_MOVE_STEP, 0f);
            } else if (action == R.id.censor_action_move_down) {
                done = editRegion(virtualViewId, 0f, ACTION_MOVE_STEP, 0f);
            } else if (action == R.id.censor_action_larger) {
                done = editRegion(virtualViewId, 0f, 0f, ACTION_RESIZE_STEP);
            } else if (action == R.id.censor_action_smaller) {
                done = editRegion(virtualViewId, 0f, 0f, -ACTION_RESIZE_STEP);
            } else if (action == R.id.censor_action_delete) {
                done = removeRegion(virtualViewId);
            } else {
                return false;
            }
            if (done) {
                invalidateVirtualView(virtualViewId);
            }
            return done;
        }
    }

    /** Runs a move, resize or reset action on the crop frame. */
    private boolean performCropAction(int action) {
        if (action == R.id.censor_action_move_left) {
            return editCrop(-ACTION_MOVE_STEP, 0f, 0f);
        } else if (action == R.id.censor_action_move_right) {
            return editCrop(ACTION_MOVE_STEP, 0f, 0f);
        } else if (action == R.id.censor_action_move_up) {
            return editCrop(0f, -ACTION_MOVE_STEP, 0f);
        } else if (action == R.id.censor_action_move_down) {
            return editCrop(0f, ACTION_MOVE_STEP, 0f);
        } else if (action == R.id.censor_action_larger) {
            return editCrop(0f, 0f, ACTION_RESIZE_STEP);
        } else if (action == R.id.censor_action_smaller) {
            return editCrop(0f, 0f, -ACTION_RESIZE_STEP);
        } else if (action == R.id.censor_action_reset_crop) {
            if (crop == null || listener == null) {
                return false;
            }
            listener.onCropChanged(null);
            return true;
        }
        return false;
    }

    /**
     * Moves the crop by a step in image fractions, kept inside the photo, or grows or shrinks it
     * about its centre; the change goes to the listener as a drag's would.
     */
    @VisibleForTesting
    boolean editCrop(float dx, float dy, float grow) {
        if (listener == null || tool != Tool.CROP) {
            return false;
        }
        float[] p = crop != null ? crop.points() : new float[] {0f, 0f, 1f, 1f};
        float left = p[0];
        float top = p[1];
        float right = p[2];
        float bottom = p[3];
        if (grow != 0f) {
            float halfW = Math.max(ACTION_MIN_SIDE / 2f, (right - left) / 2f + grow);
            float halfH = Math.max(ACTION_MIN_SIDE / 2f, (bottom - top) / 2f + grow);
            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            left = Math.max(0f, cx - halfW);
            top = Math.max(0f, cy - halfH);
            right = Math.min(1f, cx + halfW);
            bottom = Math.min(1f, cy + halfH);
        } else {
            dx = Math.max(-left, Math.min(1f - right, dx));
            dy = Math.max(-top, Math.min(1f - bottom, dy));
            left += dx;
            right += dx;
            top += dy;
            bottom += dy;
        }
        CensorRegion changed = CensorRegion.rect(left, top, right, bottom);
        if (changed.equals(crop) || (crop == null && left <= 0f && top <= 0f && right >= 1f && bottom >= 1f)) {
            return false;
        }
        listener.onCropChanged(changed);
        return true;
    }

    /** The crop frame's bounds on screen, kept inside the view and never empty. */
    @NonNull
    private Rect cropBoundsInView() {
        RectF frame = cropFramePx();
        if (frame == null) {
            frame = new RectF(0, 0, image == null ? 1 : image.getWidth(), image == null ? 1 : image.getHeight());
        }
        updateDrawMatrix();
        drawMatrix.mapRect(frame);
        Rect bounds = new Rect();
        frame.roundOut(bounds);
        if (!bounds.intersect(0, 0, getWidth(), getHeight()) || bounds.isEmpty()) {
            bounds.set(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()));
        }
        return bounds;
    }

    /**
     * Starts a drag with the crop tool: near a corner or an edge of the frame it resizes, inside
     * it moves, and outside it draws a new frame.
     */
    private void startCrop(float viewX, float viewY) {
        if (image == null) {
            return;
        }
        float[] p = toImage(viewX, viewY);
        RectF frame = cropFramePx();
        startX = p[0];
        startY = p[1];
        cropEdit.set(frame);
        cropEditStart.set(frame);
        cropEdges = edgesAt(frame, viewX, viewY);
        if (cropEdges != 0) {
            cropGesture = CropGesture.RESIZE;
        } else if (frame.contains(p[0], p[1])) {
            cropGesture = CropGesture.MOVE;
        } else {
            cropGesture = CropGesture.DRAW;
            cropEdit.set(p[0], p[1], p[0], p[1]);
        }
        invalidate();
    }

    /** The edges of {@code frame} within grabbing distance of a view point, as bits; 0 for none. */
    private int edgesAt(@NonNull RectF frame, float viewX, float viewY) {
        RectF v = new RectF(frame);
        drawMatrix.mapRect(v);
        float reach = dp(HANDLE_TOUCH_DP);
        if (viewX < v.left - reach || viewX > v.right + reach
                || viewY < v.top - reach || viewY > v.bottom + reach) {
            return 0;
        }
        int edges = 0;
        float dl = Math.abs(viewX - v.left);
        float dr = Math.abs(viewX - v.right);
        if (Math.min(dl, dr) <= reach) {
            edges |= dl <= dr ? EDGE_LEFT : EDGE_RIGHT;
        }
        float dt = Math.abs(viewY - v.top);
        float db = Math.abs(viewY - v.bottom);
        if (Math.min(dt, db) <= reach) {
            edges |= dt <= db ? EDGE_TOP : EDGE_BOTTOM;
        }
        return edges;
    }

    private void continueCrop(float viewX, float viewY) {
        if (cropGesture == CropGesture.NONE || image == null) {
            return;
        }
        float[] p = toImage(viewX, viewY);
        switch (cropGesture) {
            case MOVE -> {
                float dx = Math.max(-cropEditStart.left,
                        Math.min(image.getWidth() - cropEditStart.right, p[0] - startX));
                float dy = Math.max(-cropEditStart.top,
                        Math.min(image.getHeight() - cropEditStart.bottom, p[1] - startY));
                cropEdit.set(cropEditStart);
                cropEdit.offset(dx, dy);
            }
            case RESIZE -> {
                float left = (cropEdges & EDGE_LEFT) != 0 ? p[0] : cropEditStart.left;
                float right = (cropEdges & EDGE_RIGHT) != 0 ? p[0] : cropEditStart.right;
                float top = (cropEdges & EDGE_TOP) != 0 ? p[1] : cropEditStart.top;
                float bottom = (cropEdges & EDGE_BOTTOM) != 0 ? p[1] : cropEditStart.bottom;
                cropEdit.set(Math.min(left, right), Math.min(top, bottom),
                        Math.max(left, right), Math.max(top, bottom));
            }
            default -> cropEdit.set(Math.min(startX, p[0]), Math.min(startY, p[1]),
                    Math.max(startX, p[0]), Math.max(startY, p[1]));
        }
        invalidate();
    }

    /**
     * Ends a drag with the crop tool. A frame smaller than a stray touch changes nothing; anything
     * else goes to the listener, and the frame stays as dragged until the edit comes back.
     */
    private void finishCrop() {
        if (cropGesture == CropGesture.NONE || image == null || listener == null) {
            cropGesture = CropGesture.NONE;
            invalidate();
            return;
        }
        float minImagePx = dp(MIN_RECT_DP) / mapScale();
        if (cropEdit.width() < minImagePx || cropEdit.height() < minImagePx
                || cropEdit.equals(cropEditStart)) {
            cropGesture = CropGesture.NONE;
            invalidate();
            return;
        }
        float w = image.getWidth();
        float h = image.getHeight();
        listener.onCropChanged(CensorRegion.rect(cropEdit.left / w, cropEdit.top / h,
                cropEdit.right / w, cropEdit.bottom / h));
    }

    /** The accessibility helper, for tests that walk the virtual nodes. */
    @VisibleForTesting
    @NonNull
    ExploreByTouchHelper accessibilityHelper() {
        return accessibilityHelper;
    }

    /**
     * Starts moving or resizing the selected rectangle when the touch is on one of its handles or
     * inside it, or selects and starts moving an unselected rectangle the touch is well inside.
     * Otherwise notes an unselected rectangle under the finger, which a tap selects.
     */
    private boolean startEditing(float viewX, float viewY) {
        float[] p = toImage(viewX, viewY);
        int selected = selectedIndex();
        int edges = 0;
        boolean grab = false;
        if (selected >= 0) {
            RectF r = rectPx(regions.get(selected));
            edges = rectEdgesAt(r, viewX, viewY);
            grab = edges != 0 || r.contains(p[0], p[1]);
        }
        if (!grab) {
            int under = rectToGrabAt(viewX, viewY);
            if (under >= 0) {
                selectedOnDown = true;
                selectionBeforeDown = selectedRegion;
                selected = under;
                selectedRegion = regions.get(under);
                edges = 0;
                grab = true;
                reportSelection();
            }
        }
        if (!grab) {
            tapIndex = rectAt(p[0], p[1]);
            return false;
        }
        RectF r = rectPx(regions.get(selected));
        rectGesture = edges != 0 ? RectGesture.RESIZE : RectGesture.MOVE;
        editEdges = edges;
        editStart.set(r);
        editRect.set(r);
        startX = p[0];
        startY = p[1];
        shapeIsStroke = false;
        shape = new Path();
        shape.addRect(editRect, Path.Direction.CW);
        invalidate();
        return true;
    }

    private void continueEditing(float viewX, float viewY) {
        if (shape == null || image == null) {
            return;
        }
        float[] p = toImage(viewX, viewY);
        if (rectGesture == RectGesture.MOVE) {
            // Kept whole inside the photo.
            float dx = Math.max(-editStart.left, Math.min(image.getWidth() - editStart.right, p[0] - startX));
            float dy = Math.max(-editStart.top, Math.min(image.getHeight() - editStart.bottom, p[1] - startY));
            editRect.set(editStart);
            editRect.offset(dx, dy);
        } else {
            float left = (editEdges & EDGE_LEFT) != 0 ? p[0] : editStart.left;
            float right = (editEdges & EDGE_RIGHT) != 0 ? p[0] : editStart.right;
            float top = (editEdges & EDGE_TOP) != 0 ? p[1] : editStart.top;
            float bottom = (editEdges & EDGE_BOTTOM) != 0 ? p[1] : editStart.bottom;
            editRect.set(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom));
        }
        shape.rewind();
        shape.addRect(editRect, Path.Direction.CW);
        invalidate();
    }

    private void finishEditing() {
        int selected = selectedIndex();
        float minImagePx = dp(MIN_RECT_DP) / mapScale();
        if (shape == null || image == null || listener == null || selected < 0
                || editRect.width() < minImagePx || editRect.height() < minImagePx
                // A tap that picked the rectangle up without moving it: it just stays selected.
                || editRect.equals(editStart)) {
            shape = null;
            rectGesture = RectGesture.DRAW;
            invalidate();
            return;
        }
        float w = image.getWidth();
        float h = image.getHeight();
        CensorRegion moved = CensorRegion.rect(editRect.left / w, editRect.top / h,
                editRect.right / w, editRect.bottom / h).withEffect(regions.get(selected).effect());
        // The outline stays until the covered preview arrives; the moved rectangle stays selected.
        selectedRegion = moved;
        listener.onRegionChanged(selected, moved);
    }

    private void startShape(float viewX, float viewY) {
        float[] p = toImage(viewX, viewY);
        startX = p[0];
        startY = p[1];
        shapeIsStroke = tool == Tool.BRUSH;
        shape = new Path();
        if (shapeIsStroke) {
            // Same size on screen at any zoom: zoomed in, the brush covers finer details.
            strokeWidth = brushWidth / currentZoom();
            strokePoints = new float[64];
            strokeCount = 0;
            addStrokePoint(p[0], p[1]);
            rebuildStroke();
        }
        invalidate();
    }

    private void extendShape(float viewX, float viewY) {
        if (shape == null) {
            return;
        }
        float[] p = toImage(viewX, viewY);
        if (shapeIsStroke) {
            addStrokePoint(p[0], p[1]);
            rebuildStroke();
        } else {
            shape.rewind();
            shape.addRect(Math.min(startX, p[0]), Math.min(startY, p[1]),
                    Math.max(startX, p[0]), Math.max(startY, p[1]), Path.Direction.CW);
        }
        invalidate();
    }

    private void finishShape(float viewX, float viewY) {
        if (shape == null || image == null || listener == null) {
            shape = null;
            return;
        }
        float w = image.getWidth();
        float h = image.getHeight();
        CensorRegion region;
        if (shapeIsStroke) {
            float[] normalized = new float[strokeCount];
            for (int i = 0; i < strokeCount; i += 2) {
                normalized[i] = strokePoints[i] / w;
                normalized[i + 1] = strokePoints[i + 1] / h;
            }
            region = CensorRegion.stroke(normalized, strokeWidth).withEffect(newEffect);
        } else {
            float[] p = toImage(viewX, viewY);
            float minImagePx = dp(MIN_RECT_DP) / mapScale();
            if (Math.abs(p[0] - startX) < minImagePx || Math.abs(p[1] - startY) < minImagePx) {
                // A tap: selects the rectangle under it, or clears the selection.
                selectedRegion = tapIndex >= 0 ? regions.get(tapIndex) : null;
                shape = null;
                invalidate();
                reportSelection();
                return;
            }
            region = CensorRegion.rect(startX / w, startY / h, p[0] / w, p[1] / h).withEffect(newEffect);
            // Not selected: the next drag, even one starting on this rectangle's edge, draws the
            // next one, as when covering several lines of text. A tap selects it for moving.
            selectedRegion = null;
        }
        // The outline stays until the covered preview arrives.
        listener.onRegionDrawn(region);
    }

    private void addStrokePoint(float x, float y) {
        if (strokeCount + 2 > strokePoints.length) {
            strokePoints = Arrays.copyOf(strokePoints, strokePoints.length * 2);
        }
        strokePoints[strokeCount++] = x;
        strokePoints[strokeCount++] = y;
    }

    private void rebuildStroke() {
        if (image == null || shape == null) {
            return;
        }
        float[] normalized = new float[strokeCount];
        for (int i = 0; i < strokeCount; i += 2) {
            normalized[i] = strokePoints[i] / image.getWidth();
            normalized[i + 1] = strokePoints[i + 1] / image.getHeight();
        }
        shape.set(CensorRegion.stroke(normalized, strokeWidth)
                .toPath(image.getWidth(), image.getHeight()));
    }

    /** View coordinates to image pixels, kept inside the image. */
    @NonNull
    private float[] toImage(float viewX, float viewY) {
        float[] p = {viewX, viewY};
        inverse.mapPoints(p);
        if (image != null) {
            p[0] = Math.max(0, Math.min(image.getWidth(), p[0]));
            p[1] = Math.max(0, Math.min(image.getHeight(), p[1]));
        }
        return p;
    }

    /** Screen pixels per image pixel right now. */
    private float mapScale() {
        return Math.max(1e-6f, drawMatrix.mapRadius(1f));
    }

    private float currentZoom() {
        return Math.max(1e-6f, userMatrix.mapRadius(1f));
    }

    /** Keeps the zoomed image covering the view, or centred when it is smaller than the view. */
    private void constrainPan() {
        if (image == null) {
            return;
        }
        updateDrawMatrix();
        RectF bounds = new RectF(0, 0, image.getWidth(), image.getHeight());
        drawMatrix.mapRect(bounds);
        float dx = 0;
        float dy = 0;
        if (bounds.width() <= getWidth()) {
            dx = (getWidth() - bounds.width()) / 2f - bounds.left;
        } else if (bounds.left > 0) {
            dx = -bounds.left;
        } else if (bounds.right < getWidth()) {
            dx = getWidth() - bounds.right;
        }
        if (bounds.height() <= getHeight()) {
            dy = (getHeight() - bounds.height()) / 2f - bounds.top;
        } else if (bounds.top > 0) {
            dy = -bounds.top;
        } else if (bounds.bottom < getHeight()) {
            dy = getHeight() - bounds.bottom;
        }
        userMatrix.postTranslate(dx, dy);
    }

    private static float focusX(@NonNull MotionEvent event) {
        float sum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getX(i);
        }
        return sum / event.getPointerCount();
    }

    private static float focusY(@NonNull MotionEvent event) {
        float sum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getY(i);
        }
        return sum / event.getPointerCount();
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
