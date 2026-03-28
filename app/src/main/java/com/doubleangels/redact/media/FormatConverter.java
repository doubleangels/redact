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

    private FormatConverter() {
    }

    /** Number of selectable output format options (chips). */
    public static final int FORMAT_OPTION_COUNT = 4;

    @NonNull
    public static Bitmap.CompressFormat formatAtIndex(int index) {
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

    public static boolean isHeicOutputSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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

        if (isHeicFormat(format) && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            throw new IOException("heic_requires_api_34");
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
