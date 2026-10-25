package com.doubleangels.redact.ui;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;

/**
 * The summary card Clean, Convert and Censor share ({@code layout/summary_card}). Each tab sets its
 * icon, hint and select label here, so the three cards look and behave the same.
 */
public final class SummaryCard {

    private SummaryCard() {
    }

    /**
     * Sets up {@code card} for a tab. {@code hintTab} is the tab's {@code AppPreferences.HINT_}
     * name; its hint shows until a job on that tab has succeeded once.
     */
    public static void bind(@NonNull View card, @DrawableRes int icon, @StringRes int hint,
                            @StringRes int selectLabel, @NonNull String hintTab) {
        ((ImageView) card.findViewById(R.id.summaryIcon)).setImageResource(icon);
        ((TextView) card.findViewById(R.id.summaryHint)).setText(hint);
        View select = card.findViewById(R.id.selectButton);
        CharSequence label = card.getContext().getString(selectLabel);
        select.setContentDescription(label);
        select.setTooltipText(label);
        refreshHint(card, hintTab);
    }

    /** Hides the hint once the tab has been used; called again whenever the selection changes. */
    public static void refreshHint(@NonNull View card, @NonNull String hintTab) {
        boolean learned = AppPreferences.isSummaryHintLearned(card.getContext(), hintTab);
        card.findViewById(R.id.summaryHint).setVisibility(learned ? View.GONE : View.VISIBLE);
    }
}
