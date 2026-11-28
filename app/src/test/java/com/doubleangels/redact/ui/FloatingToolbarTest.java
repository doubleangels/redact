package com.doubleangels.redact.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
public class FloatingToolbarTest {

    private Application app;
    private FrameLayout area;
    private View toolbar;
    private View grip;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(AppPreferences.PREFS_NAME, 0).edit().clear().commit();
        area = new FrameLayout(app);
        toolbar = new FrameLayout(app);
        grip = new View(app);
        ((ViewGroup) toolbar).addView(grip, new FrameLayout.LayoutParams(40, 40));
        area.addView(toolbar, new FrameLayout.LayoutParams(200, 40));
    }

    /** Lays the area out at 1000 x 600, leaving the toolbar 800 x 560 pixels of room. */
    private void layOut() {
        area.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        area.layout(0, 0, 1000, 600);
    }

    private void touch(int action, float x, float y, long downTime) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        grip.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test
    public void startsAtTheBottomCentre() {
        FloatingToolbar floating = FloatingToolbar.attach(area, toolbar, grip);
        layOut();
        assertArrayEquals(FloatingToolbar.HOME, floating.position(), 0f);
        assertEquals(400f, toolbar.getTranslationX(), 0.5f);
        assertEquals(560f, toolbar.getTranslationY(), 0.5f);
    }

    @Test
    public void draggingTheGrip_movesIt_withinTheArea_andRemembersWhere() {
        FloatingToolbar floating = FloatingToolbar.attach(area, toolbar, grip);
        layOut();
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 10, 10, down);
        touch(MotionEvent.ACTION_MOVE, -390, -550, down);
        assertEquals(0f, toolbar.getTranslationX(), 0.5f);
        assertEquals(0f, toolbar.getTranslationY(), 0.5f);
        // Far past the edges: kept inside.
        touch(MotionEvent.ACTION_MOVE, 5000, 5000, down);
        assertEquals(800f, toolbar.getTranslationX(), 0.5f);
        assertEquals(560f, toolbar.getTranslationY(), 0.5f);
        touch(MotionEvent.ACTION_MOVE, -190, -270, down);
        touch(MotionEvent.ACTION_UP, -190, -270, down);
        assertArrayEquals(new float[] {0.25f, 0.5f}, floating.position(), 0.01f);
        assertArrayEquals(new float[] {0.25f, 0.5f}, AppPreferences.getCensorToolbarPosition(app), 0.01f);
    }

    @Test
    public void keepsItsPlace_whenTheAreaChangesSize() {
        AppPreferences.setCensorToolbarPosition(app, 0.25f, 0.5f);
        FloatingToolbar.attach(area, toolbar, grip);
        layOut();
        assertEquals(200f, toolbar.getTranslationX(), 0.5f);
        // Turned on its side: the same fractions of the new room.
        area.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        area.layout(0, 0, 600, 1000);
        assertEquals(100f, toolbar.getTranslationX(), 0.5f);
        assertEquals(480f, toolbar.getTranslationY(), 0.5f);
    }

    @Test
    public void doubleTappingTheGrip_putsItBackAtTheBottomCentre() {
        AppPreferences.setCensorToolbarPosition(app, 0f, 0f);
        FloatingToolbar floating = FloatingToolbar.attach(area, toolbar, grip);
        layOut();
        long first = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 10, 10, first);
        touch(MotionEvent.ACTION_UP, 10, 10, first);
        ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        long second = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 10, 10, second);
        touch(MotionEvent.ACTION_UP, 10, 10, second);
        assertArrayEquals(FloatingToolbar.HOME, floating.position(), 0f);
        assertArrayEquals(FloatingToolbar.HOME, AppPreferences.getCensorToolbarPosition(app), 0f);
    }

    @Test
    public void accessibilityActions_moveItToTheTopAndBottom() {
        AppPreferences.setCensorToolbarPosition(app, 0.3f, 0.7f);
        FloatingToolbar floating = FloatingToolbar.attach(area, toolbar, grip);
        layOut();
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        grip.onInitializeAccessibilityNodeInfo(info);
        int top = -1;
        int bottom = -1;
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            if (app.getString(R.string.censor_toolbar_move_top).contentEquals(String.valueOf(action.getLabel()))) {
                top = action.getId();
            } else if (app.getString(R.string.censor_toolbar_move_bottom)
                    .contentEquals(String.valueOf(action.getLabel()))) {
                bottom = action.getId();
            }
        }
        assertTrue(top != -1 && bottom != -1);
        assertTrue(grip.performAccessibilityAction(top, null));
        assertArrayEquals(new float[] {0.3f, 0f}, floating.position(), 0.001f);
        assertEquals(0f, toolbar.getTranslationY(), 0.5f);
        assertTrue(grip.performAccessibilityAction(bottom, null));
        assertArrayEquals(new float[] {0.3f, 1f}, floating.position(), 0.001f);
        assertEquals(560f, toolbar.getTranslationY(), 0.5f);
    }

    @Test
    public void aToolbarWiderThanItsArea_staysAtTheStart() {
        FloatingToolbar floating = FloatingToolbar.attach(area, toolbar, grip);
        area.measure(View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(30, View.MeasureSpec.EXACTLY));
        area.layout(0, 0, 150, 30);
        floating.moveTo(1f, 1f);
        assertTrue(toolbar.getTranslationX() <= 0f);
        assertEquals(0f, toolbar.getTranslationY(), 0.5f);
    }

    @Test
    public void offsetAndFraction_areEachOthersInverse_andStayInRange() {
        assertEquals(250f, FloatingToolbar.offset(0.25f, 1000), 0f);
        assertEquals(0f, FloatingToolbar.offset(-1f, 1000), 0f);
        assertEquals(1000f, FloatingToolbar.offset(3f, 1000), 0f);
        assertEquals(0f, FloatingToolbar.offset(0.5f, 0), 0f);
        assertEquals(500f, FloatingToolbar.offset(Float.NaN, 1000), 0f);
        assertEquals(0.25f, FloatingToolbar.fraction(250f, 1000), 0f);
        assertEquals(1f, FloatingToolbar.fraction(5000f, 1000), 0f);
        assertEquals(0f, FloatingToolbar.fraction(-5f, 1000), 0f);
        assertEquals(0.5f, FloatingToolbar.fraction(10f, -20), 0f);
    }
}
