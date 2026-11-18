package com.doubleangels.redact.media;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.sentry.AppMetrics;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Private copies of files another app shares into Redact. A share's URIs can stop working at any
 * moment (the sending app's grant ends with its task), so each item is copied first and the clean
 * works from the copy. The copies hold the original media, metadata and all, so they are only ever
 * removed with {@link SecureDelete}.
 *
 * <p>Needs only the application's context: nothing here belongs to the screen that started it.
 */
public final class InboundSnapshots {

    /** The name every snapshot starts with, which the cache sweeps look for. */
    public static final String PREFIX = "inbound_";

    private InboundSnapshots() {
    }

    /**
     * Copies each of {@code uris} into the cache, in order, until they are done or
     * {@code stillWanted} says the share was cancelled. Each copy goes to {@code keep}, which
     * refuses it once the share's snapshots were already handed over for deletion; a refused copy
     * is deleted here and the copying stops. An item that can't be read is left out and counted in
     * {@code failed}, as the clean would only fail on it the same way at every later step.
     *
     * @return the copies, as file URIs, in the order of {@code uris}
     */
    @NonNull
    public static List<Uri> copyAll(@NonNull Context app, @NonNull List<Uri> uris,
                                    @NonNull BooleanSupplier stillWanted, @NonNull Predicate<File> keep,
                                    @NonNull AtomicInteger failed) {
        List<Uri> copies = new ArrayList<>();
        failed.set(0);
        for (Uri uri : uris) {
            if (!stillWanted.getAsBoolean()) {
                break;
            }
            try {
                File snapshot = copy(app, uri, stillWanted);
                if (!stillWanted.getAsBoolean() || !keep.test(snapshot)) {
                    // The share was cancelled during the copy and its cleanup has already run,
                    // so nothing else would delete this one.
                    SecureDelete.secureDelete(snapshot);
                    break;
                }
                copies.add(Uri.fromFile(snapshot));
            } catch (IOException e) {
                if (!stillWanted.getAsBoolean()) {
                    break;
                }
                failed.incrementAndGet();
                AppMetrics.snapshotFailure(
                        e instanceof FileNotFoundException ? "not_found" : e.getClass().getSimpleName());
                if (e instanceof FileNotFoundException) {
                    // The sharing app handed over a URI whose item no longer exists (deleted, or a
                    // stale cloud or gallery entry). Nothing on our side to fix.
                    SentryManager.logEvent("Share", "An inbound item no longer exists and was skipped.");
                } else {
                    SentryManager.recordException(e);
                }
            }
        }
        return copies;
    }

    /** Copies one shared item into the cache, giving up as soon as {@code stillWanted} turns false. */
    @NonNull
    static File copy(@NonNull Context app, @NonNull Uri uri, @NonNull BooleanSupplier stillWanted)
            throws IOException {
        String type;
        try {
            type = app.getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            type = null;
        }
        // The suffix is all the clean has to go on later (the copy has no type), and it picks the
        // output's format: naming every image .jpg turned PNGs, WebPs and HEICs into JPEGs, and a
        // PNG's transparency into black.
        String suffix = suffixFor(type, MediaUriResolver.readDisplayName(app, uri));
        File dest = new File(app.getCacheDir(), PREFIX + System.nanoTime() + suffix);
        try (InputStream in = app.getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("Cannot open inbound stream");
            }
            copyWhileWanted(in, out, stillWanted);
        } catch (IOException e) {
            // A partial copy still holds part of the original.
            SecureDelete.secureDelete(dest);
            throw e;
        }
        return dest;
    }

    private static void copyWhileWanted(@NonNull InputStream in, @NonNull OutputStream out,
                                        @NonNull BooleanSupplier stillWanted) throws IOException {
        try {
            MediaSizeLimits.copyStream(in, out, () -> {
                if (!stillWanted.getAsBoolean()) {
                    throw new UncheckedIOException(new ProcessingCancelledException("Share cancelled"));
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Securely deletes {@code snapshots} on {@code executor}, so a large video never blocks the caller. */
    public static void deleteAll(@NonNull List<File> snapshots, @NonNull Executor executor) {
        if (snapshots.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            for (File file : snapshots) {
                if (file != null && file.exists() && !SecureDelete.secureDelete(file)) {
                    file.deleteOnExit();
                }
            }
        });
    }

    /**
     * The suffix for a snapshot of a shared item: the one its type names, else the extension of its
     * name when that is a media extension Redact recognizes, else {@code .bin}.
     */
    @NonNull
    public static String suffixFor(@Nullable String mimeType, @Nullable String name) {
        if (mimeType != null) {
            switch (mimeType.toLowerCase(Locale.US)) {
                case "image/jpeg":
                case "image/jpg":
                    return ".jpg";
                case "image/png":
                    return ".png";
                case "image/webp":
                    return ".webp";
                case "image/heic":
                    return ".heic";
                case "image/heif":
                    return ".heif";
                case "image/avif":
                    return ".avif";
                case "image/gif":
                    return ".gif";
                case "image/bmp":
                    return ".bmp";
                case "video/mp4":
                    return ".mp4";
                case "video/quicktime":
                    return ".mov";
                case "video/webm":
                    return ".webm";
                case "video/x-matroska":
                    return ".mkv";
                case "video/3gpp":
                    return ".3gp";
                default:
                    break;
            }
        }
        String lower = name != null ? name.toLowerCase(Locale.US) : "";
        int dot = lower.lastIndexOf('.');
        String extension = dot >= 0 ? lower.substring(dot) : "";
        switch (extension) {
            case ".jpeg":
                return ".jpg";
            case ".jpg":
            case ".png":
            case ".webp":
            case ".heic":
            case ".heif":
            case ".avif":
            case ".gif":
            case ".bmp":
            case ".mp4":
            case ".m4v":
            case ".mov":
            case ".webm":
            case ".mkv":
            case ".3gp":
            case ".avi":
                return extension;
            default:
                break;
        }
        // A type with no known extension still says which decoder the item needs.
        if (mimeType != null && mimeType.startsWith("video/")) {
            return ".mp4";
        }
        if (mimeType != null && mimeType.startsWith("image/")) {
            return ".jpg";
        }
        return ".bin";
    }
}
