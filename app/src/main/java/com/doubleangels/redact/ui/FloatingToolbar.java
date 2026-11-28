package com.doubleangels.redact.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.ViewCompat;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;

/**
 * Lets Censor's toolbar float anywhere over the photo: dragged by its grip, kept inside the area it
 * floats in, and remembered as fractions of the room it has, so it lands in the same place after a
 * rotation or on another screen size. A double tap on the grip puts it back at the bottom centre.
 * TalkBack and Switch Access move it with the grip's actions instead of dragging.
 *
 * <p>The area is a layer over the photo that catches no touches itself, so drawing works anywhere
 * the toolbar is not.
 */
public final class FloatingToolbar {

    /** Where the toolbar starts, and where a double tap puts it back: the bottom centre. */
    @VisibleForTesting
    static final float[] HOME = {0.5f, 1f};

    @NonNull
    private final ViewGroup area;
    @NonNull
    private final View toolbar;
    @NonNull
    private final Context context;
    private float fractionX;
    private float fractionY;

    private FloatingToolbar(@NonNull ViewGroup area, @NonNull View toolbar) {
        this.area = area;
        this.toolbar = toolbar;
        this.context = area.getContext();
        float[] saved = AppPreferences.getCensorToolbarPosition(context);
        fractionX = clampFraction(saved[0]);
        fractionY = clampFraction(saved[1]);
    }

    /**
     * Makes {@code toolbar}, a child of {@code area} at its top left, float there, dragged by
     * {@code grip}.
     */
    @NonNull
    public static FloatingToolbar attach(@NonNull ViewGroup area, @NonNull View toolbar, @NonNull View grip) {
        FloatingToolbar floating = new FloatingToolbar(area, toolbar);
        View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> floating.place();
        area.addOnLayoutChangeListener(relayout);
        toolbar.addOnLayoutChangeListener(relayout);
        floating.listenTo(grip);
        return floating;
    }

    /** The toolbar's place as {x, y} fractions of its room. */
    @NonNull
    public float[] position() {
        return new float[] {fractionX, fractionY};
    }

    /** Moves the toolbar to {@code x}, {@code y} (fractions of its room) and remembers it. */
    public void moveTo(float x, float y) {
        fractionX = clampFraction(x);
        fractionY = clampFraction(y);
        AppPreferences.setCensorToolbarPosition(context, fractionX, fractionY);
        place();
    }

    /** Puts the toolbar where its fractions say, inside the room it has now. */
    @VisibleForTesting
    void place() {
        toolbar.setTranslationX(offset(fractionX, roomX()));
        toolbar.setTranslationY(offset(fractionY, roomY()));
    }

    private int roomX() {
        return area.getWidth() - area.getPaddingLeft() - area.getPaddingRight() - toolbar.getWidth();
    }

    private int roomY() {
        return area.getHeight() - area.getPaddingTop() - area.getPaddingBottom() - toolbar.getHeight();
    }

    /** The offset for {@code fraction} of {@code room} pixels; none when there is no room. */
    @VisibleForTesting
    static float offset(float fraction, int room) {
        return room > 0 ? Math.round(clampFraction(fraction) * room) : 0f;
    }

    /** The fraction of {@code room} an offset of {@code offset} pixels is, kept within 0 to 1. */
    @VisibleForTesting
    static float fraction(float offset, int room) {
        return room > 0 ? clampFraction(offset / room) : HOME[0];
    }

    private static float clampFraction(float value) {
        return Float.isNaN(value) ? 0.5f : Math.max(0f, Math.min(1f, value));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void listenTo(@NonNull View grip) {
        GestureDetector doubleTap = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                moveTo(HOME[0], HOME[1]);
                return true;
            }
        });
        float[] start = new float[4];
        grip.setOnTouchListener((v, event) -> {
            doubleTap.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawX();
                    start[1] = event.getRawY();
                    start[2] = toolbar.getTranslationX();
                    start[3] = toolbar.getTranslationY();
                    // The toolbar, not whatever is under it, follows the finger from here.
                    area.requestDisallowInterceptTouchEvent(true);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float x = start[2] + event.getRawX() - start[0];
                    float y = start[3] + event.getRawY() - start[1];
                    fractionX = fraction(x, roomX());
                    fractionY = fraction(y, roomY());
                    place();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    AppPreferences.setCensorToolbarPosition(context, fractionX, fractionY);
                    return true;
                default:
                    return true;
            }
        });
        ViewCompat.addAccessibilityAction(grip, context.getString(R.string.censor_toolbar_move_top),
                (view, args) -> {
                    moveTo(fractionX, 0f);
                    return true;
                });
        ViewCompat.addAccessibilityAction(grip, context.getString(R.string.censor_toolbar_move_bottom),
                (view, args) -> {
                    moveTo(fractionX, 1f);
                    return true;
                });
    }
}
