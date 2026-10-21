package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Saves a censored copy of a photo: decodes it upright at full size, covers the regions and
 * encodes a new file in the source's format. The copy is built from pixels only, so none of the
 * original's metadata comes along, including the small EXIF preview that would still show the
 * uncensored photo.
 */
public final class CensorExporter {

    /** The saved copy, and whether it had to be saved smaller than the original. */
    public static final class Result {
        @NonNull
        public final Uri uri;
        public final boolean downscaled;

        Result(@NonNull Uri uri, boolean downscaled) {
            this.uri = uri;
            this.downscaled = downscaled;
        }
    }

    private CensorExporter() {
    }

    /**
     * @param fileName the source's file name, used for the output's type and, when Keep Original
     *                 File Names is on, its name
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
                context, source, AppPreferences.getMaxBitmapSize(context)));
        boolean downscaled = sourceLongSide > 0
                && Math.max(bitmap.getWidth(), bitmap.getHeight()) < sourceLongSide;

        Uri out;
        boolean written = false;
        try {
            CensorEffects.apply(bitmap, regions, effect);
            String name = MediaFileNames.outputBaseName(context, fileName) + spec.extension;
            out = OutputDestination.create(context, false, name, spec.mimeType);
            try (OutputStream os = resolver.openOutputStream(out)) {
                if (os == null) {
                    throw new IOException("Cannot open output stream");
                }
                if (!FormatConverter.compressBitmapToStream(context, bitmap, spec.compressFormat, os)) {
                    throw new IOException("Compress failed");
                }
                os.flush();
                written = true;
            } finally {
                if (!written) {
                    OutputDestination.discard(resolver, out);
                }
            }
        } finally {
            bitmap.recycle();
        }
        OutputDestination.publish(resolver, out);
        return new Result(out, downscaled);
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
