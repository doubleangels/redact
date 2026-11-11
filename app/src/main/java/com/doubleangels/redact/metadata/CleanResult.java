package com.doubleangels.redact.metadata;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * What one {@link MetadataStripper} clean produced, handed back by the call itself rather than
 * left in fields for the caller to read afterwards, so two cleans can never mix up results.
 *
 * @param uri         the clean output (a gallery entry, a file in the chosen folder, or a
 *                    FileProvider URI for a share), or null when the clean failed
 * @param file        the clean file behind a share's URI, for cleanup; null otherwise
 * @param stats       what the clean found to remove, or null when it failed before reading
 * @param unsupported the clean failed because this device cannot decode the source's format
 */
public record CleanResult(@Nullable Uri uri, @Nullable File file, @Nullable CleanStats stats,
                          boolean unsupported) {

    /** Whether the clean produced an output. */
    public boolean succeeded() {
        return uri != null;
    }
}
