package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides, before a clean starts, whether a selected file has anything left to remove.
 *
 * <p>A file counts as {@link Status#ALREADY_CLEAN} only when it can be read and carries none of the
 * identifying metadata a clean would remove. Its folder never decides it: the output folder can be
 * one that also holds originals, and anything can be moved into {@code Pictures/Redact}. Redact's own
 * outputs pass because their contents are clean. The rules mirror what the stripper verifies after a
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
        /** Nothing identifying to remove: the file was read and holds nothing a clean would take out. */
        ALREADY_CLEAN,
        /** The file could not be read reliably, or the time budget ran out. */
        UNKNOWN
    }

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
        // Always read the file. Where it sits says nothing about it: the output folder can be one
        // that also holds originals (DCIM/Camera, Download), and a photo can be moved into
        // Pictures/Redact by hand, so a folder rule would call uncleaned originals clean.
        return item.isVideo() ? assessVideo(context, item.uri()) : assessImage(context, item.uri());
    }

    @NonNull
    private static Status assessImage(@NonNull Context context, @NonNull Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri);
        if (!isExifReadableMime(mime)) {
            return Status.UNKNOWN;
        }
        Set<String> ignored = new HashSet<>(MetadataStripper.tagsToPreserve(context, false));
        ignored.addAll(CleanVerifier.NON_IDENTIFYING_UNCLEARABLE_TAGS);
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
            if ((!keepLocation && CleanVerifier.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)) != null)
                    || (!keepDate && CleanVerifier.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) != null)) {
                return Status.NEEDS_CLEANING;
            }
            for (int key : CleanVerifier.ADDITIONAL_VIDEO_PRIVACY_KEYS) {
                if (CleanVerifier.normalizeMetadataValue(retriever.extractMetadata(key)) != null) {
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
