package com.doubleangels.redact.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.List;

/**
 * Opens a file Redact just saved in the app the user views photos and videos with, so they can
 * look at the result without leaving to find it in the gallery.
 *
 * <p>Redact itself takes photos and videos opened from other apps (into Scan), so it is one of the
 * viewers Android offers. With a default viewer set, the file goes straight to it. Otherwise, or if
 * Redact was made the default, the user picks from a list that leaves Redact out: opening a result
 * back in Redact would only land in Scan, and choosing Always there would make every Open do so.
 * Seeing the other viewers needs the {@code <queries>} entry in the manifest.
 */
public final class OpenResult {

    private OpenResult() {
    }

    /** Shows the Open button only after a completed job that saved exactly one file. */
    public static void updateButton(@NonNull View button, boolean completed, @NonNull List<Uri> outputs) {
        button.setVisibility(completed && outputs.size() == 1 ? View.VISIBLE : View.GONE);
    }

    /** Opens {@code uri} for viewing, or says no app can when none is installed. */
    public static void open(@NonNull Context context, @Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        String type;
        try {
            type = context.getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            // The viewer is asked to work out the type itself.
            SentryManager.logEvent("ui", "A saved result's type could not be read: "
                    + e.getClass().getSimpleName() + ".");
            type = null;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type != null ? type : "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(null, uri));

        Intent launch = view;
        if (!hasOtherDefaultViewer(context, view)) {
            launch = Intent.createChooser(view, context.getString(R.string.open_result_chooser_title))
                    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{
                            new ComponentName(context.getPackageName(),
                                    "com.doubleangels.redact.MainActivity")})
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (!(context instanceof Activity)) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        SentryManager.logEvent("ui", "A saved result is being opened in another app.");
        try {
            context.startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            SentryManager.logEvent("ui", "No app could open a saved result.");
            Notice.show(context, R.string.open_result_no_app, false);
        }
    }

    /** Whether a viewer other than Redact is set to open {@code view} without asking. */
    private static boolean hasOtherDefaultViewer(@NonNull Context context, @NonNull Intent view) {
        try {
            PackageManager pm = context.getPackageManager();
            return isOtherDefaultViewer(
                    pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY),
                    pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY),
                    context.getPackageName());
        } catch (RuntimeException e) {
            // Unsure: the list is always safe, only one tap longer.
            return false;
        }
    }

    /**
     * Whether {@code chosen}, what Android would open without asking, is a default viewer that is
     * not Redact. With no default, Android names its own picker instead, which is not one of the
     * {@code candidates}; that is how a real default is told apart from the picker.
     */
    @VisibleForTesting
    static boolean isOtherDefaultViewer(@Nullable ResolveInfo chosen,
                                        @Nullable List<ResolveInfo> candidates,
                                        @NonNull String ownPackage) {
        if (chosen == null || chosen.activityInfo == null || candidates == null) {
            return false;
        }
        ActivityInfo target = chosen.activityInfo;
        if (ownPackage.equals(target.packageName)) {
            return false;
        }
        for (ResolveInfo candidate : candidates) {
            ActivityInfo info = candidate.activityInfo;
            if (info != null && target.packageName.equals(info.packageName)
                    && target.name.equals(info.name)) {
                return true;
            }
        }
        return false;
    }
}
