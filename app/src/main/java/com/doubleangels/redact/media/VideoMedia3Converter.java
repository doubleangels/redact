package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.metrics.LogSessionId;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Clock;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.AssetLoader;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.DefaultMuxer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.UnstableApi;

/**
 * Transcodes video with Jetpack Media3 {@link Transformer}, then copies the result into
 * {@code Movies/Redact} as MP4. Format index matches {@link FormatConverter#FORMAT_OPTION_COUNT}:
 * H.264, H.265, VP9, or AV1 (device-dependent).
 */
@OptIn(markerClass = UnstableApi.class)
public final class VideoMedia3Converter {

    /** H.264 / AAC MP4, used for Clean metadata stripping (broad device support, audio preserved). */
    public static final int FORMAT_STRIP_METADATA = 0;

    /** 0–100 while Media3 reports export progress (main thread). */
    public interface TranscodeProgressListener {
        void onProgress(int percent);
    }

    private static final long AWAIT_TIMEOUT_MINUTES = 60;
    /** Replaces the wait above in tests: Media3 never finishes an export under Robolectric. */
    private static volatile Long testAwaitTimeoutMs;

    /** Output short sides tried, largest first, when the encoder rejects the source resolution. */
    private static final int[] FALLBACK_SHORT_SIDES = {1080, 720, 480};

    private static final AtomicReference<Transformer> activeTransformer = new AtomicReference<>();
    /** Owner id of the flow that started the active export; -1 when idle. */
    private static final AtomicLong activeTranscodeOwner = new AtomicLong(-1L);
    /** Ends the wait of the export in progress when it is cancelled: Transformer.cancel() calls no listener. */
    private static final AtomicReference<Runnable> activeCancelHook = new AtomicReference<>();

    /*
     * Owner ids: each kind of job counts its own runs from 1, so the kind is kept in the high bits.
     * Without it, a share's session 3 and a Clean's run 3 were the same owner, and leaving a photo
     * share cancelled the video a Clean was transcoding.
     */
    private static final long OWNER_CLEAN = 1L << 40;
    private static final long OWNER_CONVERT = 2L << 40;
    private static final long OWNER_SHARE = 3L << 40;

    /** The owner id of Clean run {@code run}, for {@link #cancelActiveTranscode(long)}. */
    public static long cleanOwner(int run) {
        return OWNER_CLEAN | (run & 0xFFFFFFFFL);
    }

    /** The owner id of Convert run {@code run}. */
    public static long convertOwner(int run) {
        return OWNER_CONVERT | (run & 0xFFFFFFFFL);
    }

    /** The owner id of share session {@code session}. */
    public static long shareOwner(int session) {
        return OWNER_SHARE | (session & 0xFFFFFFFFL);
    }

    @VisibleForTesting
    public static void setAwaitTimeoutForTests(@Nullable Long timeoutMs) {
        testAwaitTimeoutMs = timeoutMs;
    }
    /** Serializes Media3 exports so Clean and Convert cannot cancel each other's transformer. */
    private static final ReentrantLock TRANSCODE_LOCK = new ReentrantLock();

    private VideoMedia3Converter() {
    }

    /**
     * Makes {@link Transformer} decode and re-encode every track. Without it, a source already in
     * the requested codec is passed through as is, keeping whatever the camera or an editor put
     * inside the stream itself: SEI user data (camcorder date and GPS, drone telemetry, encoder
     * settings), SEI held in the codec configuration, and audio stream comments. The container is
     * scrubbed separately, but only a re-encode leaves nothing of the source but its pictures and
     * sound. Media3 still falls back from keeping HDR to tone mapping when the device cannot
     * encode HDR.
     */
    @VisibleForTesting
    static final class AlwaysEncodeFactory implements Codec.EncoderFactory {
        private final Codec.EncoderFactory delegate;

        AlwaysEncodeFactory(@NonNull Codec.EncoderFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId)
                throws ExportException {
            return delegate.createForAudioEncoding(format, logSessionId);
        }

        @Override
        public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
                throws ExportException {
            return delegate.createForVideoEncoding(format, logSessionId);
        }

