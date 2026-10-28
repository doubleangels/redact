package com.doubleangels.redact.ui;

import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowLayoutInfo;

import java.util.Objects;

/**
 * How a foldable is held: flat or not folding at all, like a book (a vertical fold that splits the
 * screen into a left and a right half), or on a table (a horizontal fold that splits it into a top
 * and a bottom half). Only a fold that separates the screen counts, so a flat foldable whose
 * hinge you cannot feel or see is treated as one big screen.
 *
 * <p>The hinge is in window coordinates, like the window size, so the usable area can be worked
 * out without asking the display.
 */
public final class FoldPosture {

    public enum Type { NONE, BOOK, TABLETOP }

    public static final FoldPosture NONE = new FoldPosture(Type.NONE, new Rect(), 0, 0);

    private final Type type;
    private final Rect hinge;
    private final int windowWidth;
    private final int windowHeight;

    private FoldPosture(@NonNull Type type, @NonNull Rect hinge, int windowWidth, int windowHeight) {
        this.type = type;
        this.hinge = hinge;
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
    }

    /** A vertical fold at {@code hinge}, in a window this big. */
    @NonNull
    public static FoldPosture book(@NonNull Rect hinge, int windowWidth, int windowHeight) {
        return new FoldPosture(Type.BOOK, new Rect(hinge), windowWidth, windowHeight);
    }

    /** A horizontal fold at {@code hinge}, in a window this big. */
    @NonNull
    public static FoldPosture tabletop(@NonNull Rect hinge, int windowWidth, int windowHeight) {
        return new FoldPosture(Type.TABLETOP, new Rect(hinge), windowWidth, windowHeight);
    }

    /** The posture {@code info} describes for a window of {@code windowBounds}. */
    @NonNull
    public static FoldPosture from(@NonNull WindowLayoutInfo info, @NonNull Rect windowBounds) {
        for (DisplayFeature feature : info.getDisplayFeatures()) {
            if (!(feature instanceof FoldingFeature fold) || !fold.isSeparating()) {
                continue;
            }
            Rect bounds = fold.getBounds();
            return fold.getOrientation() == FoldingFeature.Orientation.HORIZONTAL
                    ? tabletop(bounds, windowBounds.width(), windowBounds.height())
                    : book(bounds, windowBounds.width(), windowBounds.height());
        }
        return NONE;
    }

    @NonNull
    public Type type() {
        return type;
    }

    public boolean isSplit() {
        return type != Type.NONE;
    }

    @NonNull
    public Rect hinge() {
        return new Rect(hinge);
    }

    /** True when the left half of a book posture is at least as wide as the right half. */
    private boolean startPaneIsLarger() {
        return hinge.left >= windowWidth - hinge.right;
    }

    /** Space the app must leave empty on the left so nothing sits under or beyond the hinge. */
    public int leftInset() {
        return type == Type.BOOK && !startPaneIsLarger() ? hinge.right : 0;
    }

    /** Space the app must leave empty on the right. */
    public int rightInset() {
        return type == Type.BOOK && startPaneIsLarger() ? windowWidth - hinge.left : 0;
    }

    /** Space the app must leave empty at the bottom: the whole lower half of a tabletop. */
    public int bottomInset() {
        return type == Type.TABLETOP ? windowHeight - hinge.top : 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FoldPosture other)) {
            return false;
        }
        return type == other.type && windowWidth == other.windowWidth
                && windowHeight == other.windowHeight && hinge.equals(other.hinge);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, hinge, windowWidth, windowHeight);
    }

    @NonNull
    @Override
    public String toString() {
        return type + (type == Type.NONE ? "" : " hinge=" + hinge + " window=" + windowWidth + "x" + windowHeight);
    }
}
