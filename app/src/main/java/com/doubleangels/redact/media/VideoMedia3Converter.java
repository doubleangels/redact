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
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
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
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * How long an export may go without progress, or without its output growing, before it is
     * given up. A fixed limit on the whole export failed long 4K videos on slow phones after an
     * hour of good work; one that only fires when nothing moves does not.
     */
    private static final long STALL_TIMEOUT_MS = 10L * 60L * 1000L;
    /** How often the waiting thread checks an export for progress. */
    private static final long STALL_CHECK_SECONDS = 5;
    /** How often a job waiting for another's export checks whether it was cancelled. */
    private static final long LOCK_POLL_MS = 250;
    /** Replaces the stall check in tests: Media3 never finishes an export under Robolectric. */
    private static volatile Long testAwaitTimeoutMs;

    /**
     * How long an export may go without writing a sample before Media3 aborts it as stalled.
     * Phones that declare a media performance class keep Media3's default (10 seconds, 25 on an
     * emulator). Others get at least 20 seconds, since a budget phone decoding in software or
     * starting a slow codec can take longer between samples without being stuck.
     */
    private static final long MAX_SAMPLE_GAP_MS = Transformer.DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS;
    private static final long MAX_SAMPLE_GAP_UNRATED_MS = Math.max(MAX_SAMPLE_GAP_MS, 20_000L);

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

    /** A job waiting for {@link #TRANSCODE_LOCK}, so cancelling it need not wait for the export ahead. */
    private static final class Waiter {
        final long ownerId;
        volatile boolean cancelled;

        Waiter(long ownerId) {
            this.ownerId = ownerId;
        }
    }

    private static final java.util.Set<Waiter> WAITERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /**
     * Keeps Media3 from writing any metadata of its own. Transformer copies the source's location
     * and dates into the output through {@link Muxer#addMetadataEntry}; dropping every entry here
     * means a source can be transcoded directly, where it used to be copied into a metadata-free
     * remux first, a whole extra read, write and secure delete of the video. Rotation is not an
     * entry (it goes with the track format), so it is kept. The output is still scrubbed and
     * verified afterwards.
     */
    @VisibleForTesting
    static final class MetadataDroppingMuxerFactory implements Muxer.Factory {
        private final Muxer.Factory delegate;

        MetadataDroppingMuxerFactory(@NonNull Muxer.Factory delegate) {
            this.delegate = delegate;
        }

        @NonNull
        @Override
        public Muxer create(@NonNull String path) throws MuxerException {
            Muxer muxer = delegate.create(path);
            return new Muxer() {
                @Override
                public int addTrack(@NonNull Format format) throws MuxerException {
                    return muxer.addTrack(format);
                }

                @Override
                public void writeSampleData(int trackId, @NonNull java.nio.ByteBuffer data,
                                            @NonNull BufferInfo bufferInfo) throws MuxerException {
                    muxer.writeSampleData(trackId, data, bufferInfo);
                }

                @Override
                public void addMetadataEntry(@NonNull androidx.media3.common.Metadata.Entry entry) {
                    // Dropped: see the class comment.
                }

                @Override
                public void close() throws MuxerException {
                    muxer.close();
                }
            };
        }

        @NonNull
        @Override
        public ImmutableList<String> getSupportedSampleMimeTypes(int trackType) {
            return delegate.getSupportedSampleMimeTypes(trackType);
        }

        @Override
        public boolean supportsWritingNegativeTimestampsInEditList() {
            return delegate.supportsWritingNegativeTimestampsInEditList();
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
        for (Waiter waiter : WAITERS) {
            if (ownerId < 0 || waiter.ownerId == ownerId) {
                waiter.cancelled = true;
            }
        }
        if (ownerId >= 0 && activeTranscodeOwner.get() != ownerId) {
            return;
        }
        // The hook cancels the export's transformer, or, when it has not been built yet, stops it
        // from being started: both run on the main thread, so one always sees the other.
        Runnable cancelHook = activeCancelHook.get();
        Transformer transformer = activeTransformer.get();
        if (cancelHook == null && transformer == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (cancelHook != null) {
                cancelHook.run();
                return;
            }
            try {
                transformer.cancel();
            } catch (RuntimeException ignored) {
                // Transformer may already be finished.
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
        // Read once: every codec and size attempt below would otherwise open the source again.
        boolean hasAudio = sourceHasAudio(app, sourceUri);

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
                        ownerId,
                        hasAudio);
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
     * size or the codecs stall on it, retries at progressively smaller standard sizes. Media3 sizes
     * the encoder itself, but for some uncommon resolutions its fallback search throws instead of
     * picking a smaller size ("unsupported width" from {@code MediaCodecInfo.VideoCapabilities}).
     */
    private static void transcodeWithResolutionFallback(
            @NonNull Context app,
            @NonNull Uri sourceUri,
            @NonNull String outputPath,
            @NonNull String videoMimeType,
            int hdrMode,
            @Nullable TranscodeProgressListener progressListener,
            long ownerId,
            boolean hasAudio)
            throws IOException, InterruptedException {
        try {
            transcodeToPathOnce(
                    app, sourceUri, outputPath, videoMimeType, hdrMode, progressListener, ownerId, 0,
                    hasAudio);
        } catch (RetryAtSmallerSizeException e) {
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
                            shortSide,
                            hasAudio);
                    warnDownscaled(app, shortSide);
                    return;
                } catch (RetryAtSmallerSizeException retryFailure) {
                    lastFailure = retryFailure;
                }
            }
            throw lastFailure;
        }
    }

    /**
     * Tells the user a video was saved smaller than its source, since nothing else on screen
     * shows it. Clean, Share and Convert all come through here.
     */
    @VisibleForTesting
    static void warnDownscaled(@NonNull Context app, int shortSide) {
        com.doubleangels.redact.sentry.SentryManager.log(
                "The video was saved at " + shortSide + "p after the full size failed.");
        String message = app.getString(com.doubleangels.redact.R.string.video_downscaled, shortSide);
        new Handler(Looper.getMainLooper()).post(
                () -> android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show());
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
            int outputShortSide,
            boolean hasAudio)
            throws IOException, InterruptedException {

        File outFile = new File(outputPath);

        Waiter waiter = new Waiter(ownerId);
        WAITERS.add(waiter);
        boolean locked = false;
        try {
            while (!(locked = TRANSCODE_LOCK.tryLock(LOCK_POLL_MS, TimeUnit.MILLISECONDS))) {
                if (waiter.cancelled) {
                    throw new InterruptedException("Video conversion was cancelled");
                }
            }
            // Owner first, then stop waiting: a cancel in between finds one or the other.
            activeTranscodeOwner.set(ownerId);
            WAITERS.remove(waiter);
            if (waiter.cancelled) {
                activeTranscodeOwner.compareAndSet(ownerId, -1L);
                throw new InterruptedException("Video conversion was cancelled");
            }
            transcodeToPathOnceLocked(
                    app,
                    sourceUri,
                    outputPath,
                    videoMimeType,
                    hdrMode,
                    progressListener,
                    outFile,
                    ownerId,
                    outputShortSide,
                    hasAudio);
        } finally {
            WAITERS.remove(waiter);
            if (locked) {
                TRANSCODE_LOCK.unlock();
            }
        }
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
            int outputShortSide,
            boolean hasAudio)
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
                new EditedMediaItemSequence.Builder(trackTypes(hasAudio))
                        .addItem(editedMediaItem)
                        .build();

        Composition composition = new Composition.Builder(sequence).setHdrMode(hdrMode).build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<ExportException> errorRef = new AtomicReference<>();
        AtomicReference<Throwable> syncFailureRef = new AtomicReference<>();
        AtomicReference<Transformer> transformerRef = new AtomicReference<>();
        // The last progress Media3 reported, for the stall check; -1 before the first.
        AtomicInteger progressSeen = new AtomicInteger(-1);

        Handler mainHandler = new Handler(Looper.getMainLooper());
        // Polled with or without a listener: the stall check needs the progress too.
        AtomicBoolean pollingActive = new AtomicBoolean(true);
        final Runnable[] pollRunnable = new Runnable[1];
        // Runs on the main thread. Transformer.cancel() calls no listener, so this also ends the
        // wait; before the transformer is built, it keeps it from being started at all.
        Runnable cancelHook = () -> {
            cancelled.set(true);
            stopPolling(mainHandler, pollingActive, pollRunnable[0]);
            Transformer t = transformerRef.get();
            if (t != null) {
                try {
                    t.cancel();
                } catch (RuntimeException ignored) {
                    // Transformer may already be finished.
                }
            }
            latch.countDown();
        };
        activeCancelHook.set(cancelHook);
        pollRunnable[0] =
                () -> {
                    if (!pollingActive.get()) {
                        return;
                    }
                    Transformer t = transformerRef.get();
                    if (t != null) {
                        ProgressHolder holder = new ProgressHolder();
                        int state = t.getProgress(holder);
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            progressSeen.set(holder.progress);
                        }
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE && progressListener != null) {
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
                    if (cancelled.get()) {
                        return; // cancelled before it started; the hook already ended the wait
                    }
                    try {
                        Transformer transformer =
                                new Transformer.Builder(app)
                                        .setAssetLoaderFactory(decoderFallbackAssetLoaderFactory(app))
                                        .setMuxerFactory(new MetadataDroppingMuxerFactory(
                                                new DefaultMuxer.Factory()))
                                        .setEncoderFactory(new AlwaysEncodeFactory(
                                                new DefaultEncoderFactory.Builder(app).build()))
                                        .setVideoMimeType(videoMimeType)
                                        .setAudioMimeType(audioMimeTypeForVideoMime(videoMimeType))
                                        .setMaxDelayBetweenMuxerSamplesMs(
                                                maxSampleGapMs(Build.VERSION.MEDIA_PERFORMANCE_CLASS))
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
                        mainHandler.post(pollRunnable[0]);
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
                    : awaitUnlessStalled(latch, outFile, progressSeen);
        } finally {
            activeTranscodeOwner.compareAndSet(ownerId, -1L);
            activeTransformer.compareAndSet(transformerRef.get(), null);
            activeCancelHook.compareAndSet(cancelHook, null);
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
            throw new IOException("Video conversion stalled");
        }

        ExportException exportException = errorRef.get();
        if (exportException != null) {
            deleteQuietly(app, outFile);
            if (isUnsupportedInput(exportException)) {
                throw new UnsupportedMediaException(
                        "Unsupported video format: " + exportException.getMessage(), exportException);
            }
            if (isEncoderResolutionFailure(exportException) || isMuxerStall(exportException)
                    || isVideoEncoderFailure(exportException)) {
                throw new RetryAtSmallerSizeException(
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
     * Waits for an export to end. Returns false when neither its progress nor the size of its
     * output has changed for {@link #STALL_TIMEOUT_MS}; however long it runs, an export that keeps
     * moving is waited for.
     */
    private static boolean awaitUnlessStalled(
            @NonNull CountDownLatch latch, @NonNull File outFile, @NonNull AtomicInteger progressSeen)
            throws InterruptedException {
        long lastChange = android.os.SystemClock.elapsedRealtime();
        long lastLength = -1;
        int lastProgress = -1;
        while (!latch.await(STALL_CHECK_SECONDS, TimeUnit.SECONDS)) {
            long now = android.os.SystemClock.elapsedRealtime();
            long length = outFile.length();
            int progress = progressSeen.get();
            if (length != lastLength || progress != lastProgress) {
                lastLength = length;
                lastProgress = progress;
                lastChange = now;
            } else if (now - lastChange > STALL_TIMEOUT_MS) {
                return false;
            }
        }
        return true;
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

    /**
     * Whether Media3's watchdog aborted the export because no sample reached the muxer for too
     * long. Some hardware codecs hang at certain frame sizes (REDACT-25), and a smaller frame
     * gives them a lighter load to retry with.
     */
    @androidx.annotation.VisibleForTesting
    static boolean isMuxerStall(@NonNull ExportException e) {
        return e.errorCode == ExportException.ERROR_CODE_MUXING_TIMEOUT;
    }

    /**
     * Whether the video encoder failed to start or gave out mid-export. Hardware encoders can fail
     * on sources past their limits, such as 4K at 60fps and about 70 Mbps on a Snapdragon 778G+
     * (REDACT-26), and a smaller frame brings the load back within them. Audio encoder failures
     * are left alone, since a smaller frame would not help.
     */
    @androidx.annotation.VisibleForTesting
    static boolean isVideoEncoderFailure(@NonNull ExportException e) {
        return (e.errorCode == ExportException.ERROR_CODE_ENCODING_FAILED
                        || e.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED)
                && e.codecInfo != null && e.codecInfo.isVideo && !e.codecInfo.isDecoder;
    }

    /** The stall timeout for a phone of {@code mediaPerformanceClass} (0 when it declares none). */
    @androidx.annotation.VisibleForTesting
    static long maxSampleGapMs(int mediaPerformanceClass) {
        return mediaPerformanceClass > 0 ? MAX_SAMPLE_GAP_MS : MAX_SAMPLE_GAP_UNRATED_MS;
    }

    /** An export that failed at its frame size; a smaller size may still succeed. */
    private static final class RetryAtSmallerSizeException extends IOException {
        RetryAtSmallerSizeException(String message, Throwable cause) {
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
