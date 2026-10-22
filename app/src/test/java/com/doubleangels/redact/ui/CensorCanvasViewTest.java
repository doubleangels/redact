package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.doubleangels.redact.media.CensorRegion;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Zoom limits, the zoom-aware brush, the move tool and where zoomed-in shapes land. */
@RunWith(RobolectricTestRunner.class)
public class CensorCanvasViewTest {

    private CensorCanvasView view;
    private CensorRegion drawn;
    private CensorRegion changed;
    private int changedIndex = -1;
    private final java.util.List<Integer> selections = new java.util.ArrayList<>();
    private final java.util.List<Float> zooms = new java.util.ArrayList<>();
    private final java.util.List<CensorRegion> regions = new java.util.ArrayList<>();

    @Before
    public void setUp() {
        view = new CensorCanvasView(RuntimeEnvironment.getApplication());
        // 400 x 300 photo in a 400 x 300 view: fits exactly, one view pixel per photo pixel.
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 300);
        view.setImage(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888));
        view.setListener(new CensorCanvasView.Listener() {
            @Override
            public void onRegionDrawn(@NonNull CensorRegion region) {
                drawn = region;
                regions.add(region);
                view.setRegions(regions);
            }

            @Override
            public void onRegionChanged(int index, @NonNull CensorRegion region) {
                changedIndex = index;
                changed = region;
                regions.set(index, region);
                view.setRegions(regions);
            }

            @Override
            public void onSelectionChanged(int index) {
                selections.add(index);
            }

            @Override
            public void onZoomChanged(float zoom) {
                zooms.add(zoom);
            }
        });
    }

    private void drag(float x0, float y0, float x1, float y1) {
        long t = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_MOVE, (x0 + x1) / 2, (y0 + y1) / 2, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_MOVE, x1, y1, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, x1, y1, 0));
    }

    /** Two fingers 100 px apart moving to 300 px apart about the centre: a pinch to zoom in. */
    private void pinchOut() {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[1].id = 1;
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 150, 150, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 10,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords(150, 250), 0, 0, 1f, 1f, 0, 0, 0, 0));
        for (int i = 1; i <= 10; i++) {
            float half = 50 + i * 10;
            view.onTouchEvent(MotionEvent.obtain(t, t + 10 + i * 16, MotionEvent.ACTION_MOVE,
                    2, props, coords(200 - half, 200 + half), 0, 0, 1f, 1f, 0, 0, 0, 0));
        }
        view.onTouchEvent(MotionEvent.obtain(t, t + 200,
                MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords(50, 350), 0, 0, 1f, 1f, 0, 0, 0, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 210, MotionEvent.ACTION_UP, 50, 150, 0));
    }

    private static MotionEvent.PointerCoords[] coords(float x0, float x1) {
        MotionEvent.PointerCoords a = new MotionEvent.PointerCoords();
        MotionEvent.PointerCoords b = new MotionEvent.PointerCoords();
        a.x = x0;
        a.y = 150;
        b.x = x1;
        b.y = 150;
        return new MotionEvent.PointerCoords[] {a, b};
    }

    @Test
    public void zoom_staysBetweenFitAndTheMaximum() {
        assertEquals(1f, view.getZoom(), 0.001f);
        view.zoomBy(2f);
        assertEquals(2f, view.getZoom(), 0.001f);

        for (int i = 0; i < 10; i++) {
            view.zoomBy(2f);
        }
        assertEquals(CensorCanvasView.MAX_ZOOM, view.getZoom(), 0.001f);

        for (int i = 0; i < 10; i++) {
            view.zoomBy(0.5f);
        }
        assertEquals(1f, view.getZoom(), 0.001f);
    }

    @Test
    public void pinching_zoomsWithTheMoveTool() {
        view.setTool(CensorCanvasView.Tool.MOVE);
        pinchOut();
        assertTrue("zoom " + view.getZoom(), view.getZoom() > 1.5f);
        assertNull("moving draws nothing", drawn);
    }

    @Test
    public void pinching_whileDrawing_neitherZoomsNorDraws() {
        view.setTool(CensorCanvasView.Tool.BRUSH);
        pinchOut();
        assertEquals(1f, view.getZoom(), 0.001f);
        assertNull(drawn);

        view.setTool(CensorCanvasView.Tool.RECT);
        pinchOut();
        assertEquals(1f, view.getZoom(), 0.001f);
        assertNull(drawn);
    }

    @Test
    public void zoomedIn_theBrushCoversLessOfThePhoto() {
        view.setTool(CensorCanvasView.Tool.BRUSH);
        view.setBrushWidth(0.04f);
        drag(100, 150, 300, 150);
        assertEquals(0.04f, drawn.strokeWidth(), 1e-6f);

        view.zoomBy(4f);
        drag(100, 150, 300, 150);
        assertEquals(0.01f, drawn.strokeWidth(), 1e-6f);
    }

    @Test
    public void zoomedIn_aRectangleLandsWhereItWasDrawn() {
        // Zoomed 2x about the centre (200, 150), the view shows photo x 100..300, y 75..225.
        view.zoomBy(2f);
        drag(0, 0, 400, 300);

        assertEquals(CensorRegion.Shape.RECT, drawn.shape());
        float[] p = drawn.points();
        assertEquals(0.25f, p[0], 0.002f);
        assertEquals(0.25f, p[1], 0.002f);
        assertEquals(0.75f, p[2], 0.002f);
        assertEquals(0.75f, p[3], 0.002f);
    }

    @Test
    public void moveTool_pansWithoutDrawing() {
        view.zoomBy(2f);
        view.setTool(CensorCanvasView.Tool.MOVE);
        drag(300, 150, 200, 150);
        assertNull(drawn);

        // Panned 100 view pixels left: the left edge now shows photo x 150 instead of 100.
        view.setTool(CensorCanvasView.Tool.RECT);
        drag(0, 0, 100, 100);
        assertNotNull(drawn);
        assertEquals(150f / 400f, drawn.points()[0], 0.002f);
    }

    @Test
    public void resizingWhileZoomed_keepsThePhotoUnderTheCentre() {
        view.zoomBy(4f);
        view.setTool(CensorCanvasView.Tool.MOVE);
        drag(250, 150, 200, 150); // Pan so the centre is no longer the photo's centre.
        view.setTool(CensorCanvasView.Tool.RECT);
        drag(190, 140, 210, 160);
        float[] before = drawn.points();

        // The view gets shorter, as when the brush size slider appears.
        view.layout(0, 0, 400, 250);
        drag(190, 115, 210, 135);
        float[] after = drawn.points();

        assertEquals((before[0] + before[2]) / 2, (after[0] + after[2]) / 2, 0.002f);
        assertEquals((before[1] + before[3]) / 2, (after[1] + after[3]) / 2, 0.002f);
    }

    @Test
    public void tappingARectangleSelectsIt_andDraggingInsideMovesIt() {
        drag(100, 100, 200, 200);
        assertEquals("a new rectangle is not selected", -1, view.selectedIndex());
        drag(150, 150, 150, 150);
        assertEquals(0, view.selectedIndex());

        drag(150, 150, 250, 180);

        assertEquals(0, changedIndex);
        float[] p = changed.points();
        assertEquals(200f / 400f, p[0], 0.002f);
        assertEquals(130f / 300f, p[1], 0.002f);
        assertEquals(300f / 400f, p[2], 0.002f);
        assertEquals(230f / 300f, p[3], 0.002f);
        assertEquals("moving is not drawing", 1, regions.size());
    }

    @Test
    public void draggingACornerHandle_resizesTheSelectedRectangle() {
        drag(100, 100, 200, 200);
        drag(150, 150, 150, 150);

        drag(200, 200, 300, 260);

        float[] p = changed.points();
        assertEquals(100f / 400f, p[0], 0.002f);
        assertEquals(100f / 300f, p[1], 0.002f);
        assertEquals(300f / 400f, p[2], 0.002f);
        assertEquals(260f / 300f, p[3], 0.002f);
    }

    @Test
    public void aMoveIsKeptInsideThePhoto() {
        drag(100, 100, 200, 200);
        drag(150, 150, 150, 150);

        drag(150, 150, 390, 290);

        float[] p = changed.points();
        assertEquals(1f, p[2], 0.002f);
        assertEquals(1f, p[3], 0.002f);
        assertEquals(0.75f, p[0], 0.002f);
    }

    @Test
    public void tappingSelects_andTappingEmptySpaceClearsTheSelection() {
        drag(20, 20, 120, 120);
        drag(250, 150, 350, 250);
        drag(300, 200, 300, 200);
        assertEquals(1, view.selectedIndex());

        drag(70, 70, 70, 70);
        assertEquals(0, view.selectedIndex());

        drag(200, 60, 200, 60);
        assertEquals(-1, view.selectedIndex());
    }

    @Test
    public void draggingOverAnUnselectedRectangle_drawsANewOne() {
        drag(20, 20, 120, 120);
        drag(250, 150, 350, 250);
        drag(300, 200, 300, 200); // Selects the second one.

        drag(30, 30, 100, 100);

        assertEquals(3, regions.size());
        assertEquals(-1, changedIndex);
    }

    @Test
    public void otherTools_clearTheSelection() {
        drag(100, 100, 200, 200);
        drag(150, 150, 150, 150);
        assertEquals(0, view.selectedIndex());
        view.setTool(CensorCanvasView.Tool.BRUSH);
        assertEquals(-1, view.selectedIndex());
    }

    @Test
    public void rectanglesDrawnSideBySide_areAllDrawn() {
        for (int i = 0; i < 5; i++) {
            drag(20 + i * 60, 50, 80 + i * 60, 250); // Each starts on the last one's edge.
        }
        assertEquals(5, regions.size());
        assertEquals(-1, changedIndex);
    }

    @Test
    public void selecting_isReported_soDeleteCanShow_andClearingHidesIt() {
        drag(100, 100, 200, 200);
        drag(150, 150, 150, 150); // Tap to select.
        assertEquals(java.util.List.of(0), selections);

        view.clearSelection();
        assertEquals(java.util.List.of(0, -1), selections);

        drag(150, 150, 150, 150);
        regions.clear();
        view.setRegions(regions); // The rectangle was deleted.
        assertEquals(java.util.List.of(0, -1, 0, -1), selections);
    }

    private void doubleTap(float x, float y) {
        long t = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0));
        view.onTouchEvent(MotionEvent.obtain(t + 120, t + 120, MotionEvent.ACTION_DOWN, x, y, 0));
        view.onTouchEvent(MotionEvent.obtain(t + 120, t + 160, MotionEvent.ACTION_UP, x, y, 0));
    }

    @Test
    public void doubleTap_withMove_zoomsIn_andAgainShowsTheWholePhoto() {
        view.setTool(CensorCanvasView.Tool.MOVE);

        doubleTap(100, 100);
        assertEquals(CensorCanvasView.DOUBLE_TAP_ZOOM, view.getZoom(), 0.001f);
        assertEquals(CensorCanvasView.DOUBLE_TAP_ZOOM, zooms.get(zooms.size() - 1), 0.001f);

        doubleTap(200, 150);
        assertEquals(1f, view.getZoom(), 0.001f);
        assertEquals(1f, zooms.get(zooms.size() - 1), 0.001f);
        assertNull("moving draws nothing", drawn);
    }

    @Test
    public void doubleTap_whileDrawing_doesNotZoom() {
        view.setTool(CensorCanvasView.Tool.RECT);
        doubleTap(100, 100);
        assertEquals(1f, view.getZoom(), 0.001f);
        assertTrue(zooms.isEmpty());
    }

    @Test
    public void resetZoom_showsTheWholePhoto_andIsReported() {
        view.zoomBy(4f);
        view.resetZoom();
        assertEquals(1f, view.getZoom(), 0.001f);
        assertEquals(java.util.List.of(4f, 1f), zooms);
    }

    @Test
    public void brushPreview_isTheBrushWidthOnScreen_atAnyZoom() {
        view.setBrushWidth(0.04f);
        // 400 px photo fitted 1:1: 4% of 400 px is 16 px on screen.
        assertEquals(16f, view.brushScreenWidth(), 0.01f);
        view.zoomBy(4f);
        assertEquals("the brush keeps its size on screen", 16f, view.brushScreenWidth(), 0.01f);
    }
}
