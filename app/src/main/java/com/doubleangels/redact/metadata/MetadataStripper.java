package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.MediaFileNames;
import com.doubleangels.redact.media.MediaSizeLimits;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.media.ProcessingCancelledException;
import com.doubleangels.redact.media.SecureDelete;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;


/**
 * Provides functionality to strip metadata from media files (images and
 * videos).
 *
 * This class handles:
 * - Removing EXIF data from images while preserving essential information like
 * orientation
 * - Processing videos to remove metadata
 * - Creating clean copies of media files for both storing and sharing
 * - Memory management for large media files
 * - Progress reporting during processing operations
 *
 * The class implements two primary workflows:
 * - Stripping metadata and saving to MediaStore (permanent storage)
 * - Stripping metadata and saving to app cache (temporary, for sharing)
 *
 * For images, the process involves:
 * - Reading the original image and any essential EXIF data to preserve
 * - Rebuilding a JPEG, PNG or WebP from only the segments that make up the picture
 *   ({@link ImageContainerMetadata}), or re-encoding any other image from its pixels
 * - Restoring only essential non-identifying EXIF tags (like orientation)
 * - Verifying that no EXIF tag beyond those, and nothing outside EXIF, remains
 *
 * For videos, the process involves:
 * - Decoding and re-encoding every track with Media3 {@code Transformer}, scrubbing what the muxer
 *   writes ({@link VideoContainerMetadata}), and verifying the result; a quick transmux is used
 *   only when Faster Video Cleaning is on and Strict Clean is off
 *
 * @see ExifInterface
 * @see MediaStore
 * @see FileProvider
 */
public class MetadataStripper {

    /**
     * Interface for reporting progress during media processing operations.
     *
     * Implementations of this interface can be used to update UI elements
     * like progress bars or status text during potentially lengthy operations.
     */
    public interface ProgressCallback {
        /**
         * Called periodically during processing to update on progress.
         *
         * @param percentOfCurrentItem approximate completion for the file in progress (0–100)
         * @param message              human-readable status
         */
        void onProgress(int percentOfCurrentItem, String message);
    }

    /**
     * Application context for accessing system services.
     * Used for accessing ContentResolver, FileProvider, and storage directories.
     */
    final Context context;

    /**
     * Content resolver for MediaStore operations.
     * Used for reading from and writing to the device's media collections.
     */
    final ContentResolver contentResolver;

    /**
     * Optional callback for progress updates.
     * If set, this will be called during processing to report progress.
     */
    private ProgressCallback progressCallback;

    @VisibleForTesting
    @Nullable
    File testFastStripVideoMetadataOverride;

    @VisibleForTesting
    boolean testForceOutputDirMkdirsFailure;

    @VisibleForTesting
    void setTestFastStripVideoMetadataOverride(@Nullable File overrideFile) {
        this.testFastStripVideoMetadataOverride = overrideFile;
    }

    @VisibleForTesting
    void setTestForceOutputDirMkdirsFailure(boolean force) {
        this.testForceOutputDirMkdirsFailure = force;
    }

    /**
     * What one clean call has found and made so far: the tags the settings keep, its statistics,
     * and where its output went. Each call has its own, so two calls never share kept tags or
     * results, and the public methods hand the outcome back as a {@link CleanResult}.
     */
    static final class Job {
        final Map<String, String> kept = new HashMap<>();
        @Nullable
        CleanStats stats;
        @Nullable
        File file;
        boolean unsupported;

        @NonNull
        CleanResult result(@Nullable Uri uri) {
            return new CleanResult(uri, uri != null ? file : null, stats, uri == null && unsupported);
        }
    }

    private final AtomicBoolean operationCancelled =
            new AtomicBoolean(false);

    volatile long transcodeOwnerId = -1L;

    @Nullable
    private volatile BooleanSupplier externalCancellationCheck;

    public void resetCancellation() {
        operationCancelled.set(false);
    }

    public void setTranscodeOwnerId(long ownerId) {
        transcodeOwnerId = ownerId;
    }

    public long getTranscodeOwnerId() {
        return transcodeOwnerId;
    }

    public void requestCancellation() {
        operationCancelled.set(true);
        VideoMedia3Converter.cancelActiveTranscode(transcodeOwnerId);
    }

