package com.doubleangels.redact.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import androidx.annotation.NonNull;

import com.doubleangels.redact.CleanPreset;
import com.google.android.material.color.MaterialColors;

/** A preset's name over its description, for the Settings list and the Clean tab's menu. */
public final class CleanPresetLabels {

    private CleanPresetLabels() {
    }

    @NonNull
    public static CharSequence twoLine(@NonNull Context context, @NonNull CleanPreset preset) {
        SpannableStringBuilder text = new SpannableStringBuilder(context.getString(preset.nameRes));
        int start = text.length();
        text.append('\n').append(context.getString(preset.descriptionRes));
        int color = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        text.setSpan(new ForegroundColorSpan(color), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.875f), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
}
