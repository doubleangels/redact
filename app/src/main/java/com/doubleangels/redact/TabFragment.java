package com.doubleangels.redact;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.ui.DroppedMedia;
import com.doubleangels.redact.ui.FoldPosture;
import com.doubleangels.redact.ui.MainViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * A top-level tab. Its fade through when tabs change runs on a hardware layer, so each frame
 * redraws one texture with a new alpha and scale instead of the whole screen's views into an
 * offscreen buffer; the layer is dropped as soon as the animation ends.
 */
public abstract class TabFragment extends Fragment {

    /** Tag of the view that holds a tab's content column and its side padding. */
    private static final String EDGE_PADDED_TAG = "edgePadded";

    /** Tag of an action button that lines up with the end of the content column. */
    private static final String EDGE_FAB_TAG = "edgeFab";

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MainViewModel viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        viewModel.getFoldPosture().observe(getViewLifecycleOwner(), posture -> fitToPane(view, posture));
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                fitToPane(v, viewModel.getFoldPosture().getValue());
            }
        });
        if (acceptsMedia()) {
            DroppedMedia.acceptDrops(requireActivity(), view, this::onMediaReceived);
            View paste = view.findViewById(R.id.emptyStatePasteButton);
            if (paste != null) {
                paste.setOnClickListener(v -> pasteMedia());
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPasteButton();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            refreshPasteButton();
        }
    }

    /**
     * Whether this tab takes photos and videos dropped on it or pasted into it, handed to
     * {@link #onMediaReceived}. Clean, Scan, Convert and Censor do.
     */
    boolean acceptsMedia() {
        return false;
    }

    /**
     * Photos and videos dropped on this tab, pasted into it, or picked: other apps' content
     * URIs that have passed {@link com.doubleangels.redact.media.InboundUriPolicy}.
     */
    void onMediaReceived(@NonNull List<android.net.Uri> uris) {
    }

    /** Pastes the photos and videos on the clipboard, when the person asks: the button or Ctrl+V. */
    boolean pasteMedia() {
        if (!acceptsMedia() || getContext() == null) {
            return false;
        }
        List<android.net.Uri> uris = DroppedMedia.fromClipboard(requireContext());
        if (uris.isEmpty()) {
            return false;
        }
        onMediaReceived(uris);
        return true;
    }

    /** Shows the empty state's Paste button only while the clipboard holds a photo or video. */
    void refreshPasteButton() {
        View root = getView();
        View paste = root != null ? root.findViewById(R.id.emptyStatePasteButton) : null;
        if (paste != null) {
            paste.setVisibility(acceptsMedia() && DroppedMedia.clipboardOffersMedia(requireContext())
                    ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * The side padding of a tab comes from the window width, which suits a whole window but not
     * one half of a screen folded like a book: half of a 840dp window would be left with a few
     * dozen dp of content. While the screen is folded like that, the padding is worked out from the
     * half the tab really has, and the original padding returns when it is flat again.
     */
    /**
     * A runtime permission result MainActivity received for this tab. Tabs that ask for
     * permissions override it; the others ignore it.
     */
    void handlePermissionResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    }

    /** MainActivity's first-run permission flow has finished while this tab was showing. */
    void onHostPermissionFlowCompleted() {
    }

    private static void fitToPane(@NonNull View root, @Nullable FoldPosture posture) {
        View column = root.findViewWithTag(EDGE_PADDED_TAG);
        if (column == null) {
            return;
        }
        int[] original = (int[]) column.getTag(R.id.edge_padding_original);
        if (original == null) {
            original = new int[] {column.getPaddingLeft(), column.getPaddingRight()};
            column.setTag(R.id.edge_padding_original, original);
        }
        int left = original[0];
        int right = original[1];
        if (posture != null && posture.type() == FoldPosture.Type.BOOK) {
            int width = root.getWidth();
            if (width == 0) {
                return;
            }
            Resources res = root.getResources();
            int centred = Math.max(res.getDimensionPixelSize(R.dimen.screen_edge_padding_compact),
                    (width - res.getDimensionPixelSize(R.dimen.content_max_width)) / 2);
            left = centred;
            right = centred;
        }
        if (left != column.getPaddingLeft() || right != column.getPaddingRight()) {
            column.setPadding(left, column.getPaddingTop(), right, column.getPaddingBottom());
        }
        // The action buttons sit at the end edge of the column, so they follow its padding.
        List<View> buttons = new ArrayList<>();
        collectWithTag(root, EDGE_FAB_TAG, buttons);
        for (View button : buttons) {
            if (!(button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) {
                continue;
            }
            Integer originalMargin = (Integer) button.getTag(R.id.edge_margin_original);
            if (originalMargin == null) {
                originalMargin = params.getMarginEnd();
                button.setTag(R.id.edge_margin_original, originalMargin);
            }
            int margin = posture != null && posture.type() == FoldPosture.Type.BOOK ? right : originalMargin;
            if (params.getMarginEnd() != margin) {
                params.setMarginEnd(margin);
                button.setLayoutParams(params);
            }
        }
    }

    private static void collectWithTag(@NonNull View view, @NonNull String tag, @NonNull List<View> out) {
        if (tag.equals(view.getTag())) {
            out.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectWithTag(group.getChildAt(i), tag, out);
            }
        }
    }

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
