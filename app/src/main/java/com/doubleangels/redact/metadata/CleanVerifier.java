package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.exifinterface.media.ExifInterface;
import androidx.media3.common.Format;
import com.doubleangels.redact.media.OutputDestination;
import com.doubleangels.redact.sentry.SentryManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks that a cleaned file is clean, for {@link MetadataStripper} and its cleaners: EXIF, XMP and
 * container scans of pictures, the video metadata and stream scans, and the byte-for-byte check of what
 * reached the output. Moved out of MetadataStripper unchanged; see CLAUDE.md before changing any of it.
 */
final class CleanVerifier {

    private final MetadataStripper host;
    private final Context context;
    private final ContentResolver contentResolver;

    CleanVerifier(@NonNull MetadataStripper host) {
        this.host = host;
        this.context = host.context;
        this.contentResolver = host.contentResolver;
    }


    /**
     * Verifies a converted video carries no location, original date, or author/title-style
     * metadata from {@code sourceUri}.
     *
     * @throws IOException if identifying metadata survived
     */
    public void requireConvertedVideoClean(@NonNull File convertedFile, @NonNull Uri sourceUri)
            throws IOException {
        requireVideoMetadataClean(convertedFile, extractVideoPrivacyMetadata(sourceUri));
    }

    /** Source location/date used to detect preserved privacy metadata after cleaning. */
    @VisibleForTesting
    static final class VideoPrivacySnapshot {
        @Nullable final String location;
        @Nullable final String date;
        /** How many of {@link #ADDITIONAL_VIDEO_PRIVACY_KEYS} the video carries, for the clean summary. */
        final int additionalFieldCount;
        /**
         * Identifying boxes found by reading the container itself ({@link VideoContainerMetadata}),
         * which sees tags the platform retriever does not, such as QuickTime keys and 3GPP loci.
         */
        final int containerFieldCount;
        final boolean containerHasLocation;
        /** The names of those boxes, so the statistics can tell dates, devices and names apart. */
        @NonNull final List<String> containerFields;
        /** True when the retriever found an author, writer, artist or composer. */
        final boolean hasName;

        VideoPrivacySnapshot(@Nullable String location, @Nullable String date) {
            this(location, date, 0);
        }

        VideoPrivacySnapshot(@Nullable String location, @Nullable String date, int additionalFieldCount) {
            this(location, date, additionalFieldCount, 0, false);
        }

        VideoPrivacySnapshot(@Nullable String location, @Nullable String date, int additionalFieldCount,
                             int containerFieldCount, boolean containerHasLocation) {
            this(location, date, additionalFieldCount, containerFieldCount, containerHasLocation,
                    Collections.emptyList(), false);
        }

        VideoPrivacySnapshot(@Nullable String location, @Nullable String date, int additionalFieldCount,
                             @NonNull List<String> containerFields, boolean containerHasLocation,
                             boolean hasName) {
            this(location, date, additionalFieldCount, containerFields.size(), containerHasLocation,
                    containerFields, hasName);
        }

        private VideoPrivacySnapshot(@Nullable String location, @Nullable String date,
                                     int additionalFieldCount, int containerFieldCount,
                                     boolean containerHasLocation, @NonNull List<String> containerFields,
                                     boolean hasName) {
            this.location = location;
            this.date = date;
            this.additionalFieldCount = additionalFieldCount;
            this.containerFieldCount = containerFieldCount;
            this.containerHasLocation = containerHasLocation;
            this.containerFields = containerFields;
            this.hasName = hasName;
        }

        static VideoPrivacySnapshot empty() {
            return new VideoPrivacySnapshot(null, null);
        }
    }

    /**
     * MediaMetadataRetriever reports this exact string for METADATA_KEY_DATE when the source
     * container's creation_time atom is zero (the QuickTime/MP4 epoch, 1904-01-01) -- i.e. no
     * real recording date was ever set. Our own muxer/encoder output surfaces this identical
     * placeholder whenever it writes no explicit date, so comparing it as if it were a real date
     * makes every date-less source file fail verification against its own, equally date-less,
     * output.
     */
    private static final String VIDEO_DATE_UNSET_PLACEHOLDER = "19040101T000000.000Z";