        /** Asks the real factory, so Media3 still tone maps HDR the device cannot encode. */
        @OptIn(markerClass = ExperimentalApi.class)
        @Override
        public boolean isVideoFormatSupported(Format format) {
            return delegate.isVideoFormatSupported(format);
        }

        @Override
        public boolean audioNeedsEncoding() {
            return true;
        }

        @Override
        public boolean videoNeedsEncoding() {
            return true;
        }
    }

    /** Cancels an in-flight Media3 export (any owner). */
    public static void cancelActiveTranscode() {
        cancelActiveTranscode(-1L);
    }

    /**
     * Cancels an in-flight export only when it belongs to {@code ownerId}.
     * Pass {@code -1} to cancel regardless of owner.
     */
    public static void cancelActiveTranscode(long ownerId) {
        if (ownerId >= 0 && activeTranscodeOwner.get() != ownerId) {
            return;
        }
        Transformer transformer = activeTransformer.get();
        if (transformer == null) {
            return;
        }
        Runnable endWait = activeCancelHook.get();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                transformer.cancel();
            } catch (RuntimeException ignored) {
                // Transformer may already be finished.
            }
            // cancel() tells no listener, so the export would otherwise wait out its timeout.
            if (endWait != null) {
                endWait.run();
            }
        });
    }

    /**
     * Runs {@link Transformer} to {@code outputPath} (MP4).
     */
    public static int transcodeToPath(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            int formatIndex)
            throws IOException, InterruptedException {
        return transcodeToPath(context, sourceUri, outputPath, formatIndex, null);
    }

    public static int transcodeToPath(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            int formatIndex,
            @Nullable TranscodeProgressListener progressListener)
            throws IOException, InterruptedException {
        return transcodeToPath(context, sourceUri, outputPath, formatIndex, progressListener, -1L);
    }

    public static int transcodeToPath(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            int formatIndex,
            @Nullable TranscodeProgressListener progressListener,
            long ownerId)
            throws IOException, InterruptedException {

        File outFile = new File(outputPath);
        Context app = context.getApplicationContext();

        IOException lastFailure = null;
        for (int attemptIndex : encoderFallbackOrder(formatIndex)) {
            deleteQuietly(app, outFile);
            try {
                String videoMime = videoMimeTypeForFormatIndex(attemptIndex);
                transcodeWithResolutionFallback(
                        app,
                        sourceUri,
                        outputPath,
                        videoMime,
                        hdrModeForMp4VideoMime(videoMime),
                        progressListener,
                        ownerId);
                return attemptIndex;
            } catch (UnsupportedMediaException e) {
                // The source itself can't be read; another output codec won't change that.
                deleteQuietly(app, outFile);
                throw e;
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        if (lastFailure == null) {
            lastFailure = new IOException("Video conversion failed");
        }
        throw lastFailure;
    }

    /**
     * Runs one export at the source resolution and, if the encoder can't be configured for that
     * size, retries at progressively smaller standard sizes. Media3 sizes the encoder itself, but
     * for some uncommon resolutions its fallback search throws instead of picking a smaller size
     * ("unsupported width" from {@code MediaCodecInfo.VideoCapabilities}).
     */
    private static void transcodeWithResolutionFallback(
            @NonNull Context app,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            @NonNull String videoMimeType,
            int hdrMode,
            @Nullable TranscodeProgressListener progressListener,
            long ownerId)
            throws IOException, InterruptedException {
        try {
            transcodeToPathOnce(
                    app, sourceUri, outputPath, videoMimeType, hdrMode, progressListener, ownerId, 0);
        } catch (EncoderResolutionException e) {
            IOException lastFailure = e;
            for (int shortSide : fallbackShortSides(readSourceShortSide(app, sourceUri))) {
                try {
                    transcodeToPathOnce(
                            app,
                            sourceUri,
                            outputPath,
                            videoMimeType,
                            hdrMode,
                            progressListener,
                            ownerId,
                            shortSide);
                    return;
                } catch (EncoderResolutionException retryFailure) {
                    lastFailure = retryFailure;
                }
            }
            throw lastFailure;
        }
    }

    /**
     * Downscale targets strictly smaller than the source's short side, so a retry never upscales.
     * When the source size is unknown ({@code <= 0}) every target is tried.
     */
    @androidx.annotation.VisibleForTesting
    static int[] fallbackShortSides(int sourceShortSide) {
        int count = 0;
        int[] sides = new int[FALLBACK_SHORT_SIDES.length];
        for (int side : FALLBACK_SHORT_SIDES) {
            if (sourceShortSide <= 0 || side < sourceShortSide) {
                sides[count++] = side;
            }
        }
        return Arrays.copyOf(sides, count);
    }

    private static int readSourceShortSide(@NonNull Context app, @NonNull Uri sourceUri) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(app, sourceUri);
            int width = parseIntOrZero(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseIntOrZero(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return width > 0 && height > 0 ? Math.min(width, height) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The track types to write: video, plus audio only when the source has some. Declaring audio
     * for a source without any makes Transformer add a silent track the original never had.
     */
    @VisibleForTesting
    @NonNull
    static ImmutableSet<Integer> trackTypes(boolean sourceHasAudio) {
        return sourceHasAudio
                ? ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
                : ImmutableSet.of(C.TRACK_TYPE_VIDEO);
    }

    /**
     * Whether the source has an audio track. When its tracks cannot be read this answers true, as
     * before, so audio is never dropped by mistake.
     */
    private static boolean sourceHasAudio(@NonNull Context app, @NonNull Uri sourceUri) {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(app, sourceUri, null);
            int tracks = extractor.getTrackCount();
            if (tracks == 0) {
                return true;
            }
            for (int i = 0; i < tracks; i++) {
                String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // Keeping the audio track is the safe default when the source cannot be inspected.
            com.doubleangels.redact.sentry.SentryManager.log("The video's tracks could not be read, so its audio is kept.");
            return true;
        } finally {
            extractor.release();
        }
    }

    private static int parseIntOrZero(@Nullable String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Preferred encoder order when the user-selected MIME fails (common for AV1/VP9 at 4K or on OEM limits).
     */
    private static int[] encoderFallbackOrder(int requestedFormatIndex) {
        switch (requestedFormatIndex) {
            case -1:
                return new int[] {};
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

    private static String audioMimeTypeForVideoMime(@NonNull String videoMimeType) {
        if (MimeTypes.VIDEO_VP9.equals(videoMimeType) || MimeTypes.VIDEO_VP8.equals(videoMimeType)) {
            return MimeTypes.AUDIO_OPUS;
        }
        return MimeTypes.AUDIO_AAC;
    }

    private static void transcodeToPathOnce(
            @NonNull Context app,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            @NonNull String videoMimeType,
            int hdrMode,
            @Nullable TranscodeProgressListener progressListener,
            long ownerId,
            int outputShortSide)
            throws IOException, InterruptedException {

        File outFile = new File(outputPath);

        TRANSCODE_LOCK.lock();
        try {
            transcodeToPathOnceLocked(
                    app,
                    sourceUri,
                    outputPath,
                    videoMimeType,
                    hdrMode,
                    progressListener,
                    outFile,
                    ownerId,
                    outputShortSide);
        } finally {
            TRANSCODE_LOCK.unlock();
        }
    }

    private static void transcodeToPathOnce(
            @NonNull Context app,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            @NonNull String videoMimeType,
            int hdrMode,
            @Nullable TranscodeProgressListener progressListener)
            throws IOException, InterruptedException {
        transcodeToPathOnce(
                app, sourceUri, outputPath, videoMimeType, hdrMode, progressListener, -1L, 0);
    }

    private static void transcodeToPathOnceLocked(
            @NonNull Context app,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            @NonNull String videoMimeType,
            int hdrMode,
            @Nullable TranscodeProgressListener progressListener,
            @NonNull File outFile,
            long ownerId,
            int outputShortSide)
            throws IOException, InterruptedException {

        activeTranscodeOwner.set(ownerId);
        MediaItem mediaItem = MediaItem.fromUri(sourceUri);
        EditedMediaItem.Builder editedMediaItemBuilder =
                new EditedMediaItem.Builder(mediaItem).setRemoveAudio(false);
        if (outputShortSide > 0) {
            editedMediaItemBuilder.setEffects(new Effects(
                    ImmutableList.of(),
                    ImmutableList.of(Presentation.createForShortSide(outputShortSide))));
        }
        EditedMediaItem editedMediaItem = editedMediaItemBuilder.build();

        EditedMediaItemSequence sequence =
                new EditedMediaItemSequence.Builder(trackTypes(sourceHasAudio(app, sourceUri)))
                        .addItem(editedMediaItem)
                        .build();

        Composition composition = new Composition.Builder(sequence).setHdrMode(hdrMode).build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable endWait = () -> {
            cancelled.set(true);
            latch.countDown();
        };
        activeCancelHook.set(endWait);
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
                            try {
                                progressListener.onProgress(holder.progress);
                            } catch (RuntimeException e) {
                                // A listener (e.g. MetadataStripper.reportTranscodeProgress)
                                // signals cancellation by throwing here after already calling
                                // cancelActiveTranscode; stop polling instead of letting this
                                // escape uncaught from the main thread's Handler loop.
                                pollingActive.set(false);
                                return;
                            }
                        }
                    }
                    mainHandler.postDelayed(pollRunnable[0], 300);
                };

        mainHandler.post(
                () -> {
                    try {
                        Transformer transformer =
                                new Transformer.Builder(app)
                                        .setAssetLoaderFactory(decoderFallbackAssetLoaderFactory(app))
                                        .setMuxerFactory(new DefaultMuxer.Factory())
                                        .setEncoderFactory(new AlwaysEncodeFactory(
                                                new DefaultEncoderFactory.Builder(app).build()))
                                        .setVideoMimeType(videoMimeType)
                                        .setAudioMimeType(audioMimeTypeForVideoMime(videoMimeType))
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
                        activeTransformer.set(transformer);
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

        boolean finished;
        try {
            Long testTimeout = testAwaitTimeoutMs;
            finished = testTimeout != null
                    ? latch.await(testTimeout, TimeUnit.MILLISECONDS)
                    : latch.await(AWAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } finally {
            activeTranscodeOwner.compareAndSet(ownerId, -1L);
            activeTransformer.compareAndSet(transformerRef.get(), null);
            activeCancelHook.compareAndSet(endWait, null);
        }
        if (cancelled.get() && errorRef.get() == null && syncFailureRef.get() == null) {
            stopPolling(mainHandler, pollingActive, pollRunnable[0]);
            deleteQuietly(app, outFile);
            throw new InterruptedException("Video conversion was cancelled");
        }
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
            deleteQuietly(app, outFile);
            throw new IOException("Video conversion timed out");
        }

        ExportException exportException = errorRef.get();
        if (exportException != null) {
            deleteQuietly(app, outFile);
            if (isUnsupportedInput(exportException)) {
                throw new UnsupportedMediaException(
                        "Unsupported video format: " + exportException.getMessage(), exportException);
            }
            if (isEncoderResolutionFailure(exportException)) {
                throw new EncoderResolutionException(
                        "Video conversion failed: " + exportException.getMessage(), exportException);
            }
            throw new IOException(
                    "Video conversion failed: " + exportException.getMessage(), exportException);
        }
        Throwable syncFailure = syncFailureRef.get();
        if (syncFailure != null) {
            deleteQuietly(app, outFile);
            throw new IOException(
                    "Video conversion failed: " + syncFailure.getMessage(), syncFailure);
        }

        if (!outFile.exists() || outFile.length() == 0) {
            deleteQuietly(app, outFile);
            throw new IOException("Video conversion produced no output");
        }
    }

    /**
     * Whether an export failed because of what the input is (an unrecognized container, or a
     * codec no decoder on this device handles) rather than a processing fault. Container parse
     * failures arrive as {@link ExportException#ERROR_CODE_UNSPECIFIED} wrapping the player's
     * {@link PlaybackException}, so the cause chain is checked too.
     */
    @androidx.annotation.VisibleForTesting
    static boolean isUnsupportedInput(@NonNull ExportException e) {
        if (e.errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
            return true;
        }
        Throwable t = e.getCause();
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof PlaybackException) {
                int code = ((PlaybackException) t).errorCode;
                if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an export failed because the encoder couldn't be configured for the frame size.
     * {@code VideoCapabilities.getSupportedHeightsFor/getSupportedWidthsFor} throw
     * {@link IllegalArgumentException} "unsupported width"/"unsupported height" from Media3's
     * encoder resolution search; that surfaces wrapped as a frame processing error.
     */
    @androidx.annotation.VisibleForTesting
    static boolean isEncoderResolutionFailure(@NonNull ExportException e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof IllegalArgumentException) {
                String message = t.getMessage();
                if (message != null
                        && (message.contains("unsupported width")
                                || message.contains("unsupported height"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** An export the encoder rejected for its frame size; a smaller size may still succeed. */
    private static final class EncoderResolutionException extends IOException {
        EncoderResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Media3's default asset loader only tries the first decoder the device lists. Hardware decoders
     * can refuse to start on streams at or past their limits (e.g. 4K60 high-bitrate AVC), and
     * without fallback the whole export fails even though a lower-priority decoder could handle it.
     */
    @NonNull
    private static AssetLoader.Factory decoderFallbackAssetLoaderFactory(@NonNull Context app) {
        return new DefaultAssetLoaderFactory(
                app,
                new DefaultDecoderFactory.Builder(app).setEnableDecoderFallback(true).build(),
                Clock.DEFAULT,
                /* logSessionId= */ null);
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
        transcodeToPath(context, sourceUri, outputFile.getAbsolutePath(), formatIndex, progressListener, -1L);
    }

    public static void transcodeToFile(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull File outputFile,
            int formatIndex,
            @Nullable TranscodeProgressListener progressListener,
            long ownerId)
            throws IOException, InterruptedException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("Failed to create output directory");
            }
        }
        transcodeToPath(
                context, sourceUri, outputFile.getAbsolutePath(), formatIndex, progressListener, ownerId);
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
        File outFile = File.createTempFile("vid_transform_", TRANSCODE_EXTENSION, app.getCacheDir());
        transcodeToPath(app, sourceUri, outFile.getAbsolutePath(), formatIndex, progressListener);

        try {
            return copyToMoviesRedact(app, outFile, baseDisplayName);
        } finally {
            deleteQuietly(app, outFile);
        }
    }

    private static void deleteQuietly(@NonNull Context context, @Nullable File file) {
        if (file != null && file.exists()) {
            SecureDelete.secureDelete(context, file);
        }
    }

    /** Maps format chip index to output video MIME (MP4). */
    @NonNull
    @androidx.annotation.VisibleForTesting
    static String videoMimeTypeForFormatIndex(int formatIndex) {
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

    /**
     * The extension of everything {@link Transformer} writes: its default muxer is MP4 for every
     * codec, VP9 and AV1 included.
     */
    public static final String TRANSCODE_EXTENSION = ".mp4";

    /**
     * The container a source in this format is usually in, which the fast transmux keeps (it
     * writes WebM for VP8/VP9). A transcode is always {@link #TRANSCODE_EXTENSION}.
     */
    @NonNull
    public static String extensionForFormatIndex(int formatIndex) {
        switch (formatIndex) {
            case 2:
                return ".webm";
            case 3:
                return ".mkv";
            case 0:
            case 1:
            default:
                return ".mp4";
        }
    }

    /**
     * Saves a finished video to the output folder. Its extension and type follow the container
     * {@code file} is really in (WebM only from the fast transmux, otherwise MP4), not the codec:
     * a VP9 or AV1 transcode is MP4 too.
     */
    @NonNull
    public static Uri copyToMoviesRedact(Context context, File file, String originalFileName)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        boolean webm = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".webm");
        String ext = webm ? ".webm" : ".mp4";
        String mime = webm ? "video/webm" : "video/mp4";
        String outName = MediaFileNames.outputBaseName(context, originalFileName) + ext;

        Uri outUri = OutputDestination.create(context, true, outName, mime);

        try (InputStream in = new FileInputStream(file);
                OutputStream out = resolver.openOutputStream(outUri)) {
            if (out == null) {
                OutputDestination.discard(resolver, outUri);
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
                OutputDestination.discard(resolver, outUri);
            } catch (Exception ignored) {
                com.doubleangels.redact.sentry.SentryManager.recordException(ignored);
            }
            throw e;
        }
        OutputDestination.publish(resolver, outUri);
        return outUri;
    }
}
