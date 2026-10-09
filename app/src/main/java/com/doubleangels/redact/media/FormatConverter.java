package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.MetadataStripper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Converts image content to a target bitmap format (saved under {@code Pictures/Redact}) or
 * transcodes video with Media3 {@link VideoMedia3Converter} (saved under {@code Movies/Redact}).
 *
 * <p>Output format index: 0 = JPEG / H.264, 1 = PNG / H.265, 2 = WebP / VP9, 3 = HEIC / AV1 (HEIC
 * requires API 34+).
 */
public final class FormatConverter {



    @androidx.annotation.VisibleForTesting
    @Nullable
    static Bitmap.CompressFormat testHeicCompressFormatOverride;

    @androidx.annotation.VisibleForTesting
    @Nullable
    static Bitmap.CompressFormat testTreatFormatAsHeic;

    /**
     * Rotates/flips {@code bitmap} according to the EXIF orientation of {@code sourceUri}.
     * {@link BitmapFactory} ignores orientation, and the converted file carries no EXIF.
     */
    @NonNull
    private static Bitmap applyExifOrientation(
            @NonNull Context context, @NonNull Uri sourceUri, @NonNull Bitmap bitmap) {
        int orientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
            if (in != null) {
                orientation = new androidx.exifinterface.media.ExifInterface(in).getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception e) {
            return bitmap;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        switch (orientation) {
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return bitmap;
        }
        try {
            Bitmap out = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (out != bitmap) {
                bitmap.recycle();
            }
            return out;
        } catch (OutOfMemoryError e) {
            return bitmap;
        }
    }

    private FormatConverter() {
    }

    /** Number of selectable output format options (chips). */
    public static final int FORMAT_OPTION_COUNT = 4;

    /** {@link IOException#getMessage()} when HEIC clean/convert is requested below API 34. */
    public static final String HEIC_REQUIRES_API_34 = "heic_requires_api_34";

    @NonNull
    public static Bitmap.CompressFormat formatAtIndex(int index) {
        return formatAtIndexUnchecked(effectiveImageFormatIndex(index));
    }

    /**
     * Maps format chip index to image output index. Index 3 (HEIC) is not allowed below API 34;
     * JPEG (0) is used instead. Video index 3 (AV1) is unchanged — call only for image paths.
     */
    public static int effectiveImageFormatIndex(int formatIndex) {
        if (formatIndex == 3 && !isHeicProcessingSupported()) {
            return 0;
        }
        return formatIndex;
    }

    @NonNull
    private static Bitmap.CompressFormat formatAtIndexUnchecked(int index) {
        switch (index) {
            case 1:
                return Bitmap.CompressFormat.PNG;
            case 2:
                return Bitmap.CompressFormat.WEBP;
            case 3:
                return heicCompressFormatOrJpeg();
            case 0:
            default:
                return Bitmap.CompressFormat.JPEG;
        }
    }

    /**
     * HEIC/HEIF output via {@link Bitmap#compress} needs API 34+; resolved with {@code
     * CompressFormat.valueOf("HEIC")} for compatibility across compile SDKs.
     */
    @NonNull
    private static Bitmap.CompressFormat heicCompressFormatOrJpeg() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return Bitmap.CompressFormat.JPEG;
        }
        if (testHeicCompressFormatOverride != null) {
            return testHeicCompressFormatOverride;
        }
        for (Bitmap.CompressFormat format : Bitmap.CompressFormat.values()) {
            if (isHeicFormat(format)) {
                return format;
            }
        }
        return Bitmap.CompressFormat.JPEG;
    }

    /** Whether HEIC images can be cleaned or converted on this device (API 34+). */
    public static boolean isHeicProcessingSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    public static boolean isHeicOutputSupported() {
        if (!isHeicProcessingSupported()) {
            return false;
        }
        for (Bitmap.CompressFormat format : Bitmap.CompressFormat.values()) {
            if (isHeicFormat(format)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHeicFormat(@NonNull Bitmap.CompressFormat format) {
        if (testTreatFormatAsHeic != null && testTreatFormatAsHeic == format) {
            return true;
        }
        String n = format.name();
        return "HEIC".equals(n);
    }

    public static int qualityForFormat(@NonNull Bitmap.CompressFormat format) {
        return qualityForFormat(format, AppPreferences.QUALITY_PRESET_HIGH);
    }

    public static int qualityForFormat(@NonNull Bitmap.CompressFormat format, @NonNull Context context) {
        return qualityForFormat(format, AppPreferences.getImageQualityPreset(context));
    }

    public static int qualityForFormat(@NonNull Bitmap.CompressFormat format, int qualityPreset) {
        if (format == Bitmap.CompressFormat.PNG) {
            return 100;
        }
        boolean webpOrHeic = format == Bitmap.CompressFormat.WEBP || isHeicFormat(format);
        return AppPreferences.qualityForLossyFormat(qualityPreset, webpOrHeic);
    }

    /** Resolved output format for metadata stripping (preserves source type when possible). */
    public static final class ImageFormatSpec {
        public final String extension;
        public final String mimeType;
        public final Bitmap.CompressFormat compressFormat;
        /** False when HEIC bitmap encode is unavailable (API below 34). */
        public final boolean bitmapFallbackSupported;

        public ImageFormatSpec(
                @NonNull String extension,
                @NonNull String mimeType,
                @NonNull Bitmap.CompressFormat compressFormat,
                boolean bitmapFallbackSupported) {
            this.extension = extension;
            this.mimeType = mimeType;
            this.compressFormat = compressFormat;
            this.bitmapFallbackSupported = bitmapFallbackSupported;
        }
    }

    /**
     * Maps filename extension and/or MIME type to an output image format for cleaning.
     * Prefers extension when recognized; falls back to MIME, then JPEG.
     */
    @NonNull
    public static ImageFormatSpec resolveImageFormat(
            @Nullable String extensionWithDot, @Nullable String mimeType) throws IOException {
        if (isHeicSource(extensionWithDot, mimeType) && !isHeicProcessingSupported()) {
            return jpegSpec();
        }
        ImageFormatSpec fromExtension = specForExtension(extensionWithDot);
        if (fromExtension != null) {
            return fromExtension;
        }
        ImageFormatSpec fromMime = specForMime(mimeType);
        if (fromMime != null) {
            return fromMime;
        }
        return jpegSpec();
    }

    private static boolean isHeicSource(
            @Nullable String extensionWithDot, @Nullable String mimeType) {
        if (extensionWithDot != null) {
            String ext = extensionWithDot.toLowerCase(java.util.Locale.US);
            if (".heic".equals(ext) || ".heif".equals(ext)) {
                return true;
            }
        }
        if (mimeType != null) {
            String mime = mimeType.toLowerCase(java.util.Locale.US);
            return "image/heic".equals(mime) || "image/heif".equals(mime);
        }
        return false;
    }

    /**
     * Writes a bitmap using the same encoding rules as {@link #convertImageToPictures}.
     */
    public static boolean compressBitmapToStream(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            @NonNull Bitmap.CompressFormat format,
            @NonNull OutputStream os) {
        int q = qualityForFormat(format, context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isHeicFormat(format)) {
            return bitmap.compress(format, q, os);
        }
        if (format == Bitmap.CompressFormat.WEBP) {
            return bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, os);
        }
        return bitmap.compress(format, q, os);
    }

    @NonNull
    private static ImageFormatSpec jpegSpec() {
        return new ImageFormatSpec(".jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, true);
    }

    /** JPEG output spec for callers that need a public accessor. */
    @NonNull
    public static ImageFormatSpec jpegFormatSpec() {
        return jpegSpec();
    }

    @Nullable
    private static ImageFormatSpec specForExtension(@Nullable String extensionWithDot) {
        if (extensionWithDot == null || extensionWithDot.isEmpty()) {
            return null;
        }
        String ext = extensionWithDot.toLowerCase(java.util.Locale.US);
        return switch (ext) {
            case ".jpg", ".jpeg" -> jpegSpec();
            case ".png" -> new ImageFormatSpec(
                    ".png", "image/png", Bitmap.CompressFormat.PNG, true);
            case ".webp" -> new ImageFormatSpec(
                    ".webp", "image/webp", Bitmap.CompressFormat.WEBP, true);
            case ".heic", ".heif" -> heicSpec();
            default -> null;
        };
    }

    @Nullable
    private static ImageFormatSpec specForMime(@Nullable String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return null;
        }
        String mime = mimeType.toLowerCase(java.util.Locale.US);
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> jpegSpec();
            case "image/png" -> new ImageFormatSpec(
                    ".png", "image/png", Bitmap.CompressFormat.PNG, true);
            case "image/webp" -> new ImageFormatSpec(
                    ".webp", "image/webp", Bitmap.CompressFormat.WEBP, true);
            case "image/heic", "image/heif" -> heicSpec();
            default -> null;
        };
    }

    @NonNull
    private static ImageFormatSpec heicSpec() {
        Bitmap.CompressFormat heic = heicCompressFormatOrJpeg();
        if (!isHeicFormat(heic)) {
            return jpegSpec();
        }
        return new ImageFormatSpec(".heic", "image/heic", heic, true);
    }

    /**
     * Decodes the image at {@code sourceUri}, re-encodes as {@code format}, and inserts into
     * the user's gallery. Caller must have read access to the source URI.
     *
     * <p>Uses {@link ImageDecoder} when {@link BitmapFactory} cannot decode (e.g. some HEIF/AVIF
     * sources on newer Android versions).
     *
     * @param baseDisplayName file name without path; extension is replaced with the output type
     */
    @NonNull
    public static Uri convertImageToPictures(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull Bitmap.CompressFormat format,
            @NonNull String baseDisplayName) throws IOException {

        ContentResolver resolver = context.getContentResolver();
        String mimeIn = resolver.getType(sourceUri);
        if (mimeIn != null && mimeIn.startsWith("video/")) {
            throw new IOException("video_not_supported");
        }

        if (isHeicFormat(format) && !isHeicProcessingSupported()) {
            format = Bitmap.CompressFormat.JPEG;
        }

        String mime = mimeForFormat(format);
        String ext = extensionForFormat(format);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(sourceUri)) {
            if (in == null) {
                throw new IOException("Cannot open source");
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }

        Bitmap bitmap;
        boolean needsOrientationFix = false;
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            int sampleSize = calculateInSampleSize(context, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            try (InputStream in = resolver.openInputStream(sourceUri)) {
                if (in == null) {
                    throw new IOException("Cannot open source");
                }
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            needsOrientationFix = bitmap != null;
        } else {
            bitmap = null;
        }

        if (bitmap == null) {
            // ImageDecoder already applies EXIF orientation to the pixels.
            bitmap = decodeBitmapFromUri(context, sourceUri);
        }
        if (bitmap == null) {
            throw new IOException("Decode failed");
        }
        if (needsOrientationFix) {
            // The output carries no EXIF at all, so bake the rotation into the pixels.
            bitmap = applyExifOrientation(context, sourceUri, bitmap);
        }

        String outName = MediaFileNames.generateShortRandomName() + ext;

        Uri outUri;
        try {
            outUri = OutputDestination.create(context, false, outName, mime);
        } catch (IOException e) {
            bitmap.recycle();
            throw e;
        }

        try (OutputStream os = resolver.openOutputStream(outUri)) {
            if (os == null) {
                OutputDestination.discard(resolver, outUri);
                throw new IOException("Cannot open output stream");
            }
            int q = qualityForFormat(format, context);
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isHeicFormat(format)) {
                ok = bitmap.compress(format, q, os);
            } else if (format == Bitmap.CompressFormat.WEBP) {
                ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, os);
            } else {
                ok = bitmap.compress(format, q, os);
            }
            if (!ok) {
                OutputDestination.discard(resolver, outUri);
                throw new IOException("Compress failed");
            }
            os.flush();
        } finally {
            bitmap.recycle();
        }
        // Re-encoding from decoded pixels drops all source metadata (EXIF, XMP, IPTC, ICC
        // thumbnails); intentionally nothing is copied back.
        OutputDestination.publish(resolver, outUri);
        return outUri;
    }

    /**
     * Decodes a bitmap from a URI, using {@link ImageDecoder} when {@link BitmapFactory} cannot.
     */
    @Nullable
    public static Bitmap decodeBitmapFromUri(@NonNull Context context, @NonNull Uri sourceUri)
            throws IOException {
        return decodeWithImageDecoder(context, sourceUri);
    }

    private static Bitmap decodeWithImageDecoder(Context context, Uri sourceUri)
            throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), sourceUri);
        return ImageDecoder.decodeBitmap(
                source,
                (decoder, info, s) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int maxDimension = AppPreferences.getMaxBitmapSize(context);
                    int maxSide = Math.max(w, h);
                    if (maxSide > maxDimension) {
                        float scale = maxDimension / (float) maxSide;
                        decoder.setTargetSize(
                                Math.max(1, Math.round(w * scale)),
                                Math.max(1, Math.round(h * scale)));
                    }
                });
    }

    private static String extensionForFormat(Bitmap.CompressFormat format) {
        if (format == Bitmap.CompressFormat.PNG) {
            return ".png";
        }
        if (format == Bitmap.CompressFormat.WEBP) {
            return ".webp";
        }
        if (isHeicFormat(format)) {
            return ".heic";
        }
        return ".jpg";
    }

    private static String mimeForFormat(Bitmap.CompressFormat format) {
        if (format == Bitmap.CompressFormat.PNG) {
            return "image/png";
        }
        if (format == Bitmap.CompressFormat.WEBP) {
            return "image/webp";
        }
        if (isHeicFormat(format)) {
            return "image/heic";
        }
        return "image/jpeg";
    }

    private static final int MAX_ENCODER_DIMENSION = 16383;

    public static int calculateInSampleSize(Context context, BitmapFactory.Options options) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        int maxDimension = AppPreferences.getMaxBitmapSize(context);
        if (height > maxDimension || width > maxDimension) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= maxDimension
                    && (halfWidth / inSampleSize) >= maxDimension) {
                inSampleSize *= 2;
            }
        }
        // Encoders reject extreme aspect ratios (WebP caps each side at 16383 px), so long
        // screenshots must be downsampled even when the shorter side is small. Decoders round a
        // sampled side up (32767 px at 1/2 gives 16384), so compare the rounded-up size.
        int longestSide = Math.max(height, width);
        while ((longestSide + inSampleSize - 1) / inSampleSize > MAX_ENCODER_DIMENSION) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    /**
     * Transcodes video with Jetpack Media3 Transformer; output is MP4 in the gallery. Format index:
     * 0 = H.264, 1 = H.265, 2 = VP9, 3 = AV1 (see {@link VideoMedia3Converter}).
     *
     * @return URI of the new {@link MediaStore} video entry
     */
    @NonNull
    public static Uri convertVideoToMovies(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String baseDisplayName,
            int formatIndex) throws IOException {
        return convertVideoToMovies(context, sourceUri, baseDisplayName, formatIndex, null);
    }

    @NonNull
    public static Uri convertVideoToMovies(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String baseDisplayName,
            int formatIndex,
            @Nullable VideoMedia3Converter.TranscodeProgressListener progressListener)
            throws IOException {
        return convertVideoToMovies(
                context, sourceUri, baseDisplayName, formatIndex, progressListener, null, -1L);
    }

    @NonNull
    public static Uri convertVideoToMovies(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String baseDisplayName,
            int formatIndex,
            @Nullable VideoMedia3Converter.TranscodeProgressListener progressListener,
            @Nullable int[] outActualFormatIndex)
            throws IOException {
        return convertVideoToMovies(
                context, sourceUri, baseDisplayName, formatIndex, progressListener, outActualFormatIndex, -1L);
    }

    @NonNull
    public static Uri convertVideoToMovies(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String baseDisplayName,
            int formatIndex,
            @Nullable VideoMedia3Converter.TranscodeProgressListener progressListener,
            @Nullable int[] outActualFormatIndex,
            long transcodeOwnerId)
            throws IOException {
        return convertVideoToMovies(
                context, sourceUri, baseDisplayName, formatIndex, progressListener, outActualFormatIndex,
                transcodeOwnerId, null);
    }

    /**
     * @param cancelled polled during the metadata-stripping remux and before the transcode starts, so
     *     cancelling a conversion takes effect immediately rather than after the (long) remux
     */
    @NonNull
    public static Uri convertVideoToMovies(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String baseDisplayName,
            int formatIndex,
            @Nullable VideoMedia3Converter.TranscodeProgressListener progressListener,
            @Nullable int[] outActualFormatIndex,
            long transcodeOwnerId,
            @Nullable java.util.function.BooleanSupplier cancelled)
            throws IOException {
        MetadataStripper stripper = new MetadataStripper(context);
        stripper.setCancellationCheck(cancelled);
        File cleanSource = null;
        File outFile = null;
        try {
            // Transmux first so container metadata (location, dates, tags) never reaches the
            // transcoder, then verify the result the same way the Clean tab does.
            cleanSource = stripper.transmuxVideoWithoutMetadata(sourceUri);
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Video conversion cancelled");
            }
            Uri transcodeInput = cleanSource != null ? Uri.fromFile(cleanSource) : sourceUri;
            outFile = File.createTempFile(
                    "vid_transform_",
                    VideoMedia3Converter.extensionForFormatIndex(formatIndex),
                    context.getApplicationContext().getCacheDir());
            int actualFormat = VideoMedia3Converter.transcodeToPath(
                    context.getApplicationContext(),
                    transcodeInput,
                    outFile.getAbsolutePath(),
                    formatIndex,
                    progressListener,
                    transcodeOwnerId);
            if (outActualFormatIndex != null && outActualFormatIndex.length > 0) {
                outActualFormatIndex[0] = actualFormat;
            }
            // The remuxed copy was only the transcoder's input; free that space before the
            // verify-and-copy step instead of holding it until the end.
            if (cleanSource != null) {
                stripper.deleteTempFile(cleanSource);
                cleanSource = null;
            }
            stripper.requireConvertedVideoClean(outFile, sourceUri);
            return VideoMedia3Converter.copyToMoviesRedact(
                    context, outFile, baseDisplayName, actualFormat);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Video conversion interrupted", e);
        } finally {
            if (cleanSource != null) {
                stripper.deleteTempFile(cleanSource);
            }
            if (outFile != null && outFile.exists()) {
                stripper.deleteTempFile(outFile);
            }
        }
    }

    @NonNull
    public static String videoFormatLabel(@NonNull Context context, int formatIndex) {
        String[] labels = context.getResources().getStringArray(R.array.settings_video_format_labels);
        int idx = AppPreferences.clampFormatIndex(formatIndex);
        return idx < labels.length ? labels[idx] : labels[0];
    }
}
