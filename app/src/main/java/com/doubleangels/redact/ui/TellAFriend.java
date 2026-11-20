package com.doubleangels.redact.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.AppMetrics;

/**
 * Opens the share sheet with a short message about Redact and its Play link. Only text is handed
 * over: no file, no URI grant, and no callback telling Redact which app or person it went to.
 */
public final class TellAFriend {

    private TellAFriend() {
    }

    public static void share(@NonNull Context context) {
        try {
            context.startActivity(Intent.createChooser(
                    buildIntent(context), context.getString(R.string.tell_friend_chooser_title)));
        } catch (ActivityNotFoundException none) {
            AppMetrics.externalAppMissing(AppMetrics.EXTERNAL_SHARE_SHEET);
            Notice.show(context, R.string.settings_link_unavailable, false);
        }
    }

    /** The plain-text share the chooser wraps. */
    @VisibleForTesting
    @NonNull
    static Intent buildIntent(@NonNull Context context) {
        String message = context.getString(
                R.string.tell_friend_message, StoreLinks.playListingUrl(context));
        return new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message)
                .putExtra(Intent.EXTRA_TITLE, context.getString(R.string.app_name));
    }
}
