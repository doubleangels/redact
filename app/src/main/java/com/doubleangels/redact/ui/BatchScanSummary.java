package com.doubleangels.redact.ui;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.metadata.CleanStats;
import com.doubleangels.redact.metadata.MetadataSurvey;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Scan's summary of several files at once: how many of them carry a location, a date, the
 * phone's or camera's name, or a person's name, with the names of the files that have a
 * location, and a button that sends them all to Clean. Each file is read on the phone the way
 * Scan reads one; nothing is kept once the dialog closes.
 */
public final class BatchScanSummary {

    /** Most file names listed under "with a location"; the rest are counted. */
    private static final int MAX_NAMED = 10;

    /** What the files carried, counted. */
    static final class Tally {
        int files;
        int unreadable;
        int withLocation;
        int withDate;
        int withDevice;
        int withName;
        int clean;
        final List<String> locationNames = new ArrayList<>();

        void add(@NonNull String name, @Nullable CleanStats stats) {
            files++;
            if (stats == null) {
                unreadable++;
                return;
            }
            if (stats.hadLocation) {
                withLocation++;
                locationNames.add(name);
            }
            withDate += stats.hadDate ? 1 : 0;
            withDevice += stats.hadDevice ? 1 : 0;
            withName += stats.hadName ? 1 : 0;
            clean += stats.removedFields == 0 ? 1 : 0;
        }

        @NonNull
        String describe(@NonNull Resources res) {
            StringBuilder text = new StringBuilder(
                    res.getQuantityString(R.plurals.scan_batch_checked, files, files));
            line(text, res, R.plurals.scan_batch_location, withLocation);
            line(text, res, R.plurals.scan_batch_date, withDate);
            line(text, res, R.plurals.scan_batch_device, withDevice);
            line(text, res, R.plurals.scan_batch_name, withName);
            line(text, res, R.plurals.scan_batch_nothing, clean);
            line(text, res, R.plurals.scan_batch_unreadable, unreadable);
            if (!locationNames.isEmpty()) {
                text.append("\n\n").append(res.getString(R.string.scan_batch_location_files));
                int shown = Math.min(MAX_NAMED, locationNames.size());
                for (int i = 0; i < shown; i++) {
                    text.append("\n• ").append(locationNames.get(i));
                }
                if (locationNames.size() > shown) {
                    int more = locationNames.size() - shown;
                    text.append("\n").append(res.getQuantityString(R.plurals.scan_batch_more, more, more));
                }
            }
            return text.toString();
        }

        private static void line(@NonNull StringBuilder text, @NonNull Resources res, int plural, int count) {
            if (count > 0) {
                text.append("\n• ").append(res.getQuantityString(plural, count, count));
            }
        }
    }

    private BatchScanSummary() {
    }

    /**
     * Reads {@code items} off the main thread and shows what they carry. {@code onClean} gets
     * them all when the person chooses to clean them.
     */
    public static void show(@NonNull Fragment fragment, @NonNull List<MediaItem> items,
                            @NonNull Consumer<List<MediaItem>> onClean) {
        Context context = fragment.requireContext();
        Context app = context.getApplicationContext();
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.scan_batch_title)
                .setMessage(context.getResources().getQuantityString(
                        R.plurals.scan_batch_reading, items.size(), items.size()))
                .setNegativeButton(R.string.licenses_close, null)
                .setPositiveButton(R.string.scan_batch_clean, (d, w) -> onClean.accept(new ArrayList<>(items)))
                .show();
        FoldDialogs.place(dialog);
        // Not until every file has been read: the summary is what the choice is made on.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        Handler main = new Handler(Looper.getMainLooper());
        SentryManager.logEvent("Scan", "A summary scan of several files is starting.");
        Thread worker = new Thread(() -> {
            Tally tally = new Tally();
            try {
                for (MediaItem item : items) {
                    if (!dialog.isShowing()) {
                        return; // closed while reading: no need to finish
                    }
                    tally.add(item.displayName(app), MetadataSurvey.survey(app, item));
                }
            } catch (Exception | OutOfMemoryError e) {
                // Recorded, and the dialog closed, rather than a crash or a summary left reading.
                SentryManager.recordException(e);
                main.post(() -> {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                });
                return;
            }
            SentryManager.logEvent("Scan", "A summary scan of several files finished.");
            main.post(() -> {
                if (!dialog.isShowing() || !fragment.isAdded()) {
                    return;
                }
                dialog.setMessage(tally.describe(fragment.getResources()));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(tally.files > tally.unreadable);
            });
        }, "redact-batch-scan");
        worker.start();
    }
}
