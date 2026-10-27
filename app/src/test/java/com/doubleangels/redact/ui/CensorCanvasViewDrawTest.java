package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/** What the canvas puts on screen: the photo, the shape being drawn, handles and the brush preview. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CensorCanvasViewDrawTest {

    private CensorCanvasView view;
    private final List<CensorRegion> regions = new ArrayList<>();

    @Before
    public void setUp() {
        view = new CensorCanvasView(RuntimeEnvironment.getApplication());
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 300);
        Bitmap photo = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.RED);
        view.setImage(photo);
        view.setListener(new CensorCanvasView.Listener() {
            @Override
            public void onRegionDrawn(@NonNull CensorRegion region) {
                regions.add(region);
                view.setRegions(regions);
            }

            @Override
            public void onRegionChanged(int index, @NonNull CensorRegion region) {
                regions.set(index, region);
                view.setRegions(regions);
            }

            @Override
            public void onSelectionChanged(int index) {}

            @Override
            public void onZoomChanged(float zoom) {}
        });
    }

    private Bitmap render() {
        Bitmap screen = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(screen));
        return screen;
    }

    private MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    private void drag(float x0, float y0, float x1, float y1) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x0, y0));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x1, y1));
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x1, y1));
    }

    @Test
    public void withoutAPhoto_nothingIsDrawn() {
        view.setImage(null);
        assertEquals(0, render().getPixel(200, 150));
    }

    @Test
    public void thePhotoFillsTheView() {
        assertEquals(Color.RED, render().getPixel(200, 150));
    }

    @Test
    public void aRectangleBeingDrawn_coversItsArea_beforeTheFingerLifts() {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 100));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 250, 220));

        Bitmap screen = render();

        assertNotEquals(Color.RED, screen.getPixel(175, 160));
        assertEquals(Color.RED, screen.getPixel(320, 260));
    }

    @Test
    public void aBrushStrokeBeingDrawn_coversItsPath() {
        view.setTool(CensorCanvasView.Tool.BRUSH);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 60, 150));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 340, 150));

        assertNotEquals(Color.RED, render().getPixel(200, 150));
    }

    @Test
    public void aSelectedRectangle_showsHandlesOnItsCorners() {
        drag(100, 100, 200, 200);
        int before = render().getPixel(100, 100);
        drag(150, 150, 150, 150); // tap: select it

        assertNotEquals(before, render().getPixel(100, 100));
    }

    @Test
    public void resizingARectangle_keepsItsHandlesOnScreen() {
        drag(100, 100, 200, 200);
        drag(150, 150, 150, 150);
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 200, 200));
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 300, 260));

        assertNotEquals(Color.RED, render().getPixel(300, 260));
    }

    @Test
    public void theBrushPreview_isACircleInTheMiddle_untilItIsHidden() {
        view.setBrushWidth(0.2f);
        view.setBrushPreview(true);
        assertNotEquals(Color.RED, render().getPixel(200, 150));

        view.setBrushWidth(0.3f);
        view.setBrushPreview(false);
        assertEquals(Color.RED, render().getPixel(200, 150));
    }
}
