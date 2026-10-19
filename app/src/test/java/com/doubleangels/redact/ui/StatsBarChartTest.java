package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.annotation.NonNull;

import com.doubleangels.redact.R;
import com.google.android.material.color.MaterialColors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class StatsBarChartTest {

    private static final int WIDTH = 600;
    private static final int HEIGHT = 200;

    /** Keeps every bar drawn instead of painting it. */
    private static final class RecordingCanvas extends Canvas {
        final List<RectF> bars = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();

        @Override
        public void drawRoundRect(@NonNull RectF rect, float rx, float ry, @NonNull Paint paint) {
            bars.add(new RectF(rect));
            colors.add(paint.getColor());
        }
    }

    private Context context;

    @Before
    public void setUp() {
        // A themed context, as the chart takes its colours from the theme.
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Redact);
    }

    private StatsBarChart chart(long[] values, int direction) {
        StatsBarChart chart = new StatsBarChart(context);
        chart.setLayoutDirection(direction);
        chart.setValues(values);
        chart.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, WIDTH, HEIGHT);
        return chart;
    }

    private static RecordingCanvas draw(StatsBarChart chart) {
        RecordingCanvas canvas = new RecordingCanvas();
        chart.draw(canvas);
        return canvas;
    }

    private static long[] days(long... values) {
        return values;
    }

    @Test
    public void drawsOneBarPerDay_allSittingOnTheBottomInsideTheView() {
        long[] values = new long[30];
        values[29] = 4;
        RecordingCanvas canvas = draw(chart(values, View.LAYOUT_DIRECTION_LTR));

        assertEquals(30, canvas.bars.size());
        float previousLeft = -1;
        for (RectF bar : canvas.bars) {
            assertEquals(HEIGHT, bar.bottom, 0.01f);
            assertTrue(bar.left >= 0 && bar.right <= WIDTH + 0.01f);
            assertTrue("bars run left to right without overlapping", bar.left > previousLeft);
            previousLeft = bar.left;
        }
    }

    @Test
    public void theBusiestDayFillsTheHeight_andOthersScaleWithIt() {
        RecordingCanvas canvas = draw(chart(days(10, 5, 0), View.LAYOUT_DIRECTION_LTR));

        assertEquals(HEIGHT, canvas.bars.get(0).height(), 0.01f);
        assertEquals(HEIGHT / 2f, canvas.bars.get(1).height(), 0.01f);
        float stub = canvas.bars.get(2).height();
        assertTrue("an empty day is a short stub", stub > 0 && stub < HEIGHT / 10f);
    }

    @Test
    public void emptyDaysUseAQuieterColourThanBusyOnes() {
        RecordingCanvas canvas = draw(chart(days(3, 0), View.LAYOUT_DIRECTION_LTR));

        int primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0);
        int outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0);
        assertEquals(primary, (int) canvas.colors.get(0));
        assertEquals(outline, (int) canvas.colors.get(1));
        assertNotEquals(primary, outline);
    }

    @Test
    public void aDayWithOneFileAmongBusyDays_isStillVisiblyABar() {
        RecordingCanvas canvas = draw(chart(days(100_000, 1), View.LAYOUT_DIRECTION_LTR));

        float stubHeight = draw(chart(days(1, 0), View.LAYOUT_DIRECTION_LTR)).bars.get(1).height();
        assertTrue(canvas.bars.get(1).height() >= stubHeight);
    }

    @Test
    public void nothingCounted_drawsOnlyStubs() {
        RecordingCanvas canvas = draw(chart(new long[30], View.LAYOUT_DIRECTION_LTR));

        float first = canvas.bars.get(0).height();
        for (RectF bar : canvas.bars) {
            assertEquals(first, bar.height(), 0.01f);
        }
    }

    @Test
    public void hugeCounts_doNotBreakTheScale() {
        RecordingCanvas canvas = draw(chart(days(Long.MAX_VALUE, Long.MAX_VALUE / 2), View.LAYOUT_DIRECTION_LTR));

        assertEquals(HEIGHT, canvas.bars.get(0).height(), 0.01f);
        assertEquals(HEIGHT / 2f, canvas.bars.get(1).height(), 1f);
    }

    @Test
    public void noValues_drawsNothing() {
        assertTrue(draw(chart(new long[0], View.LAYOUT_DIRECTION_LTR)).bars.isEmpty());
    }

    @Test
    public void rightToLeft_putsTodayOnTheLeft() {
        long[] values = days(1, 0, 0, 9);
        RecordingCanvas ltr = draw(chart(values, View.LAYOUT_DIRECTION_LTR));
        RecordingCanvas rtl = draw(chart(values, View.LAYOUT_DIRECTION_RTL));

        RectF todayLtr = ltr.bars.get(3);
        RectF todayRtl = rtl.bars.get(3);
        assertTrue(todayLtr.left > ltr.bars.get(0).left);
        assertTrue(todayRtl.left < rtl.bars.get(0).left);
        assertEquals(todayLtr.height(), todayRtl.height(), 0.01f);
        assertEquals(WIDTH - todayLtr.right, todayRtl.left, 0.01f);
    }

    @Test
    public void changingTheArrayAfterwards_doesNotChangeTheChart() {
        long[] values = days(5, 5);
        StatsBarChart chart = chart(values, View.LAYOUT_DIRECTION_LTR);
        values[1] = 0;

        RecordingCanvas canvas = draw(chart);
        assertEquals(canvas.bars.get(0).height(), canvas.bars.get(1).height(), 0.01f);
    }

    @Test
    public void isReadAsOneItemByScreenReaders() {
        StatsBarChart chart = chart(new long[30], View.LAYOUT_DIRECTION_LTR);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, chart.getImportantForAccessibility());
    }
}
