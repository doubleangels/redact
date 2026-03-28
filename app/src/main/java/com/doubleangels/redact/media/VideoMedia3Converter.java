package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultMuxer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.Nullable;

/**
 * Transcodes video with Jetpack Media3 {@link Transformer}, then copies the result into
 * {@code Movies/Redact} as MP4. Format index matches {@link FormatConverter#FORMAT_OPTION_COUNT}:
 * H.264, H.265, VP9, or AV1 (device-dependent).
 */
public final class VideoMedia3Converter {

    /** H.264 / AAC MP4 — used for Clean metadata stripping (broad device support, audio preserved). */
    public static final int FORMAT_STRIP_METADATA = 0;

    /** 0–100 while Media3 reports export progress (main thread). */
    public interface TranscodeProgressListener {
        void onProgress(int percent);
    }

    private static final int TARGET_FPS = 30;
    private static final long AWAIT_TIMEOUT_MINUTES = 60;

    private VideoMedia3Converter() {
    }

    /**
     * Runs {@link Transformer} to {@code outputPath} (MP4).
     */
    public static void transcodeToPath(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            int formatIndex)
            throws IOException, InterruptedException {
        transcodeToPath(context, sourceUri, outputPath, formatIndex, null);
    }

    public static void transcodeToPath(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            int formatIndex,
            @Nullable TranscodeProgressListener progressListener)
            throws IOException, InterruptedException {

        File outFile = new File(outputPath);
        Context app = context.getApplicationContext();

        IOException lastFailure = null;
        for (int attemptIndex : encoderFallbackOrder(formatIndex)) {
            deleteQuietly(outFile);
            try {
                String videoMime = videoMimeTypeForFormatIndex(attemptIndex);
                transcodeToPathOnce(
                        app,
                        sourceUri,
                        outputPath,
                        videoMime,
                        hdrModeForMp4VideoMime(videoMime),
                        progressListener);
                return;
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IOException("Video conversion failed");
    }

    /**
     * Preferred encoder order when the user-selected MIME fails (common for AV1/VP9 at 4K or on OEM limits).
     */
    private static int[] encoderFallbackOrder(int requestedFormatIndex) {
        switch (requestedFormatIndex) {
            case 3:
                return new int[] {3, 1, 0};
            case 2:
                return new int[] {2, 1, 0};
            case 1:
                return new int[] {1, 0};
            case 0:
            default:
                return new int[] {0};
        }
    }

    /**
     * VP8/VP9 outputs cannot carry the same HDR as HEVC; tone-map HDR sources to SDR so the encoder
     * and muxer agree on a supported format.
     */
    private static int hdrModeForMp4VideoMime(@NonNull String videoMimeType) {
        if (MimeTypes.VIDEO_VP9.equals(videoMimeType) || MimeTypes.VIDEO_VP8.equals(videoMimeType)) {
            return Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL;
        }
        return Composition.HDR_MODE_KEEP_HDR;
    }

    private static void transcodeToPathOnce(
            @NonNull Context app,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            @NonNull String videoMimeType,
            int hdrMode,
            @Nullable TranscodeProgressListener progressListener)
            throws IOException, InterruptedException {

        File outFile = new File(outputPath);

        MediaItem mediaItem = MediaItem.fromUri(sourceUri);
        EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem).setFrameRate(TARGET_FPS).build();

        EditedMediaItemSequence sequence =
                new EditedMediaItemSequence.Builder(
                                ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                        .addItem(editedMediaItem)
                        .build();

        Composition composition = new Composition.Builder(sequence).setHdrMode(hdrMode).build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ExportException> errorRef = new AtomicReference<>();
        AtomicReference<Throwable> syncFailureRef = new AtomicReference<>();
        AtomicReference<Transformer> transformerRef = new AtomicReference<>();

        Handler mainHandler = new Handler(Looper.getMainLooper());
        AtomicBoolean pollingActive = new AtomicBoolean(progressListener != null);
        final Runnable[] pollRunnable = new Runnable[1];
        pollRunnable[0] =
                () -> {
                    if (!pollingActive.get()) {
                        return;
                    }
                    Transformer t = transformerRef.get();
                    if (t != null && progressListener != null) {
                        ProgressHolder holder = new ProgressHolder();
                        int state = t.getProgress(holder);
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            progressListener.onProgress(holder.progress);
                        }
                    }
                    mainHandler.postDelayed(pollRunnable[0], 100);
                };

        mainHandler.post(
                () -> {
                    try {
                        Transformer transformer =
                                new Transformer.Builder(app)
                                        .setMuxerFactory(new DefaultMuxer.Factory())
                                        .setVideoMimeType(videoMimeType)
                                        .addListener(
                                                new Transformer.Listener() {
                                                    @Override
                                                    public void onCompleted(
                                                            Composition composition,
                                                            ExportResult exportResult) {
                                                        stopPolling(
                                                                mainHandler,
                                                                pollingActive,
                                                                pollRunnable[0]);
                                                        latch.countDown();
                                                    }

                                                    @Override
                                                    public void onError(
                                                            Composition composition,
                                                            ExportResult exportResult,
                                                            ExportException exception) {
                                                        stopPolling(
                                                                mainHandler,
                                                                pollingActive,
                                                                pollRunnable[0]);
                                                        errorRef.set(exception);
                                                        latch.countDown();
                                                    }
                                                })
                                        .build();
                        transformerRef.set(transformer);
                        transformer.start(composition, outputPath);
                        if (progressListener != null) {
                            mainHandler.post(pollRunnable[0]);
                        }
                    } catch (RuntimeException e) {
                        stopPolling(mainHandler, pollingActive, pollRunnable[0]);
                        syncFailureRef.set(e);
                        latch.countDown();
                    }
                });

        boolean finished = latch.await(AWAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            CountDownLatch cancelDone = new CountDownLatch(1);
            mainHandler.post(
                    () -> {
                        stopPolling(mainHandler, pollingActive, pollRunnable[0]);
                        @Nullable Transformer t = transformerRef.get();
                        if (t != null) {
                            t.cancel();
                        }
                        cancelDone.countDown();
                    });
            cancelDone.await(5, TimeUnit.SECONDS);
            deleteQuietly(outFile);
            throw new IOException("Video conversion timed out");
        }

        ExportException exportException = errorRef.get();
        if (exportException != null) {
            deleteQuietly(outFile);
            throw new IOException(
                    "Video conversion failed: " + exportException.getMessage(), exportException);
        }
        Throwable syncFailure = syncFailureRef.get();
        if (syncFailure != null) {
            deleteQuietly(outFile);
            throw new IOException(
                    "Video conversion failed: " + syncFailure.getMessage(), syncFailure);
        }

        if (!outFile.exists() || outFile.length() == 0) {
            deleteQuietly(outFile);
            throw new IOException("Video conversion produced no output");
        }
    }

    private static void stopPolling(
            Handler mainHandler, AtomicBoolean pollingActive, @Nullable Runnable pollRunnable) {
        pollingActive.set(false);
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }
    }

