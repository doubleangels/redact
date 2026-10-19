package com.doubleangels.redact.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * Files cleaned per day as a row of rounded bars, oldest on the start side and today on the end
 * side. Days with nothing cleaned show a short stub so the row still reads as a timeline. Screen
 * readers get the summary set with {@link #setContentDescription} instead of the bars.
 */
public class StatsBarChart extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float gap;
    private final float stubHeight;
    @NonNull
    private long[] values = new long[0];

    public StatsBarChart(@NonNull Context context) {
        this(context, null);
    }

    public StatsBarChart(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        barPaint.setColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        emptyPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOutlineVariant));
        gap = dp(3);
        stubHeight = dp(4);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    /** Sets one value per bar, oldest first. */
    public void setValues(@NonNull long[] values) {
        this.values = values.clone();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int count = values.length;
        if (count == 0) {
            return;
        }
        float left = getPaddingLeft();
        float width = getWidth() - left - getPaddingRight();
        float bottom = getHeight() - getPaddingBottom();
        float height = bottom - getPaddingTop();
        float barWidth = Math.max(1f, (width - gap * (count - 1)) / count);
        float radius = Math.min(barWidth / 2f, dp(4));
        long max = 0;
        for (long v : values) {
            max = Math.max(max, v);
        }
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < count; i++) {
            int slot = rtl ? count - 1 - i : i;
            float x = left + slot * (barWidth + gap);
            long v = values[i];
            float barHeight = v == 0 || max == 0
                    ? stubHeight
                    : Math.max(stubHeight, height * v / (float) max);
            bar.set(x, bottom - barHeight, x + barWidth, bottom);
            canvas.drawRoundRect(bar, radius, radius, v == 0 ? emptyPaint : barPaint);
        }
    }
}
