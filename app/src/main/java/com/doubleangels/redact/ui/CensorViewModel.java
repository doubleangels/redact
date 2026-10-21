package com.doubleangels.redact.ui;

import android.app.Application;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorExporter;
import com.doubleangels.redact.media.CensorRegion;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.UnsupportedMediaException;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State for the Censor tab, shared with the activity so the picker, Scan and "Censor with
 * Redact" can all hand it a photo: the photo, the covered regions with undo and redo, the covered
 * preview and saving the result. Survives tab switches and rotation.
 */
public class CensorViewModel extends AndroidViewModel {

    public enum State { NONE, LOADING, LOAD_FAILED, UNSUPPORTED, READY, SAVING, SAVED, SAVE_FAILED }

    /**
     * Longer side of the editing preview, enough to see fine details when zoomed in; the saved
     * photo is full size.
     */
    static final int PREVIEW_MAX_DIMENSION = 3072;

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
            new MutableLiveData<>(CensorEffects.Effect.PIXELATE);
    /** Bumped whenever the regions, the undo history or the saved copy change. */
    private final MutableLiveData<Integer> historyVersion = new MutableLiveData<>(0);
    /** A photo waiting for the user to discard unsaved edits before it replaces the current one. */
    private final MutableLiveData<Uri> pendingReplacement = new MutableLiveData<>();

