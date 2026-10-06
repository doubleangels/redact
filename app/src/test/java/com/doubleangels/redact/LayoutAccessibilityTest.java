package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Interactive controls must be at least 48dp, the Android accessibility minimum for touch targets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LayoutAccessibilityTest {

    private Context context;
    private FrameLayout parent;
    private int minTarget;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Redact);
        parent = new FrameLayout(context);
        minTarget = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48, context.getResources().getDisplayMetrics()));
    }

    private View inflate(int layout) {
        return LayoutInflater.from(context).inflate(layout, parent, false);
    }

    private void assertTouchTarget(String what, View v) {
        assertTrue(what + " height " + v.getLayoutParams().height + "px < " + minTarget + "px",
                v.getLayoutParams().height >= minTarget);
        assertTrue(what + " width " + v.getLayoutParams().width + "px < " + minTarget + "px",
                v.getLayoutParams().width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        || v.getLayoutParams().width >= minTarget);
    }

    @Test
    public void convertRowRemoveButton_isAFullSizeTouchTarget() {
        View row = inflate(R.layout.item_convert_file_row);
        assertTouchTarget("remove button", row.findViewById(R.id.convertItemRemove));
    }

    @Test
    public void scanActionCards_areAFullSizeTouchTarget() {
        View card = inflate(R.layout.item_scan_action_card);
        assertTouchTarget("action card", card);
    }

    @Test
    public void convertRowThumbnail_isHiddenFromScreenReaders_becauseTheNameFollowsIt() {
        View thumb = inflate(R.layout.item_convert_file_row).findViewById(R.id.convertItemThumbnail);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, thumb.getImportantForAccessibility());
    }
}