    /**
     * Transcodes into an existing file path (e.g. cache for sharing). Parent directories are created if needed.
     */
    public static void transcodeToFile(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull File outputFile,
            int formatIndex)
            throws IOException, InterruptedException {
        transcodeToFile(context, sourceUri, outputFile, formatIndex, null);
    }

    public static void transcodeToFile(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull File outputFile,
            int formatIndex,
            @Nullable TranscodeProgressListener progressListener)
            throws IOException, InterruptedException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("Failed to create output directory");
            }
        }
        transcodeToPath(context, sourceUri, outputFile.getAbsolutePath(), formatIndex, progressListener);
    }

    @NonNull
    public static Uri transcodeToGallery(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String baseDisplayName,
            int formatIndex) throws IOException, InterruptedException {
        return transcodeToGallery(context, sourceUri, baseDisplayName, formatIndex, null);
    }

    @NonNull
    public static Uri transcodeToGallery(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String baseDisplayName,
            int formatIndex,
            @Nullable TranscodeProgressListener progressListener)
            throws IOException, InterruptedException {

        Context app = context.getApplicationContext();
        File outFile =
                new File(app.getCacheDir(), "vid_transform_" + System.currentTimeMillis() + ".mp4");
        transcodeToPath(app, sourceUri, outFile.getAbsolutePath(), formatIndex, progressListener);

        try {
            return copyToMoviesRedact(app, outFile, baseDisplayName);
        } finally {
            deleteQuietly(outFile);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Maps format chip index to output video MIME (MP4). */
    @NonNull
    private static String videoMimeTypeForFormatIndex(int formatIndex) {
        switch (formatIndex) {
            case 1:
                return MimeTypes.VIDEO_H265;
            case 2:
                return MimeTypes.VIDEO_VP9;
            case 3:
                return MimeTypes.VIDEO_AV1;
            case 0:
            default:
                return MimeTypes.VIDEO_H264;
        }
    }

    @NonNull
    private static Uri copyToMoviesRedact(Context context, File file, String baseDisplayName)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String safeName = sanitizeFileName(stripExtension(baseDisplayName));
        String outName = safeName + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, outName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Redact");
        }

        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri outUri = resolver.insert(collection, values);
        if (outUri == null) {
            throw new IOException("MediaStore insert failed");
        }

        try (InputStream in = new FileInputStream(file);
                OutputStream out = resolver.openOutputStream(outUri)) {
            if (out == null) {
                resolver.delete(outUri, null, null);
                throw new IOException("Cannot open output stream");
            }
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            try {
                resolver.delete(outUri, null, null);
            } catch (Exception ignored) {
            }
            throw e;
        }
        return outUri;
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
}
