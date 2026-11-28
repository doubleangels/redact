package com.doubleangels.redact.ui;

import android.os.SystemClock;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorExporter;
import com.doubleangels.redact.media.CensorRegion;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.media.OutputDestination;
import com.doubleangels.redact.media.UnsupportedMediaException;
import com.doubleangels.redact.media.VideoCensorExporter;
import com.doubleangels.redact.media.VideoCensorRegion;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.metadata.CleanStats;
import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.sentry.AppMetrics;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.stats.StatsStore;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import io.sentry.SpanStatus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * State for the Censor tab, shared with the activity so the picker, Scan and "Censor with
 * Redact" can all hand it a photo: the photo, the covered regions and the crop with undo and redo,
 * the covered preview and saving the result. Survives tab switches and rotation.
 *
 * <p>The crop is kept in fractions of the whole upright photo, like the regions, so neither has
 * to be moved when the other changes; the preview stays whole and the canvas dims what the crop
 * leaves out. Only the saved copy is cut down to it.
 */
public class CensorViewModel extends AndroidViewModel {

    public enum State { NONE, LOADING, LOAD_FAILED, UNSUPPORTED, READY, SAVING, SAVED, SAVE_FAILED }

    /**
     * Longer side of the editing preview, enough to see fine details when zoomed in; the saved
     * photo is full size (CensorExporter decodes the original again).
     */
    static final int PREVIEW_MAX_DIMENSION = 3072;
    /**
     * The effect until the user picks one. Black keeps nothing of what it covers; Pixelate and Blur
     * keep one noisy average colour per block, which attacks on pixelated text work from. An effect
     * the user chose before stays chosen.
     */
    static final CensorEffects.Effect DEFAULT_EFFECT = CensorEffects.Effect.BLACK;
    /**
     * The limit on a phone with a small heap: up to three previews are alive at once (the photo,
     * the covered copy and a spare), about 38 MB each at 3072 x 3072 but 17 MB at 2048.
     */
    static final int PREVIEW_MAX_DIMENSION_LOW_MEMORY = 2048;
    /** Heap size, in MB, below which the smaller preview is used. */
    static final int LOW_MEMORY_CLASS_MB = 256;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "redact-censor");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicInteger renderGeneration = new AtomicInteger();
    /** Bumped for every photo opened or closed, so a slow load cannot replace a newer photo. */
    private final AtomicInteger photoGeneration = new AtomicInteger();

    private final MutableLiveData<State> state = new MutableLiveData<>(State.NONE);
    private final MutableLiveData<Bitmap> preview = new MutableLiveData<>();
    private final MutableLiveData<String> fileName = new MutableLiveData<>();
    private final MutableLiveData<CensorEffects.Effect> effect =
            new MutableLiveData<>(DEFAULT_EFFECT);
    /** Bumped whenever the regions, the undo history or the saved copy change. */
    private final MutableLiveData<Integer> historyVersion = new MutableLiveData<>(0);
    /** A photo waiting for the user to discard unsaved edits before it replaces the current one. */
    private final MutableLiveData<Uri> pendingReplacement = new MutableLiveData<>();

    private final List<CensorRegion> regions = new ArrayList<>();
    /**
     * When each region covers a video, in the same order as {@link #regions}; every region of a
     * photo covers it whole.
     */
    private final List<Span> times = new ArrayList<>();
    /** The part of the photo the saved copy keeps, in fractions of the photo; null for all of it. */
    @Nullable
    private CensorRegion crop;

    /** When a region covers a video, in microseconds, both ends included. */
    public record Span(long startUs, long endUs) {
        public static final Span WHOLE = new Span(0L, VideoCensorRegion.END_OF_VIDEO);

        public boolean isWhole() {
            return startUs <= 0L && endUs == VideoCensorRegion.END_OF_VIDEO;
        }
    }

    /** One version of the edits: the regions, their times and the crop, as undo and redo restore them. */
    private record Edit(@NonNull List<CensorRegion> regions, @NonNull List<Span> times,
                        @Nullable CensorRegion crop) {
    }

    /** Whether the open file is a video, edited one frame at a time. */
    private volatile boolean video;
    /** Whether the file last asked for is a video, known even when it could not be opened. */
    private volatile boolean sourceIsVideo;
    /** The video's length and its time between frames, in microseconds; 0 when unknown. */
    private volatile long durationUs;
    private volatile long frameIntervalUs;
    /** The time of the frame being shown, in microseconds. */
    private volatile long playheadUs;
    /** Bumped for every frame asked for, so a slow decode cannot replace a newer frame. */
    private final AtomicInteger frameGeneration = new AtomicInteger();
    /** How far a video save has got, 0 to 100. */
    private final MutableLiveData<Integer> saveProgress = new MutableLiveData<>(0);
    /** Owns this screen's video exports, so cancelling one stops only it. */
    private final long transcodeOwner = NEXT_TRANSCODE_OWNER.incrementAndGet();
    private static final AtomicLong NEXT_TRANSCODE_OWNER = new AtomicLong(1L << 40);

    /** Earlier and undone versions of the edits, newest first. */
    private final Deque<Edit> undoHistory = new ArrayDeque<>();
    private final Deque<Edit> redoHistory = new ArrayDeque<>();

    @Nullable
    private Uri source;
    @Nullable
    private volatile Bitmap basePreview;
    /** The preview as last rendered, which a newly added region is drawn on top of. */
    @Nullable
    private volatile Bitmap renderedPreview;
    /** The preview on screen, set by the fragment, and the one it replaced, reused for the next render. */
    @Nullable
    private Bitmap shownPreview;
    private final AtomicReference<Bitmap> spare =
            new AtomicReference<>();
    /** The regions and effect {@link #renderedPreview} shows. */
    @NonNull
    private volatile List<CensorRegion> renderedRegions = List.of();
    @Nullable
    private volatile CensorEffects.Effect renderedEffect;
    @Nullable
    private volatile CensorExporter.Result lastSaved;
    private volatile boolean lastSaveFailed;
    /** Whether the photo fills the screen; kept here so it survives a rotation. */
    private boolean fullScreen;
    /** The regions and effect the last saved copy was made with, to tell whether there are unsaved edits. */
    @Nullable
    private volatile List<CensorRegion> savedRegions;
    @Nullable
    private volatile List<Span> savedTimes;
    @Nullable
    private volatile CensorEffects.Effect savedEffect;
    /** The crop the last saved copy was made with. */
    @Nullable
    private volatile CensorRegion savedCrop;

    private final int previewMaxDimension;

    public CensorViewModel(@NonNull Application application) {
        super(application);
        effect.setValue(savedEffect(application));
        previewMaxDimension = previewMaxDimension(application);
    }

    /** The preview's longer side for this phone's heap. */
    public static int previewMaxDimension(@NonNull Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return PREVIEW_MAX_DIMENSION_LOW_MEMORY;
        }
        return manager.isLowRamDevice() || manager.getMemoryClass() < LOW_MEMORY_CLASS_MB
                ? PREVIEW_MAX_DIMENSION_LOW_MEMORY
                : PREVIEW_MAX_DIMENSION;
    }

    /** The effect used last time, so it does not have to be picked again for every photo. */
    @NonNull
    static CensorEffects.Effect savedEffect(@NonNull Application application) {
        String name = AppPreferences.getCensorEffect(application);
        if (name != null) {
            try {
                return CensorEffects.Effect.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // A name from another version: use the default.
            }
        }
        return DEFAULT_EFFECT;
    }

    @NonNull
    public LiveData<State> getState() {
        return state;
    }

    @NonNull
    public LiveData<Bitmap> getPreview() {
        return preview;
    }

    @NonNull
    public LiveData<String> getFileName() {
        return fileName;
    }

    @NonNull
    public LiveData<CensorEffects.Effect> getEffect() {
        return effect;
    }

    @NonNull
    public LiveData<Integer> getHistoryVersion() {
        return historyVersion;
    }

    @NonNull
    public LiveData<Uri> getPendingReplacement() {
        return pendingReplacement;
    }

    @Nullable
    public CensorExporter.Result getLastSaved() {
        return lastSaved;
    }

    public boolean isLastSaveFailed() {
        return lastSaveFailed;
    }

    public boolean isFullScreen() {
        return fullScreen;
    }

    public void setFullScreen(boolean fullScreen) {
        this.fullScreen = fullScreen;
    }

    @Nullable
    public Uri getSource() {
        return source;
    }

    /** Whether the open file is a video. */
    public boolean isVideo() {
        return video;
    }

    /**
     * Whether the file last asked for is a video, even one that could not be opened, so the
     * screen can say which kind of file failed.
     */
    public boolean isSourceVideo() {
        return sourceIsVideo;
    }

    /** The open video's length in microseconds; 0 for a photo or when unknown. */
    public long getDurationUs() {
        return durationUs;
    }

    /** The time of the video frame on screen, in microseconds. */
    public long getPlayheadUs() {
        return playheadUs;
    }

    /** How far a video save has got, 0 to 100. */
    @NonNull
    public LiveData<Integer> getSaveProgress() {
        return saveProgress;
    }

    /**
     * Opens {@code uri} for censoring. The photo already open is kept with its edits; with
     * unsaved edits on another photo, the new one waits in {@link #getPendingReplacement}.
     */
    public void requestOpen(@NonNull Uri uri) {
        State current = state.getValue();
        if (uri.equals(source) && current != State.NONE && current != State.LOAD_FAILED) {
            return;
        }
        if (hasUnsavedEdits()) {
            pendingReplacement.setValue(uri);
            return;
        }
        open(uri);
    }

    /** The user chose to discard their edits for the waiting photo. */
    public void confirmReplacement() {
        Uri uri = pendingReplacement.getValue();
        pendingReplacement.setValue(null);
        if (uri != null) {
            open(uri);
        }
    }

    public void cancelReplacement() {
        pendingReplacement.setValue(null);
    }

    /** Closes the photo and forgets its edits. */
    public void close() {
        photoGeneration.incrementAndGet();
        renderGeneration.incrementAndGet();
        reset();
        source = null;
        fullScreen = false;
        state.setValue(State.NONE);
    }

    private void open(@NonNull Uri uri) {
        int generation = photoGeneration.incrementAndGet();
        renderGeneration.incrementAndGet();
        reset();
        source = uri;
        state.setValue(State.LOADING);
        worker.execute(() -> SentryManager.runInTransaction("censor.photo_open", () -> {
            // Asks the file's provider for its type, so off the main thread.
            boolean isVideo = isVideoFile(getApplication(), uri);
            sourceIsVideo = isVideo;
            SentryManager.logEvent("censor", isVideo
                    ? "A video was opened for censoring." : "A photo was opened for censoring.");
            try {
                String name = OutputDestination.displayName(getApplication().getContentResolver(), uri);
                Bitmap decoded = isVideo
                        ? openVideo(uri)
                        : fitPreview(FormatConverter.decodeUpright(getApplication(), uri, previewMaxDimension),
                                previewMaxDimension);
                if (generation != photoGeneration.get()) {
                    return;
                }
                video = isVideo;
                basePreview = decoded;
                renderedPreview = decoded;
                renderedRegions = List.of();
                fileName.postValue(name);
                preview.postValue(decoded);
                state.postValue(State.READY);
                AppMetrics.censorLoad(AppMetrics.SUCCESS);
            } catch (UnsupportedMediaException e) {
                SentryManager.logEvent("censor", "A photo could not be decoded for censoring.");
                SentryManager.setCurrentSpanStatus(SpanStatus.UNIMPLEMENTED);
                AppMetrics.censorLoad(AppMetrics.UNSUPPORTED);
                if (generation == photoGeneration.get()) {
                    state.postValue(State.UNSUPPORTED);
                }
            } catch (Exception e) {
                SentryManager.recordException(e);
                AppMetrics.censorLoad(AppMetrics.FAILED);
                if (generation == photoGeneration.get()) {
                    state.postValue(State.LOAD_FAILED);
                }
                SentryManager.failCurrentSpan(e);
            }
        }));
    }

    /**
     * Whether {@code uri} is a video, by its type or, when its provider gives none, its name. It
     * asks the provider, so it belongs off the main thread.
     */
    public static boolean isVideoFile(@NonNull Context context, @NonNull Uri uri) {
        String type;
        try {
            type = context.getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            // A provider that does not know the file: its name is all there is.
            type = null;
        }
        if (type != null) {
            return type.startsWith("video/");
        }
        String path = uri.getLastPathSegment();
        return path != null && MediaSelector.isVideoFromMimeAndName(null, path);
    }

    /**
     * Reads the video's length and frame rate and returns its first frame, upright and no larger
     * than the preview, on the worker.
     *
     * @throws UnsupportedMediaException when it has no frame this phone can decode
     */
    @NonNull
    private Bitmap openVideo(@NonNull Uri uri) throws IOException {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(getApplication(), uri);
            long duration = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
            long frames = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            Bitmap frame = frameAt(retriever, 0L);
            if (frame == null || duration <= 0L) {
                throw new UnsupportedMediaException("No video frame to censor", null);
            }
            durationUs = duration;
            frameIntervalUs = frames > 0 ? duration / frames : 0L;
            playheadUs = 0L;
            return frame;
        } catch (RuntimeException e) {
            throw new UnsupportedMediaException("Video unreadable for censoring", e);
        }
    }

    /** The upright frame nearest {@code timeUs}, no larger than the preview, or null. */
    @Nullable
    private Bitmap frameAt(@NonNull MediaMetadataRetriever retriever, long timeUs) {
        return retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                previewMaxDimension, previewMaxDimension);
    }

    private static long parseLong(@Nullable String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Shows the video's frame at {@code timeUs}, covered by the areas active then. Frames are
     * decoded off the main thread, and only the last one asked for is shown.
     */
    public void setPlayhead(long timeUs) {
        Uri uri = source;
        if (!video || uri == null) {
            return;
        }
        long clamped = Math.max(0L, Math.min(timeUs, Math.max(0L, durationUs - frameIntervalUs)));
        if (clamped == playheadUs && basePreview != null) {
            return;
        }
        playheadUs = clamped;
        int generation = frameGeneration.incrementAndGet();
        int photo = photoGeneration.get();
        worker.execute(() -> {
            if (generation != frameGeneration.get() || photo != photoGeneration.get()) {
                return;
            }
            Bitmap frame = null;
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(getApplication(), uri);
                frame = frameAt(retriever, clamped);
            } catch (IOException | RuntimeException e) {
                SentryManager.logEvent("censor", "A video frame could not be decoded for the preview.");
            }
            if (frame == null || generation != frameGeneration.get() || photo != photoGeneration.get()) {
                return;
            }
            basePreview = frame;
            renderedPreview = frame;
            renderedRegions = List.of();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (generation == frameGeneration.get()) {
                    bumpHistory();
                    render();
                }
            });
        });
    }

    /**
     * Scales a decoded photo down to {@code maxDimension} exactly: decoding only halves,
     * so a photo just under twice the limit would otherwise be kept at full size.
     */
    @NonNull
    private static Bitmap fitPreview(@NonNull Bitmap decoded, int maxDimension) {
        int longest = Math.max(decoded.getWidth(), decoded.getHeight());
        if (longest <= maxDimension) {
            return decoded;
        }
        float scale = maxDimension / (float) longest;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            if (scaled != decoded) {
                decoded.recycle();
            }
            return scaled;
        } catch (OutOfMemoryError e) {
            SentryManager.logEvent("censor", "The censor preview could not be scaled down because memory ran out.");
            AppMetrics.lowMemory("censor_preview_scale");
            return decoded;
        }
    }

    private void reset() {
        regions.clear();
        times.clear();
        video = false;
        sourceIsVideo = false;
        durationUs = 0L;
        frameIntervalUs = 0L;
        playheadUs = 0L;
        frameGeneration.incrementAndGet();
        saveProgress.setValue(0);
        crop = null;
        undoHistory.clear();
        redoHistory.clear();
        basePreview = null;
        renderedPreview = null;
        shownPreview = null;
        spare.set(null);
        renderedRegions = List.of();
        lastSaved = null;
        lastSaveFailed = false;
        savedRegions = null;
        savedTimes = null;
        savedEffect = null;
        savedCrop = null;
        preview.setValue(null);
        fileName.setValue(null);
        bumpHistory();
    }

    /** Adds a covered area; one without its own effect takes the current effect. */
    public void addRegion(@NonNull CensorRegion region) {
        if (region.effect() == null) {
            region = region.withEffect(effectOrDefault());
        }
        if (region.isEmpty() || (video && region.shape() != CensorRegion.Shape.RECT)) {
            // Clears the drawn outline, which waits for a new preview. A video has no brush.
            preview.setValue(preview.getValue());
            return;
        }
        remember();
        regions.add(region);
        // A new area covers the whole video until its time range is narrowed: covering too much
        // is the safe mistake.
        times.add(Span.WHOLE);
        lastSaveFailed = false;
        bumpHistory();
        renderAdded(region);
    }

    /**
     * Moves or resizes the region at {@code index}, as an edit that can be undone; a region without
     * its own effect keeps the one it had.
     */
    public void replaceRegion(int index, @NonNull CensorRegion region) {
        if (region.effect() == null && index >= 0 && index < regions.size()) {
            region = region.withEffect(regions.get(index).effect());
        }
        if (index < 0 || index >= regions.size() || region.isEmpty() || region.equals(regions.get(index))) {
            // Clears the drawn outline, which waits for a new preview.
            preview.setValue(preview.getValue());
            return;
        }
        remember();
        regions.set(index, region);
        historyChanged();
    }

    /** Removes the region at {@code index}, as an edit that can be undone. */
    public void removeRegion(int index) {
        if (index < 0 || index >= regions.size()) {
            return;
        }
        remember();
        regions.remove(index);
        times.remove(index);
        historyChanged();
    }

    /**
     * Sets when the region at {@code index} covers the video, in microseconds, as an edit that can
     * be undone. An end at or past the last frame covers to the end of the video.
     */
    public void setRegionTimes(int index, long startUs, long endUs) {
        if (index < 0 || index >= regions.size()) {
            return;
        }
        long start = Math.max(0L, Math.min(startUs, endUs));
        long end = Math.max(startUs, endUs);
        if (durationUs > 0L && end >= durationUs - Math.max(1L, frameIntervalUs)) {
            end = VideoCensorRegion.END_OF_VIDEO;
        }
        Span span = new Span(start, end);
        if (span.equals(times.get(index))) {
            bumpHistory();
            return;
        }
        remember();
        times.set(index, span);
        historyChanged();
    }

    /** When the region at {@code index} covers the video, or the whole of it when there is none. */
    @NonNull
    public Span regionTimes(int index) {
        return index >= 0 && index < times.size() ? times.get(index) : Span.WHOLE;
    }

    /**
     * Sets the part of the photo the saved copy keeps, in fractions of the photo, as an edit that
     * can be undone; null, or a crop of the whole photo, keeps all of it. The preview is not
     * covered again: the canvas dims what is left out.
     */
    public void setCrop(@Nullable CensorRegion newCrop) {
        CensorRegion normalized = newCrop == null || newCrop.isEmpty() || isWholePhoto(newCrop)
                ? null : newCrop;
        if (Objects.equals(normalized, crop)) {
            // Clears the frame being dragged, which waits for the edit to come back.
            bumpHistory();
            return;
        }
        remember();
        crop = normalized;
        lastSaveFailed = false;
        bumpHistory();
    }

    @Nullable
    public CensorRegion getCrop() {
        return crop;
    }

    private static boolean isWholePhoto(@NonNull CensorRegion r) {
        float[] p = r.points();
        return p[0] <= 0f && p[1] <= 0f && p[2] >= 1f && p[3] >= 1f;
    }

    /** Saves the current edits for undo, before they change. */
    private void remember() {
        undoHistory.push(currentEdit());
        redoHistory.clear();
    }

    @NonNull
    private Edit currentEdit() {
        return new Edit(new ArrayList<>(regions), new ArrayList<>(times), crop);
    }

    /** Puts back an earlier or undone version, covering the preview again only if the regions changed. */
    private void restore(@NonNull Edit edit) {
        boolean regionsChanged = !edit.regions().equals(regions) || !edit.times().equals(times);
        regions.clear();
        regions.addAll(edit.regions());
        times.clear();
        times.addAll(edit.times());
        crop = edit.crop();
        if (regionsChanged) {
            historyChanged();
        } else {
            lastSaveFailed = false;
            bumpHistory();
        }
    }

    /** Whether there is anything to save: a covered area or a crop. */
    public boolean hasEdits() {
        return !regions.isEmpty() || crop != null;
    }

    public boolean canUndo() {
        return !undoHistory.isEmpty();
    }

    public boolean canRedo() {
        return !redoHistory.isEmpty();
    }

    public void undo() {
        if (canUndo()) {
            redoHistory.push(currentEdit());
            restore(undoHistory.pop());
        }
    }

    public void redo() {
        if (canRedo()) {
            undoHistory.push(currentEdit());
            restore(redoHistory.pop());
        }
    }

    /**
     * Sets the effect for the areas covered from now on. Areas already covered keep theirs; use
     * {@link #setRegionEffect} to change one.
     */
    public void setEffect(@NonNull CensorEffects.Effect newEffect) {
        if (newEffect != effect.getValue()) {
            effect.setValue(newEffect);
            AppPreferences.setCensorEffect(getApplication(), newEffect.name());
            if (anyTakesPhotoEffect()) {
                lastSaveFailed = false;
                render();
            }
        }
    }

    /** Covers the region at {@code index} with {@code newEffect}, as an edit that can be undone. */
    public void setRegionEffect(int index, @NonNull CensorEffects.Effect newEffect) {
        if (index < 0 || index >= regions.size()) {
            return;
        }
        CensorRegion region = regions.get(index);
        if (region.effect() == newEffect) {
            return;
        }
        remember();
        regions.set(index, region.withEffect(newEffect));
        historyChanged();
    }

    /** The effect the region at {@code index} is covered with, or null when there is none. */
    @Nullable
    public CensorEffects.Effect regionEffect(int index) {
        if (index < 0 || index >= regions.size()) {
            return null;
        }
        CensorEffects.Effect own = regions.get(index).effect();
        return own != null ? own : effectOrDefault();
    }

    /** Whether any region has no effect of its own, so the current effect shows on it. */
    private boolean anyTakesPhotoEffect() {
        for (CensorRegion region : regions) {
            if (region.effect() == null) {
                return true;
            }
        }
        return false;
    }

    /** Whether there are edits (regions or a crop) that have not been saved in their current form. */
    public boolean hasUnsavedEdits() {
        if (!hasEdits()) {
            return false;
        }
        List<CensorRegion> saved = savedRegions;
        return saved == null || !saved.equals(regions) || !Objects.equals(savedCrop, crop)
                || (video && !times.equals(savedTimes))
                // The current effect only shows on areas without their own.
                || (anyTakesPhotoEffect() && savedEffect != effect.getValue());
    }

    @NonNull
    public List<CensorRegion> regions() {
        return new ArrayList<>(regions);
    }

    public void save() {
        Uri uri = source;
        if (uri == null || !hasEdits() || state.getValue() != State.READY) {
            return;
        }
        List<CensorRegion> snapshot = new ArrayList<>(regions);
        List<Span> timesSnapshot = new ArrayList<>(times);
        boolean isVideo = video;
        CensorRegion cropSnapshot = crop;
        CensorEffects.Effect chosen = effectOrDefault();
        int generation = photoGeneration.get();
        String name = fileName.getValue();
        saveProgress.setValue(0);
        state.setValue(State.SAVING);
        SentryManager.logEvent("censor", "The app is saving a censored copy.");
        long saveStartMs = SystemClock.elapsedRealtime();
        worker.execute(() -> SentryManager.runInTransaction("censor.copy_save", () -> {
            SentryManager.beginOperation("censor_export");
            try {
                CensorExporter.Result result = isVideo
                        ? CensorExporter.Result.ofVideo(VideoCensorExporter.export(getApplication(), uri, name,
                                timed(snapshot, timesSnapshot), cropSnapshot, chosen,
                                saveProgress::postValue, transcodeOwner, null))
                        : CensorExporter.export(getApplication(), uri, name, snapshot, cropSnapshot, chosen);
                AppMetrics.item(AppMetrics.CENSOR, false, AppMetrics.SUCCESS,
                        SystemClock.elapsedRealtime() - saveStartMs);
                AppMetrics.censorRegions(snapshot.size());
                // The copy went through a clean, so it counts in the statistics like one.
                StatsStore.recordClean(getApplication(),
                        result.stats != null ? result.stats
                                : new CleanStats(0, false),
                        false, false);
                if (generation != photoGeneration.get()) {
                    return;
                }
                lastSaved = result;
                lastSaveFailed = false;
                savedRegions = snapshot;
                savedTimes = timesSnapshot;
                savedEffect = chosen;
                savedCrop = cropSnapshot;
                // Counted here, so a save that finishes with the app in the background still counts.
                ReviewPrompt.recordSuccessfulJob(getApplication(),
                        result.stats != null && result.stats.hadLocation);
                AppPreferences.setSummaryHintLearned(
                        getApplication(), AppPreferences.HINT_CENSOR);
                LocalNotifications.showCleanComplete(getApplication(), 1, 0, result.stats != null && result.stats.hadLocation ? 1 : 0);
                SentryManager.logEvent("censor", "The censored copy was saved.");
                countCensorSave(result.downscaled, SystemClock.elapsedRealtime() - saveStartMs);
                state.postValue(State.SAVED);
            } catch (Exception e) {
                SentryManager.setCustomKey("error_type", SentryManager.errorType(e));
                AppMetrics.item(AppMetrics.CENSOR, false,
                        AppMetrics.outcomeOf(e, UnsupportedMediaException.isCauseOf(e)), -1);
                SentryManager.recordException(e);
                SentryManager.failCurrentSpan(e);
                if (generation == photoGeneration.get()) {
                    lastSaveFailed = true;
                    state.postValue(State.SAVE_FAILED);
                }
            }
        }));
    }

    /** The regions with their times, as the video exporter takes them. */
    @NonNull
    static List<VideoCensorRegion> timed(@NonNull List<CensorRegion> regions, @NonNull List<Span> times) {
        List<VideoCensorRegion> timed = new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            Span span = i < times.size() ? times.get(i) : Span.WHOLE;
            timed.add(VideoCensorRegion.of(regions.get(i), span.startUs(), span.endUs()));
        }
        return timed;
    }

    /** After the screen has reacted to a save (or its failure), editing continues. */
    public void acknowledgeSaveResult() {
        State current = state.getValue();
        if (current == State.SAVED || current == State.SAVE_FAILED) {
            state.setValue(State.READY);
            bumpHistory();
        }
    }

    private void historyChanged() {
        lastSaveFailed = false;
        bumpHistory();
        render();
    }

    private void bumpHistory() {
        Integer version = historyVersion.getValue();
        historyVersion.setValue(version == null ? 1 : version + 1);
    }

    /** Covers all the current regions in a copy of the photo, off the main thread. */
    private void render() {
        Bitmap base = basePreview;
        if (base == null) {
            return;
        }
        List<CensorRegion> snapshot = visibleRegions();
        CensorEffects.Effect chosen = effectOrDefault();
        int generation = renderGeneration.incrementAndGet();
        worker.execute(() -> {
            if (generation != renderGeneration.get()) {
                return;
            }
            if (snapshot.isEmpty()) {
                renderedPreview = base;
                renderedRegions = List.of();
                preview.postValue(base);
                return;
            }
            covered(base, snapshot, snapshot, chosen, generation);
        });
    }

    /**
     * Covers just a newly added region on top of the last preview: far less work than covering
     * every region again, and the regions already covered keep their look.
     */
    private void renderAdded(@NonNull CensorRegion region) {
        Bitmap base = basePreview;
        if (base == null) {
            return;
        }
        if (video) {
            // The frame shows only the areas active at the playhead.
            render();
            return;
        }
        List<CensorRegion> snapshot = new ArrayList<>(regions);
        List<CensorRegion> before = snapshot.subList(0, snapshot.size() - 1);
        CensorEffects.Effect chosen = effectOrDefault();
        int generation = renderGeneration.incrementAndGet();
        worker.execute(() -> {
            if (generation != renderGeneration.get()) {
                return;
            }
            Bitmap last = renderedPreview;
            if (last != null && chosen == renderedEffect && before.equals(renderedRegions)) {
                covered(last, List.of(region), snapshot, chosen, generation);
            } else {
                // A full render was skipped for this one: cover everything from the photo.
                covered(base, snapshot, snapshot, chosen, generation);
            }
        });
    }

    /** The regions the preview shows covered: for a video, those active at the playhead. */
    @NonNull
    List<CensorRegion> visibleRegions() {
        if (!video) {
            return new ArrayList<>(regions);
        }
        long margin = VideoCensorExporter.frameMarginUs(frameIntervalUs);
        List<CensorRegion> visible = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            Span span = i < times.size() ? times.get(i) : Span.WHOLE;
            if (VideoCensorRegion.of(regions.get(i), span.startUs(), span.endUs())
                    .coversFrame(playheadUs, margin)) {
                visible.add(regions.get(i));
            }
        }
        return visible;
    }

    /**
     * Called by the fragment with each preview it puts on screen. The one it replaced is no longer
     * drawn, so the next render can paint into it instead of allocating another full-size bitmap;
     * without this, every edit left a photo-sized bitmap for the garbage collector.
     */
    public void onPreviewShown(@Nullable Bitmap bitmap) {
        Bitmap previous = shownPreview;
        shownPreview = bitmap;
        if (previous != null && previous != bitmap && previous != basePreview && previous.isMutable()) {
            spare.set(previous);
        }
    }

    /** A bitmap to render into: the spare when it fits and is not {@code from}, else a new one. */
    @NonNull
    private Bitmap target(@NonNull Bitmap from) {
        Bitmap reuse = spare.getAndSet(null);
        if (reuse != null && reuse != from && !reuse.isRecycled() && from.getConfig() != Bitmap.Config.HARDWARE
                && reuse.getWidth() == from.getWidth() && reuse.getHeight() == from.getHeight()) {
            Paint copy = new Paint();
            copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            new Canvas(reuse).drawBitmap(from, 0, 0, copy);
            return reuse;
        }
        return from.copy(Bitmap.Config.ARGB_8888, true);
    }

    /**
     * Runs on the worker: copies {@code from}, covers {@code cover} in it and shows it as the
     * preview of {@code shows}.
     */
    private void covered(@NonNull Bitmap from, @NonNull List<CensorRegion> cover,
                         @NonNull List<CensorRegion> shows, @NonNull CensorEffects.Effect chosen,
                         int generation) {
        try {
            Bitmap copy = target(from);
            CensorEffects.apply(copy, cover, chosen);
            if (generation == renderGeneration.get()) {
                renderedPreview = copy;
                renderedRegions = new ArrayList<>(shows);
                renderedEffect = chosen;
                preview.postValue(copy);
            }
        } catch (OutOfMemoryError e) {
            SentryManager.logEvent("censor", "The censor preview ran out of memory.");
            AppMetrics.lowMemory("censor_preview");
            // Like a finished render, only for the photo and edits still on screen: after Clear
            // or another photo, this one must not come back.
            if (generation == renderGeneration.get()) {
                preview.postValue(from);
            }
        } catch (RuntimeException e) {
            // A region the effect can't draw leaves the last preview up, rather than ending the
            // app mid-edit. The save covers the regions again from the photo, so this is only
            // the preview.
            SentryManager.recordException(e);
            if (generation == renderGeneration.get()) {
                preview.postValue(from);
            }
        }
    }

    /**
     * Records how long the export took, and counts a copy that came out smaller than the original
     * (Max Processing Resolution set below the photo). Every save is counted by
     * {@link AppMetrics#item}.
     */
    private static void countCensorSave(boolean downscaled, long durationMs) {
        if (downscaled) {
            AppMetrics.censorSavedSmaller();
        }
        AppMetrics.jobDuration(AppMetrics.CENSOR, durationMs);
    }

    @NonNull
    private CensorEffects.Effect effectOrDefault() {
        CensorEffects.Effect chosen = effect.getValue();
        return chosen != null ? chosen : DEFAULT_EFFECT;
    }


    @Override
    protected void onCleared() {
        // A photo save already running finishes; nothing new is started. A video export would run
        // for minutes with nobody to see it, so it is stopped and its working copy deleted.
        VideoMedia3Converter.cancelActiveTranscode(transcodeOwner);
        worker.shutdown();
    }
}
