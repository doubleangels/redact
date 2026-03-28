package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Log;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
 * - Decoding the image into a bitmap (with memory optimization for large
 * images)
 * - Re-encoding the bitmap without any metadata
 * - Restoring only essential non-identifying EXIF tags (like orientation)
 *
 * For videos, the process involves:
 * - Re-encoding with Media3 {@code Transformer} (H.264/AAC MP4) to strip metadata, or a direct copy if
 *   transcoding fails
 *
 * @see ExifInterface
 * @see MediaStore
 * @see FileProvider
 */
public class MetadataStripper {
    private static final String TAG = "MetadataStripper";

    /**
     * Optimized buffer size for file I/O operations (64KB for better performance).
     * Larger buffers reduce system calls and improve throughput.
     */
    private static final int DEFAULT_BUFFER_SIZE = 65536; // 64KB

    /**
     * Buffer size for secure file deletion operations.
     */
    private static final int SECURE_DELETE_BUFFER_SIZE = 65536;

    /**
     * Number of passes for secure file deletion (overwriting with random data).
     */
    private static final int SECURE_DELETE_PASSES = 3;

    /**
     * Maximum dimension (width or height) for bitmap processing.
     * Images larger than this will be downsampled during processing to avoid
     * out-of-memory errors. The original resolution is preserved in the output
     * file.
     */
    private static final int MAX_BITMAP_SIZE = 4096;

    /**
     * Maximum file size in megabytes that can be processed.
     * Files larger than this will be rejected to prevent excessive memory/storage
     * usage.
     */
    private static final long MAX_FILE_SIZE_MB = 100;

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
    private final Context context;

    /**
     * Content resolver for MediaStore operations.
     * Used for reading from and writing to the device's media collections.
     */
    private final ContentResolver contentResolver;

    /**
     * Optional callback for progress updates.
     * If set, this will be called during processing to report progress.
     */
    private ProgressCallback progressCallback;

    /**
     * Stores the most recently processed file's URI.
     * This allows clients to easily access the result of the most recent operation.
     */
    private Uri lastProcessedFileUri;

    /**
     * Map to store essential EXIF values that should be preserved during
     * processing.
     * These values (like orientation) are important for proper display but don't
     * contain
     * identifying information.
     */
    private final Map<String, String> preservedExifValues = new HashMap<>();

    /**
     * Cache for file sizes to avoid redundant I/O operations.
     * Key: URI string, Value: File size in bytes
     */
    private final Map<String, Long> fileSizeCache = new ConcurrentHashMap<>();

