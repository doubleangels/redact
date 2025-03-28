package com.doubleangels.redact.metadata;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.R;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MetadataDisplayer is a utility class that extracts and formats metadata from media files (images and videos).
 * It provides methods to extract metadata in both consolidated and sectioned formats, handling permissions
 * and formatting the output for display to users.
 *
 * The class uses ExifInterface for image metadata and MediaMetadataRetriever for video metadata,
 * and performs operations asynchronously to avoid blocking the UI thread.
 */
public class MetadataDisplayer {
    private static final String TAG = "MetadataDisplayer";

    // Constants defining metadata section keys
    public static final String SECTION_BASIC_INFO = "basic_info";
    public static final String SECTION_CAMERA_DETAILS = "camera_details";
    public static final String SECTION_LOCATION = "location";
    public static final String SECTION_TECHNICAL = "technical";

    /**
     * Callback interface for receiving consolidated metadata extraction results.
     */
    public interface MetadataCallback {
        /**
         * Called when metadata extraction is successful.
         *
         * @param metadata The extracted metadata formatted as a string
         * @param isVideo Whether the processed file is a video
         */
        void onMetadataExtracted(String metadata, boolean isVideo);

        /**
         * Called when metadata extraction fails.
         *
         * @param error Error message describing the failure
         */
        void onExtractionFailed(String error);
    }

    /**
     * Callback interface for receiving sectioned metadata extraction results.
     */
    public interface SectionedMetadataCallback {
        /**
         * Called when sectioned metadata extraction is successful.
         *
         * @param metadataSections Map containing different sections of metadata
         * @param isVideo Whether the processed file is a video
         */
        void onMetadataExtracted(Map<String, String> metadataSections, boolean isVideo);

        /**
         * Called when metadata extraction fails.
         *
         * @param error Error message describing the failure
         */
        void onExtractionFailed(String error);
    }

