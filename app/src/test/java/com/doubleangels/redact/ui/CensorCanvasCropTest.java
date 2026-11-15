package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.CensorRegion;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/** The crop tool: the frame's handles, moving it, drawing a new one, dimming and its node. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CensorCanvasCropTest {

    private static final float EPS = 0.002f;

    private CensorCanvasView view;
    /** Every crop the canvas reported; the last one is put back, as the view model does. */
    private final List<CensorRegion> crops = new ArrayList<>();
    private int cropChanges;
    private CensorRegion drawn;

    @Before
    public void setUp() {
        view = new CensorCanvasView(RuntimeEnvironment.getApplication());
        // 400 x 300 photo in a 400 x 300 view: one view pixel per photo pixel.
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 300);
        view.setImage(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888));
        view.setListener(new CensorCanvasView.Listener() {
            @Override
            public void onRegionDrawn(@NonNull CensorRegion region) {
                drawn = region;
            }

            @Override
            public void onRegionChanged(int index, @NonNull CensorRegion region) {
            }

            @Override
            public void onCropChanged(@Nullable CensorRegion crop) {
                cropChanges++;
                crops.add(crop);
                view.setCrop(crop);
            }
        });
        view.setTool(CensorCanvasView.Tool.CROP);
    }

    private void drag(float x0, float y0, float x1, float y1) {
        long t = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y0, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_MOVE, (x0 + x1) / 2, (y0 + y1) / 2, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_MOVE, x1, y1, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, x1, y1, 0));
    }

    private CensorRegion lastCrop() {
        assertTrue("no crop was reported", cropChanges > 0);
        return crops.get(crops.size() - 1);
    }

    private static void assertCrop(CensorRegion crop, float l, float t, float r, float b) {
        assertNotNull(crop);
        float[] p = crop.points();
        assertEquals(l, p[0], EPS);
        assertEquals(t, p[1], EPS);
        assertEquals(r, p[2], EPS);
        assertEquals(b, p[3], EPS);
    }

    @Test
    public void draggingAnEdgeOfTheWholePhoto_cropsFromThatSide() {
        drag(2, 150, 100, 150);
        assertCrop(lastCrop(), 0.25f, 0f, 1f, 1f);
    }

    @Test
    public void draggingACorner_movesBothItsEdges() {
        view.setCrop(CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f));
        drag(300, 225, 350, 280);
        assertCrop(lastCrop(), 0.25f, 0.25f, 0.875f, 280 / 300f);
    }

    @Test
    public void draggingInside_movesTheFrame_keptInsideThePhoto() {
        view.setCrop(CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f));
        drag(200, 150, 220, 170);
        assertCrop(lastCrop(), 0.30f, 0.25f + 20 / 300f, 0.80f, 0.75f + 20 / 300f);

        drag(200, 150, 400, 150);
        float[] p = lastCrop().points();
        assertEquals("stopped at the right edge", 1f, p[2], EPS);
        assertEquals(0.5f, p[2] - p[0], EPS);
    }

    @Test
    public void draggingOutsideTheFrame_drawsANewOne() {
        view.setCrop(CensorRegion.rect(0.5f, 0.5f, 0.75f, 0.75f));
        drag(20, 20, 120, 100);
        assertCrop(lastCrop(), 0.05f, 20 / 300f, 0.30f, 100 / 300f);
    }

    @Test
    public void aTapOrATinyDrag_changesNothing() {
        view.setCrop(CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f));
        drag(200, 150, 200, 150);
        drag(20, 20, 24, 23);
        assertEquals(0, cropChanges);
    }

    @Test
    public void aSecondFinger_cancelsTheDrag() {
        long t = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 2, 150, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_MOVE, 80, 150, 0));
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[1].id = 1;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
        coords[0].x = 80;
        coords[0].y = 150;
        coords[1].x = 200;
        coords[1].y = 200;
        view.onTouchEvent(MotionEvent.obtain(t, t + 20,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0));
        view.onTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, 80, 150, 0));
        assertEquals(0, cropChanges);
    }

    @Test
    public void theOtherTools_drawAsUsual_whileACropIsSet() {
        view.setCrop(CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f));
        view.setTool(CensorCanvasView.Tool.RECT);
        drag(20, 20, 120, 100);
        assertNotNull("drawn even where the crop leaves out", drawn);
        assertEquals(0, cropChanges);
    }

    @Test
    public void whatTheCropLeavesOut_isDimmed() {
        Bitmap white = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        white.eraseColor(0xFFFFFFFF);
        view.setImage(white);
        view.setCrop(CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f));
        view.setTool(CensorCanvasView.Tool.BRUSH);
        Bitmap out = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(out));
        int inside = out.getPixel(200, 150);
        int outside = out.getPixel(20, 20);
        assertTrue("inside stays bright", (inside & 0xFF) > 0xF0);
        assertTrue("outside is darker", (outside & 0xFF) < (inside & 0xFF));
    }

    /** Near the screen's sides a handle drag would be taken for Back, so the handles are excluded. */
    @Test
    public void theHandles_areKeptFromTheBackGesture_onlyWithTheCropTool() {
        view.draw(new Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)));
        List<Rect> rects = view.getSystemGestureExclusionRects();
        assertEquals("three on each side", 6, rects.size());
        assertTrue(rects.get(0).contains(0, 0));

        view.setTool(CensorCanvasView.Tool.BRUSH);
        view.draw(new Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)));
        assertTrue(view.getSystemGestureExclusionRects().isEmpty());
    }

    // ---- accessibility ---------------------------------------------------------------------------

    private AccessibilityNodeProviderCompat provider() {
        return view.accessibilityHelper().getAccessibilityNodeProvider(view);
    }

    private boolean hasAction(AccessibilityNodeInfoCompat node, int id) {
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : node.getActionList()) {
            if (action.getId() == id) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theFrame_isANode_thatCanBeShrunkAndReset() {
        AccessibilityNodeInfoCompat whole = provider().createAccessibilityNodeInfo(CensorCanvasView.CROP_NODE_ID);
        assertNotNull(whole);
        assertEquals(view.getContext().getString(R.string.censor_crop_whole_description),
                whole.getContentDescription().toString());
        assertTrue(hasAction(whole, R.id.censor_action_smaller));
        assertFalse("the whole photo can't grow", hasAction(whole, R.id.censor_action_larger));
        assertFalse("nor be reset", hasAction(whole, R.id.censor_action_reset_crop));

        assertTrue(provider().performAction(CensorCanvasView.CROP_NODE_ID, R.id.censor_action_smaller, null));
        assertCrop(lastCrop(), 0.025f, 0.025f, 0.975f, 0.975f);

        AccessibilityNodeInfoCompat cropped = provider().createAccessibilityNodeInfo(CensorCanvasView.CROP_NODE_ID);
        assertEquals(view.getContext().getString(R.string.censor_crop_description),
                cropped.getContentDescription().toString());
        assertTrue(hasAction(cropped, R.id.censor_action_larger));
        assertTrue(provider().performAction(CensorCanvasView.CROP_NODE_ID, R.id.censor_action_move_left, null));
        assertCrop(lastCrop(), 0f, 0.025f, 0.95f, 0.975f);

        assertTrue(provider().performAction(CensorCanvasView.CROP_NODE_ID, R.id.censor_action_reset_crop, null));
        assertNull(lastCrop());
    }

    @Test
    public void theFrame_canOnlyBeEditedWithTheCropTool() {
        view.setTool(CensorCanvasView.Tool.RECT);
        assertFalse(view.editCrop(0f, 0f, -0.1f));
        view.setTool(CensorCanvasView.Tool.CROP);
        assertTrue(view.editCrop(0f, 0f, -0.1f));
        assertEquals(1, cropChanges);
    }
}