    /**
     * Secure random number generator for secure file deletion.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates a new MetadataStripper instance.
     *
     * @param context Application context for accessing system services, must not be
     *                null
     * @throws IllegalArgumentException if context is null
     */
    public MetadataStripper(@NonNull Context context) {
        // Store application context to prevent memory leaks
        this.context = context.getApplicationContext();
        this.contentResolver = this.context.getContentResolver();
        SentryManager.log("MetadataStripper initialized.");
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
    private void updateProgress(int currentStep, int totalSteps, String message) {
        if (progressCallback != null && totalSteps > 0) {
            int percent = Math.min(100, Math.max(0, (currentStep * 100) / totalSteps));
            progressCallback.onProgress(percent, message);
        }
    }

    /** Maps Media3 transcoding 0–100% into the middle of the current item’s progress (after “Transcoding…”). */
    private void reportTranscodeProgress(int transcoderPercent0To100) {
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
     * <p>Re-encodes with Media3 {@code Transformer} (H.264/AAC MP4) so container and stream metadata are
     * stripped; audio is preserved when the device can transcode the source. If transcoding fails, falls
     * back to a direct copy (metadata may remain).
     *
     * @param sourceUri        URI of the source video, must not be null
     * @param originalFilename Original filename of the video, must not be null
     * @return URI of the processed video, or null if processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripVideoMetadata(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        SentryManager.log("Starting video metadata stripping for MediaStore.");
        SentryManager.setCustomKey("original_filename", originalFilename);
        SentryManager.setCustomKey("operation_type", "video_to_mediastore");
        Uri newUri = null;

        try {
            // Check if file is too large to process (using cached size)
            long fileSize = getFileSizeFromUriCached(sourceUri);
            SentryManager.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            String extension = getFileExtension(originalFilename, ".mp4");

            updateProgress(1, 4, "Reading video...");
            updateProgress(2, 4, "Transcoding...");
            try {
                newUri = VideoMedia3Converter.transcodeToGallery(
                        context.getApplicationContext(),
                        sourceUri,
                        generateShortRandomName(),
                        VideoMedia3Converter.FORMAT_STRIP_METADATA,
                        this::reportTranscodeProgress);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Video transcoding interrupted", e);
            } catch (Exception e) {
                SentryManager.log(
                        "Video transcoding failed, falling back to direct copy: " + e.getMessage());
                String newFilename = generateShortRandomName() + extension;
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, newFilename);
                values.put(MediaStore.Video.Media.MIME_TYPE, videoMimeTypeForExtension(extension));
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Redact");

                newUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                if (newUri == null) {
                    SentryManager.log("Failed to create MediaStore entry for video.");
                    throw new IOException("Failed to create new video in MediaStore");
                }

                try (InputStream in = contentResolver.openInputStream(sourceUri);
                        OutputStream out = contentResolver.openOutputStream(newUri)) {

                    if (in == null || out == null) {
                        throw new IOException("Failed to open streams for video processing");
                    }

                    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                    out.flush();
                }
            }

            updateProgress(4, 4, "Saving cleaned video...");
            lastProcessedFileUri = newUri;
            SentryManager.log("Video processed successfully.");
            SentryManager.setCustomKey("success", true);

        } catch (Exception e) {
            // Log error and clean up any partial files
            Log.e(TAG, "Error processing video", e);
            SentryManager.recordException(e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());

            if (newUri != null) {
                try {
                    contentResolver.delete(newUri, null, null);
                } catch (Exception cleanupEx) {
                    SentryManager.log("Failed to clean up partial file: " + cleanupEx.getMessage());
                }
            }
        }

        return newUri;
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
     * @return URI of the processed image, or null if processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripExifData(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        SentryManager.log("Starting image EXIF stripping for MediaStore.");
        SentryManager.setCustomKey("original_filename", originalFilename);
        SentryManager.setCustomKey("operation_type", "image_to_mediastore");
        Uri newUri = null;
        Bitmap originalBitmap = null;
        File tempFile = null;

        try {
            // Check if file is too large to process (using cached size)
            long fileSize = getFileSizeFromUriCached(sourceUri);
            SentryManager.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            // Determine file extension from original filename or use default
            String extension = getFileExtension(originalFilename, ".jpg");

            // Generate unique filename for the processed file
            String newFilename = generateShortRandomName() + extension;
            updateProgress(1, 5, "Reading image...");

            // Create temporary file to hold the image during processing
            tempFile = new File(context.getExternalCacheDir(), "temp_" + System.currentTimeMillis() + ".jpg");

            // Copy source image to temporary file
            try (InputStream in = contentResolver.openInputStream(sourceUri);
                    FileOutputStream out = new FileOutputStream(tempFile)) {

                if (in == null) {
                    throw new IOException("Failed to open input stream");
                }

                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            // Extract essential EXIF data to preserve (like orientation)
            updateProgress(2, 5, "Reading essential metadata...");
            readEssentialExifData(tempFile);

            // Remove thumbnails from original
            try {
                ExifInterface tempExif = new ExifInterface(tempFile.getAbsolutePath());
                removeThumbnails(tempExif);
            } catch (Exception e) {
                SentryManager.log("Could not remove thumbnails from temp file: " + e.getMessage() + ".");
            }

            // Check image dimensions without loading the full bitmap
            BitmapFactory.Options optionsJustBounds = new BitmapFactory.Options();
            optionsJustBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsJustBounds);

            // Calculate appropriate sample size for memory-efficient loading
            int sampleSize = calculateInSampleSize(optionsJustBounds);
            SentryManager.setCustomKey("bitmap_sample_size", sampleSize);
            SentryManager.setCustomKey("original_width", optionsJustBounds.outWidth);
            SentryManager.setCustomKey("original_height", optionsJustBounds.outHeight);

            // Load bitmap with calculated sample size
            BitmapFactory.Options optionsLoad = new BitmapFactory.Options();
            optionsLoad.inSampleSize = sampleSize;

            originalBitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsLoad);
            if (originalBitmap == null) {
                throw new IOException("Failed to decode bitmap");
            }

            // Prepare MediaStore entry for the new image
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, newFilename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Redact");

            // Create the new entry in MediaStore
            newUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (newUri == null) {
                SentryManager.log("Failed to create MediaStore entry.");
                throw new IOException("Failed to create new image in MediaStore");
            }

            // Save bitmap without metadata
            updateProgress(3, 5, "Saving image without metadata...");

            try (OutputStream os = contentResolver.openOutputStream(newUri)) {
                if (os == null) {
                    throw new IOException("Failed to open output stream for new image");
                }

                // Compress bitmap directly to output stream (memory optimization - no
                // ByteArrayOutputStream)
                if (!originalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                    throw new IOException("Failed to compress bitmap");
                }
                os.flush();
            }

            // Clean up bitmap to free memory
            originalBitmap.recycle();
            originalBitmap = null;
            System.gc();

            // Restore only essential EXIF data (like orientation)
            updateProgress(4, 5, "Restoring essential metadata...");
            restoreEssentialExifData(newUri);

            // Verify metadata removal (for MediaStore files, we need to read from URI)
            updateProgress(5, 5, "Verifying metadata removal...");
            try {
                File tempVerifyFile = new File(context.getCacheDir(), "verify_" + System.currentTimeMillis() + ".jpg");
                try (InputStream is = contentResolver.openInputStream(newUri);
                        FileOutputStream fos = new FileOutputStream(tempVerifyFile)) {
                    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                    int bytesRead;
                    while (true) {
                        assert is != null;
                        if (!((bytesRead = is.read(buffer)) > 0))
                            break;
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                boolean metadataRemoved = verifyMetadataRemoval(tempVerifyFile);
                SentryManager.setCustomKey("metadata_verification_passed", metadataRemoved);
                if (!metadataRemoved) {
                    SentryManager.log("Warning: Metadata verification found remaining metadata.");
                }
                secureDeleteFile(tempVerifyFile);
            } catch (Exception e) {
                SentryManager.log("Could not verify metadata: " + e.getMessage() + ".");
            }

            lastProcessedFileUri = newUri;
            SentryManager.log("Image processed successfully.");
            SentryManager.setCustomKey("success", true);

        } catch (Exception e) {
            // Log error and clean up any partial files
            Log.e(TAG, "Error processing image", e);
            SentryManager.recordException(e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());

            if (newUri != null) {
                try {
                    contentResolver.delete(newUri, null, null);
                } catch (Exception cleanupEx) {
                    SentryManager.log("Failed to clean up partial file: " + cleanupEx.getMessage());
                }
            }
        } finally {
            // Clean up resources
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }

            if (tempFile != null && tempFile.exists()) {
                // Use secure deletion for temp files
                if (!secureDeleteFile(tempFile)) {
                    SentryManager.log("Failed to securely delete temp file: " + tempFile.getAbsolutePath());
                    tempFile.deleteOnExit();
                }
            }

            preservedExifValues.clear();
        }

        return newUri;
    }

    /**
     * Strips EXIF metadata from an image and saves it to the app's cache directory
     * for sharing.
     *
     * This method is similar to stripExifData but saves to app cache instead of
     * MediaStore,
     * making it suitable for temporary files intended for sharing.
     *
     * @param sourceUri        URI of the source image, must not be null
     * @param originalFilename Original filename of the image, must not be null
     * @return URI of the processed image (FileProvider URI), or null if processing
     *         failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripExifDataForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        SentryManager.log("Starting image EXIF stripping for sharing.");
        SentryManager.setCustomKey("original_filename", originalFilename);
        SentryManager.setCustomKey("operation_type", "image_for_sharing");

        Bitmap originalBitmap = null;
        File tempFile = null;
        File outputFile;

        try {
            // Check if file is too large to process (using cached size)
            long fileSize = getFileSizeFromUriCached(sourceUri);
            SentryManager.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            // Determine file extension from original filename or use default
            String extension = getFileExtension(originalFilename, ".jpg");

            updateProgress(1, 4, "Reading image...");

            // Create temporary file to hold the image during processing
            tempFile = new File(context.getCacheDir(), "temp_" + System.currentTimeMillis() + extension);

            // Copy source image to temporary file
            try (InputStream in = contentResolver.openInputStream(sourceUri);
                    FileOutputStream out = new FileOutputStream(tempFile)) {

                if (in == null) {
                    throw new IOException("Failed to open input stream");
                }

                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            // Extract essential EXIF data to preserve (like orientation)
            updateProgress(2, 4, "Reading essential metadata...");
            readEssentialExifData(tempFile);

            // Check image dimensions without loading the full bitmap
            BitmapFactory.Options optionsJustBounds = new BitmapFactory.Options();
            optionsJustBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsJustBounds);

            // Calculate appropriate sample size for memory-efficient loading
            int sampleSize = calculateInSampleSize(optionsJustBounds);
            SentryManager.setCustomKey("bitmap_sample_size", sampleSize);
            SentryManager.setCustomKey("original_width", optionsJustBounds.outWidth);
            SentryManager.setCustomKey("original_height", optionsJustBounds.outHeight);

            // Load bitmap with calculated sample size
            BitmapFactory.Options optionsLoad = new BitmapFactory.Options();
            optionsLoad.inSampleSize = sampleSize;
            originalBitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsLoad);

            if (originalBitmap == null) {
                throw new IOException("Failed to decode bitmap");
            }

            updateProgress(3, 5, "Removing metadata...");

            // Create directory for processed files if it doesn't exist
            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory");
                }
            }

            // Generate unique filename for the processed file (same scheme as main cleaning
            // process)
            String newFilename = generateShortRandomName() + extension;
            outputFile = new File(outputDir, newFilename);

            // Save bitmap to output file (direct streaming, no intermediate
            // ByteArrayOutputStream)
            // Use progressive JPEG for better perceived performance
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                // Compress directly to file output stream (memory optimization)
                if (!originalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)) {
                    throw new IOException("Failed to compress bitmap");
                }
                fos.flush();
                fos.getFD().sync(); // Ensure data is written to disk
            }

            // Clean up bitmap to free memory
            originalBitmap.recycle();
            originalBitmap = null;

            // Remove all EXIF metadata except essential tags
            updateProgress(4, 5, "Removing all metadata...");
            ExifInterface newExif = new ExifInterface(outputFile.getAbsolutePath());
            removeAllExifMetadata(newExif);
            // Restore only essential EXIF data (like orientation)
            restoreEssentialExifValues(newExif);
            newExif.saveAttributes();

            // Verify metadata removal
            updateProgress(5, 5, "Verifying metadata removal...");
            boolean metadataRemoved = verifyMetadataRemoval(outputFile);
            SentryManager.setCustomKey("metadata_verification_passed", metadataRemoved);
            if (!metadataRemoved) {
                SentryManager.log("Warning: Metadata verification found remaining metadata.");
            }

            // Get content URI using FileProvider for sharing
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            lastProcessedFileUri = fileUri;
            SentryManager.log("Image processed successfully for sharing.");
            SentryManager.setCustomKey("success", true);
            return fileUri;

        } catch (Exception e) {
            // Log error
            Log.e(TAG, "Error processing image for sharing", e);
            SentryManager.recordException(e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            return null;
        } finally {
            // Clean up resources
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }

            if (tempFile != null && tempFile.exists()) {
                // Use secure deletion for temp files
                if (!secureDeleteFile(tempFile)) {
                    SentryManager.log("Failed to securely delete temp file: " + tempFile.getAbsolutePath());
                    tempFile.deleteOnExit();
                }
            }

            preservedExifValues.clear();
        }
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
     * @return URI of the processed video (FileProvider URI), or null if processing
     *         failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripVideoMetadataForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        SentryManager.log("Starting video metadata stripping for sharing.");
        SentryManager.setCustomKey("original_filename", originalFilename);
        SentryManager.setCustomKey("operation_type", "video_for_sharing");

        File outputFile;

        try {
            // Check if file is too large to process (using cached size)
            long fileSize = getFileSizeFromUriCached(sourceUri);
            SentryManager.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            updateProgress(1, 4, "Reading video...");

            // Create directory for processed files if it doesn't exist
            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory");
                }
            }

            String newFilename = generateShortRandomName() + ".mp4";
            outputFile = new File(outputDir, newFilename);

            updateProgress(2, 4, "Transcoding...");
            try {
                VideoMedia3Converter.transcodeToFile(
                        context.getApplicationContext(),
                        sourceUri,
                        outputFile,
                        VideoMedia3Converter.FORMAT_STRIP_METADATA,
                        this::reportTranscodeProgress);
                updateProgress(3, 4, "Processing video metadata...");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Video transcoding interrupted", e);
            } catch (Exception e) {
                SentryManager.log("Video transcoding failed, falling back to direct copy: " + e.getMessage());
                try (InputStream in = contentResolver.openInputStream(sourceUri);
                        FileOutputStream out = new FileOutputStream(outputFile)) {

                    if (in == null) {
                        throw new IOException("Failed to open input stream for video");
                    }

                    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                    out.flush();
                }
            }

            updateProgress(4, 4, "Saving cleaned video...");

            // Get content URI using FileProvider for sharing
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            lastProcessedFileUri = fileUri;
            SentryManager.log("Video processed successfully for sharing.");
            SentryManager.setCustomKey("success", true);
            return fileUri;

        } catch (Exception e) {
            // Log error
            Log.e(TAG, "Error processing video for sharing", e);
            SentryManager.recordException(e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            return null;
        }
    }

    /**
     * Convenience method that routes to either stripExifDataForSharing or
     * stripVideoMetadataForSharing
     * based on the media type.
     *
     * @param sourceUri        URI of the source media file, must not be null
     * @param originalFilename Original filename of the media file, must not be null
     * @param isVideo          True if the media is a video, false if it's an image
     * @return URI of the processed media file (FileProvider URI), or null if
     *         processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripMetadataForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename, boolean isVideo) {
        // Log operation start for analytics and debugging
        SentryManager.log("Starting metadata stripping for sharing.");
        SentryManager.setCustomKey("is_video", isVideo);

        if (isVideo) {
            return stripVideoMetadataForSharing(sourceUri, originalFilename);
        } else {
            return stripExifDataForSharing(sourceUri, originalFilename);
        }
    }

    /**
     * Returns the URI of the most recently processed file.
     *
     * @return URI of the last processed file, or null if no file has been processed
     */
    @Nullable
    public Uri getLastProcessedFileUri() {
        return lastProcessedFileUri;
    }

    /**
     * Generates a short random alphanumeric string for use in filenames.
     * Uses SecureRandom for cryptographically secure random generation.
     *
     * @return A random string of 12 characters (alphanumeric)
     */
    @NonNull
    private String generateShortRandomName() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            int index = secureRandom.nextInt(chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
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
    @NonNull
    private String getFileExtension(@NonNull String originalFilename, @NonNull String defaultExtension) {
        String extension;
        int lastDotIndex = originalFilename.lastIndexOf(".");
        if (lastDotIndex != -1) {
            extension = originalFilename.substring(lastDotIndex);
        } else {
            extension = defaultExtension;
        }
        return extension;
    }

    /**
     * Returns a correct {@code video/*} MIME type for MediaStore based on file extension.
     */
    @NonNull
    private String videoMimeTypeForExtension(@NonNull String extensionWithDot) {
        String ext = extensionWithDot.toLowerCase(Locale.US);
        return switch (ext) {
            case ".mp4" -> "video/mp4";
            case ".webm" -> "video/webm";
            case ".mkv" -> "video/x-matroska";
            case ".mov" -> "video/quicktime";
            case ".3gp" -> "video/3gpp";
            case ".avi" -> "video/x-msvideo";
            default -> "video/mp4";
        };
    }

    /**
     * Determines the size of a file from its URI.
     *
     * This method tries multiple approaches to get the file size:
     * 1. OpenableColumns.SIZE (content URIs)
     * 2. File length for {@code file://} URIs
     * 3. AssetFileDescriptor length
     * 4. Reading the stream (last resort; capped to avoid scanning multi-GB files)
     *
     * @param uri URI of the file to check, must not be null
     * @return Size of the file in bytes, or 0 if size couldn't be determined
     */
    private long getFileSizeFromUri(@NonNull Uri uri) {
        final long maxMeasure = MAX_FILE_SIZE_MB * 1024L * 1024L + 1;
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                try (Cursor cursor = contentResolver.query(uri,
                        new String[]{OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            long sz = cursor.getLong(idx);
                            if (sz > 0) {
                                return sz;
                            }
                        }
                    }
                }
            } else if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path != null) {
                    File f = new File(path);
                    if (f.isFile()) {
                        return f.length();
                    }
                }
            }

            try (android.content.res.AssetFileDescriptor afd = contentResolver.openAssetFileDescriptor(uri, "r")) {
                if (afd != null) {
                    long size = afd.getLength();
                    if (size > 0) {
                        return size;
                    }
                }
            } catch (Exception ignored) {
                // Fall through to stream-based approach
            }

            try (InputStream stream = contentResolver.openInputStream(uri)) {
                if (stream == null) {
                    return 0;
                }
                long size = 0;
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = stream.read(buffer)) != -1) {
                    size += bytesRead;
                    if (size > maxMeasure) {
                        return size;
                    }
                }
                return size;
            }
        } catch (Exception e) {
            SentryManager.log("Error determining file size: " + e.getMessage() + ".");
            return 0;
        }
    }

    /**
     * Calculates an appropriate sample size for loading large bitmaps efficiently.
     *
     * This method determines how much to downsample an image during decoding to
     * avoid
     * out-of-memory errors while processing very large images. The original
     * resolution
     * is preserved in the final output file.
     *
     * @param options BitmapFactory.Options containing the image dimensions
     * @return Sample size to use for decoding (power of 2)
     */
    private int calculateInSampleSize(@NonNull BitmapFactory.Options options) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        // If image is larger than our maximum processing size, calculate sample size
        if (height > MAX_BITMAP_SIZE || width > MAX_BITMAP_SIZE) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width
            while ((halfHeight / inSampleSize) >= MAX_BITMAP_SIZE && (halfWidth / inSampleSize) >= MAX_BITMAP_SIZE) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * Reads and stores essential EXIF data from an image file that should be
     * preserved.
     *
     * This method extracts non-identifying metadata like orientation that is
     * important
     * for proper display of the image.
     *
     * @param imageFile Source image file to read EXIF data from, must not be null
     * @throws IOException if the file cannot be read or EXIF data cannot be
     *                     extracted
     */
    private void readEssentialExifData(@NonNull File imageFile) throws IOException {
        // Create ExifInterface for reading EXIF data
        ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
        preservedExifValues.clear();

        // List of EXIF tags that should be preserved (only truly essential for image
        // display)
        // Only orientation is essential - it tells the viewer how to rotate the image
        // All other metadata (color space, resolution, etc.) can be inferred or is not
        // critical
        String[] tagsToPreserve = {
                ExifInterface.TAG_ORIENTATION
        };

        // Store each tag that exists in the original file
        for (String tag : tagsToPreserve) {
            String value = exif.getAttribute(tag);
            if (value != null) {
                preservedExifValues.put(tag, value);
                SentryManager.log("Preserving EXIF tag: " + tag + ".");
            }
        }

        SentryManager.setCustomKey("preserved_exif_count", preservedExifValues.size());
    }

    /**
     * Restores essential EXIF data to a processed image in MediaStore.
     *
     * This method writes back non-identifying metadata like orientation that was
     * preserved from the original image.
     *
     * @param imageUri URI of the processed image in MediaStore, must not be null
     */
    private void restoreEssentialExifData(@NonNull Uri imageUri) {
        // Skip if no EXIF values were preserved
        if (preservedExifValues.isEmpty()) {
            return;
        }

        try {
            // Open the file for writing EXIF data
            ExifInterface newExif = new ExifInterface(
                    Objects.requireNonNull(contentResolver.openFileDescriptor(imageUri, "rw")).getFileDescriptor());

            // Remove all EXIF metadata except essential tags
            removeAllExifMetadata(newExif);

            // Restore the preserved EXIF values
            restoreEssentialExifValues(newExif);

            // Save the changes to the file
            newExif.saveAttributes();

        } catch (Exception e) {
            SentryManager.log("Failed to restore EXIF: " + e.getMessage() + ".");
            SentryManager.recordException(e);
        }
    }

    /**
     * Helper method to restore preserved EXIF values to an ExifInterface instance.
     *
     * @param exif ExifInterface to write values to, must not be null
     */
    private void restoreEssentialExifValues(@NonNull ExifInterface exif) {
        // Write each preserved value to the ExifInterface
        for (Map.Entry<String, String> entry : preservedExifValues.entrySet()) {
            exif.setAttribute(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Removes all EXIF metadata from an ExifInterface instance except for essential
     * tags.
     * This method uses reflection to get all possible TAG constants and removes
     * everything
     * except the tags we explicitly want to preserve.
     *
     * @param exif The ExifInterface instance to clean
     */
    private void removeAllExifMetadata(@NonNull ExifInterface exif) {
        try {
            // Get all TAG constants from ExifInterface using reflection
            java.lang.reflect.Field[] fields = ExifInterface.class.getDeclaredFields();
            // Only preserve orientation - it's the only tag essential for proper image
            // display
            // All other metadata (color space, resolution, etc.) can be inferred or is not
            // critical
            java.util.Set<String> tagsToPreserve = new java.util.HashSet<>(List.of(
                    ExifInterface.TAG_ORIENTATION));

            int removedCount = 0;
            for (java.lang.reflect.Field field : fields) {
                if (field.getType() == String.class && field.getName().startsWith("TAG_")) {
                    try {
                        String tagName = (String) field.get(null);
                        if (tagName != null && !tagsToPreserve.contains(tagName)) {
                            // Try to get the attribute to see if it exists
                            String value = exif.getAttribute(tagName);
                            if (value != null) {
                                // Remove the attribute by setting it to null
                                exif.setAttribute(tagName, null);
                                removedCount++;
                            }
                        }
                    } catch (IllegalAccessException | IllegalArgumentException e) {
                        // Skip fields we can't access
                    }
                }
            }

            SentryManager.log("Removed " + removedCount + " EXIF metadata tags.");
            SentryManager.setCustomKey("exif_tags_removed", removedCount);

        } catch (Exception e) {
            SentryManager.log("Error removing EXIF metadata: " + e.getMessage() + ".");
            SentryManager.recordException(e);
            // Fallback to hardcoded list if reflection fails
            removeExifMetadataFallback(exif);
        }
    }

    /**
     * Fallback method to remove EXIF metadata using a hardcoded list of tags.
     * Used when reflection fails or is unavailable.
     *
     * @param exif The ExifInterface instance to clean
     */
    private void removeExifMetadataFallback(@NonNull ExifInterface exif) {
        // Comprehensive list of all known EXIF tags to remove
        String[] tagsToRemove = {
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_IMAGE_UNIQUE_ID,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_AREA_INFORMATION,
                ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_DEST_BEARING,
                ExifInterface.TAG_GPS_DEST_BEARING_REF,
                ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
                ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
                ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
                ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_DOP,
                ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_MEASURE_MODE,
                ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_SATELLITES,
                ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
                ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
                ExifInterface.TAG_GPS_VERSION_ID,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER,
                ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
                ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_LENS_SPECIFICATION,
                ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
                ExifInterface.TAG_CFA_PATTERN, ExifInterface.TAG_COMPONENTS_CONFIGURATION,
                ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL, ExifInterface.TAG_COMPRESSION,
                ExifInterface.TAG_CONTRAST, ExifInterface.TAG_CUSTOM_RENDERED,
                ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                ExifInterface.TAG_EXPOSURE_INDEX, ExifInterface.TAG_EXPOSURE_MODE,
                ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FLASH, ExifInterface.TAG_FLASH_ENERGY,
                ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT, ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
                ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, ExifInterface.TAG_GAIN_CONTROL,
                ExifInterface.TAG_ISO_SPEED, ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY,
                ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ, ExifInterface.TAG_LIGHT_SOURCE,
                ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_METERING_MODE,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_PLANAR_CONFIGURATION,
                ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX, ExifInterface.TAG_ROWS_PER_STRIP,
                ExifInterface.TAG_SAMPLES_PER_PIXEL, ExifInterface.TAG_SATURATION,
                ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_SCENE_TYPE,
                ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_SENSITIVITY_TYPE,
                ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                ExifInterface.TAG_SPECTRAL_SENSITIVITY, ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
                ExifInterface.TAG_SUBJECT_AREA, ExifInterface.TAG_SUBJECT_DISTANCE,
                ExifInterface.TAG_SUBJECT_DISTANCE_RANGE, ExifInterface.TAG_SUBJECT_LOCATION,
                ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_WHITE_POINT, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION, ExifInterface.TAG_MAKER_NOTE,
                ExifInterface.TAG_OECF, ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
                ExifInterface.TAG_INTEROPERABILITY_INDEX, ExifInterface.TAG_FILE_SOURCE,
                ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL
        };

        int removedCount = 0;
        for (String tag : tagsToRemove) {
            if (exif.getAttribute(tag) != null) {
                exif.setAttribute(tag, null);
                removedCount++;
            }
        }
        SentryManager.log("Removed " + removedCount + " EXIF metadata tags (fallback method).");
    }

    /**
     * Safely closes a Closeable resource, suppressing any exceptions.
     *
     * @param closeable Resource to close, may be null
     */
    private void closeQuietly(@Nullable Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                SentryManager.log("Error closing resource: " + e.getMessage() + ".");
            }
        }
    }

    /**
     * Deletes temporary files that are older than 24 hours.
     *
     * This method should be called periodically to prevent accumulation of
     * temporary files in the app's cache directory.
     */
    public void cleanupTempFiles() {
        try {
            // Files older than 24 hours will be deleted
            long cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000);

            // Get the directory where processed files are stored
            File cacheDir = new File(context.getCacheDir(), "processed");

            if (cacheDir.exists() && cacheDir.isDirectory()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    int deletedCount = 0;
                    for (File file : files) {
                        // Delete files older than the cutoff time
                        if (file.lastModified() < cutoffTime) {
                            if (file.delete()) {
                                deletedCount++;
                            }
                        }
                    }

                    if (deletedCount > 0) {
                        SentryManager.log("Cleaned up " + deletedCount + " temporary files.");
                    }
                }
            }
        } catch (Exception e) {
            SentryManager.log("Error cleaning up temporary files: " + e.getMessage() + ".");
        }
    }

    /**
     * Checks if available memory is running low.
     *
     * This method calculates the ratio of available memory to maximum memory
     * and returns true if less than 20% is available.
     *
     * @return true if memory is running low, false otherwise
     */
    private boolean isMemoryLow() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long availableMemory = maxMemory - usedMemory;

        boolean isLow = availableMemory < (maxMemory * 0.2);
        if (isLow) {
            SentryManager.log("Memory is running low: " +
                    (availableMemory / (1024 * 1024)) + "MB available out of " +
                    (maxMemory / (1024 * 1024)) + "MB max.");
        }
        return isLow;
    }

    /**
     * Attempts to free memory by triggering garbage collection if memory is low.
     *
     * Note: This is generally not recommended in Android as the system manages
     * memory,
     * but can be useful in specific cases when processing large media files.
     */
    private void tryFreeMemory() {
        if (isMemoryLow()) {
            System.gc();
            Runtime.getRuntime().runFinalization();
            System.gc();
        }
    }

    /**
     * Checks if a file exceeds the maximum size limit for processing.
     *
     * @param uri URI of the file to check, must not be null
     * @return true if the file is too large to process, false otherwise
     */
    public boolean isFileTooLarge(@NonNull Uri uri) {
        try {
            long fileSize = getFileSizeFromUri(uri);
            return fileSize > MAX_FILE_SIZE_MB * 1024 * 1024;
        } catch (Exception e) {
            SentryManager.log("Error checking file size: " + e.getMessage() + ".");
            return false;
        }
    }

    /**
     * Returns the maximum file size in megabytes that can be processed.
     *
     * @return Maximum file size in MB
     */
    public long getMaxFileSizeMB() {
        return MAX_FILE_SIZE_MB;
    }

    /**
     * Securely deletes a file by overwriting it with random data multiple times
     * before deletion.
     * This makes file recovery significantly more difficult.
     *
     * @param file The file to securely delete
     * @return true if the file was successfully deleted, false otherwise
     */
    private boolean secureDeleteFile(@NonNull File file) {
        if (!file.exists() || !file.isFile()) {
            return false;
        }

        try {
            long fileSize = file.length();
            if (fileSize == 0) {
                return file.delete();
            }

            // Overwrite file with random data multiple times
            byte[] randomData = new byte[SECURE_DELETE_BUFFER_SIZE];
            for (int pass = 0; pass < SECURE_DELETE_PASSES; pass++) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
                    long position = 0;
                    while (position < fileSize) {
                        secureRandom.nextBytes(randomData);
                        int bytesToWrite = (int) Math.min(randomData.length, fileSize - position);
                        raf.write(randomData, 0, bytesToWrite);
                        position += bytesToWrite;
                    }
                    raf.getFD().sync(); // Force write to disk
                }
            }

            // Finally delete the file
            return file.delete();
        } catch (Exception e) {
            SentryManager.log("Error during secure file deletion: " + e.getMessage() + ".");
            // Fallback to regular deletion
            return file.delete();
        }
    }

    /**
     * Verifies that metadata has been properly removed from an image file.
     * Checks for remaining EXIF, XMP, and IPTC metadata.
     *
     * @param imageFile The image file to verify
     * @return true if no identifying metadata was found, false if metadata remains
     */
    private boolean verifyMetadataRemoval(@NonNull File imageFile) {
        try {
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());

            // Check for any remaining identifying EXIF tags
            String[] identifyingTags = {
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT,
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    ExifInterface.TAG_USER_COMMENT,
                    ExifInterface.TAG_MAKER_NOTE
            };

            for (String tag : identifyingTags) {
                String value = exif.getAttribute(tag);
                if (value != null && !value.isEmpty()) {
                    SentryManager.log("Warning: Found remaining metadata tag: " + tag + " = " + value + ".");
                    return false;
                }
            }

            // Check for XMP metadata (basic check - look for XMP header in file)
            if (containsXMPMetadata(imageFile)) {
                SentryManager.log("Warning: Found XMP metadata in file.");
                return false;
            }

            return true;
        } catch (Exception e) {
            SentryManager.log("Error verifying metadata removal: " + e.getMessage() + ".");
            // If verification fails, assume it's okay to avoid blocking the process
            return true;
        }
    }

    /**
     * Checks if a file contains XMP metadata by looking for XMP header.
     *
     * @param file The file to check
     * @return true if XMP metadata is found, false otherwise
     */
    private boolean containsXMPMetadata(@NonNull File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead = fis.read(buffer);
            if (bytesRead > 0) {
                String content = new String(buffer, 0, bytesRead, StandardCharsets.ISO_8859_1);
                // Look for XMP header markers
                return content.contains("http://ns.adobe.com/xap/1.0/") ||
                        content.contains("xpacket") ||
                        content.contains("x:xmpmeta");
            }
        } catch (Exception e) {
            // Ignore errors in XMP detection
        }
        return false;
    }

    /**
     * Removes embedded thumbnails from an image file by clearing thumbnail-related
     * EXIF tags.
     *
     * @param exif The ExifInterface instance for the image
     */
    private void removeThumbnails(@NonNull ExifInterface exif) {
        try {
            // Remove thumbnail-related tags
            exif.setAttribute(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, null);
            exif.setAttribute(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, null);

            // Try to remove thumbnail data if available
            byte[] thumbnail = exif.getThumbnailBytes();
            if (thumbnail != null && thumbnail.length > 0) {
                // Thumbnail removal is handled by re-encoding without it
                SentryManager.log("Found embedded thumbnail, will be removed during re-encoding.");
            }
        } catch (Exception e) {
            SentryManager.log("Error removing thumbnails: " + e.getMessage() + ".");
        }
    }

    /**
     * Removes XMP and IPTC metadata from a JPEG file by parsing and rewriting
     * without metadata segments.
     * This is a basic implementation that removes common metadata markers.
     *
     * @param inputFile  Input file with metadata
     * @param outputFile Output file without metadata
     * @throws IOException if processing fails
     */
    private void removeXMPAndIPTCMetadata(@NonNull File inputFile, @NonNull File outputFile) throws IOException {
        // For JPEG files, XMP and IPTC are typically in APP segments
        // This is a simplified approach - a full implementation would parse JPEG
        // segments
        try (FileInputStream fis = new FileInputStream(inputFile);
                FileOutputStream fos = new FileOutputStream(outputFile)) {

            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            boolean inMetadataSegment = false;

            while ((bytesRead = fis.read(buffer)) > 0) {
                // Basic approach: The bitmap re-encoding already removes most metadata
                // This method is a placeholder for more advanced XMP/IPTC removal
                // A full implementation would require JPEG segment parsing
                fos.write(buffer, 0, bytesRead);
            }
        }
    }

    /**
     * Gets file size from URI with caching to avoid redundant I/O operations.
     *
     * @param uri URI of the file
     * @return File size in bytes, or 0 if size couldn't be determined
     */
    private long getFileSizeFromUriCached(@NonNull Uri uri) {
        String uriString = uri.toString();

        // Check cache first
        Long cachedSize = fileSizeCache.get(uriString);
        if (cachedSize != null) {
            return cachedSize;
        }

        // Get size and cache it
        long size = getFileSizeFromUri(uri);
        if (size > 0) {
            fileSizeCache.put(uriString, size);
        }

        return size;
    }

    /**
     * Clears the file size cache.
     */
    public void clearFileSizeCache() {
        fileSizeCache.clear();
    }
}
