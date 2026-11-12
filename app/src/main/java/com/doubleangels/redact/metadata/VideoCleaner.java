package com.doubleangels.redact.metadata;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.FileProvider;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.MuxerException;
import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.media.OutputDestination;
import com.doubleangels.redact.media.ProcessingCancelledException;
import com.doubleangels.redact.media.UnsupportedMediaException;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.common.collect.ImmutableList;
import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cleans videos for {@link MetadataStripper}: the transmux fast path, the WebM remux, the Media3
 * transcode, and putting back what Keep Location and Keep Date Taken ask for, each output checked by
 * {@link CleanVerifier}. Moved out of MetadataStripper unchanged.
 */
final class VideoCleaner {

    private static final String TAG = "MetadataStripper";

    private final MetadataStripper host;
    private final Context context;
    private final ContentResolver contentResolver;

    VideoCleaner(@NonNull MetadataStripper host) {
        this.host = host;
        this.context = host.context;
        this.contentResolver = host.contentResolver;
    }


    /**
     * Process video to remove metadata and save to MediaStore (external storage).
     *
     * <p>Re-encodes with Media3 so container and stream metadata are stripped. A fast transmux-only path
     * is used only when {@link AppPreferences#isVideoFallbackCopy} is enabled and strict clean is off.
     *
     * @param sourceUri        URI of the source video, must not be null
     * @param originalFilename Original filename of the video, must not be null
     * @return the saved video's URI (null when the clean failed) and what it removed
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @NonNull
    public CleanResult cleanVideo(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        SentryManager.log("The app is starting video metadata stripping for MediaStore.");
        SentryManager.beginOperation("video_to_mediastore");
        Uri newUri = null;
        MetadataStripper.Job job = new MetadataStripper.Job();

        try {
            host.throwIfCancelled();

            host.updateProgress(1, 4, context.getString(R.string.strip_progress_reading_video));

            CleanVerifier.VideoPrivacySnapshot sourceSnapshot = host.verifier.extractVideoPrivacyMetadata(sourceUri);
            job.stats = CleanVerifier.countVideoPrivacyFields(sourceSnapshot);

            host.throwIfCancelled();
            int formatIndex = detectVideoFormatIndex(sourceUri, originalFilename);

            // The quick transmux is only worth its full copy of the video when that copy may be
            // saved as it is. A transcode reads the source directly: Media3 is kept from writing
            // any of its metadata (VideoMedia3Converter.MetadataDroppingMuxerFactory).
            // What Keep Location and Keep Date Taken ask to put back; null when they are off.
            double[] keptLocation = keptVideoLocation(sourceSnapshot, sourceUri);
            long[] keptTimes = keptVideoTimes(sourceUri);
            job.stats = job.stats.keeping(keptLocation != null, keptTimes != null);
            File tempCleanFile = null;
            if (mayKeepTransmux()) {
                host.updateProgress(2, 4, context.getString(R.string.strip_progress_transmuxing));
                tempCleanFile = fastStripVideoMetadata(sourceUri, keptLocation, keptTimes != null);
            }
            Uri cleanSourceUri = tempCleanFile != null ? Uri.fromFile(tempCleanFile) : sourceUri;

            boolean needsTranscode = !shouldSkipVideoTranscode(formatIndex, tempCleanFile, false);
            boolean savedViaFastPath = false;
            boolean fastPathFailed = false;

            try {
                if (!needsTranscode && tempCleanFile != null) {
                    try {
                        host.updateProgress(3, 4, context.getString(R.string.strip_progress_verifying));
                        host.verifier.requireVideoMetadataClean(tempCleanFile, sourceSnapshot, keptLocation != null);
                        restoreVideoTimes(tempCleanFile, keptTimes);
                        host.updateProgress(4, 4, context.getString(R.string.strip_progress_saving_clean_copy));
                        host.checkCancelled();
                        newUri = VideoMedia3Converter.copyToMoviesRedact(
                                context, tempCleanFile, originalFilename);
                        savedViaFastPath = true;
                    } catch (IOException fastPathError) {
                        fastPathFailed = true;
                        SentryManager.log(
                                "The fast video clean failed verification, so the app is re-encoding: "
                                        + fastPathError.getClass().getSimpleName() + ".");
                    }
                }
                if (!savedViaFastPath) {
                    host.updateProgress(3, 4, context.getString(R.string.strip_progress_transcoding_target));
                    File transcodeOutput = File.createTempFile(
                            "vid_transform_", VideoMedia3Converter.TRANSCODE_EXTENSION, context.getCacheDir());
                    try {
                        VideoMedia3Converter.transcodeToPath(
                                        context.getApplicationContext(),
                                        cleanSourceUri,
                                        transcodeOutput.getAbsolutePath(),
                                        formatIndex,
                                        host::reportTranscodeProgress,
                                        host.transcodeOwnerId,
                                        keptLocation);
                        host.verifier.requireVideoMetadataClean(transcodeOutput, sourceSnapshot, keptLocation != null);
                        restoreVideoTimes(transcodeOutput, keptTimes);
                        host.updateProgress(4, 4, context.getString(R.string.strip_progress_saving_clean_video));
                        host.checkCancelled();
                        newUri =
                                VideoMedia3Converter.copyToMoviesRedact(
                                        context,
                                        transcodeOutput,
                                        originalFilename);
                    } finally {
                        host.secureDeleteFile(transcodeOutput);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessingCancelledException("Video processing interrupted", e);
            } catch (Exception e) {
                SentryManager.log("Video processing failed: " + e.getClass().getSimpleName() + ".");
                throw new IOException("Failed to clean video safely", e);
            } finally {
                if (tempCleanFile != null && tempCleanFile.exists()) {
                    host.secureDeleteFile(tempCleanFile);
                }
            }

            countVideoPath(savedViaFastPath ? "fast_copy" : fastPathFailed ? "fast_failed" : "transcoded");
            SentryManager.log("The video was processed successfully.");
            SentryManager.setCustomKey("success", true);

        } catch (RuntimeException e) {
            if (MetadataStripper.shouldPropagateCancellation(e)) {
                throw e;
            }
            Log.e(TAG, "Error processing video", e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            SentryManager.recordException(e);

            if (newUri != null) {
                OutputDestination.discard(contentResolver, newUri);
                newUri = null;
            }
        } catch (Exception e) {
            if (MetadataStripper.shouldPropagateCancellation(e)) {
                throw MetadataStripper.asCancellationException(e);
            }
            // Log error and clean up any partial files
            Log.e(TAG, "Error processing video", e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            SentryManager.recordException(e);

            if (newUri != null) {
                OutputDestination.discard(contentResolver, newUri);
                newUri = null;
            }
        }

        return job.result(newUri);
    }

    /**
     * Strips metadata from a video and saves it to the app's cache directory for
     * sharing.
     *
     * This method:
     * 1. Transcodes to H.264/AAC MP4 in cache (or direct copy if transcoding fails)
     * 2. Reports progress during processing
     * 3. Returns a FileProvider URI for sharing the processed video
     *
     * @param sourceUri        URI of the source video, must not be null
     * @param originalFilename Original filename of the video, must not be null
     * @return the FileProvider URI and file of the processed video (null when the clean failed),
     *         what it removed, and whether it failed because the format cannot be decoded here
     * @throws IllegalArgumentException if sourceUri or originalFilename is null
     */
    @NonNull
    public CleanResult cleanVideoForSharing(@NonNull Uri sourceUri, @NonNull String originalFilename) {
        // Log operation start for analytics and debugging
        SentryManager.log("The app is starting video metadata stripping for sharing.");

        SentryManager.beginOperation("video_for_sharing");
        MetadataStripper.Job job = new MetadataStripper.Job();

        File outputFile = null;

        try {
            host.updateProgress(1, 4, context.getString(R.string.strip_progress_reading_video));

            CleanVerifier.VideoPrivacySnapshot sourceSnapshot = host.verifier.extractVideoPrivacyMetadata(sourceUri);
            job.stats = CleanVerifier.countVideoPrivacyFields(sourceSnapshot);

            // Create directory for processed files if it doesn't exist
            File outputDir = new File(context.getCacheDir(), "processed");
            if (!outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory");
                }
            }

            // Shared videos are always H.264, which every app they go to can play. A share always
            // re-encodes (a transmux keeps what is inside the stream), straight from the source.
            int formatIndex = VideoMedia3Converter.FORMAT_STRIP_METADATA;
            String newFilename = host.generateShortRandomName() + VideoMedia3Converter.TRANSCODE_EXTENSION;
            outputFile = new File(outputDir, newFilename);

            try {
                host.updateProgress(3, 4, context.getString(R.string.strip_progress_transcoding));
                VideoMedia3Converter.transcodeToFile(
                        context.getApplicationContext(),
                        sourceUri,
                        outputFile,
                        formatIndex,
                        host::reportTranscodeProgress,
                        host.transcodeOwnerId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessingCancelledException("Video transcoding interrupted", e);
            } catch (Exception e) {
                SentryManager.log("Video processing failed: " + e.getClass().getSimpleName() + ".");
                throw new IOException("Failed to process video safely", e);
            }

            host.updateProgress(4, 4, context.getString(R.string.strip_progress_verifying));

            host.verifier.requireVideoMetadataClean(outputFile, sourceSnapshot);

            // Get content URI using FileProvider for sharing
            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    outputFile);

            job.file = outputFile;
            countVideoPath("transcoded");
            SentryManager.log("The video was processed successfully for sharing.");
            SentryManager.setCustomKey("success", true);
            return job.result(fileUri);

        } catch (Exception e) {
            // Log error
            Log.e(TAG, "Error processing video for sharing", e);
            job.unsupported = UnsupportedMediaException.isCauseOf(e);
            SentryManager.setCustomKey("success", false);
            SentryManager.setCustomKey("error_type", e.getClass().getName());
            SentryManager.recordException(e);
            if (outputFile != null && outputFile.exists()) {
                host.secureDeleteFile(outputFile);
            }
            return job.result(null);
        }
    }

    /**
     * The output codec that keeps a source's own: its video track's codec as the extractor reads
     * it, and only when the tracks cannot be read, what its name suggests. The name used to win,
     * so an HEVC video named .mp4 was re-encoded to H.264 while the same video named .mov stayed
     * HEVC.
     */
    @VisibleForTesting
    int detectVideoFormatIndex(Uri sourceUri, String fileName) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, sourceUri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                if (mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                    return 1;
                }
                if (mime.equals(MediaFormat.MIMETYPE_VIDEO_VP9) || mime.equals(MediaFormat.MIMETYPE_VIDEO_VP8)) {
                    return 2;
                }
                if (mime.equals(MediaFormat.MIMETYPE_VIDEO_AV1)) {
                    return 3;
                }
                return 0;
            }
        } catch (Exception ignored) {
            SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + ".");
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
                SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + ".");
            }
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".webm") || lower.endsWith(".vp9") || lower.endsWith(".vp8")) {
                return 2;
            }
        }
        return 0;
    }

    /**
     * Copies the selected tracks' samples in the order the source stores them, so video and audio
     * stay interleaved. Copying one whole track and then the other wrote every video sample ahead
     * of every audio sample, a file that cannot start playing until it has been read to the end.
     */
    private void remuxTracks(
            MediaExtractor extractor,
            Map<Integer, Integer> muxerTracks,
            @NonNull SampleSink muxer) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            extractor.unselectTrack(i);
        }
        for (int sourceTrack : muxerTracks.keySet()) {
            extractor.selectTrack(sourceTrack);
        }
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024 * 2);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            host.checkCancelled();
            int sourceTrack = extractor.getSampleTrackIndex();
            if (sourceTrack == -1) {
                break;
            }
            Integer muxerTrackIndex = muxerTracks.get(sourceTrack);
            if (muxerTrackIndex == null) {
                extractor.advance();
                continue;
            }
            info.size = extractor.readSampleData(buffer, 0);
            if (info.size < 0) {
                break;
            }
            if (info.size > buffer.capacity()) {
                buffer = ByteBuffer.allocate(info.size);
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) {
                    break;
                }
            }
            info.presentationTimeUs = extractor.getSampleTime();
            int sampleFlags = extractor.getSampleFlags();
            info.flags = 0;
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                info.flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                info.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
            }
            info.offset = 0;
            muxer.write(muxerTrackIndex, buffer, info);
            extractor.advance();
        }
    }

    /**
     * Transmuxes {@code sourceUri} into a cache file without container-level metadata: the quick
     * path of Faster Video Cleaning on its own. Returns null when transmuxing isn't possible;
     * callers must delete a non-null result.
     */
    @VisibleForTesting
    @Nullable
    public File transmuxVideoWithoutMetadata(@NonNull Uri sourceUri) {
        return fastStripVideoMetadata(sourceUri, null, false);
    }

    /**
     * The location to write back into a cleaned video, {latitude, longitude}: the source's, when
     * Keep Location is on and Strict Clean is off. Null otherwise, or when it has none.
     */
    @Nullable
    private double[] keptVideoLocation(@NonNull CleanVerifier.VideoPrivacySnapshot source, @NonNull Uri sourceUri) {
        if (AppPreferences.isStrictClean(context) || !AppPreferences.isPreserveLocation(context)) {
            return null;
        }
        double[] coordinates = ReadableText.iso6709(source.location);
        if (coordinates == null && source.containerHasLocation) {
            // A location the platform doesn't report, such as an iPhone's QuickTime key.
            coordinates = VideoContainerMetadata.scan(context, sourceUri).coordinates;
        }
        if (coordinates == null || coordinates.length < 2
                || Math.abs(coordinates[0]) > 90 || Math.abs(coordinates[1]) > 180) {
            return null;
        }
        return coordinates;
    }

    /**
     * When the source was recorded, {creation, modification} in seconds since 1904, to write back
     * into a cleaned video when Keep Date Taken is on and Strict Clean is off. Null otherwise.
     */
    @Nullable
    private long[] keptVideoTimes(@NonNull Uri sourceUri) {
        if (AppPreferences.isStrictClean(context) || !AppPreferences.isPreserveDateTaken(context)) {
            return null;
        }
        return VideoContainerMetadata.movieTimes(context, sourceUri);
    }

    /**
     * Puts the recording time back into a verified clean video, so galleries keep it in place.
     * Only an MP4 or QuickTime file has the headers for it; a WebM keeps none, and the clean
     * still succeeds.
     */
    private void restoreVideoTimes(@NonNull File video, @Nullable long[] times) {
        if (times == null) {
            return;
        }
        try {
            VideoContainerMetadata.writeMovieTimes(video, times);
        } catch (IOException e) {
            SentryManager.log("The recording date could not be kept: " + e.getClass().getSimpleName() + ".");
        }
    }

    /** Where {@link #remuxTracks} writes each sample. */
    private interface SampleSink {
        void write(int track, @NonNull ByteBuffer buffer,
                @NonNull MediaCodec.BufferInfo info) throws IOException;
    }

    /** The name a {@link #remuxWebmToMp4} copy starts with, so it is saved even though the source was WebM. */
    private static final String KEEP_REMUX_PREFIX = "vid_keep_";

    /**
     * @param keepDate whether Keep Date Taken will put the recording date back, which a WebM
     *     cannot hold
     */
    File fastStripVideoMetadata(Uri sourceUri, @Nullable double[] keepLocation, boolean keepDate) {
        if (host.testFastStripVideoMetadataOverride != null) {
            return host.testFastStripVideoMetadataOverride;
        }
        if (keepLocation != null || keepDate) {
            // A WebM has no field for a location, and Android reads no recording date from one,
            // so a VP9 video whose location or date is kept is copied into an MP4 instead.
            File mp4 = remuxWebmToMp4(sourceUri, keepLocation);
            if (mp4 != null) {
                return mp4;
            }
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        File outputFile = null;
        boolean succeeded = false;
        try {
            try {
                extractor.setDataSource(context, sourceUri, null);
            } catch (IOException unreadable) {
                // No platform extractor for this container. The full transcode that follows
                // decides whether the input is usable at all and reports it accordingly.
                SentryManager.logEvent("Video",
                        "The fast transmux could not open the source, so the app is falling back to a full transcode.");
                return null;
            }

            boolean useWebm = useWebmContainer(extractor);
            if (useWebm && (keepLocation != null || keepDate)) {
                // Not a WebM an MP4 can take as it is (VP8, say): a WebM keeps no location and
                // Android reads no date from one, so it is encoded again into an MP4 that does.
                SentryManager.log("The WebM can't be copied into an MP4, so it is re-encoded to keep its location or date.");
                return null;
            }
            
            outputFile = File.createTempFile(
                    "vid_transmux_", useWebm ? ".webm" : ".mp4", context.getCacheDir());
            int outputFormat = useWebm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM 
                                       : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
            
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), outputFormat);
            if (keepLocation != null && !useWebm) {
                // Keep Location: written from the value asked for, nothing copied from the source.
                muxer.setLocation((float) keepLocation[0], (float) keepLocation[1]);
            }
            
            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            int muxerVideoTrackIndex = -1;
            int muxerAudioTrackIndex = -1;
            int videoTrackCount = 0;
            int audioTrackCount = 0;
            
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    videoTrackCount++;
                    if (videoTrackIndex == -1) {
                    videoTrackIndex = i;
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION));
                    } else {
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        try {
                            retriever.setDataSource(context, sourceUri);
                            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                            if (rotation != null) {
                                muxer.setOrientationHint(Integer.parseInt(rotation));
                            }
                        } catch (Exception ignored) { SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + "."); } finally {
                            try { retriever.release(); } catch (Exception ignored) { SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + "."); }
                        }
                    }
                    muxerVideoTrackIndex = muxer.addTrack(format);
                    }
                } else if (mime.startsWith("audio/")) {
                    audioTrackCount++;
                    if (audioTrackIndex == -1) {
                    audioTrackIndex = i;
                    muxerAudioTrackIndex = muxer.addTrack(format);
                    }
                }
            }
            
            if (videoTrackCount > 1 || audioTrackCount > 1) {
                SentryManager.log("The video has extra tracks, so the fast transmux keeps only the first video and audio tracks.");
                if (AppPreferences.isStrictClean(context)) {
                    return null;
                }
            }
            
            if (videoTrackIndex == -1 && audioTrackIndex == -1) {
                return null;
            }
            
            muxer.start();

            Map<Integer, Integer> muxerTracks = new HashMap<>();
            if (videoTrackIndex != -1 && muxerVideoTrackIndex != -1) {
                muxerTracks.put(videoTrackIndex, muxerVideoTrackIndex);
            }
            if (audioTrackIndex != -1 && muxerAudioTrackIndex != -1) {
                muxerTracks.put(audioTrackIndex, muxerAudioTrackIndex);
            }
            remuxTracks(extractor, muxerTracks, muxer::writeSampleData);

            muxer.stop();
            succeeded = true;
            return outputFile;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                // MediaMuxer rejects some streams mid-write (its native writer stops and stop()
                // then throws). This fast path is best-effort: every caller falls back to a full
                // transcode from the source, which still produces a verified clean output.
                SentryManager.logEvent("Video",
                        "The fast transmux failed, so the app is falling back to a full transcode: "
                                + e.getClass().getSimpleName());
            } else {
                SentryManager.recordException(e);
            }
            return null;
        } finally {
            try { extractor.release(); } catch (Exception ignored) { SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + "."); }
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) { SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + "."); }
            }
            // Every way out but success, including the early returns once the muxer has created
            // the file, leaves an unused (possibly partly written) copy of the video behind.
            if (!succeeded && outputFile != null && outputFile.exists()) {
                host.secureDeleteFile(outputFile);
            }
        }
    }

    /**
     * Copies a VP9 WebM's video and Opus audio into an MP4 sample for sample, so a kept location
     * and recording date have somewhere to go. Media3's MP4 muxer holds both codecs, which the
     * platform's does not, and the VP9 configuration an MP4 needs is read from the first key
     * frame ({@link Vp9CodecConfig}). Returns null for anything else, which a WebM keeps holding:
     * a source that is not VP9 in WebM, VP8, Vorbis audio, or a copy that fails.
     */
    // The Media3 muxer it writes with is not yet a stable API.
    @OptIn(markerClass = UnstableApi.class)
    @Nullable
    private File remuxWebmToMp4(@NonNull Uri sourceUri, @Nullable double[] keepLocation) {
        MediaExtractor extractor = new MediaExtractor();
        File outputFile = null;
        boolean succeeded = false;
        Mp4Muxer muxer = null;
        try {
            extractor.setDataSource(context, sourceUri, null);
            if (!useWebmContainer(extractor)) {
                return null;
            }
            int videoTrack = -1;
            int audioTrack = -1;
            boolean vorbis = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/") && videoTrack == -1) {
                    if (!mime.equals(MediaFormat.MIMETYPE_VIDEO_VP9)) {
                        return null;
                    }
                    videoTrack = i;
                } else if (mime.startsWith("audio/") && audioTrack == -1) {
                    vorbis = mime.equals(MediaFormat.MIMETYPE_AUDIO_VORBIS);
                    if (!vorbis && !mime.equals(MediaFormat.MIMETYPE_AUDIO_OPUS)) {
                        return null;
                    }
                    audioTrack = i;
                }
            }
            if (videoTrack == -1) {
                return null;
            }
            MediaFormat videoFormat = extractor.getTrackFormat(videoTrack);
            byte[] vpcC = Vp9CodecConfig.vpcC(
                    firstSample(sourceUri, videoTrack),
                    videoFormat.getInteger(MediaFormat.KEY_WIDTH, 0),
                    videoFormat.getInteger(MediaFormat.KEY_HEIGHT, 0),
                    frameRateOf(videoFormat),
                    videoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0),
                    videoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0),
                    videoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE, 0));
            if (vpcC == null) {
                SentryManager.log("The WebM video does not start with a VP9 key frame, so it stays WebM.");
                return null;
            }

            outputFile = File.createTempFile(KEEP_REMUX_PREFIX, ".mp4", context.getCacheDir());
            if (vorbis) {
                // An MP4 can't hold Vorbis: the video is copied as it is and only the sound is
                // encoded again, to Opus.
                VideoMedia3Converter.copyVideoToMp4(context.getApplicationContext(), sourceUri,
                        outputFile.getAbsolutePath(), host.transcodeOwnerId, keepLocation, vpcC);
                succeeded = true;
                return outputFile;
            }
            muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFile)).build();
            if (keepLocation != null) {
                // Written from the value asked for, nothing copied from the source.
                muxer.addMetadataEntry(new Mp4LocationData(
                        (float) keepLocation[0], (float) keepLocation[1]));
            }
            Map<Integer, Integer> tracks = new LinkedHashMap<>();
            tracks.put(videoTrack, muxer.addTrack(media3Format(videoFormat)
                    .buildUpon()
                    .setInitializationData(Collections.singletonList(vpcC))
                    .build()));
            if (audioTrack != -1) {
                tracks.put(audioTrack, muxer.addTrack(media3Format(extractor.getTrackFormat(audioTrack))));
            }
            Mp4Muxer sink = muxer;
            remuxTracks(extractor, tracks, (track, buffer, info) -> {
                buffer.position(0);
                buffer.limit(info.size);
                try {
                    sink.writeSampleData(track, buffer, new BufferInfo(
                            info.presentationTimeUs, info.size,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    ? C.BUFFER_FLAG_KEY_FRAME : 0));
                } catch (MuxerException e) {
                    throw new IOException(e);
                }
            });
            muxer.close();
            muxer = null;
            succeeded = true;
            return outputFile;
        } catch (Exception e) {
            if (MetadataStripper.shouldPropagateCancellation(e)) {
                throw MetadataStripper.asCancellationException(e);
            }
            SentryManager.log("The WebM could not be copied into an MP4, so it stays WebM: "
                    + e.getClass().getSimpleName() + ".");
            return null;
        } finally {
            try { extractor.release(); } catch (Exception ignored) { SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + "."); }
            if (muxer != null) {
                try { muxer.close(); } catch (Exception ignored) { SentryManager.log("An exception was ignored: " + ignored.getClass().getSimpleName() + "."); }
            }
            if (!succeeded && outputFile != null && outputFile.exists()) {
                host.secureDeleteFile(outputFile);
            }
        }
    }

    /** The first sample of {@code track}, read with an extractor of its own. */
    @NonNull
    private byte[] firstSample(@NonNull Uri sourceUri, int track) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, sourceUri, null);
            extractor.selectTrack(track);
            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            int size = extractor.readSampleData(buffer, 0);
            if (size <= 0) {
                throw new IOException("The video has no samples");
            }
            byte[] sample = new byte[size];
            buffer.position(0);
            buffer.get(sample);
            return sample;
        } finally {
            extractor.release();
        }
    }

    private static float frameRateOf(@NonNull MediaFormat format) {
        try {
            return format.getInteger(MediaFormat.KEY_FRAME_RATE, 0);
        } catch (ClassCastException isFloat) {
            return format.getFloat(MediaFormat.KEY_FRAME_RATE, 0f);
        }
    }

    /** The platform's track format as Media3's, keeping nothing but how to decode it. */
    // Building a Media3 Format for its muxer is not yet a stable API.
    @OptIn(markerClass = UnstableApi.class)
    @NonNull
    private static Format media3Format(@NonNull MediaFormat format) {
        return MediaFormatUtil.createFormatFromMediaFormat(format)
                .buildUpon()
                .setLanguage(null)
                .setLabel(null)
                .setLabels(ImmutableList.of())
                .setMetadata(null)
                .build();
    }

    /**
     * Whether the fast transmux must write WebM: for VP8 or VP9 video, which MP4 cannot hold, or
     * for Opus or Vorbis audio with no video. The video decides when there is some, so H.264 with
     * Opus audio is not sent to a WebM muxer that would only reject the H.264.
     */
    private static boolean useWebmContainer(@NonNull MediaExtractor extractor) {
        boolean webmAudio = false;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                continue;
            }
            if (mime.startsWith("video/")) {
                return mime.equals(MediaFormat.MIMETYPE_VIDEO_VP8)
                        || mime.equals(MediaFormat.MIMETYPE_VIDEO_VP9);
            }
            if (mime.equals(MediaFormat.MIMETYPE_AUDIO_OPUS)
                    || mime.equals(MediaFormat.MIMETYPE_AUDIO_VORBIS)) {
                webmAudio = true;
            }
        }
        return webmAudio;
    }

    /**
     * Whether a gallery clean may save the quick transmux as it is (Faster Video Cleaning on,
     * Strict Clean off), which is the only reason to make one.
     */
    private boolean mayKeepTransmux() {
        if (host.testFastStripVideoMetadataOverride != null) {
            return true;
        }
        return !AppPreferences.isStrictClean(context) && AppPreferences.isVideoFallbackCopy(context);
    }

    /**
     * Whether transmux output can be saved without a full Media3 re-encode.
     * Strict-clean always re-encodes. Sharing and gallery fallback copy may reuse
     * {@link #fastStripVideoMetadata} output when it already matches the target container.
     */
    private boolean shouldSkipVideoTranscode(
            int formatIndex, @Nullable File tempCleanFile, boolean forSharing) {
        if (AppPreferences.isStrictClean(context) || tempCleanFile == null) {
            return false;
        }
        String expectedExtension = VideoMedia3Converter.extensionForFormatIndex(formatIndex);
        // A WebM copied into an MP4 to keep its location or date is the source's own picture.
        boolean keptInMp4 = tempCleanFile.getName().startsWith(KEEP_REMUX_PREFIX)
                && tempCleanFile.getName().endsWith(".mp4");
        if (!keptInMp4 && !tempCleanFile.getName().endsWith(expectedExtension)) {
            return false;
        }
        // Sharing always re-encodes: transmux does not strip container metadata (GPS, udta, etc.).
        if (forSharing) {
            return false;
        }
        if (!AppPreferences.isVideoFallbackCopy(context)) {
            return false;
        }
        // A transmux copies every sample as is, so user data inside the video stream (a
        // camcorder's date and GPS, a drone's telemetry) would survive it; only a re-encode
        // removes that, as Scan promises.
        VideoStreamMetadata.Report stream = VideoStreamMetadata.scan(context, Uri.fromFile(tempCleanFile), true);
        if (stream == null || stream.userDataMessages > 0) {
            SentryManager.log("The video stream carries user data, so the app is re-encoding it.");
            return false;
        }
        return true;
    }

    /**
     * Counts how a cleaned video was made: the quick copy that keeps the original streams, a
     * transcode after the quick copy failed its check, or a transcode from the start.
     */
    private static void countVideoPath(@NonNull String path) {
        SentryManager.count("clean.path", 1, "media_type", "video", "outcome", path);
    }
}
