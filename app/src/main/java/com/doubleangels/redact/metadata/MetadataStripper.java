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
 * - Removing EXIF data from images while preserving essential information like orientation
 * - Processing videos to remove metadata
 * - Creating clean copies of media files for both storing and sharing
 * - Memory management for large media files
 * - Progress reporting during processing operations
 */
public class MetadataStripper {
    private static final String TAG = "MetadataStripper";
    private static final int DEFAULT_BUFFER_SIZE = 4096;
    private static final int MAX_BITMAP_SIZE = 4096;
    private static final long MAX_FILE_SIZE_MB = 100;

    /**
     * Interface for reporting progress during media processing operations.
     */
    public interface ProgressCallback {
        /**
         * Called periodically during processing to update on progress.
         *
         * @param current Current progress step
         * @param total Total number of steps
         * @param message Human-readable progress message
         */
        void onProgressUpdate(int current, int total, String message);
    }

    /** Application context for accessing system services */
    private final Context context;

    /** Content resolver for MediaStore operations */
    private final ContentResolver contentResolver;

    /** Optional callback for progress updates */
    private ProgressCallback progressCallback;

    /** Stores the most recently processed file's URI */
    private Uri lastProcessedFileUri;

    /** Firebase Crashlytics for error reporting and analytics */
    private final FirebaseCrashlytics crashlytics;

    /** Map to store essential EXIF values that should be preserved during processing */
    private final Map<String, String> preservedExifValues = new HashMap<>();

    /**
     * Creates a new MetadataStripper instance.
     *
     * @param context Application context for accessing system services
     */
    public MetadataStripper(Context context) {
        this.context = context;
        this.contentResolver = context.getContentResolver();
        this.crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("MetadataStripper initialized");
    }

    /**
     * Sets a callback to receive progress updates during processing.
     *
     * @param callback The progress callback implementation
     */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * Updates the progress callback with current status.
     *
     * @param current Current step in the process
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
     * @param sourceUri URI of the source video
     * @param originalFilename Original filename of the video
     * @return URI of the processed video, or null if processing failed
     */
    public Uri stripVideoMetadata(Uri sourceUri, String originalFilename) {
        crashlytics.log("Starting video metadata stripping for MediaStore");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "video_to_mediastore");
        Uri newUri = null;

        try {
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            String extension = getFileExtension(originalFilename, ".mp4");

            String newFilename = UUID.randomUUID().toString() + extension;
            updateProgress(1, 3, "Reading video...");

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, newFilename);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/" + extension.substring(1));
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Redact");

            newUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

            if (newUri == null) {
                crashlytics.log("Failed to create MediaStore entry for video");
                throw new IOException("Failed to create new video in MediaStore");
            }

            updateProgress(2, 3, "Removing metadata...");

            try (InputStream in = contentResolver.openInputStream(sourceUri);
                 OutputStream out = contentResolver.openOutputStream(newUri)) {

                if (in == null || out == null) {
                    throw new IOException("Failed to open streams for video processing");
                }

                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int length;
                long totalBytesRead = 0;
                long fileLength = getFileSizeFromUri(sourceUri);

                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                    out.flush();

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
     * Process image to remove EXIF data and save to MediaStore (external storage).
     *
     * @param sourceUri URI of the source image
     * @param originalFilename Original filename of the image
     * @return URI of the processed image, or null if processing failed
     */
    public Uri stripExifData(Uri sourceUri, String originalFilename) {
        crashlytics.log("Starting image EXIF stripping for MediaStore");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "image_to_mediastore");
        Uri newUri = null;
        Bitmap originalBitmap = null;
        File tempFile = null;

        try {
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            String extension = getFileExtension(originalFilename, ".jpg");

            String newFilename = UUID.randomUUID().toString() + extension;
            updateProgress(1, 4, "Reading image...");

            tempFile = new File(context.getExternalCacheDir(), "temp_" + System.currentTimeMillis() + ".jpg");
            boolean tempCreated;

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

            updateProgress(2, 4, "Reading essential metadata...");
            readEssentialExifData(tempFile);

            BitmapFactory.Options optionsJustBounds = new BitmapFactory.Options();
            optionsJustBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsJustBounds);

            int sampleSize = calculateInSampleSize(optionsJustBounds);
            crashlytics.setCustomKey("bitmap_sample_size", sampleSize);
            crashlytics.setCustomKey("original_width", optionsJustBounds.outWidth);
            crashlytics.setCustomKey("original_height", optionsJustBounds.outHeight);

            BitmapFactory.Options optionsLoad = new BitmapFactory.Options();
            optionsLoad.inSampleSize = sampleSize;

            originalBitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsLoad);
            if (originalBitmap == null) {
                throw new IOException("Failed to decode bitmap");
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, newFilename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Redact");

            newUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (newUri == null) {
                crashlytics.log("Failed to create MediaStore entry");
                throw new IOException("Failed to create new image in MediaStore");
            }

            updateProgress(3, 4, "Saving image without metadata...");

            try (OutputStream os = contentResolver.openOutputStream(newUri)) {
                if (os == null) {
                    throw new IOException("Failed to open output stream for new image");
                }

                ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
                if (!originalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, byteArrayOS)) {
                    throw new IOException("Failed to compress bitmap");
                }

                os.write(byteArrayOS.toByteArray());
                os.flush();
            }

