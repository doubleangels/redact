package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides, before a clean starts, whether a selected file has anything left to remove.
 *
 * <p>A file counts as {@link Status#ALREADY_CLEAN} when it is one of Redact's own outputs (it lives
 * under {@code Pictures/Redact} or {@code Movies/Redact}), or when it can be read and carries none of
 * the identifying metadata a clean would remove. The rules mirror what the stripper verifies after a
 * clean, including the tags the current settings preserve. Nothing is stored: the verdict comes from
 * the file itself, so no history of the user's files is kept.
 *
 * <p>Anything that cannot be read with confidence is {@link Status#UNKNOWN} and is cleaned as usual,
 * so this check can only add a warning, never block or skip work on its own.
 */
public final class AlreadyCleanCheck {

    public enum Status {
        /** Metadata that a clean would remove is present. */
        NEEDS_CLEANING,
        /** Nothing identifying to remove, or the file is already a Redact output. */
        ALREADY_CLEAN,
        /** The file could not be read reliably, or the time budget ran out. */
        UNKNOWN
    }

    private static final String[] REDACT_OUTPUT_DIRS = {"Pictures/Redact", "Movies/Redact"};
    private static final int XMP_SCAN_LIMIT = 256 * 1024;

    private AlreadyCleanCheck() {
    }

    /**
     * Assesses every item in order, one {@link Status} per item. Items reached after
     * {@code budgetMs} has elapsed are {@link Status#UNKNOWN}, so a large batch of slow (for example
     * cloud-backed) files cannot hold the clean up. Call off the main thread.
     */
    @NonNull
    public static List<Status> assess(
            @NonNull Context context, @NonNull List<MediaItem> items, long budgetMs) {
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        List<Status> result = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            if (SystemClock.elapsedRealtime() > deadline) {
                result.add(Status.UNKNOWN);
                continue;
            }
            Status status;
            try {
                status = assessItem(context, item);
            } catch (Exception e) {
                status = Status.UNKNOWN;
            }
            result.add(status);
        }
        return result;
    }

    @NonNull
    static Status assessItem(@NonNull Context context, @NonNull MediaItem item) {
        if (isRedactOutput(context, item.uri())) {
            return Status.ALREADY_CLEAN;
        }
        return item.isVideo() ? assessVideo(context, item.uri()) : assessImage(context, item.uri());
    }

    static boolean isRedactOutput(@NonNull Context context, @NonNull Uri uri) {
        String relativePath = queryRelativePath(context, uri);
        if (relativePath == null) {
            try {
                Uri mediaUri = MediaStore.getMediaUri(context, uri);
                if (mediaUri != null) {
                    relativePath = queryRelativePath(context, mediaUri);
                }
            } catch (Exception ignored) {
                // Not a MediaStore-backed URI.
            }
        }
        if (relativePath == null) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        for (String dir : REDACT_OUTPUT_DIRS) {
            if (normalized.equals(dir) || normalized.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String queryRelativePath(@NonNull Context context, @NonNull Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[] {MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
            // Provider does not expose RELATIVE_PATH.
        }
        return null;
    }

    @NonNull
    private static Status assessImage(@NonNull Context context, @NonNull Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri);
        if (!isExifReadableMime(mime)) {
            return Status.UNKNOWN;
        }
        Set<String> ignored = new HashSet<>(MetadataStripper.tagsToPreserve(context, false));
        ignored.addAll(MetadataStripper.NON_IDENTIFYING_UNCLEARABLE_TAGS);
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return Status.UNKNOWN;
            }
            ExifInterface exif = new ExifInterface(in);
            for (Field field : ExifInterface.class.getDeclaredFields()) {
                if (field.getType() != String.class || !field.getName().startsWith("TAG_")) {
                    continue;
                }
                String tag;
                try {
                    tag = (String) field.get(null);
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    continue;
                }
                if (tag == null || ignored.contains(tag)) {
                    continue;
                }
                String value = exif.getAttribute(tag);
                if (value != null && !value.isEmpty()) {
                    return Status.NEEDS_CLEANING;
                }
            }
        } catch (IOException | RuntimeException e) {
            return Status.UNKNOWN;
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return Status.UNKNOWN;
            }
            return containsXmp(in) ? Status.NEEDS_CLEANING : Status.ALREADY_CLEAN;
        } catch (IOException | RuntimeException e) {
            return Status.UNKNOWN;
        }
    }

    private static boolean isExifReadableMime(@Nullable String mime) {
        if (mime == null) {
            return false;
        }
        String lower = mime.toLowerCase(Locale.ROOT);
        return lower.equals("image/jpeg") || lower.equals("image/png") || lower.equals("image/webp")
                || lower.equals("image/heic") || lower.equals("image/heif");
    }

    /** Looks for XMP packet markers in the leading bytes, where XMP lives in JPEG, PNG, WebP and HEIC. */
    private static boolean containsXmp(@NonNull InputStream in) throws IOException {
        byte[] buffer = new byte[XMP_SCAN_LIMIT];
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) > 0) {
            total += read;
        }
        String content = new String(buffer, 0, total, StandardCharsets.ISO_8859_1);
        return content.contains("<?xpacket begin") || content.contains("<x:xmpmeta");
    }

    @NonNull
    private static Status assessVideo(@NonNull Context context, @NonNull Uri uri) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, uri);
            if (MetadataStripper.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)) != null
                    || MetadataStripper.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) != null) {
                return Status.NEEDS_CLEANING;
            }
            for (int key : MetadataStripper.ADDITIONAL_VIDEO_PRIVACY_KEYS) {
                if (MetadataStripper.normalizeMetadataValue(retriever.extractMetadata(key)) != null) {
                    return Status.NEEDS_CLEANING;
                }
            }
            return Status.ALREADY_CLEAN;
        } catch (Exception e) {
            SentryManager.log("The already-clean check could not read a video.");
            return Status.UNKNOWN;
        }
    }
}
