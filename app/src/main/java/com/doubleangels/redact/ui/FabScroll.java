package com.doubleangels.redact.ui;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

/**
 * Collapses extended floating buttons to their icon while the list under them scrolls down, so they
 * cover less of it, and brings their labels back on the way up or at the top, as Material does.
 */
public final class FabScroll {

    /** Scrolls smaller than this, in dp, are taken as jitter and change nothing. */
    private static final int SLOP_DP = 4;

    private FabScroll() {
    }

    public static void shrinkOnScroll(@NonNull NestedScrollView scroll,
                                      @NonNull ExtendedFloatingActionButton... fabs) {
        int slop = Math.round(SLOP_DP * scroll.getResources().getDisplayMetrics().density);
        scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, x, y, oldX, oldY) -> {
                    int dy = y - oldY;
                    boolean extend = y == 0 || dy < -slop;
                    boolean shrink = !extend && dy > slop;
                    for (ExtendedFloatingActionButton fab : fabs) {
                        if (extend && !fab.isExtended()) {
                            fab.extend();
                        } else if (shrink && fab.isExtended()) {
                            fab.shrink();
                        }
                    }
                });
    }
}
