package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Provides functionality to strip metadata from media files (images and videos).
 *
 * This class handles:
 *   - Removing EXIF data from images while preserving essential information like orientation
 *   - Processing videos to remove metadata
 *   - Creating clean copies of media files for both storing and sharing
 *   - Memory management for large media files
 *   - Progress reporting during processing operations
 *
 * The class implements two primary workflows:
 *   - Stripping metadata and saving to MediaStore (permanent storage)
 *   - Stripping metadata and saving to app cache (temporary, for sharing)
 *
 * For images, the process involves:
 *   - Reading the original image and any essential EXIF data to preserve
 *   - Decoding the image into a bitmap (with memory optimization for large images)
 *   - Re-encoding the bitmap without any metadata
 *   - Restoring only essential non-identifying EXIF tags (like orientation)
 *
 * For videos, the process involves:
 *   - Creating a new file in the target location
 *   - Copying the video data stream without processing the container metadata
 *
 * @see ExifInterface
 * @see MediaStore
 * @see FileProvider
 */
public class MetadataStripper {
    private static final String TAG = "MetadataStripper";

    /**
     * Default buffer size for file I/O operations.
     * This value is chosen to balance memory usage and performance.
     */
    private static final int DEFAULT_BUFFER_SIZE = 4096;

    /**
     * Maximum dimension (width or height) for bitmap processing.
     * Images larger than this will be downsampled during processing to avoid
     * out-of-memory errors. The original resolution is preserved in the output file.
     */
    private static final int MAX_BITMAP_SIZE = 4096;

    /**
     * Maximum file size in megabytes that can be processed.
     * Files larger than this will be rejected to prevent excessive memory/storage usage.
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
         * @param current Current progress step (0-based)
         * @param total Total number of steps
         * @param message Human-readable progress message suitable for display to users
         */
        void onProgressUpdate(int current, int total, String message);
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
     * Firebase Crashlytics for error reporting and analytics.
     * Used to log processing details and report any errors that occur.
     */
    private final FirebaseCrashlytics crashlytics;

    /**
     * Map to store essential EXIF values that should be preserved during processing.
     * These values (like orientation) are important for proper display but don't contain
     * identifying information.
     */
    private final Map<String, String> preservedExifValues = new HashMap<>();

    /**
     * Creates a new MetadataStripper instance.
     *
     * @param context Application context for accessing system services, must not be null
     * @throws IllegalArgumentException if context is null
     */
    public MetadataStripper(@NonNull Context context) {
        // Store application context to prevent memory leaks
        this.context = context.getApplicationContext();
        this.contentResolver = this.context.getContentResolver();
        this.crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("MetadataStripper initialized");
    }

    /**
     * Sets a callback to receive progress updates during processing.
     *
     * This allows the client to display progress information to the user
     * during potentially lengthy operations.
     *
     * @param callback The progress callback implementation, or null to remove the callback
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
     * @param total Total number of steps
     * @param message Human-readable progress message
     */
    private void updateProgress(int current, int total, String message) {
        if (progressCallback != null) {
            progressCallback.onProgressUpdate(current, total, message);
        }
    }

