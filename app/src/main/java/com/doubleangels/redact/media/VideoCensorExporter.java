package com.doubleangels.redact.media;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.Effect;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;

import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.MetadataStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Saves a censored copy of a video: every frame re-encoded with its areas covered
 * ({@link VideoCensorEffect}) and cropped, then checked like a Convert result and checked again
 * that every area is really covered ({@link CensorVerifier}) before it reaches the gallery. The
 * original is never changed, and the private working copy is securely deleted whatever happens.
 */
@OptIn(markerClass = UnstableApi.class)
public final class VideoCensorExporter {

    /** The lowest frame rate an export may be cut to (VideoMedia3Converter's last step). */
    private static final int LOWEST_EXPORT_FRAME_RATE = 24;

    private VideoCensorExporter() {
    }

    /** The source's upright frame size, frame rate and codec, as the effect will see it. */
    @VisibleForTesting
    static final class Source {
        final int width;
        final int height;
        final int frameRate;
        @Nullable
        final String mime;

        Source(int width, int height, int frameRate, @Nullable String mime) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.mime = mime;
        }

        /** Time between the source's frames, rounded up; 0 when the rate is unknown. */
        long frameIntervalUs() {
            return frameRate > 0 ? (1_000_000L + frameRate - 1) / frameRate : 0L;
        }
    }

    /**
     * @param fileName the source's file name, for the output's name when Keep Original File Names
     *                 is on, with "(censored)" added
     * @param regions  the areas, in fractions of the upright frame
     * @param crop     the part of the upright frame to keep, or null for all of it
     * @param cancelled polled between steps, so a cancelled save stops
     * @return the saved copy
     * @throws IOException when it can't be made, fails a check, or is cancelled; nothing is left
     *     in the gallery and the working copy is deleted
     */
    @NonNull
    public static Uri export(
            @NonNull Context context,
            @NonNull Uri source,
            @Nullable String fileName,
            @NonNull List<VideoCensorRegion> regions,
            @Nullable CensorRegion crop,
            @NonNull CensorEffects.Effect effect,
            @Nullable VideoMedia3Converter.TranscodeProgressListener progressListener,
            long transcodeOwnerId,
            @Nullable BooleanSupplier cancelled) throws IOException {
        Context app = context.getApplicationContext();
        Source info = readSource(app, source);
        if (info.width <= 0 || info.height <= 0) {
            // Without the frame size the areas can't be placed, nor checked afterwards.
            throw new UnsupportedMediaException("Unreadable video size", null);
        }
        long interval = info.frameIntervalUs();
        List<VideoCensorRegion> rounded = roundedOutward(regions, interval);
        List<Effect> effects = effects(rounded, crop, effect, interval);

        MetadataStripper stripper = new MetadataStripper(app);
        stripper.setCancellationCheck(cancelled);
        stripper.setTranscodeOwnerId(transcodeOwnerId);
        File censored = null;
        try {
            throwIfCancelled(cancelled);
            censored = File.createTempFile("vid_censor_", VideoMedia3Converter.TRANSCODE_EXTENSION,
                    app.getCacheDir());
            VideoMedia3Converter.transcodeEditedToPath(app, source, censored.getAbsolutePath(),
                    formatIndexFor(info.mime), effects, progressListener, transcodeOwnerId);
            throwIfCancelled(cancelled);
            CensorVerifier.requireVideoAreasCovered(censored, info.width, info.height, rounded, crop,
                    effect, interval);
            String name = MediaFileNames.outputBaseName(app, fileName, R.string.censor_file_name) + ".mp4";
            return stripper.saveCensoredVideo(source, censored, name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingCancelledException("Video censor interrupted", e);
        } finally {
            if (censored != null && censored.exists()) {
                stripper.deleteTempFile(censored);
            }
        }
    }

    private static void throwIfCancelled(@Nullable BooleanSupplier cancelled) throws IOException {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new ProcessingCancelledException("Video censor cancelled");
        }
    }

    /** Every area's range widened to whole frames, so no frame at either end is left uncovered. */
    @VisibleForTesting
    @NonNull
    static List<VideoCensorRegion> roundedOutward(@NonNull List<VideoCensorRegion> regions, long frameIntervalUs) {
        List<VideoCensorRegion> rounded = new ArrayList<>(regions.size());
        for (VideoCensorRegion region : regions) {
            rounded.add(region.roundedOutward(frameIntervalUs));
        }
        return rounded;
    }

    /**
     * The edits in the order they run: the areas covered on the whole upright frame, then the crop,
     * so the areas keep their place as they do on a photo.
     */
    @VisibleForTesting
    @NonNull
    static List<Effect> effects(@NonNull List<VideoCensorRegion> regions, @Nullable CensorRegion crop,
                                @NonNull CensorEffects.Effect effect, long frameIntervalUs) {
        List<Effect> effects = new ArrayList<>(2);
        effects.add(new VideoCensorEffect(regions, effect, frameMarginUs(frameIntervalUs)));
        if (crop != null && !crop.isEmpty()) {
            float[] c = crop.points();
            // Normalized device coordinates: x from -1 at the left, y from -1 at the bottom.
            effects.add(new Crop(2f * c[0] - 1f, 2f * c[2] - 1f, 1f - 2f * c[3], 1f - 2f * c[1]));
        }
        return effects;
    }

    /**
     * How long each frame counts as on screen when deciding whether a range covers it: two of the
     * source's frames, and at least one frame at the lowest rate an export may drop to, so a frame
     * kept after others were dropped is covered for all the time it shows. Unknown rates get
     * {@link VideoCensorEffect#DEFAULT_FRAME_DURATION_US}.
     */
    public static long frameMarginUs(long frameIntervalUs) {
        if (frameIntervalUs <= 0L) {
            return VideoCensorEffect.DEFAULT_FRAME_DURATION_US;
        }
        long lowestRateFrame = (1_000_000L + LOWEST_EXPORT_FRAME_RATE - 1) / LOWEST_EXPORT_FRAME_RATE;
        return Math.max(2L * frameIntervalUs, lowestRateFrame);
    }

    /** H.265 for an H.265 source, H.264 for anything else (VideoMedia3Converter's format index). */
    @VisibleForTesting
    static int formatIndexFor(@Nullable String mime) {
        return MimeTypes.VIDEO_H265.equals(mime) ? 1 : 0;
    }

    @VisibleForTesting
    @NonNull
    static Source readSource(@NonNull Context app, @NonNull Uri source) {
        VideoMedia3Converter.SourceVideo video = VideoMedia3Converter.readSourceVideo(app, source);
        int rotation = 0;
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(app, source);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            rotation = value != null ? Integer.parseInt(value.trim()) : 0;
        } catch (IOException | RuntimeException e) {
            // Unknown rotation: taken as upright. A wrong guess fails the coverage check, never
            // passes an uncovered area.
            rotation = 0;
        }
        boolean sideways = Math.floorMod(rotation, 180) == 90;
        return new Source(sideways ? video.height : video.width, sideways ? video.width : video.height,
                video.frameRate, video.mime);
    }
}