    /**
     * Extracts metadata from a media file and returns it as a single formatted string.
     * The operation is performed asynchronously on a background thread.
     *
     * @param context Application context
     * @param mediaUri URI of the media file to analyze
     * @param callback Callback to receive the extraction result
     */
    public static void extractMetadata(Context context, Uri mediaUri, MetadataCallback callback) {
        // Initialize Crashlytics for this operation
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Starting metadata extraction");
        crashlytics.setCustomKey("operation_type", "extract_metadata");

        // Create a single thread executor to perform the extraction in the background
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                crashlytics.log("Processing media URI: " + mediaUri);
                ContentResolver contentResolver = context.getContentResolver();
                String mimeType = contentResolver.getType(mediaUri);
                // Determine if the file is a video based on MIME type
                boolean isVideo = mimeType != null && mimeType.startsWith("video/");
                crashlytics.setCustomKey("is_video", isVideo);
                crashlytics.setCustomKey("mime_type", mimeType != null ? mimeType : "unknown");

                StringBuilder metadata = new StringBuilder();

                // Extract basic file information (name, size, type)
                extractBasicFileInfo(context, mediaUri, metadata);

                // Extract either video or image specific metadata
                if (isVideo) {
                    crashlytics.log("Extracting video metadata");
                    extractVideoMetadata(context, mediaUri, metadata);
                } else {
                    crashlytics.log("Extracting image metadata");
                    extractImageMetadata(context, mediaUri, metadata);
                }

                // Return the result via callback
                crashlytics.log("Metadata extraction completed successfully");
                callback.onMetadataExtracted(metadata.toString(), isVideo);
            } catch (Exception e) {
                // Log the exception and notify callback of failure
                Log.e(TAG, "Error extracting metadata", e);
                crashlytics.recordException(e);
                crashlytics.setCustomKey("extraction_failed", true);
                crashlytics.setCustomKey("error_type", e.getClass().getName());
                callback.onExtractionFailed(context.getString(R.string.metadata_error_extraction, e.getMessage()));
            } finally {
                executor.shutdown();
            }
        });
    }

    /**
     * Extracts metadata from a media file and organizes it into logical sections.
     * The operation is performed asynchronously on a background thread.
     *
     * @param context Application context
     * @param mediaUri URI of the media file to analyze
     * @param callback Callback to receive the sectioned extraction result
     */
    public static void extractSectionedMetadata(Context context, Uri mediaUri, SectionedMetadataCallback callback) {
        // Initialize Crashlytics for this operation
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Starting sectioned metadata extraction");
        crashlytics.setCustomKey("operation_type", "extract_sectioned_metadata");

        // Create a single thread executor to perform the extraction in the background
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                crashlytics.log("Processing media URI: " + mediaUri);
                ContentResolver contentResolver = context.getContentResolver();
                String mimeType = contentResolver.getType(mediaUri);
                // Determine if the file is a video based on MIME type
                boolean isVideo = mimeType != null && mimeType.startsWith("video/");
                crashlytics.setCustomKey("is_video", isVideo);
                crashlytics.setCustomKey("mime_type", mimeType != null ? mimeType : "unknown");

                // Map to store different sections of metadata
                Map<String, String> sections = new HashMap<>();

                // Extract and add basic file information
                StringBuilder basicInfo = new StringBuilder();
                extractBasicFileInfo(context, mediaUri, basicInfo);
                sections.put(SECTION_BASIC_INFO, basicInfo.toString());

                // Extract either video or image specific metadata in sectioned format
                if (isVideo) {
                    crashlytics.log("Extracting sectioned video metadata");
                    Map<String, String> videoSections = extractVideoMetadataSectioned(context, mediaUri);
                    sections.putAll(videoSections);
                } else {
                    crashlytics.log("Extracting sectioned image metadata");
                    Map<String, String> imageSections = extractImageMetadataSectioned(context, mediaUri);
                    sections.putAll(imageSections);
                }

                // Log the number of sections extracted
                crashlytics.setCustomKey("sections_count", sections.size());
                crashlytics.log("Sectioned metadata extraction completed successfully");

                // Return the result via callback
                callback.onMetadataExtracted(sections, isVideo);
            } catch (Exception e) {
                // Log the exception and notify callback of failure
                Log.e(TAG, "Error extracting sectioned metadata", e);
                crashlytics.recordException(e);
                crashlytics.setCustomKey("extraction_failed", true);
                crashlytics.setCustomKey("error_type", e.getClass().getName());
                callback.onExtractionFailed(context.getString(R.string.metadata_error_extraction, e.getMessage()));
            } finally {
                executor.shutdown();
            }
        });
    }

    /**
     * Extracts basic file information such as name, size, and MIME type.
     *
     * @param context Application context
     * @param mediaUri URI of the media file
     * @param metadata StringBuilder to append the extracted information to
     */
    private static void extractBasicFileInfo(Context context, Uri mediaUri, StringBuilder metadata) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Extracting basic file info");

        try {
            ContentResolver contentResolver = context.getContentResolver();

            String fileName = null;
            long fileSize = -1;

            // Query the content resolver for file name and size
            try (Cursor cursor = contentResolver.query(mediaUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);

                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex);
                        crashlytics.setCustomKey("file_name", fileName != null ? fileName : "unknown");
                    }

                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex);
                        crashlytics.setCustomKey("file_size_bytes", fileSize);
                    }
                }
            }

            // Format file size in appropriate units (bytes, KB, MB)
            String formattedSize;
            if (fileSize >= 0) {
                if (fileSize < 1024) {
                    formattedSize = fileSize + " " + context.getString(R.string.metadata_size_bytes);
                } else if (fileSize < 1024 * 1024) {
                    formattedSize = String.format(Locale.getDefault(), "%.2f " + context.getString(R.string.metadata_size_kb), fileSize / 1024.0);
                } else {
                    formattedSize = String.format(Locale.getDefault(), "%.2f " + context.getString(R.string.metadata_size_mb), fileSize / (1024.0 * 1024));
                }
            } else {
                formattedSize = context.getString(R.string.metadata_unknown);
            }

            String mimeType = contentResolver.getType(mediaUri);

            // Append basic file information to the metadata StringBuilder
            metadata.append(context.getString(R.string.metadata_file_name, fileName != null ? fileName : context.getString(R.string.metadata_unknown))).append("\n");
            metadata.append(context.getString(R.string.metadata_file_size, formattedSize)).append("\n");
            metadata.append(context.getString(R.string.metadata_mime_type, mimeType != null ? mimeType : context.getString(R.string.metadata_unknown))).append("\n");

            crashlytics.log("Basic file info extracted successfully");
        } catch (Exception e) {
            // Append error message if extraction fails
            metadata.append(context.getString(R.string.metadata_error_basic_info, e.getMessage())).append("\n");
            crashlytics.log("Error extracting basic file info: " + e.getMessage());
            crashlytics.recordException(e);
        }
    }

    /**
     * Extracts detailed metadata from an image file using ExifInterface.
     * Includes image properties, camera information, exposure details, and location data if available.
     *
     * @param context Application context
     * @param imageUri URI of the image file
     * @param metadata StringBuilder to append the extracted information to
     */
    private static void extractImageMetadata(Context context, Uri imageUri, StringBuilder metadata) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Extracting image metadata");

        try (InputStream inputStream = context.getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) {
                metadata.append(context.getString(R.string.metadata_error_file_open));
                crashlytics.log("Failed to open input stream for image");
                return;
            }

            // Create ExifInterface from the input stream
            ExifInterface exifInterface;
            exifInterface = new ExifInterface(inputStream);
            crashlytics.log("ExifInterface created successfully");

            // Extract and append image properties
            metadata.append("\n").append(context.getString(R.string.metadata_image_properties_header)).append("\n");

            int width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
            int height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
            if (width > 0 && height > 0) {
                metadata.append(context.getString(R.string.metadata_dimensions, width, height)).append("\n");
                crashlytics.setCustomKey("image_width", width);
                crashlytics.setCustomKey("image_height", height);
            }

            String dateTaken = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
            if (dateTaken != null) {
                metadata.append(context.getString(R.string.metadata_date_taken, dateTaken)).append("\n");
            }

            // Extract and append camera information
            metadata.append("\n").append(context.getString(R.string.metadata_camera_information_header)).append("\n");

            String make = exifInterface.getAttribute(ExifInterface.TAG_MAKE);
            if (make != null) {
                metadata.append(context.getString(R.string.metadata_camera_make, make)).append("\n");
            }

            String model = exifInterface.getAttribute(ExifInterface.TAG_MODEL);
            if (model != null) {
                metadata.append(context.getString(R.string.metadata_camera_model, model)).append("\n");
            }

            // Extract and append exposure information
            metadata.append("\n").append(context.getString(R.string.metadata_exposure_information_header)).append("\n");

            String aperture = exifInterface.getAttribute(ExifInterface.TAG_APERTURE_VALUE);
            if (aperture != null) {
                metadata.append(context.getString(R.string.metadata_aperture, aperture)).append("\n");
            }

            String exposureTime = exifInterface.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exposureTime != null) {
                float exposureValue = Float.parseFloat(exposureTime);
                // Format exposure time as fraction (1/x) for values less than 1 second
                if (exposureValue < 1) {
                    metadata.append(context.getString(R.string.metadata_exposure_time_fraction, Math.round(1 / exposureValue))).append("\n");
                } else {
                    metadata.append(context.getString(R.string.metadata_exposure_time_seconds, exposureValue)).append("\n");
                }
            }

            // Check if we have permission to access media location
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
            crashlytics.setCustomKey("has_location_permission", hasLocationPermission);

            // Extract and append location information if available
            metadata.append("\n").append(context.getString(R.string.metadata_location_information_header)).append("\n");

            if (hasLocationPermission) {
                double[] latLong = exifInterface.getLatLong();

                if (latLong != null) {
                    metadata.append(context.getString(R.string.metadata_latitude, latLong[0])).append("\n");
                    metadata.append(context.getString(R.string.metadata_longitude, latLong[1])).append("\n");
                    crashlytics.setCustomKey("has_location_data", true);
                    crashlytics.log("Location data found in image");

                    // Try to get human-readable address from coordinates using Geocoder
                    try {
                        if (Geocoder.isPresent()) {
                            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                            List<Address> addresses = geocoder.getFromLocation(latLong[0], latLong[1], 1);

                            if (addresses != null && !addresses.isEmpty()) {
                                Address address = addresses.get(0);
                                StringBuilder addressText = new StringBuilder();

                                for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                                    addressText.append(address.getAddressLine(i));
                                    if (i < address.getMaxAddressLineIndex()) {
                                        addressText.append(", ");
                                    }
                                }

                                metadata.append(context.getString(R.string.metadata_address, addressText)).append("\n");
                                crashlytics.log("Geocoding successful");
                            }
                        }
                    } catch (Exception e) {
                        metadata.append(context.getString(R.string.metadata_geocoding_failed, e.getMessage())).append("\n");
                        crashlytics.log("Geocoding failed: " + e.getMessage());
                        crashlytics.recordException(e);
                    }
                } else {
                    metadata.append(context.getString(R.string.metadata_no_location_data)).append("\n");
                    crashlytics.setCustomKey("has_location_data", false);
                }
            } else {
                metadata.append(context.getString(R.string.metadata_location_permission_needed)).append("\n");
            }

            // Extract and append technical details
            metadata.append("\n").append(context.getString(R.string.metadata_technical_details_header)).append("\n");

            String orientation = exifInterface.getAttribute(ExifInterface.TAG_ORIENTATION);
            if (orientation != null) {
                metadata.append(context.getString(R.string.metadata_orientation, getOrientationString(context, Integer.parseInt(orientation)))).append("\n");
            }

            String flash = exifInterface.getAttribute(ExifInterface.TAG_FLASH);
            if (flash != null) {
                boolean flashFired = (Integer.parseInt(flash) & 0x1) != 0;
                metadata.append(context.getString(R.string.metadata_flash,
                        flashFired ? context.getString(R.string.metadata_flash_fired) : context.getString(R.string.metadata_flash_not_fired))).append("\n");
            }

            String whiteBalance = exifInterface.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
            if (whiteBalance != null) {
                metadata.append(context.getString(R.string.metadata_white_balance,
                        whiteBalance.equals("0") ? context.getString(R.string.metadata_white_balance_auto) :
                                context.getString(R.string.metadata_white_balance_manual))).append("\n");
            }

            crashlytics.log("Image metadata extraction completed");

        } catch (IOException e) {
            // Append error message if extraction fails
            metadata.append(context.getString(R.string.metadata_error_image_metadata, e.getMessage()));
            Log.e(TAG, "Error extracting image metadata", e);
            crashlytics.recordException(e);
        }
    }

    /**
     * Extracts image metadata and organizes it into logical sections.
     * Returns a map with different categories of metadata.
     *
     * @param context Application context
     * @param imageUri URI of the image file
     * @return Map containing different sections of metadata
     */
    private static Map<String, String> extractImageMetadataSectioned(Context context, Uri imageUri) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Extracting sectioned image metadata");

        Map<String, String> sections = new HashMap<>();
        StringBuilder mediaDetails = new StringBuilder();
        StringBuilder locationInfo = new StringBuilder();
        StringBuilder technicalDetails = new StringBuilder();

        try (InputStream inputStream = context.getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) {
                crashlytics.log("Failed to open input stream for sectioned image metadata");
                return sections;
            }

            // Create ExifInterface from the input stream
            ExifInterface exifInterface;
            exifInterface = new ExifInterface(inputStream);

            // Extract camera and media details
            String make = exifInterface.getAttribute(ExifInterface.TAG_MAKE);
            if (make != null) {
                mediaDetails.append(context.getString(R.string.metadata_camera_make, make)).append("\n");
            }

            String model = exifInterface.getAttribute(ExifInterface.TAG_MODEL);
            if (model != null) {
                mediaDetails.append(context.getString(R.string.metadata_camera_model, model)).append("\n");
            }

            String dateTaken = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
            if (dateTaken != null) {
                mediaDetails.append(context.getString(R.string.metadata_date_taken, dateTaken)).append("\n");
            }

            String aperture = exifInterface.getAttribute(ExifInterface.TAG_APERTURE_VALUE);
            if (aperture != null) {
                mediaDetails.append(context.getString(R.string.metadata_aperture, aperture)).append("\n");
            }

            String exposureTime = exifInterface.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exposureTime != null) {
                float exposureValue = Float.parseFloat(exposureTime);
                // Format exposure time as fraction (1/x) for values less than 1 second
                if (exposureValue < 1) {
                    mediaDetails.append(context.getString(R.string.metadata_exposure_time_fraction, Math.round(1 / exposureValue))).append("\n");
                } else {
                    mediaDetails.append(context.getString(R.string.metadata_exposure_time_seconds, exposureValue)).append("\n");
                }
            }

            String flash = exifInterface.getAttribute(ExifInterface.TAG_FLASH);
            if (flash != null) {
                boolean flashFired = (Integer.parseInt(flash) & 0x1) != 0;
                mediaDetails.append(context.getString(R.string.metadata_flash,
                        flashFired ? context.getString(R.string.metadata_flash_fired) : context.getString(R.string.metadata_flash_not_fired))).append("\n");
            }

            String whiteBalance = exifInterface.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
            if (whiteBalance != null) {
                mediaDetails.append(context.getString(R.string.metadata_white_balance,
                        whiteBalance.equals("0") ? context.getString(R.string.metadata_white_balance_auto) :
                                context.getString(R.string.metadata_white_balance_manual))).append("\n");
            }

            // Check if we have permission to access media location
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            crashlytics.log("Has location permission: " + hasLocationPermission);
            crashlytics.setCustomKey("has_location_permission", hasLocationPermission);

            // Extract location information if permission is granted
            if (hasLocationPermission) {
                double[] latLong = exifInterface.getLatLong();

                if (latLong != null) {
                    locationInfo.append(context.getString(R.string.metadata_latitude, latLong[0])).append("\n");
                    locationInfo.append(context.getString(R.string.metadata_longitude, latLong[1])).append("\n");
                    crashlytics.setCustomKey("has_location_data", true);

                    // Try to get human-readable address from coordinates using Geocoder
                    try {
                        if (Geocoder.isPresent()) {
                            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                            List<Address> addresses = geocoder.getFromLocation(latLong[0], latLong[1], 1);

                            if (addresses != null && !addresses.isEmpty()) {
                                Address address = addresses.get(0);
                                StringBuilder addressText = new StringBuilder();

                                for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                                    addressText.append(address.getAddressLine(i));
                                    if (i < address.getMaxAddressLineIndex()) {
                                        addressText.append(", ");
                                    }
                                }

                                locationInfo.append(context.getString(R.string.metadata_address, addressText)).append("\n");
                                crashlytics.log("Geocoding successful");
                            }
                        }
                    } catch (Exception e) {
                        locationInfo.append(context.getString(R.string.metadata_geocoding_failed, e.getMessage())).append("\n");
                        crashlytics.log("Geocoding failed: " + e.getMessage());
                        crashlytics.recordException(e);
                    }
                } else {
                    locationInfo.append(context.getString(R.string.metadata_no_image_location_data)).append("\n");
                    crashlytics.setCustomKey("has_location_data", false);
                }
            } else {
                locationInfo.append(context.getString(R.string.metadata_location_permission_needed_full)).append("\n");
            }

            // Extract technical details
            int width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
            int height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
            if (width > 0 && height > 0) {
                technicalDetails.append(context.getString(R.string.metadata_dimensions, width, height)).append("\n");
                crashlytics.setCustomKey("image_width", width);
                crashlytics.setCustomKey("image_height", height);
            }

            String orientation = exifInterface.getAttribute(ExifInterface.TAG_ORIENTATION);
            if (orientation != null) {
                technicalDetails.append(context.getString(R.string.metadata_orientation, getOrientationString(context, Integer.parseInt(orientation)))).append("\n");
            }

            String software = exifInterface.getAttribute(ExifInterface.TAG_SOFTWARE);
            if (software != null) {
                technicalDetails.append(context.getString(R.string.metadata_software, software)).append("\n");
            }

            String focalLength = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if (focalLength != null) {
                technicalDetails.append(context.getString(R.string.metadata_focal_length, focalLength)).append("\n");
            }

            String digitalZoom = exifInterface.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO);
            if (digitalZoom != null && !digitalZoom.equals("0")) {
                technicalDetails.append(context.getString(R.string.metadata_digital_zoom, digitalZoom)).append("\n");
            }

            crashlytics.log("Sectioned image metadata extraction completed");

        } catch (IOException e) {
            Log.e(TAG, "Error extracting sectioned image metadata", e);
            crashlytics.recordException(e);
        }

        // Add non-empty sections to the result map
        if (mediaDetails.length() > 0) {
            sections.put(SECTION_CAMERA_DETAILS, mediaDetails.toString());
        }

        if (locationInfo.length() > 0) {
            sections.put(SECTION_LOCATION, locationInfo.toString());
        }

        if (technicalDetails.length() > 0) {
            sections.put(SECTION_TECHNICAL, technicalDetails.toString());
        }

        return sections;
    }

    /**
     * Extracts detailed metadata from a video file using MediaMetadataRetriever.
     * Includes video properties, duration, resolution, location data if available, and technical details.
     *
     * @param context Application context
     * @param videoUri URI of the video file
     * @param metadata StringBuilder to append the extracted information to
     */
    private static void extractVideoMetadata(Context context, Uri videoUri, StringBuilder metadata) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Extracting video metadata");

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            // Set the data source to the video URI
            retriever.setDataSource(context, videoUri);
            crashlytics.log("MediaMetadataRetriever data source set successfully");

            // Extract and append video properties
            metadata.append("\n").append(context.getString(R.string.metadata_video_properties_header)).append("\n");

            // Format duration in hours:minutes:seconds
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                long durationMs = Long.parseLong(duration);
                long seconds = (durationMs / 1000) % 60;
                long minutes = (durationMs / (1000 * 60)) % 60;
                long hours = (durationMs / (1000 * 60 * 60));

                String formattedDuration;
                if (hours > 0) {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
                    metadata.append(context.getString(R.string.metadata_duration_with_hours, formattedDuration)).append("\n");
                } else {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
                    metadata.append(context.getString(R.string.metadata_duration_without_hours, formattedDuration)).append("\n");
                }
                crashlytics.setCustomKey("video_duration_ms", durationMs);
            }

            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (width != null && height != null) {
                metadata.append(context.getString(R.string.metadata_resolution, width, height)).append("\n");
                crashlytics.setCustomKey("video_width", width);
                crashlytics.setCustomKey("video_height", height);
            }

            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                metadata.append(context.getString(R.string.metadata_rotation, rotation)).append("\n");
                crashlytics.setCustomKey("video_rotation", rotation);
            }

            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null) {
                long bitrateValue = Long.parseLong(bitrate);
                metadata.append(context.getString(R.string.metadata_bitrate, bitrateValue / 1000)).append("\n");
                crashlytics.setCustomKey("video_bitrate_kbps", bitrateValue / 1000);
            }

            String date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (date != null) {
                metadata.append(context.getString(R.string.metadata_date, date)).append("\n");
            }

            // Check if we have permission to access media location
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
            crashlytics.setCustomKey("has_location_permission", hasLocationPermission);

            // Extract and append location information if available
            metadata.append("\n").append(context.getString(R.string.metadata_location_information_header)).append("\n");

            if (hasLocationPermission) {
                String latitude = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                if (latitude != null && !latitude.isEmpty()) {
                    // Parse location string which is in format "lat+lon"
                    String[] parts = latitude.split("\\+");
                    if (parts.length == 2) {
                        float lat = Float.parseFloat(parts[0]);
                        float lon = Float.parseFloat(parts[1]);

                        metadata.append(context.getString(R.string.metadata_latitude, lat)).append("\n");
                        metadata.append(context.getString(R.string.metadata_longitude, lon)).append("\n");
                        crashlytics.setCustomKey("has_location_data", true);
                        crashlytics.log("Location data found in video");

                        // Try to get human-readable address from coordinates using Geocoder
                        try {
                            if (Geocoder.isPresent()) {
                                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);

                                if (addresses != null && !addresses.isEmpty()) {
                                    Address address = addresses.get(0);
                                    StringBuilder addressText = new StringBuilder();

                                    for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                                        addressText.append(address.getAddressLine(i));
                                        if (i < address.getMaxAddressLineIndex()) {
                                            addressText.append(", ");
                                        }
                                    }

                                    metadata.append(context.getString(R.string.metadata_address, addressText)).append("\n");
                                    crashlytics.log("Geocoding successful");
                                }
                            }
                        } catch (Exception e) {
                            metadata.append(context.getString(R.string.metadata_geocoding_failed, e.getMessage())).append("\n");
                            crashlytics.log("Geocoding failed: " + e.getMessage());
                            crashlytics.recordException(e);
                        }
                    }
                } else {
                    metadata.append(context.getString(R.string.metadata_no_location_data)).append("\n");
                    crashlytics.setCustomKey("has_location_data", false);
                }
            } else {
                metadata.append(context.getString(R.string.metadata_location_permission_needed)).append("\n");
            }

            // Extract and append technical details
            metadata.append("\n").append(context.getString(R.string.metadata_technical_details_header)).append("\n");

            String frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (frameRate != null) {
                metadata.append(context.getString(R.string.metadata_frame_rate, frameRate)).append("\n");
                crashlytics.setCustomKey("video_frame_rate", frameRate);
            }

            if (bitrate != null) {
                long bitrateValue = Long.parseLong(bitrate);
                metadata.append(context.getString(R.string.metadata_bitrate, bitrateValue / 1000)).append("\n");
            }

            String audioSampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            if (audioSampleRate != null) {
                metadata.append(context.getString(R.string.metadata_sample_rate, audioSampleRate)).append("\n");
                crashlytics.setCustomKey("audio_sample_rate", audioSampleRate);
            }

            crashlytics.log("Video metadata extraction completed");

        } catch (Exception e) {
            // Append error message if extraction fails
            metadata.append(context.getString(R.string.metadata_error_video_metadata, e.getMessage()));
            Log.e(TAG, "Error extracting video metadata", e);
            crashlytics.recordException(e);
        } finally {
            try {
                // Always release the MediaMetadataRetriever to free resources
                retriever.release();
                crashlytics.log("MediaMetadataRetriever released");
            } catch (Exception ignored) {
                // Ignore exceptions during release
                crashlytics.log("Exception while releasing MediaMetadataRetriever (ignored)");
            }
        }
    }

    /**
     * Extracts video metadata and organizes it into logical sections.
     * Returns a map with different categories of metadata.
     *
     * @param context Application context
     * @param videoUri URI of the video file
     * @return Map containing different sections of metadata
     */
    private static Map<String, String> extractVideoMetadataSectioned(Context context, Uri videoUri) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Extracting sectioned video metadata");

        Map<String, String> sections = new HashMap<>();
        StringBuilder mediaDetails = new StringBuilder();
        StringBuilder locationInfo = new StringBuilder();
        StringBuilder technicalDetails = new StringBuilder();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            // Set the data source to the video URI
            retriever.setDataSource(context, videoUri);

            // Extract duration and format it appropriately
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                long durationMs = Long.parseLong(duration);
                long seconds = (durationMs / 1000) % 60;
                long minutes = (durationMs / (1000 * 60)) % 60;
                long hours = (durationMs / (1000 * 60 * 60));

                String formattedDuration;
                if (hours > 0) {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
                    mediaDetails.append(context.getString(R.string.metadata_duration_with_hours, formattedDuration)).append("\n");
                } else {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
                    mediaDetails.append(context.getString(R.string.metadata_duration_without_hours, formattedDuration)).append("\n");
                }
                crashlytics.setCustomKey("video_duration_ms", durationMs);
            }

            // Extract creation date
            String date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (date != null) {
                mediaDetails.append(context.getString(R.string.metadata_date, date)).append("\n");
            }

            // Extract resolution information
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (width != null && height != null) {
                mediaDetails.append(context.getString(R.string.metadata_resolution, width, height)).append("\n");
                crashlytics.setCustomKey("video_width", width);
                crashlytics.setCustomKey("video_height", height);
            }

            // Extract rotation information
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                mediaDetails.append(context.getString(R.string.metadata_rotation, rotation)).append("\n");
                crashlytics.setCustomKey("video_rotation", rotation);
            }

            // Check if we have permission to access media location
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            crashlytics.log("Has location permission: " + hasLocationPermission);
            crashlytics.setCustomKey("has_location_permission", hasLocationPermission);

            // Extract location information if permission is granted
            if (hasLocationPermission) {
                String location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                if (location != null && !location.isEmpty()) {
                    // Parse location string which is in format "lat+lon"
                    String[] parts = location.split("\\+");
                    if (parts.length == 2) {
                        float lat = Float.parseFloat(parts[0]);
                        float lon = Float.parseFloat(parts[1]);

                        locationInfo.append(context.getString(R.string.metadata_latitude, lat)).append("\n");
                        locationInfo.append(context.getString(R.string.metadata_longitude, lon)).append("\n");
                        crashlytics.setCustomKey("has_location_data", true);
                        crashlytics.log("Location data found in video");

                        // Try to get human-readable address from coordinates using Geocoder
                        try {
                            if (Geocoder.isPresent()) {
                                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);

                                if (addresses != null && !addresses.isEmpty()) {
                                    Address address = addresses.get(0);
                                    StringBuilder addressText = new StringBuilder();

                                    for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                                        addressText.append(address.getAddressLine(i));
                                        if (i < address.getMaxAddressLineIndex()) {
                                            addressText.append(", ");
                                        }
                                    }

                                    locationInfo.append(context.getString(R.string.metadata_address, addressText)).append("\n");
                                    crashlytics.log("Geocoding successful");
                                }
                            }
                        } catch (Exception e) {
                            locationInfo.append(context.getString(R.string.metadata_geocoding_failed, e.getMessage())).append("\n");
                            crashlytics.log("Geocoding failed: " + e.getMessage());
                            crashlytics.recordException(e);
                        }
                    }
                } else {
                    locationInfo.append(context.getString(R.string.metadata_no_video_location_data)).append("\n");
                    crashlytics.setCustomKey("has_location_data", false);
                }
            } else {
                locationInfo.append(context.getString(R.string.metadata_location_permission_needed_full)).append("\n");
            }

            // Extract technical details
            String frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (frameRate != null) {
                technicalDetails.append(context.getString(R.string.metadata_frame_rate, frameRate)).append("\n");
                crashlytics.setCustomKey("video_frame_rate", frameRate);
            }

            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null) {
                long bitrateValue = Long.parseLong(bitrate);
                technicalDetails.append(context.getString(R.string.metadata_bitrate, bitrateValue / 1000)).append("\n");
                crashlytics.setCustomKey("video_bitrate_kbps", bitrateValue / 1000);
            }

            String audioSampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            if (audioSampleRate != null) {
                technicalDetails.append(context.getString(R.string.metadata_sample_rate, audioSampleRate)).append("\n");
                crashlytics.setCustomKey("audio_sample_rate", audioSampleRate);
            }

            crashlytics.log("Sectioned video metadata extraction completed");

        } catch (Exception e) {
            Log.e(TAG, "Error extracting sectioned video metadata", e);
            crashlytics.recordException(e);
        } finally {
            try {
                // Always release the MediaMetadataRetriever to free resources
                retriever.release();
                crashlytics.log("MediaMetadataRetriever released");
            } catch (Exception ignored) {
                // Ignore exceptions during release
                crashlytics.log("Exception while releasing MediaMetadataRetriever (ignored)");
            }
        }

        // Add non-empty sections to the result map
        if (mediaDetails.length() > 0) {
            sections.put(SECTION_CAMERA_DETAILS, mediaDetails.toString());
        }

        if (locationInfo.length() > 0) {
            sections.put(SECTION_LOCATION, locationInfo.toString());
        }

        if (technicalDetails.length() > 0) {
            sections.put(SECTION_TECHNICAL, technicalDetails.toString());
        }

        return sections;
    }

    /**
     * Attempts to retrieve the file path from a content URI.
     * This is a helper method that may be useful for certain operations that require file paths.
     *
     * @param context Application context
     * @param uri Content URI to resolve
     * @return The file path if available, null otherwise
     */
    private static String getPathFromUri(Context context, Uri uri) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Attempting to get file path from URI");

        try {
            // Query the MediaStore to get the file path
            String[] projection = {MediaStore.Images.Media.DATA};
            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);

            if (cursor != null) {
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                cursor.moveToFirst();
                String path = cursor.getString(column_index);
                cursor.close();
                crashlytics.log("Successfully retrieved file path from URI");
                return path;
            }
        } catch (Exception e) {
            // Log the exception but don't throw it
            Log.e(TAG, "Error getting path from URI", e);
            crashlytics.recordException(e);
        }

        crashlytics.log("Failed to get file path from URI");
        return null;
    }

    /**
     * Converts an orientation constant from ExifInterface to a human-readable string.
     *
     * @param context Application context for string resources
     * @param orientation The orientation constant from ExifInterface
     * @return A human-readable string describing the orientation
     */
    private static String getOrientationString(Context context, int orientation) {
        FirebaseCrashlytics.getInstance().setCustomKey("image_orientation", orientation);

        return switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL -> context.getString(R.string.metadata_orientation_normal);
            case ExifInterface.ORIENTATION_ROTATE_90 -> context.getString(R.string.metadata_orientation_rotate_90);
            case ExifInterface.ORIENTATION_ROTATE_180 -> context.getString(R.string.metadata_orientation_rotate_180);
            case ExifInterface.ORIENTATION_ROTATE_270 -> context.getString(R.string.metadata_orientation_rotate_270);
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> context.getString(R.string.metadata_orientation_flip_horizontal);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> context.getString(R.string.metadata_orientation_flip_vertical);
            case ExifInterface.ORIENTATION_TRANSPOSE -> context.getString(R.string.metadata_orientation_transpose);
            case ExifInterface.ORIENTATION_TRANSVERSE -> context.getString(R.string.metadata_orientation_transverse);
            default -> context.getString(R.string.metadata_orientation_unknown, orientation);
        };
    }
}
