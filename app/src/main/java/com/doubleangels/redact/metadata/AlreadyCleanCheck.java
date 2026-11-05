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

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.OutputDestination;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
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
        if (OutputDestination.isInChosenFolder(context, uri)) {
            return true;
        }
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
        List<String> outputDirs = new ArrayList<>(Arrays.asList(REDACT_OUTPUT_DIRS));
        // The picker's Images and Recent views hand out MediaStore URIs, so chosen folders are
        // matched by path here as well as by document ID above.
        outputDirs.addAll(OutputDestination.chosenRelativePaths(context));
        for (String dir : outputDirs) {
            if (isInsideDir(normalized, dir)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code relativePath} is {@code dir} or a folder inside it, ignoring case (shared storage does). */
    private static boolean isInsideDir(@NonNull String relativePath, @NonNull String dir) {
        String path = relativePath.toLowerCase(Locale.ROOT);
        String folder = dir.toLowerCase(Locale.ROOT);
        return path.equals(folder) || path.startsWith(folder + "/");
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
                if (ExifInterface.TAG_XMP.equals(tag) && value != null && UltraHdr.isPictureOnlyXmp(value)) {
                    continue; // the Ultra HDR or Motion Photo directory a clean keeps
                }
                if (value != null && !value.isEmpty()) {
                    return Status.NEEDS_CLEANING;
                }
            }
        } catch (IOException | RuntimeException e) {
            return Status.UNKNOWN;
        }
        // IPTC, comments, text chunks, appended images and videos, a HEIF's metadata items, boxes
        // and coded user data: everything a clean removes outside EXIF, read by what the file is
        // rather than its type. A file whose structure cannot be followed is cleaned as usual.
        ImageContainerMetadata.Report container = ImageContainerMetadata.scan(context, uri);
        if (!container.recognized) {
            return Status.UNKNOWN;
        }
        return container.findings.isEmpty() ? Status.ALREADY_CLEAN : Status.NEEDS_CLEANING;
    }

    private static boolean isExifReadableMime(@Nullable String mime) {
        if (mime == null) {
            return false;
        }
        String lower = mime.toLowerCase(Locale.ROOT);
        return lower.equals("image/jpeg") || lower.equals("image/png") || lower.equals("image/webp")
                || lower.equals("image/heic") || lower.equals("image/heif");
    }

    @NonNull
    private static Status assessVideo(@NonNull Context context, @NonNull Uri uri) {
        // The retriever misses many tags (QuickTime keys, 3GPP loci, XMP, Matroska tags), so a
        // video only counts as clean once its container has been read and holds none of them.
        VideoContainerMetadata.Report container = VideoContainerMetadata.scan(context, uri);
        if (!container.recognized) {
            return Status.UNKNOWN;
        }
        // What Keep Location and Keep Date Taken keep is not something a clean would remove.
        boolean keepLocation = !AppPreferences.isStrictClean(context) && AppPreferences.isPreserveLocation(context);
        boolean keepDate = !AppPreferences.isStrictClean(context) && AppPreferences.isPreserveDateTaken(context);
        for (String field : container.fields) {
            boolean kept = (keepLocation && container.isLocationField(field))
                    || (keepDate && field.equals("date"));
            if (!kept) {
                return Status.NEEDS_CLEANING;
            }
        }
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, uri);
            if ((!keepLocation && MetadataStripper.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)) != null)
                    || (!keepDate && MetadataStripper.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) != null)) {
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
