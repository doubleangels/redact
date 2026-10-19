package com.doubleangels.redact.ui;

import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.CleanStats;

import java.util.ArrayList;
import java.util.List;

/**
 * What a clean batch removed, summed over the files that were cleaned successfully, phrased for
 * the status line and the completion notification.
 */
public final class CleanSummary {

    public final int fileCount;
    public final int removedFields;
    public final int filesWithLocation;
    public final int filesWithDate;
    public final int filesWithDevice;
    public final int filesWithName;

    CleanSummary(int fileCount, int removedFields, int filesWithLocation) {
        this(fileCount, removedFields, filesWithLocation, 0, 0, 0);
    }

    CleanSummary(int fileCount, int removedFields, int filesWithLocation, int filesWithDate,
                 int filesWithDevice, int filesWithName) {
        this.fileCount = fileCount;
        this.removedFields = removedFields;
        this.filesWithLocation = filesWithLocation;
        this.filesWithDate = filesWithDate;
        this.filesWithDevice = filesWithDevice;
        this.filesWithName = filesWithName;
    }

    @NonNull
    public static CleanSummary of(@NonNull List<CleanStats> stats) {
        int fields = 0;
        int withLocation = 0;
        int withDate = 0;
        int withDevice = 0;
        int withName = 0;
        for (CleanStats s : stats) {
            fields += s.removedFields;
            withLocation += s.hadLocation ? 1 : 0;
            withDate += s.hadDate ? 1 : 0;
            withDevice += s.hadDevice ? 1 : 0;
            withName += s.hadName ? 1 : 0;
        }
        return new CleanSummary(stats.size(), fields, withLocation, withDate, withDevice, withName);
    }

    /**
     * One or two sentences to follow the "cleaned N files" line, e.g. "Removed 24 metadata fields,
     * including location." for one file, or "Removed 112 metadata fields. 3 files had location."
     * for several. Null when no file was cleaned.
     */
    @Nullable
    public String describe(@NonNull Resources res) {
        if (fileCount == 0) {
            return null;
        }
        if (removedFields == 0) {
            return res.getString(R.string.clean_summary_nothing_found);
        }
        if (fileCount == 1) {
            int plural = filesWithLocation > 0
                    ? R.plurals.clean_summary_fields_location
                    : R.plurals.clean_summary_fields;
            return res.getQuantityString(plural, removedFields, removedFields);
        }
        String fields = res.getQuantityString(
                R.plurals.clean_summary_fields, removedFields, removedFields);
        if (filesWithLocation == 0) {
            return fields;
        }
        return fields + " " + res.getQuantityString(
                R.plurals.clean_summary_location_files, filesWithLocation, filesWithLocation);
    }

    /**
     * The kinds of personal detail the batch removed besides location, which {@link #describe}
     * already mentions, with how many files carried each, e.g. "In this batch — Date &amp; Time
     * Taken: 3 · Camera &amp; Phone: 3". Null when there were none.
     */
    @Nullable
    public String describeDetails(@NonNull Resources res) {
        List<String> items = new ArrayList<>();
        addItem(res, items, R.string.stats_removed_dates, filesWithDate);
        addItem(res, items, R.string.stats_removed_devices, filesWithDevice);
        addItem(res, items, R.string.stats_removed_names, filesWithName);
        return items.isEmpty() ? null
                : res.getString(R.string.notification_clean_batch_details, joinItems(items));
    }

    private static void addItem(@NonNull Resources res, @NonNull List<String> items, int label, long count) {
        if (count > 0) {
            items.add(item(res, res.getString(label), count));
        }
    }

    /** One "Label: 1,284" figure, numbered for the current locale. */
    @NonNull
    public static String item(@NonNull Resources res, @NonNull String label, long count) {
        return res.getString(R.string.notification_stat_item, label,
                java.text.NumberFormat.getIntegerInstance().format(count));
    }

    /** Figures separated by middle dots, which read the same in every language the app ships. */
    @NonNull
    public static String joinItems(@NonNull List<String> items) {
        return String.join(" · ", items);
    }

    /** {@code status} followed by the summary, or {@code status} alone when there is none. */
    @NonNull
    public static String append(@NonNull String status, @Nullable String summary) {
        return summary == null || summary.isEmpty() ? status : status + " " + summary;
    }
}