            originalBitmap.recycle();
            originalBitmap = null;
            System.gc();

            updateProgress(4, 4, "Restoring essential metadata...");

            restoreEssentialExifData(newUri);

            lastProcessedFileUri = newUri;
            crashlytics.log("Image processed successfully");
            crashlytics.setCustomKey("success", true);

        } catch (Exception e) {
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
     * Process image to remove EXIF data for sharing (saves to app's cache).
     *
     * @param sourceUri URI of the source image
     * @param originalFilename Original filename of the image
     * @return URI of the processed image for sharing, or null if processing failed
     */
    public Uri stripExifDataForSharing(Uri sourceUri, String originalFilename) {
        crashlytics.log("Starting image EXIF stripping for sharing");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "image_for_sharing");

        Bitmap originalBitmap = null;
        File tempFile = null;
        File outputFile;

        try {
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            String extension = getFileExtension(originalFilename, ".jpg");

            updateProgress(1, 4, "Reading image...");

            tempFile = new File(context.getCacheDir(), "temp_" + System.currentTimeMillis() + extension);

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

            updateProgress(2, 4, "Reading essential metadata...");
            readEssentialExifData(tempFile);

            BitmapFactory.Options optionsJustBounds = new BitmapFactory.Options();
            optionsJustBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsJustBounds);

            int sampleSize = calculateInSampleSize(optionsJustBounds);
            crashlytics.setCustomKey("bitmap_sample_size", sampleSize);
            crashlytics.setCustomKey("original_width", optionsJustBounds.outWidth);
            crashlytics.setCustomKey("original_height", optionsJustBounds.outHeight);

            BitmapFactory.Options optionsLoad = new BitmapFactory.Options();
            optionsLoad.inSampleSize = sampleSize;
            originalBitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath(), optionsLoad);

            if (originalBitmap == null) {
                throw new IOException("Failed to decode bitmap");
            }

            updateProgress(3, 4, "Removing metadata...");

            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory");
                }
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String cleanFileName = "clean_" + timestamp + extension;
            outputFile = new File(outputDir, cleanFileName);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                originalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush();
            }

            originalBitmap.recycle();
            originalBitmap = null;
            System.gc();

            updateProgress(4, 4, "Restoring essential metadata...");

            ExifInterface newExif = new ExifInterface(outputFile.getAbsolutePath());
            restoreEssentialExifValues(newExif);
            newExif.saveAttributes();

            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            lastProcessedFileUri = fileUri;
            crashlytics.log("Image processed successfully for sharing");
            crashlytics.setCustomKey("success", true);
            return fileUri;

        } catch (Exception e) {
            Log.e(TAG, "Error processing image for sharing", e);
            crashlytics.recordException(e);
            crashlytics.setCustomKey("success", false);
            crashlytics.setCustomKey("error_type", e.getClass().getName());
            return null;
        } finally {
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
     * Process video to remove metadata for sharing (saves to app's cache).
     *
     * @param sourceUri URI of the source video
     * @param originalFilename Original filename of the video
     * @return URI of the processed video for sharing, or null if processing failed
     */
    public Uri stripVideoMetadataForSharing(Uri sourceUri, String originalFilename) {
        crashlytics.log("Starting video metadata stripping for sharing");
        crashlytics.setCustomKey("original_filename", originalFilename);
        crashlytics.setCustomKey("operation_type", "video_for_sharing");

        File outputFile;

        try {
            long fileSize = getFileSizeFromUri(sourceUri);
            crashlytics.setCustomKey("file_size_mb", fileSize / (1024 * 1024));
            if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                throw new IOException("File too large to process: " + fileSize / (1024 * 1024) + "MB");
            }

            String extension = getFileExtension(originalFilename, ".mp4");

            updateProgress(1, 3, "Reading video...");

            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory");
                }
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String cleanFileName = "clean_" + timestamp + extension;
            outputFile = new File(outputDir, cleanFileName);

            updateProgress(2, 3, "Removing metadata...");

            try (InputStream in = contentResolver.openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(outputFile)) {

                if (in == null) {
                    throw new IOException("Failed to open input stream for video");
                }

                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int length;
                long totalBytesRead = 0;

                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                    out.flush();

                    totalBytesRead += length;
                    if (fileSize > 0 && totalBytesRead % (DEFAULT_BUFFER_SIZE * 10) == 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                        updateProgress(2, 3, "Removing metadata... " + progress + "%");
                    }
                }
            }

            updateProgress(3, 3, "Saving cleaned video...");

            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            lastProcessedFileUri = fileUri;
            crashlytics.log("Video processed successfully for sharing");
            crashlytics.setCustomKey("success", true);
            return fileUri;

        } catch (Exception e) {
            Log.e(TAG, "Error processing video for sharing", e);
            crashlytics.recordException(e);
            crashlytics.setCustomKey("success", false);
            crashlytics.setCustomKey("error_type", e.getClass().getName());
            return null;
        }
    }

    /**
     * Processes either an image or video for sharing based on the media type.
     * This is a convenience method that routes to the appropriate specialized method.
     *
     * @param sourceUri URI of the source media file
     * @param originalFilename Original filename of the media
     * @param isVideo Whether the media is a video (true) or image (false)
     * @return URI of the processed media for sharing, or null if processing failed
     */
    public Uri stripMetadataForSharing(Uri sourceUri, String originalFilename, boolean isVideo) {
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
     * @return The URI of the last processed file, or null if no file has been processed
     */
    public Uri getLastProcessedFileUri() {
        return lastProcessedFileUri;
    }

    /* ------------------------------ Helper Methods ------------------------------ */

    /**
     * Extracts file extension from original filename or provides a default.
     *
     * @param originalFilename Original filename to extract extension from
     * @param defaultExtension Default extension to use if none is found
     * @return The file extension (including the dot)
     */
    private String getFileExtension(String originalFilename, String defaultExtension) {
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
     * Calculates file size from URI.
     *
     * @param uri URI of the file to measure
     * @return Size of the file in bytes
     */
    private long getFileSizeFromUri(Uri uri) {
        try {
            try (InputStream stream = contentResolver.openInputStream(uri)) {
                if (stream == null) return 0;

                try {
                    FileDescriptor fd = ((FileDescriptor) stream.getClass().getMethod("getFD").invoke(stream));
                    assert fd != null;
                    return fd.toString().length();
                } catch (Exception ignored) {
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
     * Calculates an optimal sample size for loading large bitmaps efficiently.
     *
     * @param options BitmapFactory.Options containing the image dimensions
     * @return Optimal sample size for loading the bitmap
     */
    private int calculateInSampleSize(BitmapFactory.Options options) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > MetadataStripper.MAX_BITMAP_SIZE || width > MetadataStripper.MAX_BITMAP_SIZE) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= MetadataStripper.MAX_BITMAP_SIZE && (halfWidth / inSampleSize) >= MetadataStripper.MAX_BITMAP_SIZE) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * Reads essential EXIF data from an image file and stores it for preservation.
     *
     * @param imageFile The image file to read EXIF data from
     * @throws IOException If reading the EXIF data fails
     */
    private void readEssentialExifData(File imageFile) throws IOException {
        ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
        preservedExifValues.clear();

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
     * Restores essential EXIF data to the output file via MediaStore URI.
     *
     * @param imageUri URI of the image to restore EXIF data to
     */
    private void restoreEssentialExifData(Uri imageUri) {
        if (preservedExifValues.isEmpty()) {
            return;
        }

        try {
            ExifInterface newExif = new ExifInterface(
                    Objects.requireNonNull(contentResolver.openFileDescriptor(imageUri, "rw")).getFileDescriptor());

            restoreEssentialExifValues(newExif);
            newExif.saveAttributes();

        } catch (Exception e) {
            crashlytics.log("Failed to restore EXIF: " + e.getMessage());
            crashlytics.recordException(e);
        }
    }

    /**
     * Applies preserved EXIF values to the given ExifInterface.
     *
     * @param exif ExifInterface to apply preserved values to
     */
    private void restoreEssentialExifValues(ExifInterface exif) {
        for (Map.Entry<String, String> entry : preservedExifValues.entrySet()) {
            exif.setAttribute(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Returns a list of all EXIF tags that should be removed for privacy.
     * This is a reference list, not directly used in processing.
     *
     * @return Array of EXIF tags that contain potentially sensitive information
     */
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
     * Safely closes a closeable resource, swallowing any IOExceptions.
     *
     * @param closeable The resource to close
     */
    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                crashlytics.log("Error closing resource: " + e.getMessage());
            }
        }
    }

    /**
     * Cleans up temporary files in the cache directory to avoid filling storage.
     * Removes files older than 24 hours.
     */
    public void cleanupTempFiles() {
        try {
            long cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            File cacheDir = new File(context.getCacheDir(), "processed");

            if (cacheDir.exists() && cacheDir.isDirectory()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    int deletedCount = 0;
                    for (File file : files) {
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
     * @return true if available memory is less than 20% of maximum memory
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
     * Attempts to free memory when running low by forcing garbage collection.
     * Note: This is generally not recommended in normal operation,
     * but can help prevent OOM in critical operations.
     */
    private void tryFreeMemory() {
        if (isMemoryLow()) {
            System.gc();
            Runtime.getRuntime().runFinalization();
            System.gc();
        }
    }
}

