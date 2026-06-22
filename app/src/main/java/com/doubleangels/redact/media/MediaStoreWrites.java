package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Helpers for publishing MediaStore entries without exposing partial files to indexers.
 */
public final class MediaStoreWrites {

    private MediaStoreWrites() {
    }

    public static void markPending(@NonNull ContentValues values) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
    }

    public static void markPublished(@NonNull ContentResolver resolver, @Nullable Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
    }
}
