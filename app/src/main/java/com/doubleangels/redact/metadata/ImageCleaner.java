package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.media3.common.Format;
import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.CleanFailedException;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.MediaFileNames;
import com.doubleangels.redact.media.MediaSizeLimits;
import com.doubleangels.redact.media.OutputDestination;
import com.doubleangels.redact.media.UnsupportedMediaException;
import com.doubleangels.redact.sentry.SentryManager;
import java.io.BufferedOutputStream;
import java.util.Locale;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cleans pictures for {@link MetadataStripper}: copies the source privately, keeps only the tags the
 * settings allow, rebuilds or re-encodes the picture with nothing else in it, and writes it out once
 * {@link CleanVerifier} has checked it. Moved out of MetadataStripper unchanged.
 */
final class ImageCleaner {

    private static final String TAG = "MetadataStripper";

    private final MetadataStripper host;
    private final Context context;
    private final ContentResolver contentResolver;

    ImageCleaner(@NonNull MetadataStripper host) {
        this.host = host;
        this.context = host.context;
        this.contentResolver = host.contentResolver;
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
        // Breadcrumb for a crash report.
        SentryManager.log("The app is starting image EXIF stripping for MediaStore.");
        SentryManager.beginOperation("image_to_mediastore");
        Uri newUri = null;
        MetadataStripper.Job job = new MetadataStripper.Job();
        File tempFile = null;

        try {
            host.updateProgress(1, 5, context.getString(R.string.strip_progress_reading_image));

            // Create temporary file to hold the image during processing. createTempFile gives a
            // unique name: a clean and a share starting in the same millisecond used to share one.
            tempFile = File.createTempFile("temp_", ".img", context.getCacheDir());

            host.copySourceToTempFile(sourceUri, tempFile);
            FormatConverter.ImageFormatSpec outputFormat =
                    resolveImageOutputFormat(sourceUri, originalFilename, tempFile);
            String extension = outputFormat.extension;
            String newFilename =
                    MediaFileNames.outputBaseName(context, originalFilename)
                            + extension;

            // Extract essential EXIF data to preserve (like orientation)
            host.updateProgress(2, 5, context.getString(R.string.strip_progress_reading_essential_metadata));
            job.stats = readEssentialTagsAndStats(tempFile, false, job.kept);

            newUri = writeCleanImage(tempFile, sourceUri, newFilename, outputFormat, false, job);

            SentryManager.log("The image was processed successfully.");
            SentryManager.setCustomKey("success", true);

        } catch (RuntimeException e) {
            if (MetadataStripper.shouldPropagateCancellation(e)) {
                throw e;
            }
            Log.e(TAG, "Error processing image", e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            SentryManager.recordException(e);

            if (newUri != null) {
                OutputDestination.discard(contentResolver, newUri);
                newUri = null;
            }
        } catch (Exception e) {
            if (MetadataStripper.shouldPropagateCancellation(e)) {
                throw MetadataStripper.asCancellationException(e);
            }
            // Log error and clean up any partial files
            Log.e(TAG, "Error processing image", e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            SentryManager.recordException(e);

            if (newUri != null) {
                OutputDestination.discard(contentResolver, newUri);
                newUri = null;
            }
        } finally {
            if (tempFile != null && tempFile.exists()) {
                // Use secure deletion for temp files
                host.secureDeleteFile(tempFile);
            }
        }

        return job.result(newUri);
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
        SentryManager.log("The app is saving a censored image to MediaStore.");
        MetadataStripper.Job job = new MetadataStripper.Job();
        // A private copy of the uncensored original, deleted as soon as its tags are read.
        File originalCopy = File.createTempFile("temp_", outputFormat.extension, context.getCacheDir());
        try {
            host.copySourceToTempFile(original, originalCopy);
            job.stats = readEssentialTagsAndStats(originalCopy, false, job.kept);
            job.kept.remove(ExifInterface.TAG_ORIENTATION);
        } finally {
            host.secureDeleteFile(originalCopy);
        }
        // The censored file is also what a re-encode falls back to decoding, never the original.
        return job.result(writeCleanImage(censored, Uri.fromFile(censored), outputName, outputFormat, true, job));
    }

    /**
     * Writes {@code picture} to a new gallery entry with only the preserved tags, verifies it and
     * publishes it. On failure the entry is removed and the error is rethrown.
     *
     * @param fromPixels {@code picture} was just encoded from pixels, so it holds no metadata and a
     *                   format the walker cannot rebuild is copied rather than encoded again
     */
    @NonNull
    private Uri writeCleanImage(@NonNull File picture, @NonNull Uri pictureUri, @NonNull String newFilename,
                                @NonNull FormatConverter.ImageFormatSpec outputFormat, boolean fromPixels,
                                @NonNull MetadataStripper.Job job)
            throws IOException {
        Uri newUri = null;
        File cleanFile = null;
        MotionPhoto.Clip motion = null;
        try {
            host.updateProgress(3, 5, context.getString(R.string.strip_progress_removing_metadata));
            if (!fromPixels) {
                motion = cleanMotionClip(picture);
                if (motion != null && job.stats != null
                        && ImageContainerMetadata.sniff(picture) == ImageContainerMetadata.Format.JPEG) {
                    // Counted among the removed (EMBEDDED_VIDEO) when the JPEG was read.
                    // The video goes back, cleaned, so the summary doesn't count it as removed.
                    job.stats = job.stats.keepingOne();
                }
            }

            // Every attempt writes to a private file, and only the finished result is copied to
            // the output. Rewriting the output itself between attempts is unsafe: a provider that
            // does not truncate on "w" would keep the tail of an earlier, longer attempt, which
            // can be a full copy of the original with its metadata.
            cleanFile = File.createTempFile("temp_clean_", outputFormat.extension, context.getCacheDir());
            FormatConverter.ImageFormatSpec cleanFormat =
                    writePictureOnly(picture, pictureUri, cleanFile, outputFormat, fromPixels, motion, job.kept,
                            AppPreferences.isStrictClean(context));
            if (!cleanFormat.extension.equals(outputFormat.extension)) {
                newFilename = withExtension(newFilename, cleanFormat.extension);
            }

            // Restore only essential EXIF data (like orientation)
            host.updateProgress(4, 5, context.getString(R.string.strip_progress_restoring_essential_metadata));
            restoreEssentialExifData(cleanFile, job.kept, false);

            // Verified while still private: a folder the user chose shows a new file at once (a
            // cloud-backed one may upload it), so nothing unverified may reach the output.
            host.updateProgress(5, 5, context.getString(R.string.strip_progress_verifying));
            boolean metadataRemoved = host.verifier.verifyMetadataRemoval(cleanFile, false);
            SentryManager.setCustomKey("metadata_verification_passed", metadataRemoved);
            if (!metadataRemoved) {
                throw new CleanFailedException("Metadata verification failed");
            }

            // Create the output (a pending MediaStore entry, or a file in the chosen folder) and
            // write the verified file to it once, then check that the output holds exactly those
            // bytes: a provider that keeps the tail of an older file would add to them.
            newUri = OutputDestination.create(context, false, newFilename, cleanFormat.mimeType);
            byte[] written;
            try (InputStream in = new FileInputStream(cleanFile);
                    OutputStream os = contentResolver.openOutputStream(newUri)) {
                if (os == null) {
                    throw new CleanFailedException("Failed to open output stream for new image");
                }
                MessageDigest digest = OutputDestination.sha256();
                MediaSizeLimits.copyStream(in, new DigestOutputStream(os, digest));
                written = digest.digest();
            }
            host.verifier.requireSameContent(newUri, written);
            OutputDestination.publish(contentResolver, newUri);
            return newUri;
        } catch (IOException | RuntimeException e) {
            if (newUri != null) {
                OutputDestination.discard(contentResolver, newUri);
            }
            throw e;
        } finally {
            if (cleanFile != null && cleanFile.exists()) {
                host.secureDeleteFile(cleanFile);
            }
            if (motion != null && motion.video.exists()) {
                host.secureDeleteFile(motion.video);
            }
        }
    }

    /**
     * A Motion Photo's video, copied out of {@code picture} and cleaned, to put back after the
     * clean picture: when Keep Motion Photos is on and Strict Clean is off. Its container is
     * scrubbed and checked like any video's, and a video whose stream carries data of its own
     * (a camera's date or location in its SEI) is left out, as a copy can't remove that. Null
     * when there is none or it can't be kept, and the photo becomes a still as before. The
     * caller deletes the clip's file.
     */
    @Nullable
    private MotionPhoto.Clip cleanMotionClip(@NonNull File picture) {
        if (AppPreferences.isStrictClean(context) || !AppPreferences.isPreserveMotionPhoto(context)) {
            return null;
        }
        MotionPhoto.Found found = MotionPhoto.find(picture);
        if (found == null) {
            return null;
        }
        File video = null;
        try {
            video = File.createTempFile("motion_", ".mp4", context.getCacheDir());
            try (RandomAccessFile in = new RandomAccessFile(picture, "r");
                    OutputStream out = new BufferedOutputStream(new FileOutputStream(video), 64 * 1024)) {
                in.seek(found.start);
                byte[] buffer = new byte[64 * 1024];
                long left = found.end - found.start;
                while (left > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n < 0) {
                        throw new CleanFailedException("The video ends early");
                    }
                    out.write(buffer, 0, n);
                    left -= n;
                }
            }
            // Copied again sample for sample with only its picture and sound: a Pixel's clip has a
            // timed metadata track as well, which no scrub can empty and no check passes.
            File remuxed = host.videos.fastStripVideoMetadata(Uri.fromFile(video), null, false);
            if (remuxed != null && remuxed.getName().endsWith(".mp4")) {
                host.secureDeleteFile(video);
                video = remuxed;
            } else if (remuxed != null) {
                host.secureDeleteFile(remuxed);
            }
            VideoContainerMetadata.scrub(video);
            VideoContainerMetadata.Report container = VideoContainerMetadata.scan(video);
            if (!container.recognized || !container.fields.isEmpty()) {
                throw new CleanFailedException("its container still holds metadata");
            }
            VideoStreamMetadata.Report stream = VideoStreamMetadata.scan(context, Uri.fromFile(video), true);
            if (stream == null || stream.userDataMessages > 0) {
                throw new CleanFailedException("its stream carries data of its own");
            }
            return new MotionPhoto.Clip(video, found.presentationTimestampUs);
        } catch (IOException | RuntimeException e) {
            SentryManager.log("The Motion Photo's video could not be kept, so the photo becomes a still: "
                    + e.getClass().getSimpleName() + ".");
            SentryManager.count("clean.part_dropped", 1, "media_type", "image", "outcome", "motion_video");
            if (video != null && video.exists()) {
                host.secureDeleteFile(video);
            }
            return null;
        }
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
        // Breadcrumb for a crash report.
        SentryManager.log("The app is starting image EXIF stripping for sharing.");

        SentryManager.beginOperation("image_for_sharing");
        MetadataStripper.Job job = new MetadataStripper.Job();

        File tempFile = null;
        File outputFile = null;

        try {
            host.updateProgress(1, 4, context.getString(R.string.strip_progress_reading_image));

            tempFile = File.createTempFile("temp_", ".img", context.getCacheDir());

            host.copySourceToTempFile(sourceUri, tempFile);
            FormatConverter.ImageFormatSpec outputFormat =
                    resolveImageOutputFormat(sourceUri, originalFilename, tempFile);
            String extension = outputFormat.extension;

            host.updateProgress(2, 4, context.getString(R.string.strip_progress_reading_essential_metadata));
            job.stats = readEssentialTagsAndStats(tempFile, true, job.kept);

            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new CleanFailedException("Failed to create output directory");
                }
            }

            String newFilename = host.generateShortRandomName() + extension;
            outputFile = new File(outputDir, newFilename);

            host.updateProgress(3, 5, context.getString(R.string.strip_progress_removing_metadata));
            FormatConverter.ImageFormatSpec cleanFormat =
                    writePictureOnly(tempFile, Uri.fromFile(tempFile), outputFile, outputFormat, false, null, job.kept,
                            true);
            if (!cleanFormat.extension.equals(extension)) {
                // Shared under the name of what it really is, so the app it goes to opens it.
                File renamed = new File(outputDir, host.generateShortRandomName() + cleanFormat.extension);
                if (!outputFile.renameTo(renamed)) {
                    throw new CleanFailedException("Failed to rename the shared image");
                }
                outputFile = renamed;
            }

            // Put back only the essential tags (orientation)
            host.updateProgress(4, 5, context.getString(R.string.strip_progress_removing_all_metadata));
            restoreEssentialExifData(outputFile, job.kept, true);

            // Verify metadata removal
            host.updateProgress(5, 5, context.getString(R.string.strip_progress_verifying));
            boolean metadataRemoved = host.verifier.verifyMetadataRemoval(outputFile, true);
            SentryManager.setCustomKey("metadata_verification_passed", metadataRemoved);
            if (!metadataRemoved) {
                SentryManager.log("Metadata verification found remaining metadata.");
                if (outputFile != null && outputFile.exists()) {
                    host.secureDeleteFile(outputFile);
                }
                return job.result(null);
            }
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            job.file = outputFile;
            SentryManager.log("The image was processed successfully for sharing.");
            SentryManager.setCustomKey("success", true);
            return job.result(fileUri);

        } catch (Exception e) {
            // Log error
            Log.e(TAG, "Error processing image for sharing", e);
            job.unsupported = UnsupportedMediaException.isCauseOf(e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            SentryManager.recordException(e);
            if (outputFile != null && outputFile.exists()) {
                host.secureDeleteFile(outputFile);
            }
            return job.result(null);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                // Use secure deletion for temp files
                host.secureDeleteFile(tempFile);
            }
        }
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
        boolean rebuilt = false;
        try {
            if (ImageContainerMetadata.sniff(encoded) != ImageContainerMetadata.Format.OTHER) {
                ImageContainerMetadata.sanitize(encoded, target, context.getCacheDir());
                rebuilt = true;
            } else if (HeifContainerMetadata.isHeif(encoded)) {
                HeifContainerMetadata.sanitize(encoded, target, context.getCacheDir());
                rebuilt = true;
            }
        } catch (IOException | RuntimeException e) {
            SentryManager.log("The converted image could not be rebuilt, so it is checked as it is: "
                    + e.getClass().getSimpleName() + ".");
        }
        if (!rebuilt) {
            try (InputStream in = new FileInputStream(encoded);
                    OutputStream out = new FileOutputStream(target)) {
                MediaSizeLimits.copyStream(in, out);
            }
        }
        // Checked as a share is: nothing kept but how to show the picture.
        if (!host.verifier.verifyMetadataRemoval(target, true)) {
            throw new CleanFailedException("Converted image verification failed");
        }
    }

    /**
     * The output format for cleaning or sharing an image: what {@code content} really is, by its
     * first bytes, or what its name and type say when its bytes are not a known image.
     */
    @NonNull
    FormatConverter.ImageFormatSpec resolveImageOutputFormat(
            @NonNull Uri sourceUri, @NonNull String originalFilename, @NonNull File content) throws IOException {
        String mimeType = null;
        try {
            mimeType = contentResolver.getType(sourceUri);
        } catch (Exception e) {
            SentryManager.logEvent("Metadata", "The MIME type for the source could not be read.");
        }
        return FormatConverter.resolveCleanFormat(content, MetadataStripper.extensionFromFilename(originalFilename), mimeType);
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
    /**
     * Reads the tags a clean keeps and counts what it removes, from one parse of the file's EXIF.
     *
     * @throws IOException when the EXIF cannot be read at all
     */
    @NonNull
    private CleanStats readEssentialTagsAndStats(@NonNull File imageFile, boolean forSharing,
                                                 @NonNull Map<String, String> kept) throws IOException {
        ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
        readEssentialExifData(exif, forSharing, kept);
        return readCleanStats(imageFile, exif, forSharing);
    }

    private void readEssentialExifData(@NonNull ExifInterface exif, boolean forSharing,
                                       @NonNull Map<String, String> kept) {
        kept.clear();

        List<String> tagsToPreserve = host.getTagsToPreserve(forSharing);

        // Store each tag that exists in the original file
        for (String tag : tagsToPreserve) {
            String value = exif.getAttribute(tag);
            if (value != null) {
                kept.put(tag, value);
                SentryManager.log("The app is preserving the EXIF tag: " + tag + ".");
            }
        }
        if (!kept.containsKey(ExifInterface.TAG_COLOR_SPACE)) {
            kept.remove(ExifInterface.TAG_INTEROPERABILITY_INDEX);
        }

        SentryManager.setCustomKey("preserved_exif_count", kept.size());
    }

    /**
     * Restores essential EXIF data to a processed image in MediaStore.
     *
     * This method writes back non-identifying metadata like orientation that was
     * preserved from the original image.
     *
     * @param imageFile the processed image, before it is copied to the output
     * @param forSharing whether it is being cleaned to share, which keeps a different set of tags
     */
    private void restoreEssentialExifData(@NonNull File imageFile, @NonNull Map<String, String> kept,
                                          boolean forSharing) {
        // Skip if no EXIF values were preserved
        if (kept.isEmpty()) {
            return;
        }
        if (HeifContainerMetadata.isHeif(imageFile)) {
            restoreHeifExif(imageFile, kept);
            return;
        }
        if (ImageContainerMetadata.sniff(imageFile) == ImageContainerMetadata.Format.OTHER) {
            // ExifInterface writes only JPEG, PNG and WebP. A GIF has nowhere for EXIF to go.
            SentryManager.log("The kept EXIF tags could not be written to this format.");
            return;
        }

        try {
            ExifInterface newExif = new ExifInterface(imageFile.getAbsolutePath());

            removeAllExifMetadata(newExif, forSharing);
            restoreEssentialExifValues(newExif, kept);
            newExif.saveAttributes();
            if (ImageContainerMetadata.sniff(imageFile) == ImageContainerMetadata.Format.JPEG) {
                UltraHdr.fixPrimarySize(imageFile);
            }
        } catch (Exception e) {
            SentryManager.log("The EXIF failed to restore: " + e.getClass().getSimpleName() + ".");
            SentryManager.recordException(e);
        }
    }

    /**
     * Puts the tags the settings keep (camera settings, location, date taken) back into a clean
     * HEIC or AVIF as a new EXIF item. ExifInterface can't save a HEIF, so it writes them into a
     * one-pixel JPEG, and the TIFF block it made there is what goes in. Orientation and colour
     * space are left out: a HEIF keeps those in its own boxes, and an EXIF orientation as well
     * would turn the picture twice in some viewers.
     */
    private void restoreHeifExif(@NonNull File heif, @NonNull Map<String, String> kept) {
        Map<String, String> values = new LinkedHashMap<>(kept);
        values.keySet().removeAll(MetadataStripper.ALWAYS_KEPT_TAGS);
        if (values.isEmpty()) {
            return;
        }
        File carrier = null;
        try {
            carrier = File.createTempFile("exif_carrier_", ".jpg", context.getCacheDir());
            Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(carrier)) {
                if (!pixel.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    throw new CleanFailedException("Could not write the EXIF carrier");
                }
            } finally {
                pixel.recycle();
            }
            ExifInterface exif = new ExifInterface(carrier.getAbsolutePath());
            // The carrier's own size and the defaults ExifInterface fills in describe it, not the
            // photo, so none of them go along.
            for (String tag : new String[] {ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
                    ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_LIGHT_SOURCE}) {
                exif.setAttribute(tag, null);
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                exif.setAttribute(entry.getKey(), entry.getValue());
            }
            exif.saveAttributes();
            byte[] tiff = exifTiff(Files.readAllBytes(carrier.toPath()));
            if (tiff == null) {
                throw new CleanFailedException("The EXIF carrier has no EXIF");
            }
            HeifContainerMetadata.addExif(heif, tiff, context.getCacheDir());
        } catch (IOException | RuntimeException e) {
            SentryManager.log("The kept EXIF tags could not be added to the HEIF: " + e.getClass().getSimpleName() + ".");
        } finally {
            if (carrier != null) {
                host.secureDeleteFile(carrier);
            }
        }
    }

    /** The TIFF block of a JPEG's EXIF segment (after "Exif\0\0"), or null when it has none. */
    @Nullable
    static byte[] exifTiff(@NonNull byte[] jpeg) {
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (marker == 0xDA || length < 2 || pos + 2 + length > jpeg.length) {
                return null;
            }
            int body = pos + 4;
            if (marker == 0xE1 && length >= 8 && jpeg[body] == 'E' && jpeg[body + 1] == 'x'
                    && jpeg[body + 2] == 'i' && jpeg[body + 3] == 'f' && jpeg[body + 4] == 0 && jpeg[body + 5] == 0) {
                return Arrays.copyOfRange(jpeg, body + 6, pos + 2 + length);
            }
            pos += 2 + length;
        }
        return null;
    }

    /**
     * Helper method to restore preserved EXIF values to an ExifInterface instance.
     *
     * @param exif ExifInterface to write values to, must not be null
     */
    private void restoreEssentialExifValues(@NonNull ExifInterface exif, @NonNull Map<String, String> kept) {
        // Write each preserved value to the ExifInterface
        for (Map.Entry<String, String> entry : kept.entrySet()) {
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
     * @param forSharing whether the photo is being cleaned to share; the settings keep different
     *                   tags then
     */
    private void removeAllExifMetadata(@NonNull ExifInterface exif, boolean forSharing) {
        try {
            // Every TAG_ constant; throws when they can't be read, and the catch below falls back
            // to the fixed list (verification fails closed on the same check).
            List<String> tags = ExifTags.all();
            Set<String> tagsToPreserve = new HashSet<>(host.getTagsToPreserve(forSharing));

            int removedCount = 0;
            for (String tagName : tags) {
                if (!tagsToPreserve.contains(tagName)) {
                    // Try to get the attribute to see if it exists
                    String value = exif.getAttribute(tagName);
                    if (ExifInterface.TAG_XMP.equals(tagName) && value != null
                            && UltraHdr.isPictureOnlyXmp(value)) {
                        // The Ultra HDR directory a clean wrote: removing it loses the HDR.
                        continue;
                    }
                    if (value != null) {
                        // Remove the attribute by setting it to null
                        exif.setAttribute(tagName, null);
                        removedCount++;
                    }
                }
            }
            if (exif.getAttribute(ExifInterface.TAG_COLOR_SPACE) == null
                    && exif.getAttribute(ExifInterface.TAG_INTEROPERABILITY_INDEX) != null) {
                exif.setAttribute(ExifInterface.TAG_INTEROPERABILITY_INDEX, null);
                removedCount++;
            }

            SentryManager.log("The app removed " + removedCount + " EXIF metadata tags.");
            SentryManager.setCustomKey("exif_tags_removed", removedCount);

        } catch (Exception e) {
            SentryManager.log("An error occurred while removing EXIF metadata: " + e.getClass().getSimpleName() + ".");
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
        SentryManager.log("The app removed " + removedCount + " EXIF metadata tags using the fallback method.");
    }

    /** Counts the identifying fields in an image before it is cleaned; unreadable EXIF counts as none. */
    @NonNull
    private CleanStats readCleanStats(@NonNull File imageFile, @NonNull ExifInterface reader, boolean forSharing) {
        CleanStats exif;
        boolean exifCountedXmp = false;
        try {
            Set<String> preserved = new HashSet<>(host.getTagsToPreserve(forSharing));
            exif = CleanStats.fromExif(reader, preserved);
            String xmp = reader.getAttribute(ExifInterface.TAG_XMP);
            exifCountedXmp = xmp != null && !xmp.isEmpty()
                    && !preserved.contains(ExifInterface.TAG_XMP);
        } catch (Exception e) {
            // Only the count of removed fields is lost; the clean itself is checked elsewhere.
            SentryManager.log("The app could not count the photo's EXIF fields: "
                    + e.getClass().getSimpleName() + ".");
            exif = new CleanStats(0, false);
        }
        return exif.plus(ImageContainerMetadata.scan(imageFile), exifCountedXmp);
    }

    /**
     * Writes {@code source}'s picture to {@code target} and nothing else. A JPEG, PNG or WebP in
     * the expected format is rebuilt from its own segments without anything that is not the
     * picture, which keeps its quality ({@link ImageContainerMetadata#sanitize}), and a HEIC is
     * rebuilt around its coded pictures the same way ({@link HeifContainerMetadata#sanitize}). Anything else,
     * or a file whose structure cannot be followed, is decoded and re-encoded from its pixels, and
     * a re-encoded JPEG, PNG or WebP is then rebuilt the same way, so whatever the encoder added
     * goes too. EXIF is gone either way; the caller puts back the tags the settings keep.
     *
     * @param neutralIcc Strict Clean or a share: a JPEG's, PNG's or WebP's colour profile keeps
     *                   only its colour tags and a neutral name ({@link IccProfiles})
     */
    @NonNull
    private FormatConverter.ImageFormatSpec writePictureOnly(@NonNull File source, @NonNull Uri sourceUri,
                                  @NonNull File target,
                                  @NonNull FormatConverter.ImageFormatSpec outputFormat, boolean fromPixels,
                                  @Nullable MotionPhoto.Clip motion, @NonNull Map<String, String> kept,
                                  boolean neutralIcc)
            throws IOException {
        ImageContainerMetadata.Format expected = containerFormat(outputFormat.extension);
        // A HEIC the encoder just wrote is still rebuilt below: losslessly, so whatever the
        // encoder put beside the picture goes too.
        if (fromPixels && expected == ImageContainerMetadata.Format.OTHER && !HeifContainerMetadata.isHeif(source)) {
            // Encoding it again (HEIC, say) would only cost time and quality; the check before
            // publishing still makes sure nothing identifying is in it.
            try (InputStream in = new FileInputStream(source);
                    OutputStream out = new FileOutputStream(target)) {
                MediaSizeLimits.copyStream(in, out);
            }
            return outputFormat;
        }
        // Censor's freshly encoded pixels never held metadata, so only real cleans count a path.
        boolean rebuildFailed = false;
        if ((".heic".equalsIgnoreCase(outputFormat.extension) || ".avif".equalsIgnoreCase(outputFormat.extension))
                && HeifContainerMetadata.isHeif(source)) {
            // Rebuilt around its coded pictures, which keeps the full resolution, 10-bit colour
            // and any HDR gain map that a decode and encode would lose.
            try {
                HeifContainerMetadata.sanitize(source, target, motion, context.getCacheDir());
                countPicturePath(fromPixels, "rebuilt");
                return outputFormat;
            } catch (IOException | RuntimeException e) {
                if (fromPixels) {
                    // The encoder's own file, which holds nothing of the source's: kept as it
                    // was, and still checked before it is published.
                    try (InputStream in = new FileInputStream(source);
                            OutputStream out = new FileOutputStream(target)) {
                        MediaSizeLimits.copyStream(in, out);
                    }
                    return outputFormat;
                }
                SentryManager.log("The HEIC or AVIF could not be rebuilt without its metadata, so the app is re-encoding it: "
                        + e.getClass().getSimpleName() + ".");
                rebuildFailed = true;
            }
        }
        if (".gif".equalsIgnoreCase(outputFormat.extension) && GifContainerMetadata.isGif(source)) {
            // Rebuilt block by block, which keeps every frame, its timing and transparency.
            try {
                GifContainerMetadata.sanitize(source, target);
                countPicturePath(fromPixels, "rebuilt");
                return outputFormat;
            } catch (IOException | RuntimeException e) {
                SentryManager.log("The GIF could not be rebuilt without its metadata, so the app is re-encoding it: "
                        + e.getClass().getSimpleName() + ".");
                rebuildFailed = true;
            }
        }
        if (expected != ImageContainerMetadata.Format.OTHER
                && ImageContainerMetadata.sniff(source) == expected) {
            try {
                ImageContainerMetadata.sanitize(source, target, motion, context.getCacheDir(), neutralIcc);
                countPicturePath(fromPixels, "rebuilt");
                return outputFormat;
            } catch (IOException | RuntimeException e) {
                // A structure the walker could not follow, however it failed: the pixels are
                // re-encoded instead, which carries no metadata either.
                SentryManager.log("The image could not be rebuilt without its metadata, so the app is re-encoding it: "
                        + e.getClass().getSimpleName() + ".");
                rebuildFailed = true;
            }
        }
        host.checkCancelled();
        // Where this device can't encode the format (AVIF, GIF, or HEIC before Android 14), the
        // pixels go to a JPEG, or a PNG for a GIF, and the caller names the file for that.
        FormatConverter.ImageFormatSpec written = FormatConverter.encodableSpecFor(outputFormat);
        ImageContainerMetadata.Format writtenFormat = containerFormat(written.extension);
        File encoded = File.createTempFile("temp_encoded_", written.extension, context.getCacheDir());
        try {
            reEncode(source, sourceUri, encoded, outputFormat, kept);
            if (writtenFormat != ImageContainerMetadata.Format.OTHER
                    && ImageContainerMetadata.sniff(encoded) == writtenFormat) {
                ImageContainerMetadata.sanitize(encoded, target, motion, context.getCacheDir(), neutralIcc);
            } else {
                try (InputStream in = new FileInputStream(encoded);
                        OutputStream out = new FileOutputStream(target)) {
                    MediaSizeLimits.copyStream(in, out);
                }
            }
        } finally {
            host.secureDeleteFile(encoded);
        }
        countPicturePath(fromPixels, rebuildFailed ? "rebuild_failed" : "reencoded");
        return written;
    }

    /**
     * Counts how a cleaned picture was written: rebuilt losslessly, or re-encoded (which costs
     * quality) because the format has no lossless route or the rebuild failed.
     */
    private static void countPicturePath(boolean fromPixels, @NonNull String path) {
        if (!fromPixels) {
            SentryManager.count("clean.path", 1, "media_type", "image", "outcome", path);
        }
    }

    /** {@code name} with its extension replaced by {@code extension} (which starts with a dot). */
    @NonNull
    static String withExtension(@NonNull String name, @NonNull String extension) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + extension;
    }

    @NonNull
    private static ImageContainerMetadata.Format containerFormat(@NonNull String extension) {
        switch (extension.toLowerCase(Locale.ROOT)) {
            case ".jpg":
            case ".jpeg":
                return ImageContainerMetadata.Format.JPEG;
            case ".png":
                return ImageContainerMetadata.Format.PNG;
            case ".webp":
                return ImageContainerMetadata.Format.WEBP;
            default:
                return ImageContainerMetadata.Format.OTHER;
        }
    }

    /** Decodes {@code source} and encodes its pixels, which carry no metadata, to {@code target}. */
    private void reEncode(@NonNull File source, @NonNull Uri sourceUri, @NonNull File target,
                          @NonNull FormatConverter.ImageFormatSpec outputFormat,
                          @NonNull Map<String, String> kept) throws IOException {
        FormatConverter.ImageFormatSpec bitmapOutput = FormatConverter.encodableSpecFor(outputFormat);
        if (containerFormat(bitmapOutput.extension) == ImageContainerMetadata.Format.OTHER) {
            // No tags can be written back into this format (HEIC), so the orientation has to be
            // in the pixels: ImageDecoder applies it, BitmapFactory does not.
            Bitmap upright = FormatConverter.decodeUpright(context, Uri.fromFile(source),
                    FormatConverter.FULL_RESOLUTION);
            kept.remove(ExifInterface.TAG_ORIENTATION);
            compressTo(upright, bitmapOutput, target, 0, false);
            return;
        }
        BitmapFactory.Options optionsJustBounds = new BitmapFactory.Options();
        optionsJustBounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), optionsJustBounds);
        int sampleSize = FormatConverter.calculateInSampleSize(context, optionsJustBounds);
        SentryManager.setCustomKey("bitmap_sample_size", sampleSize);

        String path = source.getAbsolutePath();
        Bitmap bitmap = FormatConverter.decodeSampled(
                sampleSize, options -> BitmapFactory.decodeFile(path, options));
        if (bitmap == null) {
            bitmap = FormatConverter.decodeBitmapFromUri(context, sourceUri);
        }
        if (bitmap == null) {
            throw new UnsupportedMediaException("Failed to decode bitmap", null);
        }
        int sourceQuality;
        try (InputStream in = new FileInputStream(source)) {
            sourceQuality = FormatConverter.estimateJpegQuality(in);
        }
        boolean lossless;
        try (InputStream in = new FileInputStream(source)) {
            lossless = FormatConverter.isLosslessImage(in);
        }
        compressTo(bitmap, bitmapOutput, target, sourceQuality, lossless);
    }

    /** Encodes {@code bitmap} to {@code target} and recycles it. */
    private void compressTo(@NonNull Bitmap bitmap, @NonNull FormatConverter.ImageFormatSpec bitmapOutput,
                            @NonNull File target, int sourceJpegQuality, boolean losslessSource)
            throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            if (!FormatConverter.compressBitmapToStream(
                    context, bitmap, bitmapOutput.compressFormat, out, sourceJpegQuality, losslessSource)) {
                throw new CleanFailedException("Failed to compress bitmap");
            }
            out.flush();
            out.getFD().sync();
        } finally {
            bitmap.recycle();
        }
    }
}