    @NonNull
    VideoPrivacySnapshot extractVideoPrivacyMetadata(@NonNull Uri sourceUri) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, sourceUri);
            String date = normalizeMetadataValue(
                    retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DATE));
            if (VIDEO_DATE_UNSET_PLACEHOLDER.equals(date)) {
                date = null;
            }
            // Counted while the file is open, so the clean summary needs no second read.
            int additional = 0;
            boolean hasName = false;
            for (int key : ADDITIONAL_VIDEO_PRIVACY_KEYS) {
                if (normalizeMetadataValue(retriever.extractMetadata(key)) != null) {
                    additional++;
                    hasName |= NAME_VIDEO_KEYS.contains(key);
                }
            }
            VideoContainerMetadata.Report container = VideoContainerMetadata.scan(context, sourceUri);
            return new VideoPrivacySnapshot(
                    normalizeMetadataValue(
                            retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_LOCATION)),
                    date,
                    additional,
                    container.fields,
                    container.hasLocation,
                    hasName);
        } catch (Exception e) {
            SentryManager.log("The source video privacy metadata could not be read: " + e.getClass().getSimpleName() + ".");
            VideoContainerMetadata.Report container = VideoContainerMetadata.scan(context, sourceUri);
            return new VideoPrivacySnapshot(null, null, 0, container.fields, container.hasLocation, false);
        }
    }

    /**
     * Counts the identifying fields in a video: whichever is larger of what the platform retriever
     * reports (location, date and the additional keys) and what reading the container found, as
     * both see the same location and date when the retriever sees them at all.
     */
    @NonNull
    static CleanStats countVideoPrivacyFields(@NonNull VideoPrivacySnapshot snapshot) {
        int retrieved = (snapshot.location != null ? 1 : 0) + (snapshot.date != null ? 1 : 0)
                + snapshot.additionalFieldCount;
        return CleanStats.withVideoFields(Math.max(retrieved, snapshot.containerFieldCount),
                snapshot.location != null || snapshot.containerHasLocation,
                snapshot.date != null, snapshot.hasName, snapshot.containerFields);
    }

    /**
     * Removes whatever identifying boxes the muxer or encoder left in {@code videoFile} (such as
     * the Android version key and the time of cleaning), then fails unless neither the platform
     * retriever nor a read of the container itself finds anything identifying.
     */
    void requireVideoMetadataClean(
            @NonNull File videoFile, @NonNull VideoPrivacySnapshot sourceSnapshot) throws IOException {
        requireVideoMetadataClean(videoFile, sourceSnapshot, false);
    }

    /**
     * {@link #requireVideoMetadataClean(File, VideoPrivacySnapshot)}, accepting the location a
     * clean wrote itself when {@code keepLocation}: a user data box holding that and nothing else.
     */
    void requireVideoMetadataClean(
            @NonNull File videoFile, @NonNull VideoPrivacySnapshot sourceSnapshot, boolean keepLocation)
            throws IOException {
        VideoContainerMetadata.scrub(videoFile, keepLocation);
        VideoContainerMetadata.Report remaining = VideoContainerMetadata.scan(videoFile, keepLocation);
        if (!remaining.isClean()) {
            SentryManager.setCustomKey("video_metadata_verification_passed", false);
            SentryManager.setCustomKey("video_verify_failed_key",
                    remaining.recognized ? "CONTAINER_BOX" : "CONTAINER_UNREADABLE");
            countVerificationFailure("video", "video_container");
            throw new IOException("Video metadata verification failed");
        }
        boolean videoClean = verifyVideoMetadataRemoval(videoFile, sourceSnapshot, keepLocation);
        SentryManager.setCustomKey("video_metadata_verification_passed", videoClean);
        if (!videoClean) {
            countVerificationFailure("video", "video_fields");
            throw new IOException("Video metadata verification failed");
        }
    }

    @Nullable
    static String normalizeMetadataValue(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Textual video metadata keys beyond location/date that our own encoder never sets, so any
     * non-empty value found in an output file must have survived from the source container.
     */
    /** The {@link #ADDITIONAL_VIDEO_PRIVACY_KEYS} that name a person. */
    private static final Set<Integer> NAME_VIDEO_KEYS = Set.of(
            MediaMetadataRetriever.METADATA_KEY_AUTHOR,
            MediaMetadataRetriever.METADATA_KEY_WRITER,
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
            MediaMetadataRetriever.METADATA_KEY_ARTIST,
            MediaMetadataRetriever.METADATA_KEY_COMPOSER);

    static final int[] ADDITIONAL_VIDEO_PRIVACY_KEYS = {
            MediaMetadataRetriever.METADATA_KEY_AUTHOR,
            MediaMetadataRetriever.METADATA_KEY_WRITER,
            MediaMetadataRetriever.METADATA_KEY_ALBUM,
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
            MediaMetadataRetriever.METADATA_KEY_ARTIST,
            MediaMetadataRetriever.METADATA_KEY_COMPOSER,
            MediaMetadataRetriever.METADATA_KEY_TITLE,
    };

    @NonNull
    private static String videoMetadataKeyName(int key) {
        if (key == MediaMetadataRetriever.METADATA_KEY_LOCATION) {
            return "METADATA_KEY_LOCATION";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_DATE) {
            return "METADATA_KEY_DATE";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_AUTHOR) {
            return "METADATA_KEY_AUTHOR";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_WRITER) {
            return "METADATA_KEY_WRITER";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_ALBUM) {
            return "METADATA_KEY_ALBUM";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) {
            return "METADATA_KEY_ALBUMARTIST";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_ARTIST) {
            return "METADATA_KEY_ARTIST";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_COMPOSER) {
            return "METADATA_KEY_COMPOSER";
        }
        if (key == MediaMetadataRetriever.METADATA_KEY_TITLE) {
            return "METADATA_KEY_TITLE";
        }
        return "METADATA_KEY_" + key;
    }

    @VisibleForTesting
    boolean verifyVideoMetadataRemoval(
            @NonNull File videoFile, @NonNull VideoPrivacySnapshot sourceSnapshot) {
        return verifyVideoMetadataRemoval(videoFile, sourceSnapshot, false);
    }

    boolean verifyVideoMetadataRemoval(
            @NonNull File videoFile, @NonNull VideoPrivacySnapshot sourceSnapshot, boolean keepLocation) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(videoFile.getAbsolutePath());

            String location =
                    normalizeMetadataValue(
                            retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_LOCATION));
            if (location != null && !keepLocation) {
                SentryManager.setCustomKey("video_verify_failed_key", "LOCATION");
                SentryManager.log(
                        "The app found a remaining video metadata key: "
                                + videoMetadataKeyName(
                                        MediaMetadataRetriever.METADATA_KEY_LOCATION)
                                + ".");
                return false;
            }

            if (sourceSnapshot.date != null) {
                String outputDate =
                        normalizeMetadataValue(
                                retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_DATE));
                if (outputDate != null && outputDate.equals(sourceSnapshot.date)) {
                    // Only that it happened: the date is when the user recorded the video.
                    SentryManager.setCustomKey("video_verify_failed_key", "DATE_PRESERVED");
                    SentryManager.log(
                            "The original recording date was preserved in the output: "
                                    + videoMetadataKeyName(
                                            MediaMetadataRetriever.METADATA_KEY_DATE)
                                    + ".");
                    return false;
                }
            }

            for (int key : ADDITIONAL_VIDEO_PRIVACY_KEYS) {
                String value = normalizeMetadataValue(retriever.extractMetadata(key));
                if (value != null) {
                    SentryManager.setCustomKey("video_verify_failed_key", videoMetadataKeyName(key));
                    SentryManager.log(
                            "The app found a remaining video metadata key: " + videoMetadataKeyName(key) + ".");
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            SentryManager.setCustomKey("video_verify_failed_key", "RETRIEVER_ERROR");
            SentryManager.log("The video metadata verification read failed.");
            return false;
        }
    }

    /**
     * Structural/technical EXIF tags that {@link ExifInterface#setAttribute} cannot actually
     * clear to null on this library version -- {@code getAttribute} keeps returning a
     * placeholder (observed: {@code "0"}) after {@code removeAllExifMetadata} runs, regardless
     * of the source value. None of these identify a person, device, or location, so excluding
     * them from verification's "must be absent" check doesn't reopen a privacy gap -- it just
     * stops verification from permanently failing on every single image. TAG_ORIENTATION is
     * normally kept out of this problem entirely by {@link #getTagsToPreserve}, but under
     * strict-clean mode (the app's default) it's intentionally excluded from that allowlist too,
     * so it hits the same can't-actually-clear-it behavior as the others.
     */
    static final Set<String> NON_IDENTIFYING_UNCLEARABLE_TAGS = Set.of(
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_ORIENTATION);

    /**
     * Counts a check that would not vouch for a cleaned file. Each one already fails closed; the
     * count shows when a cleaning path starts leaving data behind, by media type and reason.
     */
    static void countVerificationFailure(@NonNull String mediaType, @NonNull String reason) {
        SentryManager.count("verification.failed", 1, "media_type", mediaType, "outcome", reason);
    }

    /**
     * Verifies that metadata has been properly removed from an image file: no EXIF tag beyond
     * the ones the settings keep, and nothing outside EXIF (see {@link ImageContainerMetadata}).
     *
     * @param imageFile The image file to verify
     * @return true if no identifying metadata was found, false if metadata remains
     */
    boolean verifyMetadataRemoval(@NonNull File imageFile, boolean forSharing) {
        try {
            Set<String> allowedTags = new HashSet<>(host.getTagsToPreserve(forSharing));
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());

            // Sweep every TAG_* constant, mirroring the reflection-based removal in
            // removeAllExifMetadata, so verification can't pass a file
            // that still carries a tag the fixed identifyingTags list used to miss (e.g. lens
            // make/model, body/lens serial numbers, camera owner name, GPS area info).
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
                if (tag == null || allowedTags.contains(tag) || NON_IDENTIFYING_UNCLEARABLE_TAGS.contains(tag)) {
                    continue;
                }
                String value = exif.getAttribute(tag);
                if (ExifInterface.TAG_XMP.equals(tag) && value != null && UltraHdr.isPictureOnlyXmp(value)) {
                    continue; // only the Ultra HDR gain map's numbers and directory
                }
                if (value != null && !value.isEmpty()) {
                    SentryManager.log("The app found a remaining metadata tag: " + tag + ".");
                    countVerificationFailure("image", "exif_tag");
                    return false;
                }
            }

            // Everything outside EXIF: a JPEG, PNG or WebP must be readable to the end of the
            // picture with nothing else left in it; for any other format no XMP packet may remain.
            if (ImageContainerMetadata.sniff(imageFile) != ImageContainerMetadata.Format.OTHER) {
                ImageContainerMetadata.Report container = ImageContainerMetadata.scan(imageFile);
                if (!container.isClean()) {
                    SentryManager.log("The app found remaining metadata outside EXIF: "
                            + (container.findings.isEmpty() ? "UNREADABLE" : ImageContainerMetadata.logKey(container.findings.get(0).key)) + ".");
                    countVerificationFailure("image", "container");
                    return false;
                }
            } else {
                // A HEIF or GIF is read box by box or block by block like the formats above. A
                // kept Motion Photo or Ultra HDR directory is XMP too, so a packet is allowed only
                // when the walk found that directory and nothing else; one it could not account
                // for, or any packet in a file it could not follow, fails the check.
                ImageContainerMetadata.Report container = ImageContainerMetadata.scan(imageFile);
                boolean structured = container.recognized
                        && (container.format == ImageContainerMetadata.Format.HEIF
                        || container.format == ImageContainerMetadata.Format.GIF);
                if (structured && !container.isClean()) {
                    SentryManager.log("The app found remaining metadata outside EXIF: "
                            + ImageContainerMetadata.logKey(container.findings.get(0).key) + ".");
                    countVerificationFailure("image", "container");
                    return false;
                }
                boolean keptDirectory = false;
                for (ImageContainerMetadata.Finding kept : container.kept) {
                    keptDirectory |= kept.key.equals("MOTION_PHOTO") || kept.key.equals("ULTRA_HDR");
                }
                if (!(structured && keptDirectory) && containsXmpPacket(imageFile)) {
                    SentryManager.log("The app found XMP metadata in the file.");
                    countVerificationFailure("image", "xmp");
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            SentryManager.log("An error occurred while verifying metadata removal.");
            countVerificationFailure("image", "unreadable");
            return false;
        }
    }

    /**
     * Whether an XMP packet is anywhere in the file. The whole file is searched, as some formats
     * keep XMP after the picture; a file that cannot be read counts as still holding one.
     */
    private boolean containsXmpPacket(@NonNull File file) {
        try (InputStream in = new FileInputStream(file)) {
            return ImageContainerMetadata.containsXmpPacket(in, Long.MAX_VALUE);
        } catch (IOException e) {
            SentryManager.log("XMP detection failed: " + e.getClass().getSimpleName() + ".");
            return true;
        }
    }

    /**
     * Fails unless the saved output reads back as exactly the bytes that were verified. Hashed as
     * it is read, so no second copy of the image is written to check it.
     */
    void requireSameContent(@NonNull Uri output, @NonNull byte[] expectedSha256) throws IOException {
        OutputDestination.requireSameContent(contentResolver, output, expectedSha256);
    }
}
