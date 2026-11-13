package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.CensorRegion;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.util.ArrayList;
import java.util.List;

/** Each covered area is a virtual node that TalkBack and Switch Access can select, move, resize and delete. */
@RunWith(RobolectricTestRunner.class)
public class CensorCanvasAccessibilityTest {

    private static final float EPS = 1e-4f;

    private CensorCanvasView view;
    private final List<CensorRegion> regions = new ArrayList<>();
    private final List<Integer> selections = new ArrayList<>();
    private int removedIndex = -1;

    @Before
    public void setUp() {
        view = new CensorCanvasView(RuntimeEnvironment.getApplication());
        // A 400 x 300 photo filling a 400 x 300 view: one view pixel per photo pixel.
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 300);
        view.setImage(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888));
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
            public void onRegionRemoved(int index) {
                removedIndex = index;
                regions.remove(index);
                view.setRegions(regions);
            }

            @Override
            public void onSelectionChanged(int index) {
                selections.add(index);
            }
        });
        regions.add(CensorRegion.rect(0.25f, 0.25f, 0.5f, 0.5f));
        regions.add(CensorRegion.stroke(new float[] {0.6f, 0.6f, 0.8f, 0.7f}, 0.05f));
        view.setRegions(regions);
    }

    private AccessibilityNodeProviderCompat provider() {
        AccessibilityNodeProviderCompat provider = view.accessibilityHelper().getAccessibilityNodeProvider(view);
        assertNotNull(provider);
        return provider;
    }

    private AccessibilityNodeInfoCompat node(int id) {
        AccessibilityNodeInfoCompat node = provider().createAccessibilityNodeInfo(id);
        assertNotNull(node);
        return node;
    }

    private static List<Integer> actionIds(AccessibilityNodeInfoCompat node) {
        List<Integer> ids = new ArrayList<>();
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : node.getActionList()) {
            ids.add(action.getId());
        }
        return ids;
    }

    private boolean perform(int id, int action) {
        return provider().performAction(id, action, null);
    }

    @Test
    public void eachArea_isANodeThatSaysWhichOneItIs() {
        assertEquals(2, node(View.NO_ID).getChildCount());
        assertEquals("Censored area 1 of 2", node(0).getContentDescription().toString());
        assertEquals("Censored area 2 of 2", node(1).getContentDescription().toString());
        Rect bounds = new Rect();
        node(0).getBoundsInParent(bounds);
        assertEquals(new Rect(100, 75, 200, 150), bounds);
    }

    @Test
    public void aRectangle_canBeSelectedMovedResizedAndDeleted() {
        List<Integer> ids = actionIds(node(0));
        assertTrue(ids.contains(AccessibilityNodeInfoCompat.ACTION_CLICK));
        for (int id : new int[] {R.id.censor_action_move_left, R.id.censor_action_move_right,
                R.id.censor_action_move_up, R.id.censor_action_move_down, R.id.censor_action_larger,
                R.id.censor_action_smaller, R.id.censor_action_delete}) {
            assertTrue(ids.contains(id));
        }
    }

    @Test
    public void aBrushStroke_canOnlyBeDeleted() {
        List<Integer> ids = actionIds(node(1));
        assertTrue(ids.contains(R.id.censor_action_delete));
        assertFalse(ids.contains(R.id.censor_action_move_left));
        assertFalse(ids.contains(AccessibilityNodeInfoCompat.ACTION_CLICK));
        assertFalse(perform(1, R.id.censor_action_move_left));
    }

    @Test
    public void click_selectsTheRectangle_onlyWithTheRectangleTool() {
        assertTrue(perform(0, AccessibilityNodeInfoCompat.ACTION_CLICK));
        assertEquals(0, view.selectedIndex());
        assertTrue(node(0).isSelected());

        view.setTool(CensorCanvasView.Tool.BRUSH);
        assertFalse(actionIds(node(0)).contains(AccessibilityNodeInfoCompat.ACTION_CLICK));
        assertFalse(perform(0, AccessibilityNodeInfoCompat.ACTION_CLICK));
    }

    @Test
    public void moveActions_shiftTheRectangleByAStep() {
        assertTrue(perform(0, R.id.censor_action_move_right));
        assertTrue(perform(0, R.id.censor_action_move_down));
        float[] p = regions.get(0).points();
        assertEquals(0.25f + CensorCanvasView.ACTION_MOVE_STEP, p[0], EPS);
        assertEquals(0.25f + CensorCanvasView.ACTION_MOVE_STEP, p[1], EPS);
        assertEquals(0.5f + CensorCanvasView.ACTION_MOVE_STEP, p[2], EPS);
        assertEquals(0.5f + CensorCanvasView.ACTION_MOVE_STEP, p[3], EPS);
        assertEquals(0, view.selectedIndex());

        assertTrue(perform(0, R.id.censor_action_move_left));
        assertTrue(perform(0, R.id.censor_action_move_up));
        assertEquals(0.25f, regions.get(0).points()[0], EPS);
        assertEquals(0.25f, regions.get(0).points()[1], EPS);
    }

    @Test
    public void moveActions_stopAtTheEdgeOfThePhoto() {
        regions.set(0, CensorRegion.rect(0f, 0f, 0.2f, 0.2f));
        view.setRegions(regions);
        assertFalse(perform(0, R.id.censor_action_move_left));
        assertFalse(perform(0, R.id.censor_action_move_up));
        assertEquals(0f, regions.get(0).points()[0], 0f);
    }

    @Test
    public void resizeActions_growAndShrinkAboutTheCentre() {
        assertTrue(perform(0, R.id.censor_action_larger));
        float[] p = regions.get(0).points();
        assertEquals(0.25f - CensorCanvasView.ACTION_RESIZE_STEP, p[0], EPS);
        assertEquals(0.5f + CensorCanvasView.ACTION_RESIZE_STEP, p[2], EPS);

        for (int i = 0; i < 20; i++) {
            perform(0, R.id.censor_action_smaller);
        }
        p = regions.get(0).points();
        assertTrue("a rectangle never shrinks away", p[2] - p[0] > 0.01f);
        assertEquals(0.375f, (p[0] + p[2]) / 2f, EPS);
    }

    @Test
    public void delete_removesTheArea() {
        perform(0, AccessibilityNodeInfoCompat.ACTION_CLICK);
        assertTrue(perform(0, R.id.censor_action_delete));
        assertEquals(0, removedIndex);
        assertEquals(1, regions.size());
        assertEquals(-1, view.selectedIndex());
        assertEquals(1, node(View.NO_ID).getChildCount());

        assertTrue(perform(0, R.id.censor_action_delete));
        assertEquals(0, regions.size());
    }

    /** TalkBack reaches the nodes through the platform provider, not the compat helper. */
    @Test
    public void thePlatformProvider_reachesTheAreas() {
        android.view.accessibility.AccessibilityNodeProvider platform = view.getAccessibilityNodeProvider();
        assertNotNull(platform);
        android.view.accessibility.AccessibilityNodeInfo info = platform.createAccessibilityNodeInfo(0);
        assertNotNull(info);
        assertEquals("Censored area 1 of 2", info.getContentDescription().toString());
        assertTrue(platform.performAction(1, R.id.censor_action_delete, null));
        assertEquals(1, removedIndex);
    }

    @Test
    public void unknownActionsAndGoneAreas_doNothing() {
        assertFalse(perform(0, AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD));
        assertFalse(perform(5, R.id.censor_action_delete));
        AccessibilityNodeInfoCompat gone = node(5);
        assertEquals("", gone.getContentDescription().toString());
    }

    @Test
    public void addCentredRegion_dropsASelectedRectangleInTheMiddle() {
        view.addCentredRegion();
        assertEquals(3, regions.size());
        CensorRegion added = regions.get(2);
        assertEquals(CensorRegion.Shape.RECT, added.shape());
        float[] p = added.points();
        assertEquals(0.5f, (p[0] + p[2]) / 2f, EPS);
        assertEquals(0.5f, (p[1] + p[3]) / 2f, EPS);
        assertEquals(0.3f, p[2] - p[0], EPS);
        assertEquals(2, view.selectedIndex());
        assertEquals("Censored area 3 of 3", node(2).getContentDescription().toString());
    }

    @Test
    public void addCentredRegion_coversThePartOnScreenWhenZoomedIn() {
        view.zoomBy(2f);
        view.addCentredRegion();
        float[] p = regions.get(2).points();
        assertEquals(0.15f, p[2] - p[0], EPS);
    }

    @Test
    public void addCentredRegion_withoutAPhoto_doesNothing() {
        view.setImage(null);
        view.addCentredRegion();
        assertEquals(2, regions.size());
        assertEquals(0, node(View.NO_ID).getChildCount());
    }

    @Test
    public void touchExploration_findsTheAreaUnderTheFinger() {
        AccessibilityManager manager = (AccessibilityManager) RuntimeEnvironment.getApplication()
                .getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(manager).setEnabled(true);
        Shadows.shadowOf(manager).setTouchExplorationEnabled(true);
        long t = SystemClock.uptimeMillis();
        assertTrue(view.dispatchHoverEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, 150, 100, 0)));
        assertTrue(view.dispatchHoverEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_HOVER_MOVE, 5, 5, 0)));
        view.dispatchHoverEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_HOVER_EXIT, 5, 5, 0));
    }

    @Test
    public void keyboard_reachesAnAreaAndActsOnIt() {
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class)
                .setup().get();
        activity.setContentView(view, new android.view.ViewGroup.LayoutParams(400, 300));
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 300);
        assertTrue(view.requestFocus());
        // Taking focus from the keyboard lands on the first area.
        assertEquals(0, view.accessibilityHelper().getKeyboardFocusedVirtualViewId());
        long t = SystemClock.uptimeMillis();
        assertTrue(view.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)));
        view.dispatchKeyEvent(new KeyEvent(t, t + 10, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        assertEquals(0, view.selectedIndex());
    }
}