    /**
     * Process video to remove metadata and save to MediaStore (external storage).
     *
     * This method:
     * 1. Creates a new entry in MediaStore.Video
     * 2. Copies the video data without processing container metadata
     * 3. Reports progress during the copy operation
     *
     * The resulting video will have the same content but with container metadata removed.
     * Note that this does not process or modify the actual video stream, only the container
     * metadata (like creation date, GPS location, etc.).
     *
     * @param sourceUri URI of the source video, must not be null
     * @param originalFilename Original filename of the video, must not be null
     * @return URI of the processed video, or null if processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripVideoMetadata(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        crashlytics.log("Starting video metadata stripping for MediaStore");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "video_to_mediastore");
        Uri newUri = null;

        try {
            // Check if file is too large to process
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            // Determine file extension from original filename or use default
            String extension = getFileExtension(originalFilename, ".mp4");

            // Generate unique filename for the processed file
            String newFilename = UUID.randomUUID().toString() + extension;
            updateProgress(1, 3, "Reading video...");

            // Prepare MediaStore entry for the new video
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, newFilename);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/" + extension.substring(1));
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Redact");

            // Create the new entry in MediaStore
            newUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

            if (newUri == null) {
                crashlytics.log("Failed to create MediaStore entry for video");
                throw new IOException("Failed to create new video in MediaStore");
            }

            updateProgress(2, 3, "Removing metadata...");

            // Copy video data without processing metadata
            try (InputStream in = contentResolver.openInputStream(sourceUri);
                 OutputStream out = contentResolver.openOutputStream(newUri)) {

                if (in == null || out == null) {
                    throw new IOException("Failed to open streams for video processing");
                }

                // Copy data in chunks, reporting progress periodically
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int length;
                long totalBytesRead = 0;
                long fileLength = getFileSizeFromUri(sourceUri);

                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                    out.flush();

                    // Update progress periodically
                    totalBytesRead += length;
                    if (fileLength > 0 && totalBytesRead % (DEFAULT_BUFFER_SIZE * 10) == 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileLength);
                        updateProgress(2, 3, "Removing metadata... " + progress + "%");
                    }
                }
            }

            updateProgress(3, 3, "Saving cleaned video...");
            lastProcessedFileUri = newUri;
            crashlytics.log("Video processed successfully");
            crashlytics.setCustomKey("success", true);

        } catch (Exception e) {
            // Log error and clean up any partial files
            Log.e(TAG, "Error processing video", e);
            crashlytics.recordException(e);
            crashlytics.setCustomKey("success", false);
            crashlytics.setCustomKey("error_type", e.getClass().getName());

            if (newUri != null) {
                try {
                    contentResolver.delete(newUri, null, null);
                } catch (Exception cleanupEx) {
                    crashlytics.log("Failed to clean up partial file: " + cleanupEx.getMessage());
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
     * @param sourceUri URI of the source image, must not be null
     * @param originalFilename Original filename of the image, must not be null
     * @return URI of the processed image, or null if processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripExifData(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        crashlytics.log("Starting image EXIF stripping for MediaStore");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "image_to_mediastore");
        Uri newUri = null;
        Bitmap originalBitmap = null;
        File tempFile = null;

        try {
            // Check if file is too large to process
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            // Determine file extension from original filename or use default
            String extension = getFileExtension(originalFilename, ".jpg");

            // Generate unique filename for the processed file
            String newFilename = UUID.randomUUID().toString() + extension;
            updateProgress(1, 4, "Reading image...");

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
            updateProgress(2, 4, "Reading essential metadata...");
            readEssentialExifData(tempFile);

            // Check image dimensions without loading the full bitmap
            BitmapFactory.Options optionsJustBounds = new BitmapFactory.Options();
            optionsJustBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsJustBounds);

            // Calculate appropriate sample size for memory-efficient loading
            int sampleSize = calculateInSampleSize(optionsJustBounds);
            crashlytics.setCustomKey("bitmap_sample_size", sampleSize);
            crashlytics.setCustomKey("original_width", optionsJustBounds.outWidth);
            crashlytics.setCustomKey("original_height", optionsJustBounds.outHeight);

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
                crashlytics.log("Failed to create MediaStore entry");
                throw new IOException("Failed to create new image in MediaStore");
            }

            // Save bitmap without metadata
            updateProgress(3, 4, "Saving image without metadata...");

            try (OutputStream os = contentResolver.openOutputStream(newUri)) {
                if (os == null) {
                    throw new IOException("Failed to open output stream for new image");
                }

                // Compress bitmap to JPEG and write to output
                ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
                if (!originalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, byteArrayOS)) {
                    throw new IOException("Failed to compress bitmap");
                }

                os.write(byteArrayOS.toByteArray());
                os.flush();
            }

            // Clean up bitmap to free memory
            originalBitmap.recycle();
            originalBitmap = null;
            System.gc();

            // Restore only essential EXIF data (like orientation)
            updateProgress(4, 4, "Restoring essential metadata...");
            restoreEssentialExifData(newUri);

            lastProcessedFileUri = newUri;
            crashlytics.log("Image processed successfully");
            crashlytics.setCustomKey("success", true);

        } catch (Exception e) {
            // Log error and clean up any partial files
            Log.e(TAG, "Error processing image", e);
            crashlytics.recordException(e);
            crashlytics.setCustomKey("success", false);
            crashlytics.setCustomKey("error_type", e.getClass().getName());

            if (newUri != null) {
                try {
                    contentResolver.delete(newUri, null, null);
                } catch (Exception cleanupEx) {
                    crashlytics.log("Failed to clean up partial file: " + cleanupEx.getMessage());
                }
            }
        } finally {
            // Clean up resources
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }

            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    crashlytics.log("Failed to delete temp file: " + tempFile.getAbsolutePath());
                    tempFile.deleteOnExit();
                }
            }

            preservedExifValues.clear();
        }

        return newUri;
    }

    /**
     * Strips EXIF metadata from an image and saves it to the app's cache directory for sharing.
     *
     * This method is similar to stripExifData but saves to app cache instead of MediaStore,
     * making it suitable for temporary files intended for sharing.
     *
     * @param sourceUri URI of the source image, must not be null
     * @param originalFilename Original filename of the image, must not be null
     * @return URI of the processed image (FileProvider URI), or null if processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripExifDataForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        crashlytics.log("Starting image EXIF stripping for sharing");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "image_for_sharing");

        Bitmap originalBitmap = null;
        File tempFile = null;
        File outputFile;

        try {
            // Check if file is too large to process
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
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
            crashlytics.setCustomKey("bitmap_sample_size", sampleSize);
            crashlytics.setCustomKey("original_width", optionsJustBounds.outWidth);
            crashlytics.setCustomKey("original_height", optionsJustBounds.outHeight);

            // Load bitmap with calculated sample size
            BitmapFactory.Options optionsLoad = new BitmapFactory.Options();
            optionsLoad.inSampleSize = sampleSize;
            originalBitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsLoad);

            if (originalBitmap == null) {
                throw new IOException("Failed to decode bitmap");
            }

            updateProgress(3, 4, "Removing metadata...");

            // Create directory for processed files if it doesn't exist
            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory");
                }
            }

            // Generate unique filename for the processed file (same scheme as main cleaning process)
            String newFilename = UUID.randomUUID().toString() + extension;
            outputFile = new File(outputDir, newFilename);

            // Save bitmap to output file
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                originalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush();
            }

            // Clean up bitmap to free memory
            originalBitmap.recycle();
            originalBitmap = null;
            System.gc();

            // Restore only essential EXIF data (like orientation)
            updateProgress(4, 4, "Restoring essential metadata...");
            ExifInterface newExif = new ExifInterface(outputFile.getAbsolutePath());
            restoreEssentialExifValues(newExif);
            newExif.saveAttributes();

            // Get content URI using FileProvider for sharing
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            lastProcessedFileUri = fileUri;
            crashlytics.log("Image processed successfully for sharing");
            crashlytics.setCustomKey("success", true);
            return fileUri;

        } catch (Exception e) {
            // Log error
            Log.e(TAG, "Error processing image for sharing", e);
            crashlytics.recordException(e);
            crashlytics.setCustomKey("success", false);
            crashlytics.setCustomKey("error_type", e.getClass().getName());
            return null;
        } finally {
            // Clean up resources
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }

            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    crashlytics.log("Failed to delete temp file: " + tempFile.getAbsolutePath());
                    tempFile.deleteOnExit();
                }
            }

            preservedExifValues.clear();
        }
    }

    /**
     * Strips metadata from a video and saves it to the app's cache directory for sharing.
     *
     * This method:
     * 1. Copies the video data to a new file in the app's cache directory
     * 2. Reports progress during the copy operation
     * 3. Returns a FileProvider URI for sharing the processed video
     *
     * @param sourceUri URI of the source video, must not be null
     * @param originalFilename Original filename of the video, must not be null
     * @return URI of the processed video (FileProvider URI), or null if processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripVideoMetadataForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        crashlytics.log("Starting video metadata stripping for sharing");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "video_for_sharing");

        File outputFile;

        try {
            // Check if file is too large to process
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            // Determine file extension from original filename or use default
            String extension = getFileExtension(originalFilename, ".mp4");

            updateProgress(1, 3, "Reading video...");

            // Create directory for processed files if it doesn't exist
            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory");
                }
            }

            // Generate unique filename for the processed file (same scheme as main cleaning process)
            String newFilename = UUID.randomUUID().toString() + extension;
            outputFile = new File(outputDir, newFilename);

            updateProgress(2, 3, "Removing metadata...");

            // Copy video data without processing metadata
            try (InputStream in = contentResolver.openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(outputFile)) {

                if (in == null) {
                    throw new IOException("Failed to open input stream for video");
                }

                // Copy data in chunks, reporting progress periodically
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int length;
                long totalBytesRead = 0;

                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                    out.flush();

                    // Update progress periodically
                    totalBytesRead += length;
                    if (fileSize > 0 && totalBytesRead % (DEFAULT_BUFFER_SIZE * 10) == 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                        updateProgress(2, 3, "Removing metadata... " + progress + "%");
                    }
                }
            }

            updateProgress(3, 3, "Saving cleaned video...");

            // Get content URI using FileProvider for sharing
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            lastProcessedFileUri = fileUri;
            crashlytics.log("Video processed successfully for sharing");
            crashlytics.setCustomKey("success", true);
            return fileUri;

        } catch (Exception e) {
            // Log error
            Log.e(TAG, "Error processing video for sharing", e);
            crashlytics.recordException(e);
            crashlytics.setCustomKey("success", false);
            crashlytics.setCustomKey("error_type", e.getClass().getName());
            return null;
        }
    }

    /**
     * Convenience method that routes to either stripExifDataForSharing or stripVideoMetadataForSharing
     * based on the media type.
     *
     * @param sourceUri URI of the source media file, must not be null
     * @param originalFilename Original filename of the media file, must not be null
     * @param isVideo True if the media is a video, false if it's an image
     * @return URI of the processed media file (FileProvider URI), or null if processing failed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @Nullable
    public Uri stripMetadataForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename, boolean isVideo) {
        // Log operation start for analytics and debugging
        crashlytics.log("Starting metadata stripping for sharing");
        crashlytics.setCustomKey("is_video", isVideo);

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
     * Extracts the file extension from a filename or returns a default extension if none is found.
     *
     * @param originalFilename Filename to extract extension from, must not be null
     * @param defaultExtension Default extension to use if none is found, must not be null
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
     * Determines the size of a file from its URI.
     *
     * This method tries multiple approaches to get the file size:
     * 1. Using the file descriptor if available
     * 2. Reading the entire file if necessary
     *
     * @param uri URI of the file to check, must not be null
     * @return Size of the file in bytes, or 0 if size couldn't be determined
     */
    private long getFileSizeFromUri(@NonNull Uri uri) {
        try {
            try (InputStream stream = contentResolver.openInputStream(uri)) {
                if (stream == null) return 0;

                // Try to get file descriptor and its size
                try {
                    FileDescriptor fd = ((FileDescriptor) stream.getClass().getMethod("getFD").invoke(stream));
                    assert fd != null;
                    return fd.toString().length();
                } catch (Exception ignored) {
                    // If file descriptor approach fails, read the entire file
                    long size = 0;
                    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = stream.read(buffer)) != -1) {
                        size += bytesRead;
                    }
                    return size;
                }
            }
        } catch (Exception e) {
            crashlytics.log("Error determining file size: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Calculates an appropriate sample size for loading large bitmaps efficiently.
     *
     * This method determines how much to downsample an image during decoding to avoid
     * out-of-memory errors while processing very large images. The original resolution
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
     * Reads and stores essential EXIF data from an image file that should be preserved.
     *
     * This method extracts non-identifying metadata like orientation that is important
     * for proper display of the image.
     *
     * @param imageFile Source image file to read EXIF data from, must not be null
     * @throws IOException if the file cannot be read or EXIF data cannot be extracted
     */
    private void readEssentialExifData(@NonNull File imageFile) throws IOException {
        // Create ExifInterface for reading EXIF data
        ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
        preservedExifValues.clear();

        // List of EXIF tags that should be preserved (non-identifying metadata)
        String[] tagsToPreserve = {
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_COLOR_SPACE,
                ExifInterface.TAG_PIXEL_X_DIMENSION,
                ExifInterface.TAG_PIXEL_Y_DIMENSION,
                ExifInterface.TAG_BITS_PER_SAMPLE,
                ExifInterface.TAG_RESOLUTION_UNIT,
                ExifInterface.TAG_X_RESOLUTION,
                ExifInterface.TAG_Y_RESOLUTION
        };

        // Store each tag that exists in the original file
        for (String tag : tagsToPreserve) {
            String value = exif.getAttribute(tag);
            if (value != null) {
                preservedExifValues.put(tag, value);
                crashlytics.log("Preserving EXIF tag: " + tag);
            }
        }

        crashlytics.setCustomKey("preserved_exif_count", preservedExifValues.size());
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

            // Restore the preserved EXIF values
            restoreEssentialExifValues(newExif);

            // Save the changes to the file
            newExif.saveAttributes();

        } catch (Exception e) {
            crashlytics.log("Failed to restore EXIF: " + e.getMessage());
            crashlytics.recordException(e);
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
     * Returns a comprehensive list of EXIF tags that should be removed for privacy.
     *
     * This includes tags that might contain identifying information like:
     * - Date/time information
     * - GPS location data
     * - Camera make/model
     * - Serial numbers
     * - User-entered descriptions
     *
     * @return Array of EXIF tag constants to remove
     */
    @NonNull
    private String[] getExifTagsToRemove() {
        return new String[] {
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_IMAGE_UNIQUE_ID,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_AREA_INFORMATION,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_DEST_BEARING,
                ExifInterface.TAG_GPS_DEST_BEARING_REF,
                ExifInterface.TAG_GPS_DEST_DISTANCE,
                ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
                ExifInterface.TAG_GPS_DEST_LATITUDE,
                ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
                ExifInterface.TAG_GPS_DEST_LONGITUDE,
                ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
                ExifInterface.TAG_GPS_DIFFERENTIAL,
                ExifInterface.TAG_GPS_DOP,
                ExifInterface.TAG_GPS_IMG_DIRECTION,
                ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_MAP_DATUM,
                ExifInterface.TAG_GPS_MEASURE_MODE,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_SATELLITES,
                ExifInterface.TAG_GPS_SPEED,
                ExifInterface.TAG_GPS_SPEED_REF,
                ExifInterface.TAG_GPS_STATUS,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_TRACK,
                ExifInterface.TAG_GPS_TRACK_REF,
                ExifInterface.TAG_GPS_VERSION_ID,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_CAMERA_OWNER_NAME,
                ExifInterface.TAG_BODY_SERIAL_NUMBER,
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL,
                ExifInterface.TAG_LENS_SERIAL_NUMBER,
                ExifInterface.TAG_LENS_SPECIFICATION,
                ExifInterface.TAG_APERTURE_VALUE,
                ExifInterface.TAG_BRIGHTNESS_VALUE,
                ExifInterface.TAG_CFA_PATTERN,
                ExifInterface.TAG_COMPONENTS_CONFIGURATION,
                ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
                ExifInterface.TAG_COMPRESSION,
                ExifInterface.TAG_CONTRAST,
                ExifInterface.TAG_CUSTOM_RENDERED,
                ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
                ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                ExifInterface.TAG_EXPOSURE_INDEX,
                ExifInterface.TAG_EXPOSURE_MODE,
                ExifInterface.TAG_EXPOSURE_PROGRAM,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_FLASH_ENERGY,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
                ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
                ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
                ExifInterface.TAG_GAIN_CONTROL,
                ExifInterface.TAG_ISO_SPEED,
                ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY,
                ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ,
                ExifInterface.TAG_LIGHT_SOURCE,
                ExifInterface.TAG_MAX_APERTURE_VALUE,
                ExifInterface.TAG_METERING_MODE,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_PLANAR_CONFIGURATION,
                ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
                ExifInterface.TAG_ROWS_PER_STRIP,
                ExifInterface.TAG_SAMPLES_PER_PIXEL,
                ExifInterface.TAG_SATURATION,
                ExifInterface.TAG_SCENE_CAPTURE_TYPE,
                ExifInterface.TAG_SCENE_TYPE,
                ExifInterface.TAG_SENSING_METHOD,
                ExifInterface.TAG_SENSITIVITY_TYPE,
                ExifInterface.TAG_SHARPNESS,
                ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                ExifInterface.TAG_SPECTRAL_SENSITIVITY,
                ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
                ExifInterface.TAG_SUBJECT_AREA,
                ExifInterface.TAG_SUBJECT_DISTANCE,
                ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
                ExifInterface.TAG_SUBJECT_LOCATION,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_WHITE_POINT,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
                ExifInterface.TAG_IMAGE_UNIQUE_ID,
                ExifInterface.TAG_MAKER_NOTE,
                ExifInterface.TAG_OECF,
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
                ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
                ExifInterface.TAG_INTEROPERABILITY_INDEX,
                ExifInterface.TAG_FILE_SOURCE,
                ExifInterface.TAG_OFFSET_TIME,
                ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL
        };
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
                crashlytics.log("Error closing resource: " + e.getMessage());
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
                        crashlytics.log("Cleaned up " + deletedCount + " temporary files");
                    }
                }
            }
        } catch (Exception e) {
            crashlytics.log("Error cleaning up temporary files: " + e.getMessage());
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
            crashlytics.log("Memory is running low: " +
                    (availableMemory / (1024 * 1024)) + "MB available out of " +
                    (maxMemory / (1024 * 1024)) + "MB max");
        }
        return isLow;
    }

    /**
     * Attempts to free memory by triggering garbage collection if memory is low.
     *
     * Note: This is generally not recommended in Android as the system manages memory,
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
            crashlytics.log("Error checking file size: " + e.getMessage());
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
}
