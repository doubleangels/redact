package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowLayoutInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FoldPostureTest {

    private static final Rect WINDOW = new Rect(0, 0, 840, 700);

    /** A fold with the given shape, without a real device. */
    private static final class Fold implements FoldingFeature {
        private final Rect bounds;
        private final Orientation orientation;
        private final State state;
        private final OcclusionType occlusion;

        Fold(Rect bounds, Orientation orientation, State state, OcclusionType occlusion) {
            this.bounds = bounds;
            this.orientation = orientation;
            this.state = state;
            this.occlusion = occlusion;
        }

        @NonNull @Override public Rect getBounds() { return bounds; }
        @NonNull @Override public OcclusionType getOcclusionType() { return occlusion; }
        @NonNull @Override public Orientation getOrientation() { return orientation; }
        @NonNull @Override public State getState() { return state; }
        @Override public boolean isSeparating() {
            return state == State.HALF_OPENED || occlusion == OcclusionType.FULL;
        }
    }

    private static WindowLayoutInfo info(DisplayFeature... features) {
        return new WindowLayoutInfo(List.of(features));
    }

    private static Fold halfOpened(Rect bounds, FoldingFeature.Orientation orientation) {
        return new Fold(bounds, orientation, FoldingFeature.State.HALF_OPENED, FoldingFeature.OcclusionType.NONE);
    }

    @Test
    public void noFeatures_isNone() {
        assertSame(FoldPosture.NONE, FoldPosture.from(info(), WINDOW));
        assertFalse(FoldPosture.NONE.isSplit());
    }

    @Test
    public void aFlatFoldWithAnInvisibleHinge_isNone() {
        Fold flat = new Fold(new Rect(420, 0, 420, 700), FoldingFeature.Orientation.VERTICAL,
                FoldingFeature.State.FLAT, FoldingFeature.OcclusionType.NONE);
        assertEquals(FoldPosture.Type.NONE, FoldPosture.from(info(flat), WINDOW).type());
    }

    @Test
    public void aHalfOpenedVerticalFold_isABook() {
        FoldPosture posture = FoldPosture.from(
                info(halfOpened(new Rect(420, 0, 420, 700), FoldingFeature.Orientation.VERTICAL)), WINDOW);
        assertEquals(FoldPosture.Type.BOOK, posture.type());
        assertTrue(posture.isSplit());
    }

    @Test
    public void aHalfOpenedHorizontalFold_isATabletop() {
        FoldPosture posture = FoldPosture.from(
                info(halfOpened(new Rect(0, 350, 840, 350), FoldingFeature.Orientation.HORIZONTAL)), WINDOW);
        assertEquals(FoldPosture.Type.TABLETOP, posture.type());
    }

    @Test
    public void aHingeThatCoversPartOfTheScreen_splitsEvenWhenFlat() {
        Fold hinge = new Fold(new Rect(416, 0, 424, 700), FoldingFeature.Orientation.VERTICAL,
                FoldingFeature.State.FLAT, FoldingFeature.OcclusionType.FULL);
        assertEquals(FoldPosture.Type.BOOK, FoldPosture.from(info(hinge), WINDOW).type());
    }

    @Test
    public void featuresThatAreNotFolds_areIgnored() {
        DisplayFeature cutout = () -> new Rect(0, 0, 40, 40);
        assertEquals(FoldPosture.Type.NONE, FoldPosture.from(info(cutout), WINDOW).type());
    }

    @Test
    public void book_usesTheWiderHalf() {
        // The right half is wider, so the app lives there and leaves the left alone.
        FoldPosture rightWider = FoldPosture.book(new Rect(400, 0, 408, 700), 840, 700);
        assertEquals(408, rightWider.leftInset());
        assertEquals(0, rightWider.rightInset());
        assertEquals(0, rightWider.bottomInset());

        FoldPosture leftWider = FoldPosture.book(new Rect(500, 0, 508, 700), 840, 700);
        assertEquals(0, leftWider.leftInset());
        assertEquals(340, leftWider.rightInset());
    }

    @Test
    public void equalHalves_goToTheStartSide() {
        FoldPosture even = FoldPosture.book(new Rect(420, 0, 420, 700), 840, 700);
        assertEquals(0, even.leftInset());
        assertEquals(420, even.rightInset());
    }

    @Test
    public void tabletop_usesTheTopHalf() {
        FoldPosture posture = FoldPosture.tabletop(new Rect(0, 340, 840, 348), 840, 700);
        assertEquals(0, posture.leftInset());
        assertEquals(0, posture.rightInset());
        assertEquals(360, posture.bottomInset());
    }

    @Test
    public void postures_compareByShape() {
        assertEquals(FoldPosture.book(new Rect(400, 0, 408, 700), 840, 700),
                FoldPosture.book(new Rect(400, 0, 408, 700), 840, 700));
        assertFalse(FoldPosture.book(new Rect(400, 0, 408, 700), 840, 700)
                .equals(FoldPosture.book(new Rect(300, 0, 308, 700), 840, 700)));
        assertFalse(FoldPosture.NONE.equals(FoldPosture.book(new Rect(400, 0, 408, 700), 840, 700)));
    }
}
