package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

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

    private static final int MAX_DIMENSION = 4096;

    private static void copyExifData(Context context, Uri sourceUri, Uri destUri) {
        try {
            androidx.exifinterface.media.ExifInterface oldExif = null;
            try (InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
                if (in != null) {
                    oldExif = new androidx.exifinterface.media.ExifInterface(in);
                }
            }
            
            if (oldExif != null) {
                try (android.os.ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(destUri, "rw")) {
                    if (pfd != null) {
                        androidx.exifinterface.media.ExifInterface newExif = new androidx.exifinterface.media.ExifInterface(pfd.getFileDescriptor());
                        
                        // Copy non-location EXIF only; GPS tags are omitted for privacy.
                        String[] tags = {
                            androidx.exifinterface.media.ExifInterface.TAG_DATETIME,
                            androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED,
                            androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL,
                            androidx.exifinterface.media.ExifInterface.TAG_MAKE,
                            androidx.exifinterface.media.ExifInterface.TAG_MODEL,
                            androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH,
                            androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER,
                            androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME,
                            androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH,
                            androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH,
                            androidx.exifinterface.media.ExifInterface.TAG_FLASH,
                            androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE
                        };

                        for (String tag : tags) {
                            String value = oldExif.getAttribute(tag);
                            if (value != null) {
                                newExif.setAttribute(tag, value);
                            }
                        }
                        newExif.saveAttributes();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore exif copy errors
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
        try {
            return Bitmap.CompressFormat.valueOf("HEIC");
        } catch (IllegalArgumentException e) {
            return Bitmap.CompressFormat.JPEG;
        }
    }

    /** Whether HEIC images can be cleaned or converted on this device (API 34+). */
    public static boolean isHeicProcessingSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    public static boolean isHeicOutputSupported() {
        if (!isHeicProcessingSupported()) {
            return false;
        }
        try {
            Bitmap.CompressFormat.valueOf("HEIC");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isHeicFormat(@NonNull Bitmap.CompressFormat format) {
        String n = format.name();
        return "HEIC".equals(n) || "HEIF".equals(n);
    }

    public static int qualityForFormat(@NonNull Bitmap.CompressFormat format) {
        if (format == Bitmap.CompressFormat.PNG) {
            return 100;
        }
        if (format == Bitmap.CompressFormat.WEBP) {
            return 90;
        }
        if (isHeicFormat(format)) {
            return 90;
        }
        return 92;
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
            throw new IOException(HEIC_REQUIRES_API_34);
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
            @NonNull Bitmap bitmap,
            @NonNull Bitmap.CompressFormat format,
            @NonNull OutputStream os) {
        int q = qualityForFormat(format);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isHeicFormat(format)) {
            return bitmap.compress(format, q, os);
        }
        if (format == Bitmap.CompressFormat.WEBP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, os);
        }
        return bitmap.compress(format, q, os);
    }

    @NonNull
    private static ImageFormatSpec jpegSpec() {
        return new ImageFormatSpec(".jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, true);
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
        boolean supported = isHeicOutputSupported() && isHeicFormat(heic);
        return new ImageFormatSpec(".heic", "image/heic", heic, supported);
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
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            int sampleSize = calculateInSampleSize(bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            try (InputStream in = resolver.openInputStream(sourceUri)) {
                if (in == null) {
                    throw new IOException("Cannot open source");
                }
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
        } else {
            bitmap = null;
        }

        if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bitmap = decodeWithImageDecoder(resolver, sourceUri);
        }
        if (bitmap == null) {
            throw new IOException("Decode failed");
        }

        String safeName = sanitizeFileName(stripExtension(baseDisplayName));
        String outName = safeName + ext;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, outName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Redact");
        }

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri outUri = resolver.insert(collection, values);
        if (outUri == null) {
            bitmap.recycle();
            throw new IOException("MediaStore insert failed");
        }

        try (OutputStream os = resolver.openOutputStream(outUri)) {
            if (os == null) {
                resolver.delete(outUri, null, null);
                throw new IOException("Cannot open output stream");
            }
            int q = qualityForFormat(format);
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isHeicFormat(format)) {
                ok = bitmap.compress(format, q, os);
            } else if (format == Bitmap.CompressFormat.WEBP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, os);
            } else {
                ok = bitmap.compress(format, q, os);
            }
            if (!ok) {
                resolver.delete(outUri, null, null);
                throw new IOException("Compress failed");
            }
            os.flush();
        } finally {
            bitmap.recycle();
        }
        copyExifData(context, sourceUri, outUri);
        return outUri;
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private static Bitmap decodeWithImageDecoder(ContentResolver resolver, Uri sourceUri)
            throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(resolver, sourceUri);
        return ImageDecoder.decodeBitmap(
                source,
                (decoder, info, s) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int maxSide = Math.max(w, h);
                    if (maxSide > MAX_DIMENSION) {
                        float scale = MAX_DIMENSION / (float) maxSide;
                        decoder.setTargetSize(
                                Math.max(1, Math.round(w * scale)),
                                Math.max(1, Math.round(h * scale)));
                    }
                });
    }

    private static String stripExtension(String name) {
        if (name == null || name.isEmpty()) {
            return "converted";
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot);
        }
        return name;
    }

    private static String sanitizeFileName(String name) {
        String n = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (n.isEmpty()) {
            return "converted";
        }
        if (n.length() > 80) {
            return n.substring(0, 80);
        }
        return n;
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

    private static int calculateInSampleSize(BitmapFactory.Options options) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > MAX_DIMENSION || width > MAX_DIMENSION) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= MAX_DIMENSION
                    && (halfWidth / inSampleSize) >= MAX_DIMENSION) {
                inSampleSize *= 2;
            }
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
        try {
            return VideoMedia3Converter.transcodeToGallery(
                    context, sourceUri, baseDisplayName, formatIndex, progressListener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Video conversion interrupted", e);
        }
    }
}