    private final List<CensorRegion> regions = new ArrayList<>();
    /** Earlier and undone versions of {@link #regions}, newest first. */
    private final Deque<List<CensorRegion>> undoHistory = new ArrayDeque<>();
    private final Deque<List<CensorRegion>> redoHistory = new ArrayDeque<>();

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
    private final java.util.concurrent.atomic.AtomicReference<Bitmap> spare =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** The regions and effect {@link #renderedPreview} shows. */
    @NonNull
    private volatile List<CensorRegion> renderedRegions = List.of();
    @Nullable
    private volatile CensorEffects.Effect renderedEffect;
    @Nullable
    private volatile CensorExporter.Result lastSaved;
    private volatile boolean lastSaveFailed;
    /** The regions and effect the last saved copy was made with, to tell whether there are unsaved edits. */
    @Nullable
    private volatile List<CensorRegion> savedRegions;
    @Nullable
    private volatile CensorEffects.Effect savedEffect;

    public CensorViewModel(@NonNull Application application) {
        super(application);
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

    @Nullable
    public Uri getSource() {
        return source;
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
        state.setValue(State.NONE);
    }

    private void open(@NonNull Uri uri) {
        int generation = photoGeneration.incrementAndGet();
        renderGeneration.incrementAndGet();
        reset();
        source = uri;
        state.setValue(State.LOADING);
        worker.execute(() -> {
            try {
                String name = queryFileName(uri);
                Bitmap decoded = fitPreview(
                        FormatConverter.decodeUpright(getApplication(), uri, PREVIEW_MAX_DIMENSION));
                if (generation != photoGeneration.get()) {
                    return;
                }
                basePreview = decoded;
                renderedPreview = decoded;
                renderedRegions = List.of();
                fileName.postValue(name);
                preview.postValue(decoded);
                state.postValue(State.READY);
            } catch (UnsupportedMediaException e) {
                SentryManager.log("A photo could not be decoded for censoring.");
                if (generation == photoGeneration.get()) {
                    state.postValue(State.UNSUPPORTED);
                }
            } catch (Exception e) {
                SentryManager.recordException(e);
                if (generation == photoGeneration.get()) {
                    state.postValue(State.LOAD_FAILED);
                }
            }
        });
    }

    /**
     * Scales a decoded photo down to {@link #PREVIEW_MAX_DIMENSION} exactly: decoding only halves,
     * so a photo just under twice the limit would otherwise be kept at full size.
     */
    @NonNull
    private static Bitmap fitPreview(@NonNull Bitmap decoded) {
        int longest = Math.max(decoded.getWidth(), decoded.getHeight());
        if (longest <= PREVIEW_MAX_DIMENSION) {
            return decoded;
        }
        float scale = PREVIEW_MAX_DIMENSION / (float) longest;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            if (scaled != decoded) {
                decoded.recycle();
            }
            return scaled;
        } catch (OutOfMemoryError e) {
            return decoded;
        }
    }

    private void reset() {
        regions.clear();
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
        savedEffect = null;
        preview.setValue(null);
        fileName.setValue(null);
        bumpHistory();
    }

    public void addRegion(@NonNull CensorRegion region) {
        if (region.isEmpty()) {
            // Clears the drawn outline, which waits for a new preview.
            preview.setValue(preview.getValue());
            return;
        }
        remember();
        regions.add(region);
        lastSaveFailed = false;
        bumpHistory();
        renderAdded(region);
    }

    /** Moves or resizes the region at {@code index}, as an edit that can be undone. */
    public void replaceRegion(int index, @NonNull CensorRegion region) {
        if (index < 0 || index >= regions.size() || region.isEmpty() || region.equals(regions.get(index))) {
            // Clears the drawn outline, which waits for a new preview.
            preview.setValue(preview.getValue());
            return;
        }
        remember();
        regions.set(index, region);
        historyChanged();
    }

    /** Saves the current regions for undo, before they change. */
    private void remember() {
        undoHistory.push(new ArrayList<>(regions));
        redoHistory.clear();
    }

    public boolean hasRegions() {
        return !regions.isEmpty();
    }

    public boolean canUndo() {
        return !undoHistory.isEmpty();
    }

    public boolean canRedo() {
        return !redoHistory.isEmpty();
    }

    public void undo() {
        if (canUndo()) {
            redoHistory.push(new ArrayList<>(regions));
            regions.clear();
            regions.addAll(undoHistory.pop());
            historyChanged();
        }
    }

    public void redo() {
        if (canRedo()) {
            undoHistory.push(new ArrayList<>(regions));
            regions.clear();
            regions.addAll(redoHistory.pop());
            historyChanged();
        }
    }

    public void setEffect(@NonNull CensorEffects.Effect newEffect) {
        if (newEffect != effect.getValue()) {
            effect.setValue(newEffect);
            lastSaveFailed = false;
            render();
        }
    }

    /** Whether there are regions that have not been saved in their current form. */
    public boolean hasUnsavedEdits() {
        if (regions.isEmpty()) {
            return false;
        }
        List<CensorRegion> saved = savedRegions;
        return saved == null || !saved.equals(regions) || savedEffect != effect.getValue();
    }

    @NonNull
    public List<CensorRegion> regions() {
        return new ArrayList<>(regions);
    }

    public void save() {
        Uri uri = source;
        if (uri == null || regions.isEmpty() || state.getValue() != State.READY) {
            return;
        }
        List<CensorRegion> snapshot = new ArrayList<>(regions);
        CensorEffects.Effect chosen = effectOrDefault();
        int generation = photoGeneration.get();
        String name = fileName.getValue();
        state.setValue(State.SAVING);
        SentryManager.logEvent("censor", "The user saved a censored photo.");
        worker.execute(() -> {
            try {
                CensorExporter.Result result =
                        CensorExporter.export(getApplication(), uri, name, snapshot, chosen);
                if (generation != photoGeneration.get()) {
                    return;
                }
                lastSaved = result;
                lastSaveFailed = false;
                savedRegions = snapshot;
                savedEffect = chosen;
                // Counted here, so a save that finishes with the app in the background still counts.
                ReviewPrompt.recordSuccessfulJob(getApplication());
                state.postValue(State.SAVED);
            } catch (Exception e) {
                SentryManager.recordException(e);
                if (generation == photoGeneration.get()) {
                    lastSaveFailed = true;
                    state.postValue(State.SAVE_FAILED);
                }
            }
        });
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
        List<CensorRegion> snapshot = new ArrayList<>(regions);
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
        if (reuse != null && reuse != from && !reuse.isRecycled()
                && reuse.getWidth() == from.getWidth() && reuse.getHeight() == from.getHeight()) {
            android.graphics.Paint copy = new android.graphics.Paint();
            copy.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC));
            new android.graphics.Canvas(reuse).drawBitmap(from, 0, 0, copy);
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
            SentryManager.log("The censor preview ran out of memory.");
            preview.postValue(from);
        }
    }

    @NonNull
    private CensorEffects.Effect effectOrDefault() {
        CensorEffects.Effect chosen = effect.getValue();
        return chosen != null ? chosen : CensorEffects.Effect.PIXELATE;
    }

    @Nullable
    private String queryFileName(@NonNull Uri uri) {
        try (Cursor cursor = getApplication().getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onCleared() {
        // A save already running finishes; nothing new is started.
        worker.shutdown();
    }
}
