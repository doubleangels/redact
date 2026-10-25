package com.doubleangels.redact.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.Fragment;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * Asks once, after a few successful jobs, whether the user would rate Redact on Google Play. The
 * question has only Yes and No; either answer ends it for good, and Settings › About keeps a row
 * for rating whenever the user likes.
 *
 * <p>The dialog opens the Play listing rather than the Play In-App Review API: that API's
 * guidelines forbid asking anything before it shows, and it would add a Google Play dependency to
 * an app that otherwise has none.
 */
public final class ReviewPrompt {

    /** Successful cleans, converts, scans and share-ins before asking. */
    @VisibleForTesting
    static final int ASK_AFTER = 3;

    // Kept by AppPreferences.resetAll, so resetting settings never brings the question back.
    public static final String KEY_SUCCESS_COUNT = "review_success_count";
    public static final String KEY_STATE = "review_state";

    @VisibleForTesting
    static final int STATE_NOT_ASKED = 0;
    /** Answered Yes or No: never ask again. */
    @VisibleForTesting
    static final int STATE_DONE = 2;

    private ReviewPrompt() {}

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Counts one job that finished with every file saved. */
    public static void recordSuccessfulJob(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        if (p.getInt(KEY_STATE, STATE_NOT_ASKED) == STATE_DONE) {
            return;
        }
        p.edit().putInt(KEY_SUCCESS_COUNT, p.getInt(KEY_SUCCESS_COUNT, 0) + 1).apply();
    }

    @VisibleForTesting
    static boolean isDue(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        return p.getInt(KEY_STATE, STATE_NOT_ASKED) != STATE_DONE
                && p.getInt(KEY_SUCCESS_COUNT, 0) >= ASK_AFTER;
    }

    /**
     * Counts a successful job and, when enough have built up, asks for a rating over {@code host},
     * which must be the visible screen the job finished on.
     */
    public static void onJobSucceeded(@NonNull Fragment host) {
        Context context = host.getContext();
        if (context == null) {
            return;
        }
        recordSuccessfulJob(context);
        askIfDue(host);
    }

    /**
     * Asks for a rating over {@code host} when enough jobs have succeeded and it is on screen. The
     * jobs are counted by whoever ran them, so one that finished while the app was in the
     * background or another tab was showing is still asked about once the user is back.
     */
    public static void askIfDue(@NonNull Fragment host) {
        Context context = host.getContext();
        if (context != null && isDue(context)
                && host.isAdded() && !host.isHidden() && host.getView() != null) {
            show(host);
        }
    }

    private static void show(@NonNull Fragment host) {
        Context context = host.requireContext();
        // Answered once only: marked done before the dialog shows, so a rotation or process death
        // while it is open never brings it back.
        finish(context);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.review_prompt_title)
                .setMessage(R.string.review_prompt_message)
                .setCancelable(false)
                .setPositiveButton(R.string.review_prompt_yes, (d, w) -> openStoreListing(context))
                .setNegativeButton(R.string.review_prompt_no, (d, w) -> {
                    View view = host.getView();
                    if (view != null) {
                        showDeclinedNote(view, R.string.review_prompt_declined);
                    }
                })
                .show();
    }

    private static void finish(@NonNull Context context) {
        prefs(context).edit().putInt(KEY_STATE, STATE_DONE).apply();
    }

    /** Says the question won't come back and where the setting lives, above the bottom bar. */
    static void showDeclinedNote(@NonNull View view, int messageRes) {
        Snackbar bar = Snackbar.make(view, messageRes, Snackbar.LENGTH_LONG);
        View nav = view.getRootView().findViewById(R.id.bottomNavigation);
        if (nav != null) {
            bar.setAnchorView(nav);
        }
        bar.show();
    }

    /** Opens Redact's Google Play page, in the Play Store app when there is one. */
    public static void openStoreListing(@NonNull Context context) {
        String pkg = context.getPackageName();
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException noStore) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (ActivityNotFoundException noBrowser) {
                Notice.show(context, R.string.settings_link_unavailable, false);
            }
        }
    }
}
