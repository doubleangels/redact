package com.doubleangels.redact.metadata;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.media.MediaItem;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What kinds of identifying details a file carries, read without cleaning it, for Scan's summary
 * of several files at once: whether it has a location, a date, the device's name, or a person's
 * name, and how many fields a clean would remove. Read on the phone like a scan; nothing is kept.
 */
public final class MetadataSurvey {

    /** Words in a video container's field names that name the camera or the software. */
    private static final List<String> DEVICE_WORDS = List.of("make", "model", "software", "encoder", "device");
    /** Words in a video container's field names that name a person. */
    private static final List<String> NAME_WORDS = List.of("artist", "author", "copyright", "owner", "creator");

    private MetadataSurvey() {
    }

    /** One file's findings, or null for a file that could not be read. */
    @Nullable
    public static CleanStats survey(@NonNull Context context, @NonNull MediaItem item) {
        try {
            return item.isVideo() ? video(context, item) : image(context, item);
        } catch (Exception | OutOfMemoryError e) {
            // One unreadable file is counted as such; the others are still surveyed.
            return null;
        }
    }

    @Nullable
    private static CleanStats image(@NonNull Context context, @NonNull MediaItem item) throws Exception {
        ImageContainerMetadata.Report container = ImageContainerMetadata.scan(context, item.uri());
        ExifInterface exif = MetadataDisplayer.createImageExifInterface(context, item.uri());
        CleanStats exifStats = exif != null
                ? CleanStats.fromExif(exif, Collections.emptySet())
                : new CleanStats(0, false);
        boolean exifCountedXmp = exif != null && exif.getAttribute(ExifInterface.TAG_XMP) != null;
        if (!container.recognized && (exif == null || exifStats.removedFields == 0)) {
            // Neither its structure nor any tag could be read: not a file this can vouch for.
            return null;
        }
        return exifStats.plus(container, exifCountedXmp);
    }

    @Nullable
    private static CleanStats video(@NonNull Context context, @NonNull MediaItem item) {
        VideoContainerMetadata.Report container = VideoContainerMetadata.scan(context, item.uri());
        if (!container.recognized) {
            return null; // a container this can't follow can't be vouched for
        }
        boolean location = container.hasLocation;
        boolean date = false;
        boolean device = false;
        boolean name = false;
        for (String field : container.fields) {
            String lower = field.toLowerCase(Locale.ROOT);
            date |= lower.contains("date") || lower.contains("time");
            device |= containsAny(lower, DEVICE_WORDS);
            name |= containsAny(lower, NAME_WORDS);
        }
        int fields = container.fields.size();
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, item.uri());
            if (CleanVerifier.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)) != null) {
                location = true;
            }
            if (CleanVerifier.normalizeMetadataValue(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) != null) {
                date = true;
            }
        } catch (Exception e) {
            // The container was read; the retriever only adds what it reports.
        }
        if (fields == 0 && (location || date)) {
            fields = (location ? 1 : 0) + (date ? 1 : 0);
        }
        return new CleanStats(fields, location, date, device, name);
    }

    private static boolean containsAny(@NonNull String text, @NonNull List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
