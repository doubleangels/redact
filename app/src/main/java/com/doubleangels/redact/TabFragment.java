package com.doubleangels.redact;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * A top-level tab. Its fade through when tabs change runs on a hardware layer, so each frame
 * redraws one texture with a new alpha and scale instead of the whole screen's views into an
 * offscreen buffer; the layer is dropped as soon as the animation ends.
 */
public abstract class TabFragment extends Fragment {

    @Nullable
    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        if (nextAnim == 0 || getContext() == null) {
            return null;
        }
        Animator animator = AnimatorInflater.loadAnimator(getContext(), nextAnim);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                setLayer(View.LAYER_TYPE_HARDWARE);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                setLayer(View.LAYER_TYPE_NONE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                setLayer(View.LAYER_TYPE_NONE);
            }
        });
        return animator;
    }

    private void setLayer(int type) {
        View view = getView();
        if (view != null && view.getLayerType() != type) {
            view.setLayerType(type, null);
        }
    }
}
