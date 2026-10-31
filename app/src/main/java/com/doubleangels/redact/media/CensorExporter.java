package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.metadata.CleanStats;
import com.doubleangels.redact.metadata.MetadataStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Saves a censored copy of a photo: decodes it upright at full size, covers the regions, encodes
 * the pixels in the source's format and saves them through the same clean as the Clean tab. Only
 * the tags the clean settings keep come back from the original, never its small EXIF preview,
 * which would still show the uncensored photo.
 */
public final class CensorExporter {

    /** The saved copy, whether it had to be saved smaller than the original, and what was removed. */
    public static final class Result {
        @NonNull
        public final Uri uri;
        public final boolean downscaled;
        @Nullable
        public final CleanStats stats;

        Result(@NonNull Uri uri, boolean downscaled, @Nullable CleanStats stats) {
            this.uri = uri;
            this.downscaled = downscaled;
            this.stats = stats;
        }
    }

    private CensorExporter() {
    }

    /**
     * @param fileName the source's file name, used for the output's type and, when Keep Original
     *                 File Names is on, its name with "(censored)" added
     */
    @NonNull
    public static Result export(
            @NonNull Context context,
            @NonNull Uri source,
            @Nullable String fileName,
            @NonNull List<CensorRegion> regions,
            @NonNull CensorEffects.Effect effect) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        FormatConverter.ImageFormatSpec spec =
                FormatConverter.resolveImageFormat(extensionOf(fileName), resolver.getType(source));
        int sourceLongSide = longSideOf(resolver, source);

        Bitmap bitmap = editable(FormatConverter.decodeUpright(
                context, source, FormatConverter.FULL_RESOLUTION));
        boolean downscaled = sourceLongSide > 0
                && Math.max(bitmap.getWidth(), bitmap.getHeight()) < sourceLongSide;

        // The censored pixels, encoded privately before the clean saves them.
        File censored = File.createTempFile("censored_", spec.extension, context.getCacheDir());
        try {
            try {
                CensorEffects.apply(bitmap, regions, effect);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    censorGainMap(bitmap, regions);
                }
                try (OutputStream os = new FileOutputStream(censored)) {
                    if (!FormatConverter.compressBitmapToStream(context, bitmap, spec.compressFormat, os,
                            FormatConverter.estimateJpegQuality(context, source),
                            FormatConverter.isLosslessImage(context, source))) {
                        throw new IOException("Compress failed");
                    }
                }
            } finally {
                bitmap.recycle();
            }
            String name = MediaFileNames.outputBaseName(context, fileName,
                    com.doubleangels.redact.R.string.censor_file_name) + spec.extension;
            MetadataStripper stripper = new MetadataStripper(context);
            Uri out = stripper.saveCensoredImage(source, censored, name, spec);
            return new Result(out, downscaled, stripper.getLastCleanStats());
        } finally {
            if (!SecureDelete.secureDelete(context, censored)) {
                censored.deleteOnExit();
            }
        }
    }

    /**
     * Blanks the censored regions in an Ultra HDR photo's gain map too. The gain map is a smaller
     * picture of where the scene is bright, so left alone it would still show the outline of a
     * censored face or the shape of censored text on an HDR screen. Zero there means no extra
     * brightness, so those regions just show at their SDR level. The edge is padded, so gain map
     * pixels that only partly overlap a region are covered.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @androidx.annotation.VisibleForTesting
    static void censorGainMap(@NonNull Bitmap bitmap, @NonNull List<CensorRegion> regions) {
        if (!bitmap.hasGainmap()) {
            return;
        }
        android.graphics.Gainmap gainmap = bitmap.getGainmap();
        Bitmap contents = gainmap.getGainmapContents();
        Bitmap editable = contents.isMutable() && contents.getConfig() != Bitmap.Config.HARDWARE
                ? contents
                : contents.copy(contents.getConfig() == Bitmap.Config.ALPHA_8
                        ? Bitmap.Config.ALPHA_8 : Bitmap.Config.ARGB_8888, true);
        if (editable == null) {
            // Rather than keep what could show the censored content, keep no HDR at all.
            bitmap.setGainmap(null);
            return;
        }
        android.graphics.Canvas canvas = new android.graphics.Canvas(editable);
        android.graphics.Paint clear = new android.graphics.Paint();
        clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        clear.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
        clear.setStrokeWidth(2f);
        for (CensorRegion region : regions) {
            if (!region.isEmpty()) {
                canvas.drawPath(region.toPath(editable.getWidth(), editable.getHeight()), clear);
            }
        }
        if (editable != contents) {
            gainmap.setGainmapContents(editable);
            bitmap.setGainmap(gainmap);
        }
    }

    /** A bitmap that can be drawn on: decoders may return an immutable or hardware one. */
    @NonNull
    static Bitmap editable(@NonNull Bitmap bitmap) throws IOException {
        if (bitmap.isMutable() && bitmap.getConfig() != Bitmap.Config.HARDWARE) {
            return bitmap;
        }
        try {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (copy == null) {
                throw new IOException("Cannot copy the image for editing");
            }
            return copy;
        } catch (OutOfMemoryError e) {
            throw new IOException("Not enough memory to edit the image", e);
        } finally {
            bitmap.recycle();
        }
    }

    /** The source's longer side in pixels, or 0 when only ImageDecoder can read it. */
    private static int longSideOf(@NonNull ContentResolver resolver, @NonNull Uri source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(source)) {
            if (in != null) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
        } catch (IOException | SecurityException e) {
            return 0;
        }
        return Math.max(0, Math.max(bounds.outWidth, bounds.outHeight));
    }

    @Nullable
    static String extensionOf(@Nullable String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : null;
    }
}
