package com.doubleangels.redact.ui;

import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.CleanStats;

import java.util.List;

/**
 * What a clean batch removed, summed over the files that were cleaned successfully, phrased for
 * the status line and the completion notification.
 */
public final class CleanSummary {

    public final int fileCount;
    public final int removedFields;
    public final int filesWithLocation;

    CleanSummary(int fileCount, int removedFields, int filesWithLocation) {
        this.fileCount = fileCount;
        this.removedFields = removedFields;
        this.filesWithLocation = filesWithLocation;
    }

    @NonNull
    public static CleanSummary of(@NonNull List<CleanStats> stats) {
        int fields = 0;
        int withLocation = 0;
        for (CleanStats s : stats) {
            fields += s.removedFields;
            if (s.hadLocation) {
                withLocation++;
            }
        }
        return new CleanSummary(stats.size(), fields, withLocation);
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

    /** {@code status} followed by the summary, or {@code status} alone when there is none. */
    @NonNull
    public static String append(@NonNull String status, @Nullable String summary) {
        return summary == null || summary.isEmpty() ? status : status + " " + summary;
    }
}
