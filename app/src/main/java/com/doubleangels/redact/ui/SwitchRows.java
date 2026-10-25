package com.doubleangels.redact.ui;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Makes each settings row that ends in a switch toggle it when tapped anywhere, with a ripple, as
 * Material list items do. For TalkBack the row becomes the switch, read once with its label and
 * state, and the switch itself steps aside.
 */
public final class SwitchRows {

    private SwitchRows() {
    }

    /** Wires every row under {@code root} whose last child is a switch. */
    public static void wire(@NonNull View root) {
        if (root instanceof MaterialSwitch) {
            wireRowOf((MaterialSwitch) root);
        } else if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                wire(group.getChildAt(i));
            }
        }
    }

    private static void wireRowOf(@NonNull MaterialSwitch toggle) {
        if (!(toggle.getParent() instanceof LinearLayout row)
                || row.getOrientation() != LinearLayout.HORIZONTAL
                || row.getChildCount() < 2
                || row.getChildAt(row.getChildCount() - 1) != toggle
                || row.isClickable()) {
            return;
        }
        TypedValue ripple = new TypedValue();
        row.getContext().getTheme().resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) {
                toggle.performClick();
            }
        });
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
                info.setEnabled(toggle.isEnabled());
            }
        });
    }
}
