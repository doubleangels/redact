package com.doubleangels.redact.media;

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared MIME filters and helpers for SAF-based media selection.
 * Uses the system document picker so files are visible from Downloads, Files, and other
 * providers—not only Google Photos.
 */
public final class MediaPickerContracts {

    public static final String[] IMAGE_AND_VIDEO_MIME_TYPES = {"image/*", "video/*"};

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
