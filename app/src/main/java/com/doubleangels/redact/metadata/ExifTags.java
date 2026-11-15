package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.exifinterface.media.ExifInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every EXIF tag name {@link ExifInterface} defines, found once by reflection over its
 * {@code TAG_*} constants. Cleaning removes these tags, and verification, the stats, the
 * already-clean check and Scan read them.
 *
 * <p>If a release build ever stripped or renamed those fields, a loop over them would find
 * nothing: cleaning would remove nothing and verification would pass with nothing checked. So
 * {@link #all()} throws unless the list holds tags every version has and is about as long as it
 * should be. {@code proguard-rules.pro} keeps the names; this is the check that it still does.
 */
final class ExifTags {

    /** ExifInterface defines well over this many tags; fewer means the reflection broke. */
    @VisibleForTesting
    static final int MIN_TAGS = 100;

    /** Built once; a failed build is not kept, so every caller sees the failure. */
    private static volatile List<String> all;

    /**
     * Fields to read in place of ExifInterface's own, so a test can show what a build that lost
     * them does. Never cached, and checked like the real ones, so it can only make
     * {@link #all()} fail, never pass with less.
     */
    @VisibleForTesting
    @Nullable
    static volatile Field[] testFields;

    private ExifTags() {
    }

    /**
     * The tag names in declaration order (a few aliases share a name and appear twice, as the
     * fields do).
     *
     * @throws IllegalStateException when the {@code TAG_*} constants could not be read
     */
    @NonNull
    static List<String> all() {
        Field[] override = testFields;
        if (override != null) {
            return fromFields(override);
        }
        List<String> tags = all;
        if (tags == null) {
            tags = fromFields(ExifInterface.class.getDeclaredFields());
            all = tags;
        }
        return tags;
    }

    /** The values of the {@code static String TAG_*} fields, checked as {@link #all()} says. */
    @VisibleForTesting
    @NonNull
    static List<String> fromFields(@NonNull Field[] fields) {
        List<String> tags = new ArrayList<>();
        for (Field field : fields) {
            if (field.getType() != String.class || !field.getName().startsWith("TAG_")
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                String tag = (String) field.get(null);
                if (tag != null) {
                    tags.add(tag);
                }
            } catch (IllegalAccessException | IllegalArgumentException e) {
                // Not readable: left out, and the checks below catch it if too many are.
            }
        }
        // The constants are inlined at compile time, so these names are right even when the
        // fields are gone.
        if (tags.size() < MIN_TAGS
                || !tags.contains(ExifInterface.TAG_GPS_LATITUDE)
                || !tags.contains(ExifInterface.TAG_MAKE)
                || !tags.contains(ExifInterface.TAG_DATETIME_ORIGINAL)) {
            throw new IllegalStateException(
                    "ExifInterface TAG_ constants are missing (" + tags.size() + " found)");
        }
        return Collections.unmodifiableList(tags);
    }
}
