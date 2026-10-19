package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Locale;
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
    /** True when the clean removed when the file was taken or made. */
    public final boolean hadDate;
    /** True when the clean removed the camera, phone or lens that made the file. */
    public final boolean hadDevice;
    /** True when the clean removed a person's name: an author, artist, owner or copyright holder. */
    public final boolean hadName;

    public CleanStats(int removedFields, boolean hadLocation) {
        this(removedFields, hadLocation, false, false, false);
    }

    public CleanStats(int removedFields, boolean hadLocation, boolean hadDate, boolean hadDevice,
                      boolean hadName) {
        this.removedFields = removedFields;
        this.hadLocation = hadLocation;
        this.hadDate = hadDate;
        this.hadDevice = hadDevice;
        this.hadName = hadName;
    }

    /** EXIF tags that say which camera, phone or lens made the file. */
    private static final Set<String> DEVICE_TAGS = Set.of(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_SERIAL_NUMBER);

    /** EXIF tags that name a person. */
    private static final Set<String> NAME_TAGS = Set.of(
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_CAMERA_OWNER_NAME);

    /** EXIF tags that say when the file was taken, made or changed. */
    private static boolean isDateTag(@NonNull String tag) {
        return tag.startsWith("DateTime") || tag.startsWith("OffsetTime")
                || tag.startsWith("SubSecTime") || tag.equals(ExifInterface.TAG_GPS_DATESTAMP);
    }

    /**
     * Sorts the identifying fields a video container carried, by their name as {@link
     * VideoContainerMetadata} reports it, into dates, devices and names. Location is reported by
     * the container scan itself.
     */
    @NonNull
    static CleanStats withVideoFields(int removedFields, boolean hadLocation, boolean retrieverDate,
                                      boolean retrieverName, @NonNull Iterable<String> containerFields) {
        boolean date = retrieverDate;
        boolean device = false;
        boolean name = retrieverName;
        for (String field : containerFields) {
            String f = field.toLowerCase(Locale.ROOT);
            date |= f.contains("date") || f.equals("©day");
            device |= f.contains("make") || f.contains("model") || f.contains("manufacturer")
                    || f.equals("©mak") || f.equals("©mod");
            name |= f.contains("author") || f.contains("artist") || f.contains("copyright")
                    || f.equals("©aut") || f.equals("©art") || f.equals("©cpy") || f.equals("©wrt");
        }
        return new CleanStats(removedFields, hadLocation, date, device, name);
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
        boolean hadDate = false;
        boolean hadDevice = false;
        boolean hadName = false;
        for (String tag : found) {
            hadDate |= isDateTag(tag);
            hadDevice |= DEVICE_TAGS.contains(tag);
            hadName |= NAME_TAGS.contains(tag);
        }
        return new CleanStats(found.size(), hadLocation, hadDate, hadDevice, hadName);
    }
}
