package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * What a clean removed from one file, for the summary shown when a batch finishes.
 *
 * <p>Fields are counted with the same rules {@link AlreadyCleanCheck} and the stripper's
 * verification use: tags the current settings keep and {@link
 * MetadataStripper#NON_IDENTIFYING_UNCLEARABLE_TAGS} are not counted, so a file that would trigger
 * the already-clean warning reports zero.
 */
public final class CleanStats {

    /** Identifying metadata fields the file carried that the clean removed. */
    public final int removedFields;
    /** True when the file carried a usable location that the clean removed. */
    public final boolean hadLocation;

    public CleanStats(int removedFields, boolean hadLocation) {
        this.removedFields = removedFields;
        this.hadLocation = hadLocation;
    }

    /**
     * Counts the identifying EXIF tags in {@code exif}, skipping {@code preservedTags}. Location
     * counts only when the coordinates are real: MediaStore zeroes GPS for apps without
     * ACCESS_MEDIA_LOCATION, and such a copy never carried a location to remove.
     */
    @NonNull
    static CleanStats fromExif(@NonNull ExifInterface exif, @NonNull Set<String> preservedTags) {
        Set<String> found = new HashSet<>();
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
            if (tag == null || preservedTags.contains(tag)
                    || MetadataStripper.NON_IDENTIFYING_UNCLEARABLE_TAGS.contains(tag)) {
                continue;
            }
            String value = exif.getAttribute(tag);
            if (value != null && !value.isEmpty()) {
                found.add(tag);
            }
        }
        double[] latLong = exif.getLatLong();
        boolean hadLocation = !preservedTags.contains(ExifInterface.TAG_GPS_LATITUDE)
                && latLong != null
                && MetadataDisplayer.isUsableMapCoordinate(latLong[0], latLong[1]);
        return new CleanStats(found.size(), hadLocation);
    }
}
