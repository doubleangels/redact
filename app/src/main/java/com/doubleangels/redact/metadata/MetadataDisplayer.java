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
 * Utility class for extracting and displaying metadata from media files.
 */
public class MetadataDisplayer {
    private static final String TAG = "MetadataDisplayer";

    // Section identifiers
    public static final String SECTION_BASIC_INFO = "basic_info";
    public static final String SECTION_MEDIA_DETAILS = "media_details";
    public static final String SECTION_LOCATION = "location";
    public static final String SECTION_TECHNICAL = "technical";

    /**
     * Interface for receiving metadata extraction results.
     */
    public interface MetadataCallback {
        void onMetadataExtracted(String metadata, boolean isVideo);
        void onExtractionFailed(String error);
    }

    /**
     * Interface for receiving sectioned metadata extraction results.
     */
    public interface SectionedMetadataCallback {
        void onMetadataExtracted(Map<String, String> metadataSections, boolean isVideo);
        void onExtractionFailed(String error);
    }

    /**
     * Extracts metadata from a media file and returns it as a formatted string.
     *
     * @param context Application context
     * @param mediaUri URI of the media file
     * @param callback Callback to receive extraction results
     */
    public static void extractMetadata(Context context, Uri mediaUri, MetadataCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                ContentResolver contentResolver = context.getContentResolver();
                String mimeType = contentResolver.getType(mediaUri);
                boolean isVideo = mimeType != null && mimeType.startsWith("video/");

                StringBuilder metadata = new StringBuilder();

                // Extract basic file info
                extractBasicFileInfo(context, mediaUri, metadata);

                if (isVideo) {
                    extractVideoMetadata(context, mediaUri, metadata);
                } else {
                    extractImageMetadata(context, mediaUri, metadata);
                }

                callback.onMetadataExtracted(metadata.toString(), isVideo);
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
                callback.onExtractionFailed("Error extracting metadata: " + e.getMessage());
            }
        });
    }

    /**
     * Extracts metadata from a media file and organizes it into sections.
     *
     * @param context Application context
     * @param mediaUri URI of the media file
     * @param callback Callback to receive sectioned extraction results
     */
    public static void extractSectionedMetadata(Context context, Uri mediaUri, SectionedMetadataCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                ContentResolver contentResolver = context.getContentResolver();
                String mimeType = contentResolver.getType(mediaUri);
                boolean isVideo = mimeType != null && mimeType.startsWith("video/");

                Map<String, String> sections = new HashMap<>();

                // Extract basic file info
                StringBuilder basicInfo = new StringBuilder();
                extractBasicFileInfo(context, mediaUri, basicInfo);
                sections.put(SECTION_BASIC_INFO, basicInfo.toString());

                if (isVideo) {
                    // Extract video metadata
                    Map<String, String> videoSections = extractVideoMetadataSectioned(context, mediaUri);
                    sections.putAll(videoSections);
                } else {
                    // Extract image metadata
                    Map<String, String> imageSections = extractImageMetadataSectioned(context, mediaUri);
                    sections.putAll(imageSections);
                }

                callback.onMetadataExtracted(sections, isVideo);
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
                callback.onExtractionFailed("Error extracting metadata: " + e.getMessage());
            }
        });
    }

    /**
     * Extracts basic file information.
     *
     * @param context Application context
     * @param mediaUri URI of the media file
     * @param metadata StringBuilder to append metadata to
     */
    private static void extractBasicFileInfo(Context context, Uri mediaUri, StringBuilder metadata) {
        try {
            ContentResolver contentResolver = context.getContentResolver();

            // Get file name and size
            String fileName = null;
            long fileSize = -1;

            try (Cursor cursor = contentResolver.query(mediaUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);

                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex);
                    }

                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex);
                    }
                }
            }

            // Format file size
            String formattedSize;
            if (fileSize >= 0) {
                if (fileSize < 1024) {
                    formattedSize = fileSize + " B";
                } else if (fileSize < 1024 * 1024) {
                    formattedSize = String.format(Locale.getDefault(), "%.2f KB", fileSize / 1024.0);
                } else {
                    formattedSize = String.format(Locale.getDefault(), "%.2f MB", fileSize / (1024.0 * 1024));
                }
            } else {
                formattedSize = "Unknown";
            }

            // Get MIME type
            String mimeType = contentResolver.getType(mediaUri);

            // Append basic info
            metadata.append("File Name: ").append(fileName != null ? fileName : "Unknown").append("\n");
            metadata.append("File Size: ").append(formattedSize).append("\n");
            metadata.append("MIME Type: ").append(mimeType != null ? mimeType : "Unknown").append("\n");
        } catch (Exception e) {
            metadata.append("Error extracting basic info: ").append(e.getMessage()).append("\n");
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Extracts metadata from an image file.
     *
     * @param context Application context
     * @param imageUri URI of the image file
     * @param metadata StringBuilder to append metadata to
     */
    private static void extractImageMetadata(Context context, Uri imageUri, StringBuilder metadata) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) {
                metadata.append("Error: Could not open image file");
                return;
            }

            // Create a temporary file to read EXIF data
            ExifInterface exifInterface;
            exifInterface = new ExifInterface(inputStream);

            // Extract basic image properties
            metadata.append("\nImage Properties:\n");

            // Dimensions
            int width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
            int height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
            if (width > 0 && height > 0) {
                metadata.append("Dimensions: ").append(width).append(" x ").append(height).append("\n");
            }

            // Date taken
            String dateTaken = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
            if (dateTaken != null) {
                metadata.append("Date Taken: ").append(dateTaken).append("\n");
            }

            // Camera information
            metadata.append("\nCamera Information:\n");

            String make = exifInterface.getAttribute(ExifInterface.TAG_MAKE);
            if (make != null) {
                metadata.append("Camera Make: ").append(make).append("\n");
            }

            String model = exifInterface.getAttribute(ExifInterface.TAG_MODEL);
            if (model != null) {
                metadata.append("Camera Model: ").append(model).append("\n");
            }

            // Exposure information
            metadata.append("\nExposure Information:\n");

            String aperture = exifInterface.getAttribute(ExifInterface.TAG_APERTURE_VALUE);
            if (aperture != null) {
                metadata.append("Aperture: f/").append(aperture).append("\n");
            }

            String exposureTime = exifInterface.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exposureTime != null) {
                float exposureValue = Float.parseFloat(exposureTime);
                if (exposureValue < 1) {
                    metadata.append("Exposure Time: 1/").append(Math.round(1 / exposureValue)).append(" sec\n");
                } else {
                    metadata.append("Exposure Time: ").append(exposureValue).append(" sec\n");
                }
            }

            // Location information
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            metadata.append("\nLocation Information:\n");

            if (hasLocationPermission) {
                // Use the non-deprecated method which returns double[] instead of modifying float[]
                double[] latLong = exifInterface.getLatLong();

                if (latLong != null) {
                    metadata.append("Latitude: ").append(latLong[0]).append("\n");
                    metadata.append("Longitude: ").append(latLong[1]).append("\n");

                    // Try to get address from coordinates
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

                                metadata.append("Address: ").append(addressText).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        metadata.append("Geocoding failed: ").append(e.getMessage()).append("\n");
                    }
                } else {
                    metadata.append("No location data available\n");
                }
            } else {
                metadata.append("Location permission not granted. Grant permission to see location data.\n");
            }

            // Technical details
            metadata.append("\nTechnical Details:\n");

            String orientation = exifInterface.getAttribute(ExifInterface.TAG_ORIENTATION);
            if (orientation != null) {
                metadata.append("Orientation: ").append(getOrientationString(Integer.parseInt(orientation))).append("\n");
            }

            String flash = exifInterface.getAttribute(ExifInterface.TAG_FLASH);
            if (flash != null) {
                boolean flashFired = (Integer.parseInt(flash) & 0x1) != 0;
                metadata.append("Flash: ").append(flashFired ? "Fired" : "Not Fired").append("\n");
            }

            String whiteBalance = exifInterface.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
            if (whiteBalance != null) {
                metadata.append("White Balance: ").append(whiteBalance.equals("0") ? "Auto" : "Manual").append("\n");
            }

        } catch (IOException e) {
            metadata.append("Error extracting image metadata: ").append(e.getMessage());
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Extracts sectioned metadata from an image file.
     *
     * @param context Application context
     * @param imageUri URI of the image file
     * @return Map of section identifiers to section content
     */
    private static Map<String, String> extractImageMetadataSectioned(Context context, Uri imageUri) {
        Map<String, String> sections = new HashMap<>();
        StringBuilder mediaDetails = new StringBuilder();
        StringBuilder locationInfo = new StringBuilder();
        StringBuilder technicalDetails = new StringBuilder();

        try (InputStream inputStream = context.getContentResolver().openInputStream(imageUri)) {
            if (inputStream == null) {
                return sections;
            }

            // Create ExifInterface object
            ExifInterface exifInterface;
            exifInterface = new ExifInterface(inputStream);

            // Camera and image details
            String make = exifInterface.getAttribute(ExifInterface.TAG_MAKE);
            if (make != null) {
                mediaDetails.append("Camera Make: ").append(make).append("\n");
            }

            String model = exifInterface.getAttribute(ExifInterface.TAG_MODEL);
            if (model != null) {
                mediaDetails.append("Camera Model: ").append(model).append("\n");
            }

            String dateTaken = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
            if (dateTaken != null) {
                mediaDetails.append("Date Taken: ").append(dateTaken).append("\n");
            }

            String aperture = exifInterface.getAttribute(ExifInterface.TAG_APERTURE_VALUE);
            if (aperture != null) {
                mediaDetails.append("Aperture: f/").append(aperture).append("\n");
            }

            String exposureTime = exifInterface.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exposureTime != null) {
                float exposureValue = Float.parseFloat(exposureTime);
                if (exposureValue < 1) {
                    mediaDetails.append("Exposure Time: 1/").append(Math.round(1 / exposureValue)).append(" sec\n");
                } else {
                    mediaDetails.append("Exposure Time: ").append(exposureValue).append(" sec\n");
                }
            }

            String flash = exifInterface.getAttribute(ExifInterface.TAG_FLASH);
            if (flash != null) {
                boolean flashFired = (Integer.parseInt(flash) & 0x1) != 0;
                mediaDetails.append("Flash: ").append(flashFired ? "Fired" : "Not Fired").append("\n");
            }

            String whiteBalance = exifInterface.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
            if (whiteBalance != null) {
                mediaDetails.append("White Balance: ").append(whiteBalance.equals("0") ? "Auto" : "Manual").append("\n");
            }

            // Location information
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            Log.d(TAG, "Has location permission: " + hasLocationPermission);
            FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);

            if (hasLocationPermission) {
                // Use the non-deprecated method which returns double[] instead of modifying float[]
                double[] latLong = exifInterface.getLatLong();

                if (latLong != null) {
                    locationInfo.append("Latitude: ").append(latLong[0]).append("\n");
                    locationInfo.append("Longitude: ").append(latLong[1]).append("\n");

                    // Try to get address from coordinates
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

                                locationInfo.append("Address: ").append(addressText).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        locationInfo.append("Geocoding failed: ").append(e.getMessage()).append("\n");
                        FirebaseCrashlytics.getInstance().recordException(e);
                    }
                } else {
                    locationInfo.append("No location data available in this image.\n");
                }
            } else {
                locationInfo.append("Location permission not granted. Grant permission to see full location data.\n");
            }

            // Technical details
            int width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
            int height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
            if (width > 0 && height > 0) {
                technicalDetails.append("Dimensions: ").append(width).append(" x ").append(height).append("\n");
            }

            String orientation = exifInterface.getAttribute(ExifInterface.TAG_ORIENTATION);
            if (orientation != null) {
                technicalDetails.append("Orientation: ").append(getOrientationString(Integer.parseInt(orientation))).append("\n");
            }

            String software = exifInterface.getAttribute(ExifInterface.TAG_SOFTWARE);
            if (software != null) {
                technicalDetails.append("Software: ").append(software).append("\n");
            }

            String focalLength = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if (focalLength != null) {
                technicalDetails.append("Focal Length: ").append(focalLength).append(" mm\n");
            }

            String digitalZoom = exifInterface.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO);
            if (digitalZoom != null && !digitalZoom.equals("0")) {
                technicalDetails.append("Digital Zoom: ").append(digitalZoom).append("x\n");
            }

        } catch (IOException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }

        // Add sections to the map if they have content
        if (mediaDetails.length() > 0) {
            sections.put(SECTION_MEDIA_DETAILS, mediaDetails.toString());
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
     * Extracts metadata from a video file.
     *
     * @param context Application context
     * @param videoUri URI of the video file
     * @param metadata StringBuilder to append metadata to
     */
    private static void extractVideoMetadata(Context context, Uri videoUri, StringBuilder metadata) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(context, videoUri);

            // Video properties
            metadata.append("\nVideo Properties:\n");

            // Duration
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                long durationMs = Long.parseLong(duration);
                long seconds = (durationMs / 1000) % 60;
                long minutes = (durationMs / (1000 * 60)) % 60;
                long hours = (durationMs / (1000 * 60 * 60));

                String formattedDuration;
                if (hours > 0) {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
                } else {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
                }

                metadata.append("Duration: ").append(formattedDuration).append("\n");
            }

            // Resolution
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (width != null && height != null) {
                metadata.append("Resolution: ").append(width).append(" x ").append(height).append("\n");
            }

            // Rotation
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                metadata.append("Rotation: ").append(rotation).append("°\n");
            }

            // Bitrate
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null) {
                long bitrateValue = Long.parseLong(bitrate);
                metadata.append("Bitrate: ").append(bitrateValue / 1000).append(" kbps\n");
            }

            // Date
            String date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (date != null) {
                metadata.append("Date: ").append(date).append("\n");
            }

            // Location information
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            metadata.append("\nLocation Information:\n");

            if (hasLocationPermission) {
                String latitude = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                if (latitude != null && !latitude.isEmpty()) {
                    // Location is typically stored as latitude+longitude format
                    String[] parts = latitude.split("\\+");
                    if (parts.length == 2) {
                        float lat = Float.parseFloat(parts[0]);
                        float lon = Float.parseFloat(parts[1]);

                        metadata.append("Latitude: ").append(lat).append("\n");
                        metadata.append("Longitude: ").append(lon).append("\n");

                        // Try to get address from coordinates
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

                                    metadata.append("Address: ").append(addressText).append("\n");
                                }
                            }
                        } catch (Exception e) {
                            metadata.append("Geocoding failed: ").append(e.getMessage()).append("\n");
                        }
                    }
                } else {
                    metadata.append("No location data available\n");
                }
            } else {
                metadata.append("Location permission not granted. Grant permission to see location data.\n");
            }

            // Technical details
            metadata.append("\nTechnical Details:\n");

            // Frame rate
            String frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (frameRate != null) {
                metadata.append("Frame Rate: ").append(frameRate).append(" fps\n");
            }

            // Bitrate
            if (bitrate != null) {
                long bitrateValue = Long.parseLong(bitrate);
                metadata.append("Bitrate: ").append(bitrateValue / 1000).append(" kbps\n");
            }

            // Audio sample rate
            String audioSampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            if (audioSampleRate != null) {
                metadata.append("Sample Rate: ").append(audioSampleRate).append(" Hz\n");
            }

        } catch (Exception e) {
            metadata.append("Error extracting video metadata: ").append(e.getMessage());
            FirebaseCrashlytics.getInstance().recordException(e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Ignore release errors
            }
        }
    }

    /**
     * Extracts sectioned metadata from a video file.
     *
     * @param context Application context
     * @param videoUri URI of the video file
     * @return Map of section identifiers to section content
     */
    private static Map<String, String> extractVideoMetadataSectioned(Context context, Uri videoUri) {
        Map<String, String> sections = new HashMap<>();
        StringBuilder mediaDetails = new StringBuilder();
        StringBuilder locationInfo = new StringBuilder();
        StringBuilder technicalDetails = new StringBuilder();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(context, videoUri);

            // Media details section
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                long durationMs = Long.parseLong(duration);
                long seconds = (durationMs / 1000) % 60;
                long minutes = (durationMs / (1000 * 60)) % 60;
                long hours = (durationMs / (1000 * 60 * 60));

                String formattedDuration;
                if (hours > 0) {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
                } else {
                    formattedDuration = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
                }

                mediaDetails.append("Duration: ").append(formattedDuration).append("\n");
            }

            String date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (date != null) {
                mediaDetails.append("Date: ").append(date).append("\n");
            }

            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (width != null && height != null) {
                mediaDetails.append("Resolution: ").append(width).append(" x ").append(height).append("\n");
            }

            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                mediaDetails.append("Rotation: ").append(rotation).append("°\n");
            }

            // Location section
            boolean hasLocationPermission = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;

            Log.d(TAG, "Has location permission: " + hasLocationPermission);
            FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);

            if (hasLocationPermission) {
                String location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                if (location != null && !location.isEmpty()) {
                    // Location is typically stored as latitude+longitude format
                    String[] parts = location.split("\\+");
                    if (parts.length == 2) {
                        float lat = Float.parseFloat(parts[0]);
                        float lon = Float.parseFloat(parts[1]);

                        locationInfo.append("Latitude: ").append(lat).append("\n");
                        locationInfo.append("Longitude: ").append(lon).append("\n");

                        // Try to get address from coordinates
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

                                    locationInfo.append("Address: ").append(addressText).append("\n");
                                }
                            }
                        } catch (Exception e) {
                            locationInfo.append("Geocoding failed: ").append(e.getMessage()).append("\n");
                            FirebaseCrashlytics.getInstance().recordException(e);
                        }
                    }
                } else {
                    locationInfo.append("No location data available in this video.\n");
                }
            } else {
                locationInfo.append("Location permission not granted. Grant permission to see full location data.\n");
            }

            // Technical details section
            String frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (frameRate != null) {
                technicalDetails.append("Frame Rate: ").append(frameRate).append(" fps\n");
            }

            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null) {
                long bitrateValue = Long.parseLong(bitrate);
                technicalDetails.append("Bitrate: ").append(bitrateValue / 1000).append(" kbps\n");
            }

            String audioSampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            if (audioSampleRate != null) {
                technicalDetails.append("Sample Rate: ").append(audioSampleRate).append(" Hz\n");
            }

        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Ignore release errors
            }
        }

        // Add sections to the map if they have content
        if (mediaDetails.length() > 0) {
            sections.put(SECTION_MEDIA_DETAILS, mediaDetails.toString());
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
     * Gets a file path from a Uri.
     *
     * @param context Application context
     * @param uri URI to get path for
     * @return File path or null if not available
     */
    private static String getPathFromUri(Context context, Uri uri) {
        try {
            String[] projection = {MediaStore.Images.Media.DATA};
            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);

            if (cursor != null) {
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                cursor.moveToFirst();
                String path = cursor.getString(column_index);
                cursor.close();
                return path;
            }
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }

        return null;
    }

    /**
     * Converts an orientation value to a readable string.
     *
     * @param orientation Orientation value from EXIF
     * @return Human-readable orientation string
     */
    private static String getOrientationString(int orientation) {
        return switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL -> "Normal";
            case ExifInterface.ORIENTATION_ROTATE_90 -> "Rotate 90°";
            case ExifInterface.ORIENTATION_ROTATE_180 -> "Rotate 180°";
            case ExifInterface.ORIENTATION_ROTATE_270 -> "Rotate 270°";
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Flip Horizontal";
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flip Vertical";
            case ExifInterface.ORIENTATION_TRANSPOSE -> "Transpose";
            case ExifInterface.ORIENTATION_TRANSVERSE -> "Transverse";
            default -> "Unknown (" + orientation + ")";
        };
    }
}
