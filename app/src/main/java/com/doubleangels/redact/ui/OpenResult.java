package com.doubleangels.redact.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.R;

import java.util.List;

/**
 * Opens a file Redact just saved in the app the user views photos and videos with, so they can
 * look at the result without leaving to find it in the gallery.
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
            type = null;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type != null ? type : "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(context instanceof Activity)) {
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            Notice.show(context, R.string.open_result_no_app, false);
        }
    }
}
