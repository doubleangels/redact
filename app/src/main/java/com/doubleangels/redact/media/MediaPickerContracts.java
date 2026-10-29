package com.doubleangels.redact.media;

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared MIME filters and helpers for SAF-based media selection.
 * Uses the system document picker so files are visible from Downloads, Files, and other
 * providers, not only Google Photos.
 */
public final class MediaPickerContracts {

    public static final String[] IMAGE_AND_VIDEO_MIME_TYPES = {"image/*", "video/*"};

    /** Most files Clean or Convert accepts from their file pickers in one run. */
    public static final int MAX_PICK_ITEMS = 100;

    /**
     * Most files accepted when another app shares into Redact. Lower than the picker limit because
     * a share is copied into the cache before it is processed.
     */
    public static final int MAX_SHARE_ITEMS = 50;

    private MediaPickerContracts() {
    }

    @NonNull
    public static List<Uri> trimToMax(@NonNull List<Uri> uris, int max) {
        if (uris.size() <= max) {
            return uris;
        }
        return new ArrayList<>(uris.subList(0, max));
    }
}
