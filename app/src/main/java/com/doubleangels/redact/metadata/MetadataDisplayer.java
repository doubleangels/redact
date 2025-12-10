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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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

                // Extract all metadata into a single combined list
                Map<String, String> allMetadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                
                // Extract and add basic file information
                extractBasicFileInfoToMap(context, mediaUri, allMetadata);

                // Extract either video or image specific metadata
                if (isVideo) {
                    crashlytics.log("Extracting video metadata");
                    extractVideoMetadataToMap(context, mediaUri, allMetadata);
                } else {
                    crashlytics.log("Extracting image metadata");
                    extractImageMetadataToMap(context, mediaUri, allMetadata);
                }
                
                // Combine all metadata into a single string
                StringBuilder combinedMetadata = new StringBuilder();
                for (Map.Entry<String, String> entry : allMetadata.entrySet()) {
                    combinedMetadata.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                
                // Trim trailing whitespace
                String metadataContent = combinedMetadata.toString();
                while (metadataContent.length() > 0 && Character.isWhitespace(metadataContent.charAt(metadataContent.length() - 1))) {
                    metadataContent = metadataContent.substring(0, metadataContent.length() - 1);
                }
                
                sections.put(SECTION_BASIC_INFO, metadataContent);

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
     * Extracts basic file information such as name, size, and MIME type and adds to map.
     *
     * @param context Application context
     * @param mediaUri URI of the media file
     * @param metadataMap Map to add the extracted information to
     */
    private static void extractBasicFileInfoToMap(Context context, Uri mediaUri, Map<String, String> metadataMap) {
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

            // Add basic file information to map using raw metadata keys
            metadataMap.put("DISPLAY_NAME", fileName != null ? fileName : context.getString(R.string.metadata_unknown));
            metadataMap.put("MIME_TYPE", mimeType != null ? mimeType : context.getString(R.string.metadata_unknown));
            metadataMap.put("SIZE", formattedSize);

        } catch (Exception e) {
            Log.e(TAG, "Error extracting basic file info", e);
            FirebaseCrashlytics.getInstance().recordException(e);
        }
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

            // Append basic file information using raw metadata keys - sorted alphabetically (case-insensitive)
            Map<String, String> basicInfoMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            basicInfoMap.put("DISPLAY_NAME", fileName != null ? fileName : context.getString(R.string.metadata_unknown));
            basicInfoMap.put("MIME_TYPE", mimeType != null ? mimeType : context.getString(R.string.metadata_unknown));
            basicInfoMap.put("SIZE", formattedSize);
            for (Map.Entry<String, String> entry : basicInfoMap.entrySet()) {
                metadata.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }

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
                try {
                    float exposureValue = Float.parseFloat(exposureTime);
                    // Format exposure time as fraction (1/x) for values less than 1 second
                    if (exposureValue < 1) {
                        metadata.append(context.getString(R.string.metadata_exposure_time_fraction, Math.round(1 / exposureValue))).append("\n");
                    } else {
                        metadata.append(context.getString(R.string.metadata_exposure_time_seconds, exposureValue)).append("\n");
                    }
                } catch (NumberFormatException e) {
                    crashlytics.log("Invalid exposure time format: " + exposureTime);
                    crashlytics.recordException(e);
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
                try {
                    metadata.append(context.getString(R.string.metadata_orientation, getOrientationString(context, Integer.parseInt(orientation)))).append("\n");
                } catch (NumberFormatException e) {
                    crashlytics.log("Invalid orientation format: " + orientation);
                    crashlytics.recordException(e);
                }
            }

            String flash = exifInterface.getAttribute(ExifInterface.TAG_FLASH);
            if (flash != null) {
                try {
                    boolean flashFired = (Integer.parseInt(flash) & 0x1) != 0;
                    metadata.append(context.getString(R.string.metadata_flash,
                            flashFired ? context.getString(R.string.metadata_flash_fired) : context.getString(R.string.metadata_flash_not_fired))).append("\n");
                } catch (NumberFormatException e) {
                    crashlytics.log("Invalid flash format: " + flash);
                    crashlytics.recordException(e);
                }
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
     * Extracts image metadata and adds it to a single map (alphabetically sorted).
     *
     * @param context Application context
     * @param imageUri URI of the image file
     * @param metadataMap Map to add all metadata to (will be sorted alphabetically)
     */
    private static void extractImageMetadataToMap(Context context, Uri imageUri, Map<String, String> metadataMap) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Extracting image metadata to single map");

        try (InputStream inputStream = context.getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) {
                crashlytics.log("Failed to open input stream for image metadata");
                return;
            }

            // Create ExifInterface from the input stream
            ExifInterface exifInterface;
            exifInterface = new ExifInterface(inputStream);

            // Extract ALL available EXIF tags using reflection to get all TAG constants
            java.util.Set<String> processedTags = new java.util.HashSet<>();
            
            // Store GPS latitude and longitude for conversion to decimal
            String gpsLatitude = null;
            String gpsLatitudeRef = null;
            String gpsLongitude = null;
            String gpsLongitudeRef = null;
            
            // Get all TAG constants from ExifInterface using reflection
            java.lang.reflect.Field[] fields = ExifInterface.class.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                if (field.getType() == String.class && field.getName().startsWith("TAG_")) {
                    try {
                        String tagName = (String) field.get(null);
                        if (tagName != null) {
                            String value = exifInterface.getAttribute(tagName);
                            if (value != null && !value.isEmpty()) {
                                processedTags.add(tagName);
                                
                                // Trim trailing whitespace from the value itself (e.g., XMP data may have trailing newlines)
                                String trimmedValue = value;
                                while (trimmedValue.length() > 0 && Character.isWhitespace(trimmedValue.charAt(trimmedValue.length() - 1))) {
                                    trimmedValue = trimmedValue.substring(0, trimmedValue.length() - 1);
                                }
                                
                                // Store GPS coordinates for later conversion
                                if (tagName.equals(ExifInterface.TAG_GPS_LATITUDE)) {
                                    gpsLatitude = trimmedValue;
                                    continue; // Don't add raw value yet
                                } else if (tagName.equals(ExifInterface.TAG_GPS_LATITUDE_REF)) {
                                    gpsLatitudeRef = trimmedValue;
                                    continue; // Don't add raw value yet
                                } else if (tagName.equals(ExifInterface.TAG_GPS_LONGITUDE)) {
                                    gpsLongitude = trimmedValue;
                                    continue; // Don't add raw value yet
                                } else if (tagName.equals(ExifInterface.TAG_GPS_LONGITUDE_REF)) {
                                    gpsLongitudeRef = trimmedValue;
                                    continue; // Don't add raw value yet
                                }
                                
                                // Add to single map (convert tag names to uppercase with underscores)
                                String upperTagName = convertToSnakeCase(tagName).toUpperCase();
                                metadataMap.put(upperTagName, trimmedValue);
                            }
                        }
                    } catch (IllegalAccessException | IllegalArgumentException e) {
                        // Skip fields we can't access
                        continue;
                    }
                }
            }
            
            // Convert GPS latitude and longitude from rational format to decimal degrees
            if (gpsLatitude != null && !gpsLatitude.isEmpty()) {
                try {
                    double decimalLat = convertGpsRationalToDecimal(gpsLatitude);
                    if (gpsLatitudeRef != null && gpsLatitudeRef.equalsIgnoreCase("S")) {
                        decimalLat = -decimalLat; // South is negative
                    }
                    metadataMap.put("GPS_LATITUDE", String.format(Locale.getDefault(), "%.15f", decimalLat));
                } catch (Exception e) {
                    // If conversion fails, use raw value
                    metadataMap.put("GPSLATITUDE", gpsLatitude);
                    crashlytics.log("Failed to convert GPS latitude to decimal: " + e.getMessage());
                }
            }
            
            if (gpsLongitude != null && !gpsLongitude.isEmpty()) {
                try {
                    double decimalLon = convertGpsRationalToDecimal(gpsLongitude);
                    if (gpsLongitudeRef != null && gpsLongitudeRef.equalsIgnoreCase("W")) {
                        decimalLon = -decimalLon; // West is negative
                    }
                    metadataMap.put("GPS_LONGITUDE", String.format(Locale.getDefault(), "%.15f", decimalLon));
                } catch (Exception e) {
                    // If conversion fails, use raw value
                    metadataMap.put("GPSLONGITUDE", gpsLongitude);
                    crashlytics.log("Failed to convert GPS longitude to decimal: " + e.getMessage());
                }
            }
            
            // Also handle integer attributes that don't have string values
            int width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
            int height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
            if (width > 0 && height > 0) {
                String widthTagName = convertToSnakeCase(ExifInterface.TAG_IMAGE_WIDTH).toUpperCase();
                String heightTagName = convertToSnakeCase(ExifInterface.TAG_IMAGE_LENGTH).toUpperCase();
                // Only add if not already in the map (from reflection loop)
                if (!metadataMap.containsKey(widthTagName)) {
                    metadataMap.put(widthTagName, String.valueOf(width));
                }
                if (!metadataMap.containsKey(heightTagName)) {
                    metadataMap.put(heightTagName, String.valueOf(height));
                }
                crashlytics.setCustomKey("image_width", width);
                crashlytics.setCustomKey("image_height", height);
            }

            // Check if we have permission to access media location
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            crashlytics.log("Has location permission: " + hasLocationPermission);
            crashlytics.setCustomKey("has_location_permission", hasLocationPermission);

            // Location information is already extracted via TAG_GPS_* tags above
            if (hasLocationPermission) {
                double[] latLong = exifInterface.getLatLong();
                if (latLong != null) {
                    crashlytics.setCustomKey("has_location_data", true);
                } else {
                    crashlytics.setCustomKey("has_location_data", false);
                }
            }

            crashlytics.log("Image metadata extraction completed");

        } catch (IOException e) {
            Log.e(TAG, "Error extracting image metadata", e);
            crashlytics.recordException(e);
        }
    }

    /**
     * Converts GPS coordinate from EXIF rational format (degrees/minutes/seconds) to decimal degrees.
     * Format: "degrees/1,minutes/1,seconds/100" -> decimal degrees
     *
     * @param rationalString The GPS coordinate in rational format (e.g., "39/1,39/1,3380/100")
     * @return Decimal degrees
     */
    private static double convertGpsRationalToDecimal(String rationalString) {
        // Split by comma to get degrees, minutes, seconds
        String[] parts = rationalString.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid GPS rational format: " + rationalString);
        }
        
        // Parse degrees
        double degrees = parseRational(parts[0].trim());
        
        // Parse minutes
        double minutes = parseRational(parts[1].trim());
        
        // Parse seconds
        double seconds = parseRational(parts[2].trim());
        
        // Convert to decimal degrees: degrees + (minutes/60) + (seconds/3600)
        return degrees + (minutes / 60.0) + (seconds / 3600.0);
    }

    /**
     * Parses a rational number string (e.g., "39/1" or "3380/100") to a double.
     *
     * @param rational The rational number string
     * @return The decimal value
     */
    private static double parseRational(String rational) {
        String[] fraction = rational.split("/");
        if (fraction.length != 2) {
            throw new IllegalArgumentException("Invalid rational format: " + rational);
        }
        double numerator = Double.parseDouble(fraction[0].trim());
        double denominator = Double.parseDouble(fraction[1].trim());
        if (denominator == 0) {
            throw new IllegalArgumentException("Division by zero in rational: " + rational);
        }
        return numerator / denominator;
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
                try {
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
                } catch (NumberFormatException e) {
                    crashlytics.log("Invalid duration format: " + duration);
                    crashlytics.recordException(e);
                }
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
                try {
                    long bitrateValue = Long.parseLong(bitrate);
                    metadata.append(context.getString(R.string.metadata_bitrate, bitrateValue / 1000)).append("\n");
                    crashlytics.setCustomKey("video_bitrate_kbps", bitrateValue / 1000);
                } catch (NumberFormatException e) {
                    crashlytics.log("Invalid bitrate format: " + bitrate);
                    crashlytics.recordException(e);
                }
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
                        try {
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
                        } catch (NumberFormatException e) {
                            crashlytics.log("Invalid location format: " + latitude);
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
     * Extracts video metadata and adds it to a single map (alphabetically sorted).
     *
     * @param context Application context
     * @param videoUri URI of the video file
     * @param metadataMap Map to add all metadata to (will be sorted alphabetically)
     */
    private static void extractVideoMetadataToMap(Context context, Uri videoUri, Map<String, String> metadataMap) {
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.log("Extracting video metadata to single map");

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            // Set the data source to the video URI
            retriever.setDataSource(context, videoUri);

            // Extract ALL available video metadata keys using reflection
            java.util.Set<String> processedVideoKeys = new java.util.HashSet<>();
            
            // Store LOCATION field for special processing (to split into GPSLATITUDE and GPSLONGITUDE)
            String locationValue = null;
            
            // Get all METADATA_KEY constants from MediaMetadataRetriever using reflection
            java.lang.reflect.Field[] retrieverFields = MediaMetadataRetriever.class.getDeclaredFields();
            for (java.lang.reflect.Field field : retrieverFields) {
                if (field.getType() == int.class && field.getName().startsWith("METADATA_KEY_")) {
                    try {
                        int keyCode = field.getInt(null);
                        String keyName = field.getName();
                        String value = retriever.extractMetadata(keyCode);
                        if (value != null && !value.isEmpty()) {
                            processedVideoKeys.add(keyName);
                            
                            // Trim trailing whitespace from the value itself
                            String trimmedValue = value;
                            while (trimmedValue.length() > 0 && Character.isWhitespace(trimmedValue.charAt(trimmedValue.length() - 1))) {
                                trimmedValue = trimmedValue.substring(0, trimmedValue.length() - 1);
                            }
                            
                            // Special handling for LOCATION field - don't add it directly, parse it instead
                            if (keyName.equals("METADATA_KEY_LOCATION")) {
                                locationValue = trimmedValue;
                                continue; // Skip adding this field directly
                            }
                            
                            // Remove METADATA_KEY_ prefix and convert to uppercase with underscores, then add to single map
                            String displayKeyName = keyName.toUpperCase();
                            if (displayKeyName.startsWith("METADATA_KEY_")) {
                                displayKeyName = displayKeyName.substring("METADATA_KEY_".length());
                            }
                            // Convert to snake_case if needed (e.g., "VIDEOWIDTH" -> "VIDEO_WIDTH")
                            displayKeyName = convertToSnakeCase(displayKeyName).toUpperCase();
                            metadataMap.put(displayKeyName, trimmedValue);
                            
                            // Log important keys for analytics
                            if (keyName.equals("METADATA_KEY_DURATION")) {
                                try {
                                    crashlytics.setCustomKey("video_duration_ms", Long.parseLong(value));
                                } catch (NumberFormatException e) {
                                    crashlytics.log("Invalid duration format for analytics: " + value);
                                    crashlytics.recordException(e);
                                }
                            } else if (keyName.equals("METADATA_KEY_VIDEO_WIDTH")) {
                                crashlytics.setCustomKey("video_width", value);
                            } else if (keyName.equals("METADATA_KEY_VIDEO_HEIGHT")) {
                                crashlytics.setCustomKey("video_height", value);
                            } else if (keyName.equals("METADATA_KEY_VIDEO_ROTATION")) {
                                crashlytics.setCustomKey("video_rotation", value);
                            }
                        }
                    } catch (IllegalAccessException | IllegalArgumentException e) {
                        // Skip fields we can't access
                        continue;
                    }
                }
            }
            
            // Parse LOCATION field and split into GPSLATITUDE and GPSLONGITUDE
            if (locationValue != null && !locationValue.isEmpty()) {
                try {
                    // Remove trailing slash if present
                    String cleanLocation = locationValue;
                    if (cleanLocation.endsWith("/")) {
                        cleanLocation = cleanLocation.substring(0, cleanLocation.length() - 1);
                    }
                    
                    // Parse format like "+39.6594-104.9620" or "+39.6594+104.9620"
                    // The format is: [+/-]lat[+/-]lon
                    double latitude = 0.0;
                    double longitude = 0.0;
                    
                    // Find the split point - look for the second sign (+ or -) that's not at the start
                    int splitIndex = -1;
                    for (int i = 1; i < cleanLocation.length(); i++) {
                        char c = cleanLocation.charAt(i);
                        if (c == '+' || c == '-') {
                            splitIndex = i;
                            break;
                        }
                    }
                    
                    if (splitIndex > 0) {
                        // Extract latitude (first part)
                        String latStr = cleanLocation.substring(0, splitIndex);
                        latitude = Double.parseDouble(latStr);
                        
                        // Extract longitude (second part)
                        String lonStr = cleanLocation.substring(splitIndex);
                        longitude = Double.parseDouble(lonStr);
                        
                        // Add as separate fields
                        metadataMap.put("GPS_LATITUDE", String.format(Locale.getDefault(), "%.15f", latitude));
                        metadataMap.put("GPS_LONGITUDE", String.format(Locale.getDefault(), "%.15f", longitude));
                        
                        crashlytics.log("Parsed video location: lat=" + latitude + ", lon=" + longitude);
                    } else {
                        // Fallback: try splitting by + or - in the middle
                        // Format might be different, try alternative parsing
                        crashlytics.log("Could not parse location format: " + cleanLocation);
                        // Add raw value as fallback
                        metadataMap.put("LOCATION", locationValue);
                    }
                } catch (Exception e) {
                    crashlytics.log("Failed to parse video location: " + e.getMessage());
                    crashlytics.recordException(e);
                    // Add raw value as fallback
                    metadataMap.put("LOCATION", locationValue);
                }
            }

            // Check if we have permission to access media location
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            crashlytics.log("Has location permission: " + hasLocationPermission);
            crashlytics.setCustomKey("has_location_permission", hasLocationPermission);

            // Location information is already extracted and parsed above (split into GPSLATITUDE and GPSLONGITUDE)
            if (hasLocationPermission) {
                if (locationValue != null && !locationValue.isEmpty()) {
                    crashlytics.setCustomKey("has_location_data", true);
                    crashlytics.log("Location data found in video");
                } else {
                    crashlytics.setCustomKey("has_location_data", false);
                }
            }

            // Technical details are already extracted via reflection above
            // Only add analytics for keys that weren't already processed
            if (!processedVideoKeys.contains("METADATA_KEY_CAPTURE_FRAMERATE")) {
                String frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if (frameRate != null && !metadataMap.containsKey("CAPTURE_FRAMERATE")) {
                    metadataMap.put("CAPTURE_FRAMERATE", frameRate);
                    crashlytics.setCustomKey("video_frame_rate", frameRate);
                }
            }
            
            if (!processedVideoKeys.contains("METADATA_KEY_BITRATE")) {
                String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                if (bitrate != null && !metadataMap.containsKey("BITRATE")) {
                    metadataMap.put("BITRATE", bitrate);
                    try {
                        crashlytics.setCustomKey("video_bitrate_kbps", Long.parseLong(bitrate) / 1000);
                    } catch (NumberFormatException e) {
                        crashlytics.log("Invalid bitrate format for analytics: " + bitrate);
                        crashlytics.recordException(e);
                    }
                }
            }
            
            if (!processedVideoKeys.contains("METADATA_KEY_SAMPLERATE")) {
                String audioSampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                if (audioSampleRate != null && !metadataMap.containsKey("SAMPLE_RATE")) {
                    metadataMap.put("SAMPLE_RATE", audioSampleRate);
                    crashlytics.setCustomKey("audio_sample_rate", audioSampleRate);
                }
            }

            crashlytics.log("Video metadata extraction completed");

        } catch (Exception e) {
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

    /**
     * Converts a camelCase or PascalCase string to snake_case.
     * Example: "ImageWidth" -> "Image_Width", "GPSLatitude" -> "GPS_Latitude"
     * Handles consecutive uppercase letters (like "GPS") correctly.
     *
     * @param input The input string in camelCase or PascalCase
     * @return The string in snake_case format
     */
    private static String convertToSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            // Insert underscore before uppercase letters (except the first character)
            if (Character.isUpperCase(c) && i > 0) {
                char prevChar = input.charAt(i - 1);
                // Insert underscore if:
                // 1. Previous char is lowercase, OR
                // 2. Previous char is uppercase AND next char is lowercase (end of acronym)
                if (Character.isLowerCase(prevChar) || 
                    (Character.isUpperCase(prevChar) && i < input.length() - 1 && Character.isLowerCase(input.charAt(i + 1)))) {
                    // Check if previous character is not already an underscore
                    if (result.length() > 0 && result.charAt(result.length() - 1) != '_') {
                        result.append('_');
                    }
                }
            }
            result.append(c);
        }
        return result.toString();
    }
}
