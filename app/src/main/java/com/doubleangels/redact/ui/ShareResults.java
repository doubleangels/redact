package com.doubleangels.redact.ui;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.doubleangels.redact.R;

import java.util.ArrayList;
import java.util.List;

/** Opens the system share sheet for files Redact just saved. */
public final class ShareResults {

    private ShareResults() {}

    /** Shows the Share button only after a completed batch that saved something. */
    public static void updateButton(@NonNull android.view.View button, boolean completed,
                                    @NonNull List<Uri> outputs) {
        button.setVisibility(completed && !outputs.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /**
     * Leaves Redact's own share targets out of {@code chooser}. A share into Redact forwards
     * Redact's own file, which they all refuse, so picking one would only show an error; and
     * sending a result back into Redact from a share sheet is never needed, since the tabs (and
     * Scan Result) are a tap away.
     */
    @NonNull
    public static Intent withoutOwnTargets(@NonNull Context context, @NonNull Intent chooser) {
        String pkg = context.getPackageName();
        return chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{
                new ComponentName(pkg, "com.doubleangels.redact.ShareHandlerActivity"),
                new ComponentName(pkg, "com.doubleangels.redact.ScanShareTarget"),
                new ComponentName(pkg, "com.doubleangels.redact.ConvertShareTarget"),
                new ComponentName(pkg, "com.doubleangels.redact.CensorShareTarget")});
    }

    public static void share(@NonNull Context context, @NonNull List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        String type = commonType(context, uris);
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.setType(type);
        if (uris.size() == 1) {
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        ClipData clip = ClipData.newRawUri(null, uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(withoutOwnTargets(context,
                    Intent.createChooser(send, context.getString(R.string.share_results_chooser_title))));
            com.doubleangels.redact.stats.StatsStore.recordShare(context);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(context, R.string.share_error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    /** The exact MIME type for one file; a family wildcard when all files match; else any type. */
    static String commonType(@NonNull Context context, @NonNull List<Uri> uris) {
        String common = null;
        for (Uri uri : uris) {
            String type = context.getContentResolver().getType(uri);
            if (type == null) {
                return "*/*";
            }
            if (uris.size() == 1) {
                return type;
            }
            String family = type.substring(0, type.indexOf('/') + 1) + "*";
            if (common == null) {
                common = family;
            } else if (!common.equals(family)) {
                return "*/*";
            }
        }
        return common != null ? common : "*/*";
    }
}
