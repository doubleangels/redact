package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Gainmap;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.CleanResult;
import com.doubleangels.redact.metadata.CleanStats;
import com.doubleangels.redact.metadata.MetadataStripper;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Saves a censored copy of a photo: decodes it upright at full size, covers the regions, cuts it
 * down to the crop, encodes the pixels in the source's format and saves them through the same
 * clean as the Clean tab. Only the tags the clean settings keep come back from the original, never
 * its small EXIF preview, which would still show the uncensored photo, and nothing of what the
 * crop leaves out: the pixels outside it, an Ultra HDR gain map's included, are not written.
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
        return export(context, source, fileName, regions, null, effect);
    }

    /**
     * {@link #export(Context, Uri, String, List, CensorEffects.Effect)}, keeping only {@code crop}
     * (in fractions of the upright photo, like the regions), or all of it when null.
     */
    @NonNull
    public static Result export(
            @NonNull Context context,
            @NonNull Uri source,
            @Nullable String fileName,
            @NonNull List<CensorRegion> regions,
            @Nullable CensorRegion crop,
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    censorGainMap(bitmap, regions);
                }
                // Cropped after covering, so the regions keep their place on the whole photo.
                Bitmap cropped = crop != null ? cropTo(bitmap, crop) : bitmap;
                if (cropped != bitmap) {
                    bitmap.recycle();
                    bitmap = cropped;
                }
                boolean png = spec.compressFormat == Bitmap.CompressFormat.PNG;
                // The 16-bit rewrite keeps the source's own rows, so it can't cut them down.
                boolean sixteenBit = png && crop == null
                        && writeSixteenBitPng(context, source, bitmap, regions, censored);
                if (!sixteenBit && png && HighPrecisionPng.isHighPrecision(bitmap)) {
                    // A turned 16-bit PNG, or a picture decoded at more than 8 bits some other
                    // way: its own samples can't be lined up with the picture, but the censored
                    // bitmap still holds them at full precision.
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(censored), 64 * 1024)) {
                        HighPrecisionPng.write(bitmap, os);
                    }
                    sixteenBit = true;
                }
                if (!sixteenBit) {
                    try (OutputStream os = new FileOutputStream(censored)) {
                        if (!FormatConverter.compressBitmapToStream(context, bitmap, spec.compressFormat, os,
                                FormatConverter.estimateJpegQuality(context, source),
                                FormatConverter.isLosslessImage(context, source))) {
                            throw new IOException("Compress failed");
                        }
                    }
                }
            } finally {
                bitmap.recycle();
            }
            String name = MediaFileNames.outputBaseName(context, fileName,
                    R.string.censor_file_name) + spec.extension;
            MetadataStripper stripper = new MetadataStripper(context);
            CleanResult saved = stripper.saveCensored(source, censored, name, spec);
            return new Result(saved.uri(), downscaled, saved.stats());
        } finally {
            if (!SecureDelete.secureDelete(censored)) {
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
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @VisibleForTesting
    static void censorGainMap(@NonNull Bitmap bitmap, @NonNull List<CensorRegion> regions) {
        if (!bitmap.hasGainmap()) {
            return;
        }
        Gainmap gainmap = bitmap.getGainmap();
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
        Canvas canvas = new Canvas(editable);
        Paint clear = new Paint();
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clear.setStyle(Paint.Style.FILL_AND_STROKE);
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

    /**
     * Writes a censored 16-bit PNG at 16 bits: the source's own samples everywhere but the
     * censored regions, which come from {@code censored}, the 8-bit picture the effects were drawn
     * on. {@link Bitmap#compress} would write the whole picture at 8 bits. The regions are padded
     * as for the gain map, so no edge pixel of an effect keeps its uncensored 16-bit value. False
     * when the source is not a 16-bit PNG shown as stored at full size, or the write fails, and
     * the caller encodes {@code censored} instead.
     */
    @VisibleForTesting
    static boolean writeSixteenBitPng(@NonNull Context context, @NonNull Uri source,
            @NonNull Bitmap censored, @NonNull List<CensorRegion> regions, @NonNull File target) {
        Png16.Header header = FormatConverter.sixteenBitPng(context, source);
        int width = censored.getWidth();
        int height = censored.getHeight();
        if (header == null || header.width != width || header.height != height) {
            return false;
        }
        Bitmap mask = null;
        try {
            mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            Canvas canvas = new Canvas(mask);
            Paint paint = new Paint();
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(Color.BLACK);
            for (CensorRegion region : regions) {
                if (!region.isEmpty()) {
                    canvas.drawPath(region.toPath(width, height), paint);
                }
            }
            Bitmap covered = mask;
            int[] maskRow = new int[width];
            try (InputStream in = context.getContentResolver().openInputStream(source);
                    OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 64 * 1024)) {
                if (in == null) {
                    return false;
                }
                Png16.rewrite(in, out, (y, argb, replace) -> {
                    covered.getPixels(maskRow, 0, width, 0, y, width, 1);
                    boolean any = false;
                    for (int x = 0; x < width; x++) {
                        if ((maskRow[x] >>> 24) != 0) {
                            replace[x] = true;
                            any = true;
                        }
                    }
                    if (any) {
                        censored.getPixels(argb, 0, width, 0, y, width, 1);
                    }
                    return any;
                });
            }
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            SentryManager.logEvent("Censor", 
                    "The censored 16-bit PNG could not be kept at 16 bits, so the app is saving it at 8: "
                            + e.getClass().getSimpleName() + ".");
            return false;
        } finally {
            if (mask != null) {
                mask.recycle();
            }
        }
    }

    /**
     * The pixels of {@code crop} (fractions of the photo, rounded out to whole pixels) in a new
     * bitmap, or {@code bitmap} itself when the crop is the whole photo. An Ultra HDR gain map is
     * cut to the same part; one that can't be is dropped, never kept whole.
     */
    @VisibleForTesting
    @NonNull
    static Bitmap cropTo(@NonNull Bitmap bitmap, @NonNull CensorRegion crop) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Rect r = cropRect(crop, width, height);
        if (r.left == 0 && r.top == 0 && r.right == width && r.bottom == height) {
            return bitmap;
        }
        // The gain map is taken off first and cut here, so what happens to it never depends on
        // what the platform's crop does with one.
        Gainmap gainmap = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) {
            gainmap = bitmap.getGainmap();
            bitmap.setGainmap(null);
        }
        Bitmap cropped;
        try {
            cropped = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height());
        } catch (OutOfMemoryError e) {
            throw new IOException("Not enough memory to crop the image", e);
        }
        if (cropped == bitmap) {
            // Only for a crop of the whole photo, which returned above; never write it uncut.
            throw new IOException("The image could not be cropped");
        }
        if (gainmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cropGainMap(gainmap, width, height, cropped, r);
        }
        return cropped;
    }

    /** {@code crop} in whole pixels of a {@code width} x {@code height} picture, at least one pixel. */
    @VisibleForTesting
    @NonNull
    static Rect cropRect(@NonNull CensorRegion crop, int width, int height) {
        float[] p = crop.points();
        int left = clamp((int) Math.floor(p[0] * width), 0, width - 1);
        int top = clamp((int) Math.floor(p[1] * height), 0, height - 1);
        int right = clamp((int) Math.ceil(p[2] * width), left + 1, width);
        int bottom = clamp((int) Math.ceil(p[3] * height), top + 1, height);
        return new Rect(left, top, right, bottom);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Gives {@code cropped} the part of {@code gainmap} under {@code r}, a part of the
     * {@code width} x {@code height} photo it belonged to. The gain map is a smaller picture of the
     * same scene, so kept whole it would still show what the crop left out on an HDR screen.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static void cropGainMap(@NonNull Gainmap gainmap, int width, int height,
                                    @NonNull Bitmap cropped, @NonNull Rect r) {
        try {
            Bitmap contents = gainmap.getGainmapContents();
            // The same part of the smaller picture, rounded out so its edge pixels are included.
            Rect g = cropRect(CensorRegion.rect(r.left / (float) width, r.top / (float) height,
                    r.right / (float) width, r.bottom / (float) height),
                    contents.getWidth(), contents.getHeight());
            Bitmap part = Bitmap.createBitmap(contents, g.left, g.top, g.width(), g.height());
            gainmap.setGainmapContents(part);
            cropped.setGainmap(gainmap);
        } catch (RuntimeException | OutOfMemoryError e) {
            SentryManager.logEvent("Censor", "The gain map could not be cropped, so the copy is saved without HDR: "
                    + e.getClass().getSimpleName() + ".");
            cropped.setGainmap(null);
        }
    }

    /** A bitmap that can be drawn on: decoders may return an immutable or hardware one. */
    @NonNull
    static Bitmap editable(@NonNull Bitmap bitmap) throws IOException {
        if (bitmap.isMutable() && bitmap.getConfig() != Bitmap.Config.HARDWARE) {
            return bitmap;
        }
        try {
            // A picture decoded at more than 8 bits is edited at that precision.
            Bitmap copy = bitmap.copy(HighPrecisionPng.isHighPrecision(bitmap)
                    ? bitmap.getConfig() : Bitmap.Config.ARGB_8888, true);
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
