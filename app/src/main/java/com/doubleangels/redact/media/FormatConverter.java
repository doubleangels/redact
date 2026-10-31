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
     * JPEG (0) is used instead. Video index 3 (AV1) is unchanged, so call only for image paths.
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

    /**
     * The quality for a JPEG made from a JPEG: on the High preset, never less than the source's
     * own, so a re-encode spends at least the bits the camera did (phones save at 95 to 97, the
     * preset is 92). Balanced and Smaller mean what they say, and other formats are unchanged.
     */
    @androidx.annotation.VisibleForTesting
    static int matchSourceQuality(@NonNull Bitmap.CompressFormat format, int preset, int presetQuality,
            int sourceJpegQuality) {
        if (format != Bitmap.CompressFormat.JPEG || preset != AppPreferences.QUALITY_PRESET_HIGH) {
            return presetQuality;
        }
        return Math.min(100, Math.max(presetQuality, sourceJpegQuality));
    }

    /** The IJG luminance quantization table that libjpeg's quality setting scales, in zigzag order. */
    private static final int[] STANDARD_LUMINANCE_ZIGZAG = {
            16, 11, 12, 14, 12, 10, 16, 14, 13, 14, 18, 17, 16, 19, 24, 40, 26, 24, 22, 22, 24, 49,
            35, 37, 29, 40, 58, 51, 61, 60, 57, 51, 56, 55, 64, 72, 92, 78, 64, 68, 87, 69, 55, 56,
            80, 109, 81, 87, 95, 98, 103, 104, 103, 62, 77, 113, 121, 112, 100, 120, 92, 101, 103, 99};

    /**
     * The quality a JPEG was saved at, estimated from its luminance quantization table the way
     * ImageMagick does: the libjpeg quality whose scaled standard table is closest. 0 when the
     * stream is not a JPEG or has no such table.
     */
    public static int estimateJpegQuality(@NonNull InputStream in) {
        try {
            java.io.DataInputStream data = new java.io.DataInputStream(new java.io.BufferedInputStream(in));
            if (data.readUnsignedByte() != 0xFF || data.readUnsignedByte() != 0xD8) {
                return 0;
            }
            while (true) {
                int b = data.readUnsignedByte();
                if (b != 0xFF) {
                    return 0;
                }
                int marker;
                do {
                    marker = data.readUnsignedByte();
                } while (marker == 0xFF);
                if (marker == 0xDA || marker == 0xD9) {
                    return 0;
                }
                int length = data.readUnsignedShort() - 2;
                if (length < 0) {
                    return 0;
                }
                if (marker != 0xDB) {
                    data.skipBytes(length);
                    continue;
                }
                while (length > 0) {
                    int info = data.readUnsignedByte();
                    boolean sixteenBit = (info >> 4) != 0;
                    int[] table = new int[64];
                    for (int i = 0; i < 64; i++) {
                        table[i] = sixteenBit ? data.readUnsignedShort() : data.readUnsignedByte();
                    }
                    length -= 1 + (sixteenBit ? 128 : 64);
                    if ((info & 0x0F) == 0) {
                        return closestQuality(table);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    @androidx.annotation.VisibleForTesting
    static int closestQuality(@NonNull int[] luminance) {
        int best = 0;
        long bestError = Long.MAX_VALUE;
        for (int quality = 1; quality <= 100; quality++) {
            int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
            long error = 0;
            for (int i = 0; i < 64; i++) {
                int expected = Math.max(1, Math.min(255, (STANDARD_LUMINANCE_ZIGZAG[i] * scale + 50) / 100));
                error += Math.abs(expected - luminance[i]);
            }
            if (error <= bestError) {
                bestError = error;
                best = quality;
            }
        }
        return best;
    }

    /** {@link #estimateJpegQuality(InputStream)} for a URI; 0 when it cannot be read. */
    public static int estimateJpegQuality(@NonNull Context context, @NonNull Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in != null ? estimateJpegQuality(in) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
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

    /**
     * The output format for a clean or a share, which rebuild a HEIC or AVIF around its coded
     * pictures instead of encoding it again: the source's own type, even where this device has
     * no encoder for it. Should the rebuild fail there, the clean falls back to a JPEG and names
     * it so ({@code MetadataStripper#writePictureOnly}). Other types are as
     * {@link #resolveImageFormat}.
     */
    @NonNull
    public static ImageFormatSpec resolveCleanFormat(
            @Nullable String extensionWithDot, @Nullable String mimeType) throws IOException {
        if (isHeicSource(extensionWithDot, mimeType)) {
            return isHeicProcessingSupported()
                    ? heicSpec()
                    : new ImageFormatSpec(".heic", "image/heic", Bitmap.CompressFormat.JPEG, false);
        }
        if (isAvifSource(extensionWithDot, mimeType)) {
            // Bitmap has no AVIF encoder, so only the rebuild keeps it AVIF.
            return new ImageFormatSpec(".avif", "image/avif", Bitmap.CompressFormat.JPEG, false);
        }
        if (".gif".equalsIgnoreCase(extensionWithDot) || "image/gif".equalsIgnoreCase(mimeType)) {
            // Rebuilt block by block, which keeps every frame (GifContainerMetadata).
            return new ImageFormatSpec(".gif", "image/gif", Bitmap.CompressFormat.PNG, false);
        }
        return resolveImageFormat(extensionWithDot, mimeType);
    }

    /**
     * {@link #resolveCleanFormat(String, String)} for a file whose bytes are at hand: what the
     * file is, by its first bytes, wins over what its name or type say, so a PNG named .jpg is
     * not re-encoded as a JPEG, and a share's snapshot, named by its type, is not misread.
     */
    @NonNull
    public static ImageFormatSpec resolveCleanFormat(
            @NonNull java.io.File content, @Nullable String extensionWithDot, @Nullable String mimeType)
            throws IOException {
        String sniffed = sniffImageType(content);
        if (sniffed != null) {
            return resolveCleanFormat(null, sniffed);
        }
        return resolveCleanFormat(extensionWithDot, mimeType);
    }

    /** The image type a file's first bytes say it is, or null when they say nothing known. */
    @androidx.annotation.VisibleForTesting
    @Nullable
    static String sniffImageType(@NonNull java.io.File file) {
        byte[] head = new byte[64];
        int n = 0;
        try (InputStream in = new java.io.FileInputStream(file)) {
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) {
                n += r;
            }
        } catch (IOException e) {
            return null;
        }
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (n >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return "image/png";
        }
        if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        if (n >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return "image/gif";
        }
        if (n >= 14 && head[0] == 'B' && head[1] == 'M') {
            return "image/bmp";
        }
        if (n >= 16 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            int size = Math.min(n, (int) (((head[0] & 0xFFL) << 24) | ((head[1] & 0xFF) << 16)
                    | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF)));
            boolean avif = false;
            boolean heif = false;
            for (int at = 8; at + 4 <= size; at += at == 8 ? 8 : 4) {
                String brand = new String(head, at, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                avif |= brand.equals("avif") || brand.equals("avis");
                heif |= brand.equals("heic") || brand.equals("heix") || brand.equals("mif1")
                        || brand.equals("msf1") || brand.startsWith("hev");
            }
            String major = new String(head, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (major.equals("avif") || major.equals("avis") || (avif && !major.startsWith("he"))) {
                return "image/avif";
            }
            if (heif) {
                return "image/heic";
            }
        }
        return null;
    }

    /**
     * The format a re-encode writes when the clean's own format has no encoder here: a GIF
     * becomes a PNG, which keeps every colour and its transparency, and anything else a JPEG.
     */
    @NonNull
    public static ImageFormatSpec encodableSpecFor(@NonNull ImageFormatSpec spec) {
        if (spec.bitmapFallbackSupported) {
            return spec;
        }
        return ".gif".equals(spec.extension) ? pngSpec() : jpegSpec();
    }

    @NonNull
    public static ImageFormatSpec pngSpec() {
        return new ImageFormatSpec(".png", "image/png", Bitmap.CompressFormat.PNG, true);
    }

    private static boolean isAvifSource(
            @Nullable String extensionWithDot, @Nullable String mimeType) {
        if (extensionWithDot != null && ".avif".equalsIgnoreCase(extensionWithDot)) {
            return true;
        }
        return mimeType != null && "image/avif".equalsIgnoreCase(mimeType);
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
        return compressBitmapToStream(context, bitmap, format, os, 0, false);
    }

    /**
     * Like {@link #compressBitmapToStream(Context, Bitmap, Bitmap.CompressFormat, OutputStream)},
     * for a picture decoded from a JPEG of {@code sourceJpegQuality} (0 when unknown or not a
     * JPEG), or from a lossless source ({@code losslessSource}: PNG, GIF, BMP or lossless WebP).
     *
     * <ul>
     *   <li>A JPEG made from a JPEG is never encoded below the source's own quality on High.
     *   <li>A WebP made from a lossless source is lossless too, so no pixel changes.
     *   <li>JPEG and HEIC hold no transparency, so a transparent picture is laid on white, as
     *       image editors do, instead of its clear pixels turning black.
     * </ul>
     */
    public static boolean compressBitmapToStream(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            @NonNull Bitmap.CompressFormat format,
            @NonNull OutputStream os,
            int sourceJpegQuality,
            boolean losslessSource) {
        int q = matchSourceQuality(format, AppPreferences.getImageQualityPreset(context),
                qualityForFormat(format, context), sourceJpegQuality);
        boolean opaqueFormat = format == Bitmap.CompressFormat.JPEG || isHeicFormat(format);
        Bitmap toWrite = opaqueFormat ? onWhite(bitmap) : bitmap;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isHeicFormat(format)) {
                return toWrite.compress(format, q, os);
            }
            if (format == Bitmap.CompressFormat.WEBP) {
                return losslessSource
                        ? toWrite.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_WEBP_EFFORT, os)
                        : toWrite.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, os);
            }
            return toWrite.compress(format, q, os);
        } finally {
            if (toWrite != bitmap) {
                toWrite.recycle();
            }
        }
    }

    /** For lossless WebP the quality is how hard the encoder works on size, not what it keeps. */
    private static final int LOSSLESS_WEBP_EFFORT = 75;

    /** {@code bitmap} laid on white when it has transparency; {@code bitmap} itself otherwise. */
    @androidx.annotation.VisibleForTesting
    @NonNull
    static Bitmap onWhite(@NonNull Bitmap bitmap) {
        if (!bitmap.hasAlpha()) {
            return bitmap;
        }
        android.graphics.ColorSpace colorSpace = bitmap.getColorSpace();
        Bitmap flat = colorSpace != null
                ? Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888,
                        false, colorSpace)
                : Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(flat);
        canvas.drawColor(android.graphics.Color.WHITE);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        return flat;
    }

    /**
     * Whether an image's encoding keeps every pixel exactly: PNG, GIF, BMP and lossless WebP.
     * Read from its first bytes; false when unknown.
     */
    @androidx.annotation.VisibleForTesting
    static boolean isLosslessImage(@NonNull InputStream in) {
        try {
            byte[] head = new byte[64 * 1024];
            int n = 0;
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) {
                n += r;
            }
            if (n >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return true;
            }
            if (n >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
                return true;
            }
            if (n >= 2 && head[0] == 'B' && head[1] == 'M') {
                return true;
            }
            if (n >= 16 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                // The first picture chunk says which: VP8L is lossless, VP8 (with ALPH) is not.
                for (int at = 12; at + 4 <= n; at++) {
                    if (head[at] == 'V' && head[at + 1] == 'P' && head[at + 2] == '8') {
                        if (head[at + 3] == 'L') {
                            return true;
                        }
                        if (head[at + 3] == ' ') {
                            return false;
                        }
                    }
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@link #isLosslessImage(InputStream)} for a URI; false when it cannot be read. */
    public static boolean isLosslessImage(@NonNull Context context, @NonNull Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in != null && isLosslessImage(in);
        } catch (IOException | RuntimeException e) {
            return false;
        }
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
            // Lossless sources Bitmap can't write back: PNG keeps every pixel.
            case ".gif", ".bmp" -> pngSpec();
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
            case "image/gif", "image/bmp", "image/x-ms-bmp" -> pngSpec();
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
     * @param baseDisplayName the source's file name; the output keeps it (with the output type's
     *                        extension) only when Keep Original File Names is on
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

        Bitmap bitmap = decodeUpright(context, sourceUri, FULL_RESOLUTION);

        String outName = MediaFileNames.outputBaseName(context, baseDisplayName) + ext;

        Uri outUri;
        try {
            outUri = OutputDestination.create(context, false, outName, mime);
        } catch (IOException e) {
            bitmap.recycle();
            throw e;
        }

        boolean written = false;
        try (OutputStream os = resolver.openOutputStream(outUri)) {
            if (os == null) {
                throw new IOException("Cannot open output stream");
            }
            boolean ok = compressBitmapToStream(context, bitmap, format, os,
                    estimateJpegQuality(context, sourceUri), isLosslessImage(context, sourceUri));
            if (!ok) {
                throw new IOException("Compress failed");
            }
            os.flush();
            written = true;
        } finally {
            bitmap.recycle();
            if (!written) {
                // Whatever went wrong (including the stream throwing), leave no partial file.
                OutputDestination.discard(resolver, outUri);
            }
        }
        // Re-encoding from decoded pixels drops all source metadata (EXIF, XMP, IPTC, ICC
        // thumbnails); intentionally nothing is copied back.
        OutputDestination.publish(resolver, outUri);
        return outUri;
    }

    /**
     * Decodes the image at {@code sourceUri} with its EXIF orientation baked into the pixels, no
     * larger than about {@code maxDimension} on its longest side and within what the encoders
     * accept. Halves the size again when the device runs out of memory.
     *
     * @throws UnsupportedMediaException when the bytes are not an image this device can decode
     */
    @NonNull
    public static Bitmap decodeUpright(
            @NonNull Context context, @NonNull Uri sourceUri, int maxDimension) throws IOException {
        ContentResolver resolver = context.getContentResolver();
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
            bitmap = decodeSampled(calculateInSampleSize(maxDimension, fullResolutionPixelBudget(context), bounds), opts -> {
                try (InputStream in = resolver.openInputStream(sourceUri)) {
                    if (in == null) {
                        throw new IOException("Cannot open source");
                    }
                    return BitmapFactory.decodeStream(in, null, opts);
                }
            });
            needsOrientationFix = bitmap != null;
        } else {
            bitmap = null;
        }

        if (bitmap == null) {
            // ImageDecoder already applies EXIF orientation to the pixels.
            bitmap = decodeBitmapFromUri(context, sourceUri, maxDimension);
        }
        if (bitmap == null) {
            throw new UnsupportedMediaException("Decode failed", null);
        }
        if (needsOrientationFix) {
            // The output carries no EXIF at all, so bake the rotation into the pixels.
            bitmap = applyExifOrientation(context, sourceUri, bitmap);
        }
        return bitmap;
    }

    /**
     * Decodes a bitmap from a URI, using {@link ImageDecoder} when {@link BitmapFactory} cannot.
     */
    @Nullable
    public static Bitmap decodeBitmapFromUri(@NonNull Context context, @NonNull Uri sourceUri)
            throws IOException {
        return decodeBitmapFromUri(context, sourceUri, FULL_RESOLUTION);
    }

    @Nullable
    private static Bitmap decodeBitmapFromUri(
            @NonNull Context context, @NonNull Uri sourceUri, int maxDimension) throws IOException {
        try {
            return decodeWithImageDecoder(context, sourceUri, maxDimension, fullResolutionPixelBudget(context));
        } catch (OutOfMemoryError e) {
            // Already scaled to the max bitmap size; fail this file rather than the app.
            throw new IOException("Not enough memory to decode the image", e);
        } catch (ImageDecoder.DecodeException e) {
            // SOURCE_EXCEPTION means the bytes could not be read (a real I/O failure). Any other
            // error means the bytes were read but are not an image this device can decode.
            if (e.getError() == ImageDecoder.DecodeException.SOURCE_EXCEPTION) {
                throw e;
            }
            throw new UnsupportedMediaException("Unsupported image format", e);
        }
    }

    private static Bitmap decodeWithImageDecoder(
            Context context, Uri sourceUri, int maxDimension, long maxPixels) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), sourceUri);
        return ImageDecoder.decodeBitmap(
                source,
                (decoder, info, s) -> {
                    // A hardware bitmap (the default) cannot be drawn on a software canvas, which
                    // Censor's previews and exports need.
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int maxSide = Math.max(w, h);
                    float scale = 1f;
                    if (maxDimension > 0 && maxSide > maxDimension) {
                        scale = maxDimension / (float) maxSide;
                    } else if (maxDimension <= 0 && (long) w * h > maxPixels) {
                        // Full resolution that won't fit: the largest size that does, exactly.
                        scale = (float) Math.sqrt((double) maxPixels / ((long) w * h));
                    }
                    if (scale < 1f) {
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

    /** How many times a decode that ran out of memory is retried at half the size. */
    private static final int MAX_OUT_OF_MEMORY_RETRIES = 3;

    /** One {@link BitmapFactory} decode with the given options. */
    public interface SampledDecode {
        @Nullable
        Bitmap decode(@NonNull BitmapFactory.Options options) throws IOException;
    }

    /**
     * Decodes at {@code inSampleSize}, halving the size again whenever the device runs out of
     * memory, so a huge image (a panorama, say) is saved smaller instead of crashing the app.
     * Returns null when the bytes cannot be decoded at all.
     *
     * @throws IOException when even the smallest retry runs out of memory
     */
    @Nullable
    public static Bitmap decodeSampled(int inSampleSize, @NonNull SampledDecode decode) throws IOException {
        int sampleSize = Math.max(1, inSampleSize);
        for (int attempt = 0; ; attempt++) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            try {
                return decode.decode(options);
            } catch (OutOfMemoryError e) {
                if (attempt >= MAX_OUT_OF_MEMORY_RETRIES) {
                    throw new IOException("Not enough memory to decode the image", e);
                }
                sampleSize *= 2;
            }
        }
    }

    private static final int MAX_ENCODER_DIMENSION = 16383;

    /**
     * The size limit for a picture that is saved: none but the device's free memory, so every
     * pixel is kept that can be. Only on-screen previews pass a size.
     */
    public static final int FULL_RESOLUTION = 0;

    /** Bytes a decoded pixel takes (ARGB_8888). */
    private static final int BYTES_PER_PIXEL = 4;
    /** The least a full-resolution decode is allowed, whatever the memory reading says: 24 MP. */
    private static final long MIN_FULL_RESOLUTION_PIXELS = 24_000_000L;

    /**
     * How many pixels a full-resolution decode may hold: a quarter of the memory the system says
     * is free, as the picture may be copied once while it is edited and encoded. Bitmaps live in
     * native memory, so this, not the Java heap, is the limit. A decode that still runs out is
     * retried smaller ({@link #decodeSampled}).
     */
    public static long fullResolutionPixelBudget(@NonNull Context context) {
        long budget = MIN_FULL_RESOLUTION_PIXELS;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                budget = Math.max(budget, info.availMem / 4 / BYTES_PER_PIXEL);
            }
        } catch (RuntimeException e) {
            // The floor above still applies.
        }
        return budget;
    }

    public static int calculateInSampleSize(Context context, BitmapFactory.Options options) {
        return calculateInSampleSize(FULL_RESOLUTION, fullResolutionPixelBudget(context), options);
    }

    static int calculateInSampleSize(int maxDimension, BitmapFactory.Options options) {
        return calculateInSampleSize(maxDimension, Long.MAX_VALUE, options);
    }

    /**
     * The sample size to decode at. With a resolution set, both sides stay at least that large;
     * at full resolution ({@code maxDimension <= 0}) the picture is decoded whole unless it would
     * not fit in {@code maxPixels}.
     */
    static int calculateInSampleSize(int maxDimension, long maxPixels, BitmapFactory.Options options) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (maxDimension <= 0) {
            while ((long) ((width + inSampleSize - 1) / inSampleSize)
                    * ((height + inSampleSize - 1) / inSampleSize) > maxPixels) {
                inSampleSize *= 2;
            }
        } else if (height > maxDimension || width > maxDimension) {
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
     * @param cancelled polled before the transcode starts, so a conversion cancelled while it was
     *     queued does not start at all
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
        File outFile = null;
        try {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new ProcessingCancelledException("Video conversion cancelled");
            }
            // Transcoded straight from the source: Media3 is kept from writing any metadata
            // (VideoMedia3Converter.MetadataDroppingMuxerFactory), and the result is verified the
            // same way the Clean tab's is.
            outFile = File.createTempFile(
                    "vid_transform_",
                    VideoMedia3Converter.TRANSCODE_EXTENSION,
                    context.getApplicationContext().getCacheDir());
            int actualFormat = VideoMedia3Converter.transcodeToPath(
                    context.getApplicationContext(),
                    sourceUri,
                    outFile.getAbsolutePath(),
                    formatIndex,
                    progressListener,
                    transcodeOwnerId);
            if (outActualFormatIndex != null && outActualFormatIndex.length > 0) {
                outActualFormatIndex[0] = actualFormat;
            }
            stripper.requireConvertedVideoClean(outFile, sourceUri);
            return VideoMedia3Converter.copyToMoviesRedact(
                    context, outFile, baseDisplayName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingCancelledException("Video conversion interrupted", e);
        } finally {
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