    /**
     * Lets a caller that owns the cancellation state (e.g. the Convert loop) stop an operation in
     * progress without holding a reference to this stripper.
     */
    public void setCancellationCheck(@Nullable BooleanSupplier check) {
        externalCancellationCheck = check;
    }

    void throwIfCancelled() throws IOException {
        BooleanSupplier external = externalCancellationCheck;
        if (operationCancelled.get() || Thread.currentThread().isInterrupted()
                || (external != null && external.getAsBoolean())) {
            throw new ProcessingCancelledException("Processing cancelled");
        }
    }

    void checkCancelled() {
        try {
            throwIfCancelled();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean shouldPropagateCancellation(Throwable e) {
        if (SentryManager.isUserCancellation(e)) {
            return true;
        }
        Throwable cause = e.getCause();
        return cause != null && SentryManager.isUserCancellation(cause);
    }

    static RuntimeException asCancellationException(Throwable e) {
        if (e instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(e);
    }

    /**
     * Creates a new MetadataStripper instance.
     *
     * @param context Application context for accessing system services, must not be
     *                null
     * @throws IllegalArgumentException if context is null
     */
    /** Where the work happens; each keeps its code as it was here, and reads the shared state above. */
    final ImageCleaner images;
    final VideoCleaner videos;
    final CleanVerifier verifier;

    public MetadataStripper(@NonNull Context context) {
        // Store application context to prevent memory leaks
        this.context = context.getApplicationContext();
        this.contentResolver = this.context.getContentResolver();
        this.verifier = new CleanVerifier(this);
        this.images = new ImageCleaner(this);
        this.videos = new VideoCleaner(this);
        SentryManager.log("The MetadataStripper was initialized.");
    }

    /**
     * Sets a callback to receive progress updates during processing.
     *
     * This allows the client to display progress information to the user
     * during potentially lengthy operations.
     *
     * @param callback The progress callback implementation, or null to remove the
     *                 callback
     */
    public void setProgressCallback(@Nullable ProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * Updates the progress callback with current status.
     *
     * This is an internal helper method that safely calls the progress callback
     * if one has been set.
     *
     * @param current Current step in the process (0-based)
     * @param total   Total number of steps
     * @param message Human-readable progress message
     */
    /**
     * @param currentStep 1-based step index within the current file
     * @param totalSteps  total steps for this file
     */
    void updateProgress(int currentStep, int totalSteps, String message) {
        if (progressCallback != null && totalSteps > 0) {
            int percent = Math.min(100, Math.max(0, (currentStep * 100) / totalSteps));
            progressCallback.onProgress(percent, message);
        }
    }

    /** Maps Media3 transcoding 0–100% into the middle of the current item’s progress (after “Transcoding…”). */
    void reportTranscodeProgress(int transcoderPercent0To100) {
        try {
            throwIfCancelled();
        } catch (IOException e) {
            VideoMedia3Converter.cancelActiveTranscode(transcodeOwnerId);
            throw new RuntimeException(e);
        }
        if (progressCallback != null) {
            int p = Math.min(100, Math.max(0, transcoderPercent0To100));
            int itemPercent = 50 + p / 2;
            progressCallback.onProgress(
                    itemPercent,
                    context.getString(R.string.progress_transcoding_percent, p));
        }
    }

    /**
     * Process video to remove metadata and save to MediaStore (external storage).
     *
     * <p>Re-encodes with Media3 so container and stream metadata are stripped. A fast transmux-only path
     * is used only when {@link AppPreferences#isVideoFallbackCopy} is enabled and strict clean is off.
     *
     * @param sourceUri        URI of the source video, must not be null
     * @param originalFilename Original filename of the video, must not be null
     * @return the saved video's URI (null when the clean failed) and what it removed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @NonNull
    public CleanResult cleanVideo(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        return videos.cleanVideo(sourceUri, originalFilename);
    }

    /**
     * Strips EXIF metadata from an image and saves it to MediaStore.
     *
     * This method:
     * 1. Reads the original image and extracts essential EXIF data to preserve
     * 2. Decodes the image into a bitmap with memory optimization
     * 3. Re-encodes the bitmap without metadata
     * 4. Restores only essential non-identifying EXIF data
     * 5. Saves the processed image to MediaStore
     *
     * @param sourceUri        URI of the source image, must not be null
     * @param originalFilename Original filename of the image, must not be null
     * @return the saved image's URI (null when the clean failed) and what it removed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @NonNull
    public CleanResult cleanImage(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        return images.cleanImage(sourceUri, originalFilename);
    }

    /**
     * Saves a censored photo to the gallery the way {@link #cleanImage} saves a clean one, so it
     * follows the same settings: the tags they keep are copied from the original, the file is
     * rebuilt with nothing else in it, and it is checked before anyone can see it.
     *
     * @param original the photo that was censored, read only for the tags the settings keep and
     *                 for the clean statistics
     * @param censored the censored picture, already upright, so the original's orientation is
     *                 not copied
     * @param outputName the saved file's name, with its extension
     * @return the saved copy and the original's clean statistics
     * @throws IOException when it could not be saved; nothing is left behind
     */
    @NonNull
    public CleanResult saveCensored(@NonNull Uri original, @NonNull File censored,
                                    @NonNull String outputName,
                                    @NonNull FormatConverter.ImageFormatSpec outputFormat) throws IOException {
        return images.saveCensored(original, censored, outputName, outputFormat);
    }

    /**
     * Strips EXIF metadata from an image and saves it to the app's cache directory
     * for sharing.
     *
     * This method is similar to cleanImage but saves to app cache instead of
     * MediaStore,
     * making it suitable for temporary files intended for sharing.
     *
     * @param sourceUri        URI of the source image, must not be null
     * @param originalFilename Original filename of the image, must not be null
     * @return the FileProvider URI and file of the processed image (null when the clean failed),
     *         what it removed, and whether it failed because the format cannot be decoded here
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @NonNull
    public CleanResult cleanImageForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        return images.cleanImageForSharing(sourceUri, originalFilename);
    }

    /**
     * Strips metadata from a video and saves it to the app's cache directory for
     * sharing.
     *
     * This method:
     * 1. Transcodes to H.264/AAC MP4 in cache (or direct copy if transcoding fails)
     * 2. Reports progress during processing
     * 3. Returns a FileProvider URI for sharing the processed video
     *
     * @param sourceUri        URI of the source video, must not be null
     * @param originalFilename Original filename of the video, must not be null
     * @return the FileProvider URI and file of the processed video (null when the clean failed),
     *         what it removed, and whether it failed because the format cannot be decoded here
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @NonNull
    public CleanResult cleanVideoForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        return videos.cleanVideoForSharing(sourceUri, originalFilename);
    }

    /**
     * Transmuxes {@code sourceUri} into a cache file without container-level metadata: the quick
     * path of Faster Video Cleaning on its own. Returns null when transmuxing isn't possible;
     * callers must delete a non-null result.
     */
    @VisibleForTesting
    @Nullable
    public File transmuxVideoWithoutMetadata(@NonNull Uri sourceUri) {
        return videos.transmuxVideoWithoutMetadata(sourceUri);
    }

    /**
     * Verifies a converted video carries no location, original date, or author/title-style
     * metadata from {@code sourceUri}.
     *
     * @throws IOException if identifying metadata survived
     */
    public void requireConvertedVideoClean(@NonNull File convertedFile, @NonNull Uri sourceUri)
            throws IOException {
        verifier.requireConvertedVideoClean(convertedFile, sourceUri);
    }

    /**
     * Makes an image Convert encoded into {@code target}, the file it saves: rebuilt with nothing
     * but its picture, as a clean rebuilds pixels it encoded, so whatever the encoder put beside
     * them goes too, and then checked like a clean's result. A file the rebuild can't follow is
     * copied as it is and still has to pass the check.
     *
     * @throws IOException when anything identifying is left, so nothing is saved
     */
    public void requireConvertedImageClean(@NonNull File encoded, @NonNull File target) throws IOException {
        images.requireConvertedImageClean(encoded, target);
    }

    /** Securely deletes a temp file created for conversion. */
    public void deleteTempFile(@NonNull File file) {
        secureDeleteFile(file);
    }

    /**
     * Convenience method that routes to either cleanImageForSharing or
     * cleanVideoForSharing
     * based on the media type.
     *
     * @param sourceUri        URI of the source media file, must not be null
     * @param originalFilename Original filename of the media file, must not be null
     * @param isVideo          True if the media is a video, false if it's an image
     * @return URI of the processed media file (FileProvider URI), or null if
     *         processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @NonNull
    public CleanResult cleanForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename, boolean isVideo) {
        // Log operation start for analytics and debugging
        SentryManager.log("The app is starting metadata stripping for sharing.");
        SentryManager.setCustomKey("is_video", isVideo);
        return isVideo
                ? cleanVideoForSharing(sourceUri, originalFilename)
                : cleanImageForSharing(sourceUri, originalFilename);
    }

    /**
     * Generates a short random alphanumeric string for use in filenames.
     * Uses SecureRandom for cryptographically secure random generation.
     *
     * @return A random string of 12 characters (alphanumeric)
     */
    @NonNull
    @VisibleForTesting
    String generateShortRandomName() {
        return MediaFileNames.generateShortRandomName();
    }

    /**
     * Extracts the file extension from a filename or returns a default extension if
     * none is found.
     *
     * @param originalFilename Filename to extract extension from, must not be null
     * @param defaultExtension Default extension to use if none is found, must not
     *                         be null
     * @return File extension including the dot (e.g., ".jpg")
     */
    @Nullable
    static String extensionFromFilename(@NonNull String originalFilename) {
        int lastDotIndex = originalFilename.lastIndexOf(".");
        if (lastDotIndex < 0 || lastDotIndex >= originalFilename.length() - 1) {
            return null;
        }
        return originalFilename.substring(lastDotIndex).toLowerCase(Locale.US);
    }

    List<String> getTagsToPreserve(boolean forSharing) {
        return tagsToPreserve(context, forSharing);
    }

    /** EXIF tags a clean keeps for the current settings; shared with {@link AlreadyCleanCheck}. */
    static List<String> tagsToPreserve(@NonNull Context context, boolean forSharing) {
        List<String> tags = new ArrayList<>(ALWAYS_KEPT_TAGS);
        if (forSharing || AppPreferences.isStrictClean(context)) {
            return tags;
        }

        if (AppPreferences.isPreserveCameraSettings(context)) {
            tags.addAll(List.of(
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_APERTURE_VALUE,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    // Phones write ISO here; ISOSpeed (0x8833) is a rarely used refinement.
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ExifInterface.TAG_ISO_SPEED,
                    // The same tag under its old name, which reads back the same value, so the
                    // check after a clean would otherwise count the kept ISO as left behind.
                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                    ExifInterface.TAG_EXPOSURE_PROGRAM,
                    ExifInterface.TAG_EXPOSURE_MODE,
                    ExifInterface.TAG_METERING_MODE,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_WHITE_BALANCE
            ));
        }

        if (AppPreferences.isPreserveDateTaken(context)) {
            tags.addAll(List.of(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    // ExifInterface writes DateTime from DateTimeOriginal when it saves.
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_OFFSET_TIME
            ));
        }

        if (AppPreferences.isPreserveLocation(context)) {
            tags.addAll(List.of(
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_TIMESTAMP
            ));
        }

        return tags;
    }

    /**
     * Tags every clean keeps, shares and Strict Clean included: they describe how to show the
     * picture and say nothing about who took it or where. Orientation turns it upright, and
     * ColorSpace with the interoperability index ("R03") is how cameras without an embedded
     * profile say a photo is in Adobe RGB; without them it is shown as sRGB, washed out.
     *
     * <p>The interoperability index is kept only with ColorSpace, as it means nothing alone.
     * Kept alone it is also all the EXIF IFD leads to, which ExifInterface sizes without the
     * pointer to that IFD and then writes with it: the APP1 segment comes out 12 bytes shorter
     * than what is in it, and the JPEG cannot be read past it.
     */
    static final List<String> ALWAYS_KEPT_TAGS = List.of(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_INTEROPERABILITY_INDEX);

    /**
     * Securely deletes a file by overwriting it with random data multiple times
     * before deletion.
     * This makes file recovery significantly more difficult.
     *
     * @param file The file to securely delete
     * @return true if the file was successfully deleted, false otherwise
     */
    boolean secureDeleteFile(@NonNull File file) {
        return SecureDelete.secureDelete(file);
    }

    void copySourceToTempFile(@NonNull Uri sourceUri, @NonNull File tempFile)
            throws IOException {
        try (InputStream in = contentResolver.openInputStream(sourceUri);
                FileOutputStream out = new FileOutputStream(tempFile)) {
            if (in == null) {
                throw new IOException("Failed to open input stream");
            }
            MediaSizeLimits.copyStream(in, out, this::checkCancelled);
        }
    }}
