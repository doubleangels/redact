package com.doubleangels.redact.ui;

import android.app.Activity;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;
import androidx.window.layout.WindowMetricsCalculator;

/**
 * Tells {@code onChange} how the device is folded while the activity is started: flat, like a
 * book, or on a table. It reports the current posture at once and again whenever the fold moves
 * or the window changes size, and stops listening when the activity stops.
 */
public final class FoldTracker implements DefaultLifecycleObserver {

    private final Activity activity;
    private final Consumer<FoldPosture> onChange;
    private final Consumer<WindowLayoutInfo> listener = this::onLayoutInfo;
    private WindowInfoTrackerCallbackAdapter adapter;

    public FoldTracker(@NonNull Activity activity, @NonNull Consumer<FoldPosture> onChange) {
        this.activity = activity;
        this.onChange = onChange;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        adapter = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.Companion.getOrCreate(activity));
        adapter.addWindowLayoutInfoListener(activity, ContextCompat.getMainExecutor(activity), listener);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        if (adapter != null) {
            adapter.removeWindowLayoutInfoListener(listener);
            adapter = null;
        }
    }

    private void onLayoutInfo(@NonNull WindowLayoutInfo info) {
        Rect window = WindowMetricsCalculator.Companion.getOrCreate()
                .computeCurrentWindowMetrics(activity).getBounds();
        onChange.accept(FoldPosture.from(info, window));
    }
}
