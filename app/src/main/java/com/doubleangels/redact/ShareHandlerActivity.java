package com.doubleangels.redact;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.BatchRun;
import com.doubleangels.redact.media.InboundSnapshots;
import java.util.HashMap;
import java.util.Map;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.MediaPickerContracts;
import com.doubleangels.redact.media.InboundUriPolicy;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.media.ProgressUpdateThrottler;
import com.doubleangels.redact.media.SecureDelete;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.metadata.MetadataStripper;
import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.media.ProcessingResourceWarnings;
import com.doubleangels.redact.metadata.CleanResult;
import com.doubleangels.redact.metadata.CleanStats;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.doubleangels.redact.sentry.AppMetrics;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.stats.StatsStore;
import com.doubleangels.redact.ui.ReviewPrompt;
import com.doubleangels.redact.ui.SecureWindow;
import com.doubleangels.redact.ui.ShareResults;
import com.google.android.material.color.DynamicColors;


import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class ShareHandlerActivity extends AppCompatActivity {

    /**
     * How long shared files are kept so the target app can read them. Some apps read a shared
     * file only when the user finally sends (an email draft, a chat queued offline), so this is
     * generous; the files are already clean. If the process dies first, the next start removes
     * them ({@link CacheCleanup#SHARED_OUTPUT_MAX_AGE_MS}).
     */
    private static final long SHARE_CLEANUP_DELAY_MS = CacheCleanup.SHARED_OUTPUT_MAX_AGE_MS;
    private static final String KEY_SHARING_INITIATED = "sharing_initiated";
    private static final String KEY_PROCESSING_ACTIVE = "processing_active";

    /**
     * Runs secure-delete cleanup off the calling thread. Overwriting a large video file with
     * random data multiple times plus fsync (see {@link SecureDelete}) can take long enough to
     * trip Android's ANR watchdog when it runs synchronously on the main thread, which is where
     * cancel/finish/onDestroy call into cleanup.
     */
    private static final ExecutorService cleanupExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "redact-share-cleanup"));

    /**
     * Marks a share Redact's own Clean tab started with {@link #shareWithoutSaving}, which skips
     * the confirmation a share from another app asks for: the user just tapped Share Without
     * Saving. A random value per process, so another app cannot set it to skip the question.
     */
    private static final String EXTRA_INTERNAL_TOKEN = "com.doubleangels.redact.extra.INTERNAL_TOKEN";
    private static final String INTERNAL_TOKEN = UUID.randomUUID().toString();

    /**
     * Cleans {@code uris} the way a share into Redact does and opens the share sheet with the
     * clean copies, which are deleted afterwards rather than saved to the gallery. Used by the
     * Clean tab's Share Without Saving.
     */
    public static void shareWithoutSaving(@NonNull Context context, @NonNull List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        Intent intent = new Intent(context, ShareHandlerActivity.class);
        if (uris.size() == 1) {
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        intent.setType("*/*");
        intent.putExtra(EXTRA_INTERNAL_TOKEN, INTERNAL_TOKEN);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    /** Whether {@code intent} came from {@link #shareWithoutSaving} in this process. */
    @VisibleForTesting
    static boolean isInternal(@Nullable Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            return INTERNAL_TOKEN.equals(intent.getStringExtra(EXTRA_INTERNAL_TOKEN));
        } catch (RuntimeException badExtras) {
            // Another app's malformed extras: treat it as the outside share it is.
            return false;
        }
    }

    private final ActivityResultLauncher<Intent> shareResultLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    // The chooser forwards the chosen app's result, and most apps never set one, so
                    // RESULT_CANCELED does not mean nothing was shared: an app that opens in its own
                    // task returns it at once, before it has read anything. Every result waits.
                    result -> {
                        scheduleDelayedShareCleanup();
                        finish();
                    });

    /** Builds the cleaner each share uses; tests swap it to hold a share inside its clean. */
    @VisibleForTesting
    static Function<Context, MetadataStripper> stripperFactory = MetadataStripper::new;

    private MediaSelector mediaSelector;

    private final Object fileListLock = new Object();
    private final List<File> processedFiles = new ArrayList<>();
    private final List<String> processedDisplayNames = new ArrayList<>();
    
    private boolean sharingInitiated = false;
    private volatile boolean processingActive = false;
    private final AtomicBoolean activityDestroyed = new AtomicBoolean(false);
    private final AtomicInteger shareSessionId =
            new AtomicInteger(0);
    /** The share this activity is running; a new intent replaces it (see {@link #onNewIntent}). */
    private volatile ShareSession shareSession;
    private volatile Thread processingThread;

    /**
     * One share, from the inbound intent to the chooser. A second share can arrive while the first
     * is still cleaning ({@code singleTask}), so everything a worker touches belongs to its own
     * session: its cleaner and that cleaner's last-result fields, and its inbound snapshots, which
     * the worker deletes without reaching the next share's.
     */
    static final class ShareSession {
        final int id;
        final MetadataStripper stripper;
        final AtomicBoolean ended = new AtomicBoolean(false);
        /** Whether {@link AppProcessingScope} counts this session as running; touched on the main thread only. */
        boolean counted;
        private final List<File> snapshots = new ArrayList<>();
        private boolean snapshotsClosed;
        /**
         * Items dropped while copying them to cache; counted as failures in the final toast. Kept
         * per share, so the worker of a share that was replaced can't change the next one's count.
         */
        final AtomicInteger snapshotFailCount = new AtomicInteger();
        /** Items dropped for a non-media type before copying; also counted as failures. */
        final AtomicInteger unsupportedRejectCount = new AtomicInteger();

        ShareSession(int id, @NonNull MetadataStripper stripper) {
            this.id = id;
            this.stripper = stripper;
        }

        /** Keeps a snapshot for later deletion; false once the session's snapshots were deleted. */
        synchronized boolean addSnapshot(@NonNull File snapshot) {
            if (snapshotsClosed) {
                return false;
            }
            snapshots.add(snapshot);
            return true;
        }

        /** Hands over every snapshot for deletion and refuses any added later. */
        synchronized List<File> closeSnapshots() {
            snapshotsClosed = true;
            List<File> taken = new ArrayList<>(snapshots);
            snapshots.clear();
            return taken;
        }
    }

    private AlertDialog progressDialog;
    private TextView progressMessageView;
    private final ProgressUpdateThrottler shareProgressThrottler = new ProgressUpdateThrottler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        SecureWindow.apply(this);

        try {
            mediaSelector = new MediaSelector(this);
            shareSession = newShareSession();

            SentryManager.logEvent("share", "The ShareHandlerActivity was created.");

            if (savedInstanceState != null) {
                sharingInitiated = savedInstanceState.getBoolean(KEY_SHARING_INITIATED, false);
                boolean wasProcessing = savedInstanceState.getBoolean(KEY_PROCESSING_ACTIVE, false);
                if (wasProcessing) {
                    finishWithError(getString(R.string.share_error_interrupted));
                    return;
                }
                if (sharingInitiated) {
                    finish();
                    return;
                }
            }

            createProgressDialog();

            if (savedInstanceState == null) {
                handleIntent(getIntent());
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.share_error_generic));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ShareSession previous = shareSession;
        synchronized (fileListLock) {
            // Switched under the lock so a worker of the old share cannot add a processed file
            // after cleanupProcessedOutputs below has emptied the list (see processMediaItems).
            shareSession = newShareSession();
        }
        if (previous != null) {
            previous.stripper.requestCancellation();
            endShareSession(previous);
            deleteSnapshots(previous.closeSnapshots());
            VideoMedia3Converter.cancelActiveTranscode(VideoMedia3Converter.shareOwner(previous.id));
        }
        Thread thread = processingThread;
        if (thread != null) {
            thread.interrupt();
        }
        LocalNotifications.stopProcessingForeground(this, LocalNotifications.ForegroundJob.SHARE);
        processingActive = false;
        sharingInitiated = false;
        // The old session's snapshots went above; the new session has none of its own yet.
        cleanupProcessedOutputs();
        dismissProgressDialog();
        createProgressDialog();
        handleIntent(intent);
    }

    @NonNull
    private ShareSession newShareSession() {
        ShareSession session = new ShareSession(shareSessionId.incrementAndGet(), stripperFactory.apply(this));
        session.stripper.setTranscodeOwnerId(VideoMedia3Converter.shareOwner(session.id));
        session.stripper.setProgressCallback((percent, message) ->
                shareProgressThrottler.maybeRun(
                        () -> runOnUiThreadForSession(session, () -> updateProgressMessage(message))));
        return session;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_SHARING_INITIATED, sharingInitiated);
        outState.putBoolean(KEY_PROCESSING_ACTIVE, processingActive);
    }

    private void createProgressDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.dialog_progress, null, false);
        progressMessageView = dialogView.findViewById(R.id.progress_message);

        progressDialog = builder
                .setTitle(R.string.share_processing_title)
                .setView(dialogView)
                .setNegativeButton(R.string.button_cancel, (d, w) -> cancelShareProcessing())
                .setCancelable(true)
                .setOnCancelListener(d -> cancelShareProcessing())
                .create();

        progressMessageView.setText(getString(R.string.status_processing));
        progressDialog.show();
    }

    private void updateProgressMessage(String message) {
        if (progressMessageView != null) {
            progressMessageView.setText(message);
        }
    }

    private void safeRunOnUiThread(Runnable action) {
        if (activityDestroyed.get() || isFinishing()) {
            return;
        }
        runOnUiThread(() -> {
            if (!activityDestroyed.get() && !isFinishing()) {
                action.run();
            }
        });
    }

    private void handleIntent(Intent intent) {
        try {
            String action = intent.getAction();
            String type = intent.getType();

            SentryManager.setCustomKey("intent.action", action != null ? action : "null");
            // The sender writes the type, so only a plain MIME type is sent, never its own text.
            SentryManager.setCustomKey("intent.type", mimeTypeTag(type));

            if (Intent.ACTION_SEND.equals(action)) {
                if (type == null || type.startsWith("image/") || type.startsWith("video/")
                        || "*/*".equals(type)) {
                    SentryManager.logEvent("share", "The app is handling a single media item.");
                    handleSentMedia(intent);
                } else {
                    SentryManager.setCustomKey("intent.unsupported_type", mimeTypeTag(type));
                    finishWithError(getString(R.string.share_error_unsupported_media));
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                if (type == null || type.startsWith("image/") || type.startsWith("video/")
                        || "*/*".equals(type)) {
                    SentryManager.logEvent("share", "The app is handling multiple media items.");
                    handleMultipleMedia(intent);
                } else {
                    SentryManager.setCustomKey("intent.unsupported_type", mimeTypeTag(type));
                    finishWithError(getString(R.string.share_error_unsupported_media));
                }
            } else {
                SentryManager.setCustomKey("intent.unsupported_action", action != null ? action : "null");
                finishWithError(getString(R.string.share_error_unsupported_action));
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.status_extraction_media_fail));
        }
    }

    private void handleSentMedia(Intent intent) {
        try {
            List<Uri> uris = extractUrisFromIntent(intent);
            if (uris.isEmpty()) {
                SentryManager.logEvent("share", "The app received no media URI.");
                finishWithError(getString(R.string.share_error_failed_receive_media));
                return;
            }
            if (uris.size() > MediaPickerContracts.MAX_SHARE_ITEMS) {
                finishWithError(getString(R.string.share_error_too_many_items, MediaPickerContracts.MAX_SHARE_ITEMS));
                return;
            }
            prepareAndProcess(uris);
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.share_error_generic));
        }
    }

    private void handleMultipleMedia(Intent intent) {
        try {
            List<Uri> uris = extractUrisFromIntent(intent);
            if (!uris.isEmpty()) {
                if (uris.size() > MediaPickerContracts.MAX_SHARE_ITEMS) {
                    finishWithError(getString(R.string.share_error_too_many_items, MediaPickerContracts.MAX_SHARE_ITEMS));
                    return;
                }
                prepareAndProcess(uris);
            } else {
                SentryManager.logEvent("share", "The app received an empty media list.");
                finishWithError(getString(R.string.share_error_failed_receive_media));
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.share_error_generic));
        }
    }

    /**
     * Checks the shared items, warns about storage and copies them to cache, all off the main
     * thread: each step reads every item through its provider, and the copy reads whole files,
     * which for a large video takes long enough to freeze the screen. The warning comes before the
     * copy, so the user can stop before space is used.
     */
    private void prepareAndProcess(@NonNull List<Uri> uris) {
        final ShareSession session = shareSession;
        // A recreated activity cannot resume this, so treat it like a running job (see onCreate).
        processingActive = true;
        startShareThread("redact-share-check", session, () -> {
            List<Uri> accepted = filterSupportedUris(uris, session);
            List<ProcessingResourceWarnings.Warning> warnings = accepted.isEmpty()
                    ? new ArrayList<>()
                    : ProcessingResourceWarnings.assessShareUris(
                            this, getContentResolver(), accepted, mediaSelector);
            runOnUiThreadForSession(session, () -> {
                if (accepted.isEmpty()) {
                    finishWithError(getString(session.unsupportedRejectCount.get() > 0
                            ? R.string.share_error_unsupported_media
                            : R.string.share_error_failed_receive_media));
                    return;
                }
                ProcessingResourceWarnings.runWithWarnings(
                        this, warnings, () -> snapshotAndProcess(accepted, session));
            });
        });
    }

    private void snapshotAndProcess(@NonNull List<Uri> accepted, @NonNull ShareSession session) {
        startShareThread("redact-share-snapshot", session, () -> {
            List<Uri> snapshotted = snapshotInboundUris(accepted, session);
            runOnUiThreadForSession(session, () -> {
                if (snapshotted.isEmpty()) {
                    finishWithError(getString(R.string.share_error_failed_receive_media));
                    return;
                }
                SentryManager.setCustomKey("share.media_count", snapshotted.size());
                maybeConfirmAndProcess(snapshotted, session);
            });
        });
    }

    /** Runs preparation work on its own thread; cancelling the share interrupts it. */
    private void startShareThread(@NonNull String name, @NonNull ShareSession session, @NonNull Runnable work) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Exception e) {
                SentryManager.recordException(e);
                runOnUiThreadForSession(session, () -> finishWithError(getString(R.string.share_error_generic)));
            }
        }, name);
        processingThread = thread;
        thread.start();
    }

    /** Runs {@code action} on the main thread unless the share it belongs to was replaced or ended. */
    private void runOnUiThreadForSession(@NonNull ShareSession session, @NonNull Runnable action) {
        safeRunOnUiThread(() -> {
            if (session == shareSession && !session.ended.get()) {
                action.run();
            }
        });
    }

    /**
     * Runs {@code action} on the main thread unless a newer share replaced {@code session}. For a
     * worker's last callback: the worker ends its session before the callback gets to run.
     */
    private void runOnUiThreadIfNotReplaced(@NonNull ShareSession session, @NonNull Runnable action) {
        safeRunOnUiThread(() -> {
            if (session == shareSession) {
                action.run();
            }
        });
    }

    /** Whether {@code session} is still the share this activity is running. */
    private boolean isCurrentSession(@NonNull ShareSession session) {
        return session == shareSession
                && !session.ended.get()
                && !activityDestroyed.get()
                && !Thread.currentThread().isInterrupted();
    }

    /** Copies each item to cache, on a background thread; stops early when the share ends. */
    @NonNull
    private List<Uri> snapshotInboundUris(@NonNull List<Uri> uris, @NonNull ShareSession session) {
        // Intake lets through only another app's content:// (isSupportedInboundUri), so each item
        // is copied from its provider.
        return InboundSnapshots.copyAll(getApplicationContext(), uris, () -> isCurrentSession(session),
                session::addSnapshot, session.snapshotFailCount);
    }

    /** Starts cleaning the snapshots, after asking first when Settings says to. */
    private void maybeConfirmAndProcess(List<Uri> uris, @NonNull ShareSession session) {
        if (AppPreferences.isShareConfirmBeforeStrip(this) && !isInternal(getIntent())) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_share_confirm_dialog_title)
                    .setMessage(R.string.settings_share_confirm_dialog_message)
                    .setPositiveButton(
                            R.string.settings_share_confirm_dialog_confirm,
                            (d, w) -> {
                                // A dialog left over from a share that a newer one replaced
                                // must not start or end the newer one.
                                if (isCurrentSession(session)) {
                                    createProgressDialog();
                                    processMediaItems(uris, session);
                                }
                            })
                    .setNegativeButton(
                            R.string.settings_share_confirm_dialog_cancel, (d, w) -> finishIfCurrent(session))
                    .setOnCancelListener(d -> finishIfCurrent(session))
                    .show();
        } else {
            processMediaItems(uris, session);
        }
    }

    private void finishIfCurrent(@NonNull ShareSession session) {
        if (session == shareSession) {
            finish();
        }
    }

    private void processMediaItems(List<Uri> uris, @NonNull ShareSession session) {
        final MetadataStripper stripper = session.stripper;
        processingActive = true;
        session.counted = true;
        AppProcessingScope.get(this).shareStarted();
        LocalNotifications.startProcessingForeground(
                this, LocalNotifications.ForegroundJob.SHARE, getString(R.string.share_processing_title));
        Thread worker = new Thread(() -> {
            ArrayList<Uri> processedUris = new ArrayList<>();
            // Files that could not even be copied in count as given and failed.
            int alreadyFailed = session.snapshotFailCount.get() + session.unsupportedRejectCount.get();
            int[] unsupportedFailCount = {session.unsupportedRejectCount.get()};
            boolean[] hasVideo = {false};
            boolean[] hasImage = {false};
            Map<Uri, SharedItem> described = new HashMap<>();
            try {
                BatchRun.Result result = BatchRun.run(AppMetrics.SHARE, "share_cleanup", uris, alreadyFailed,
                        uri -> describe(described, uri).video,
                        () -> !isCurrentSession(session) || Thread.currentThread().isInterrupted(),
                        (uri, i) -> {
                            int currentIndex = i + 1;
                            int total = uris.size();
                            runOnUiThreadForSession(session, () -> updateProgressMessage(
                                    getString(R.string.status_processing) + " (" + currentIndex + "/" + total + ")"));
                            SharedItem item = describe(described, uri);
                            if (item.video) {
                                hasVideo[0] = true;
                            } else {
                                hasImage[0] = true;
                            }
                            CleanResult cleaned = stripper.cleanForSharing(uri, item.fileName, item.video);
                            if (!cleaned.succeeded()) {
                                return cleaned.unsupported() ? BatchRun.Outcome.UNSUPPORTED : BatchRun.Outcome.NOT_CLEANED;
                            }
                            processedUris.add(cleaned.uri());
                            CleanStats stats = cleaned.stats();
                            StatsStore.recordClean(this, stats != null ? stats : new CleanStats(0, false), item.video, true);
                            keepForCleanup(session, cleaned);
                            return BatchRun.Outcome.SUCCEEDED;
                        },
                        new BatchRun.Hooks<>() {
                            @Override
                            public void onFailed(@NonNull Uri uri, int index, @Nullable Throwable error,
                                                 @NonNull String outcome) {
                                if (AppMetrics.UNSUPPORTED.equals(outcome)) {
                                    unsupportedFailCount[0]++;
                                    countUnsupported(describe(described, uri).mimeType);
                                }
                            }
                        });

                final boolean finalHasVideo = hasVideo[0];
                final boolean finalHasImage = hasImage[0];
                final int finalFailCount = result.failed() + alreadyFailed;
                final boolean allFailuresUnsupported =
                        finalFailCount > 0 && unsupportedFailCount[0] == finalFailCount;
                final boolean cancelled = result.cancelled() || session.ended.get();

                runOnUiThreadIfNotReplaced(session, () -> {
                    try {
                        dismissProgressDialog();

                        if (cancelled) {
                            Toast.makeText(
                                            ShareHandlerActivity.this,
                                            R.string.status_processing_cancelled,
                                            Toast.LENGTH_SHORT)
                                    .show();
                            cleanupProcessedFiles();
                            finish();
                            return;
                        }

                        if (!processedUris.isEmpty()) {
                            if (finalFailCount > 0) {
                                Toast.makeText(
                                                ShareHandlerActivity.this,
                                                getString(
                                                        R.string.share_error_partial_success,
                                                        processedUris.size(),
                                                        finalFailCount),
                                                Toast.LENGTH_LONG)
                                        .show();
                            }
                            SentryManager.logEvent("share", "Media processing completed successfully.");
                            if (finalFailCount == 0) {
                                // Counted only: the share sheet is about to open, so this is no
                                // time to ask for a rating.
                                ReviewPrompt.recordSuccessfulJob(
                                        ShareHandlerActivity.this);
                            }
                            if (processedUris.size() == 1) {
                                shareCleanFile(finalHasVideo, processedUris.get(0));
                            } else {
                                shareCleanFiles(finalHasVideo, finalHasImage, processedUris);
                            }
                        } else {
                            SentryManager.logEvent("share", "Media processing failed.");
                            finishWithError(getString(allFailuresUnsupported
                                    ? R.string.share_error_unsupported_media
                                    : R.string.share_error_processing_failed));
                        }
                    } catch (Exception e) {
                        SentryManager.recordException(e);
                        finishWithError(getString(R.string.share_error_generic));
                    }
                });
            } catch (Exception | OutOfMemoryError e) {
                // A crafted shared file must end in an error message, never a crash. BatchRun has
                // already counted the batch.
                SentryManager.recordException(e);
                runOnUiThreadIfNotReplaced(session, () -> finishWithError(getString(R.string.share_error_generic)));
            } finally {
                // The snapshots are copies of the originals, metadata included, and only the
                // cleaned files are shared, so delete them now rather than when the share ends:
                // on a successful share nothing else would until the next process start. Only
                // this session's: a share that replaced it keeps its own.
                deleteSnapshots(session.closeSnapshots());
                endShareSession(session);
            }
        }, "redact-share-worker");
        processingThread = worker;
        worker.start();
    }

    /** What a shared item is, read once from its provider and reused for the clean and the metrics. */
    private static final class SharedItem {
        @Nullable final String mimeType;
        @Nullable final String fileName;
        final boolean video;

        SharedItem(@Nullable String mimeType, @Nullable String fileName, boolean video) {
            this.mimeType = mimeType;
            this.fileName = fileName;
            this.video = video;
        }
    }

    @NonNull
    private SharedItem describe(@NonNull Map<Uri, SharedItem> described, @NonNull Uri uri) {
        SharedItem item = described.get(uri);
        if (item == null) {
            String mimeType = getContentResolver().getType(uri);
            String fileName = mediaSelector.getFileName(uri);
            item = new SharedItem(mimeType, fileName, MediaSelector.isVideoFromMimeAndName(mimeType, fileName));
            described.put(uri, item);
        }
        return item;
    }

    /** Lists a cleaned file for deletion when the share ends, or deletes it now if a newer share took over. */
    private void keepForCleanup(@NonNull ShareSession session, @NonNull CleanResult cleaned) {
        try {
            File outputFile = cleaned.file();
            if (outputFile == null || !outputFile.exists() || !outputFile.isFile()) {
                return;
            }
            boolean replaced;
            synchronized (fileListLock) {
                replaced = session != shareSession;
                if (!replaced) {
                    processedFiles.add(outputFile);
                    processedDisplayNames.add(outputFile.getName());
                }
            }
            if (replaced) {
                // A newer share took over and already emptied the list.
                List<File> stale = new ArrayList<>();
                stale.add(outputFile);
                Context appContext = getApplicationContext();
                cleanupExecutor.execute(() -> deleteProcessedFileList(appContext, stale));
            }
        } catch (Exception e) {
            SentryManager.logEvent("share", "The processed file could not be resolved for cleanup: "
                    + e.getClass().getSimpleName() + ".");
        }
    }

    private void endShareSession(@NonNull ShareSession session) {
        if (!session.ended.compareAndSet(false, true)) {
            return;
        }
        if (session.counted) {
            AppProcessingScope.get(this).shareEnded();
        }
        if (session == shareSession) {
            processingActive = false;
            LocalNotifications.stopProcessingForeground(
                    ShareHandlerActivity.this, LocalNotifications.ForegroundJob.SHARE);
        }
    }

    private void cancelShareProcessing() {
        // The export first, while it still has an owner to cancel by, then the thread: an
        // interrupted worker clears the owner on its way out (as onNewIntent orders it).
        ShareSession session = shareSession;
        if (session != null) {
            VideoMedia3Converter.cancelActiveTranscode(VideoMedia3Converter.shareOwner(session.id));
            session.stripper.requestCancellation();
        }
        Thread thread = processingThread;
        if (thread != null) {
            thread.interrupt();
        }
        if (session != null) {
            endShareSession(session);
        }
        dismissProgressDialog();
        cleanupProcessedFiles();
        Toast.makeText(this, R.string.status_processing_cancelled, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try {
                progressDialog.dismiss();
            } catch (Exception e) {
                SentryManager.recordException(e);
            }
        }
    }

    private void launchShareChooser(Intent chooser) {
        sharingInitiated = true;
        shareResultLauncher.launch(chooser);
        StatsStore.recordShare(this);
    }

    private void shareCleanFile(boolean isVideo, Uri cleanedFileUri) {
        try {
            if (cleanedFileUri != null) {
                String mimeType = isVideo ? "video/*" : "image/*";
                String firstDisplayName;
                synchronized (fileListLock) {
                    firstDisplayName = processedDisplayNames.isEmpty()
                            ? null
                            : processedDisplayNames.get(0);
                }
                String displayName = firstDisplayName != null
                        ? firstDisplayName
                        : displayNameFromUri(cleanedFileUri);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mimeType);
                shareIntent.putExtra(Intent.EXTRA_STREAM, cleanedFileUri);
                shareIntent.putExtra(Intent.EXTRA_TITLE, displayName);
                shareIntent.setClipData(ClipData.newRawUri(displayName, cleanedFileUri));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = ShareResults.withoutOwnTargets(this,
                        Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                sharingInitiated = true;
                SentryManager.logEvent("share", "The app is launching a single-item share intent.");
                launchShareChooser(chooser);
            } else {
                finishWithError(getString(R.string.share_error_failed_get_cleaned_file));
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.share_error_generic));
        }
    }

    private void shareCleanFiles(boolean hasVideo, boolean hasImage, ArrayList<Uri> cleanedFileUris) {
        try {
            if (cleanedFileUris != null && !cleanedFileUris.isEmpty()) {
                String mimeType = "*/*";
                if (hasVideo && !hasImage) {
                    mimeType = "video/*";
                } else if (hasImage && !hasVideo) {
                    mimeType = "image/*";
                }

                Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType(mimeType);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, cleanedFileUris);
                ClipData clipData = new ClipData(
                        getString(R.string.share_chooser_title),
                        new String[]{mimeType},
                        new ClipData.Item(cleanedFileUris.get(0)));
                for (int i = 1; i < cleanedFileUris.size(); i++) {
                    clipData.addItem(new ClipData.Item(cleanedFileUris.get(i)));
                }
                shareIntent.setClipData(clipData);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = ShareResults.withoutOwnTargets(this,
                        Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                sharingInitiated = true;
                SentryManager.logEvent("share", "The app is launching a multiple-item share intent.");
                launchShareChooser(chooser);
            } else {
                finishWithError(getString(R.string.share_error_failed_get_cleaned_files));
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.share_error_generic));
        }
    }

    private void cleanupInboundSnapshots() {
        ShareSession session = shareSession;
        if (session != null) {
            deleteSnapshots(session.closeSnapshots());
        }
    }

    /** Securely deletes inbound snapshots off the main thread; they hold the original media. */
    private static void deleteSnapshots(@NonNull List<File> filesToDelete) {
        InboundSnapshots.deleteAll(filesToDelete, cleanupExecutor);
    }

    private void cleanupProcessedFiles() {
        cleanupInboundSnapshots();
        cleanupProcessedOutputs();
    }

    /** Deletes the cleaned outputs the share has not handed to the chooser. */
    private void cleanupProcessedOutputs() {
        List<File> filesToDelete;
        synchronized (fileListLock) {
            filesToDelete = new ArrayList<>(processedFiles);
            processedFiles.clear();
            processedDisplayNames.clear();
        }
        if (filesToDelete.isEmpty()) {
            return;
        }
        Context appContext = getApplicationContext();
        cleanupExecutor.execute(() -> deleteProcessedFileList(appContext, filesToDelete));
    }

    private void scheduleDelayedShareCleanup() {
        List<File> filesToDelete;
        synchronized (fileListLock) {
            if (processedFiles.isEmpty()) {
                return;
            }
            filesToDelete = new ArrayList<>(processedFiles);
            processedFiles.clear();
            processedDisplayNames.clear();
        }
        Context appContext = getApplicationContext();
        Handler handler = new Handler(appContext.getMainLooper());
        handler.postDelayed(
                () -> cleanupExecutor.execute(() -> deleteProcessedFileList(appContext, filesToDelete)),
                SHARE_CLEANUP_DELAY_MS);
    }

    /**
     * Deletes shared files. They are already clean, so they are only unlinked, not overwritten:
     * overwriting would corrupt a file an app that was shared it is still reading, while unlinking
     * leaves an open file readable until it is closed.
     */
    private static void deleteProcessedFileList(@NonNull Context context, List<File> files) {
        for (File processedFile : files) {
            if (processedFile != null && processedFile.exists()) {
                try {
                    if (processedFile.delete()) {
                        SentryManager.logEvent("share", "The temporary processed file was deleted after sharing.");
                    } else {
                        SentryManager.logEvent("share", "The temporary processed file failed to delete.");
                        processedFile.deleteOnExit();
                    }
                } catch (Exception e) {
                    SentryManager.logEvent("share", "An error occurred while deleting the temporary file: " + e.getClass().getSimpleName() + ".");
                }
            }
        }
    }

    @NonNull
    private List<Uri> extractUrisFromIntent(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null) {
            return uris;
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> extraUris =
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (extraUris != null) {
                for (Uri uri : extraUris) {
                    addInboundUri(uris, uri);
                }
            }
        } else {
            Uri streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            addInboundUri(uris, streamUri);
            if (uris.isEmpty()) {
                addInboundUri(uris, intent.getData());
            }
            ClipData clipData = intent.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    addInboundUri(uris, clipData.getItemAt(i).getUri());
                }
            }
        }
        return uris;
    }

    private void addInboundUri(List<Uri> uris, Uri uri) {
        if (uri == null || !isSupportedInboundUri(uri)) {
            return;
        }
        for (Uri existing : uris) {
            if (existing.equals(uri)) {
                return;
            }
        }
        uris.add(uri);
    }

    /**
     * Only content from another app's provider, as Scan, Convert and Censor take. Redact's own
     * providers serve its working and converted files: not exported, but Redact can open them, so
     * a share naming one would let the sender have Redact read and re-share files it cannot see.
     * A file:// path is refused too: Redact can read media on shared storage that the sender may
     * have no permission for, and apps have shared content:// since Android 7.
     */
    private boolean isSupportedInboundUri(Uri uri) {
        return InboundUriPolicy.isForeignContentUri(this, uri);
    }

    @NonNull
    private List<Uri> filterSupportedUris(List<Uri> uris, @NonNull ShareSession session) {
        List<Uri> accepted = new ArrayList<>();
        session.unsupportedRejectCount.set(0);
        for (Uri uri : uris) {
            if (uri == null || !isSupportedInboundUri(uri)) {
                if (uri != null) {
                    SentryManager.logEvent("share",
                            "An inbound item with an unsupported scheme or a private location was skipped.");
                }
                continue;
            }
            String type;
            try {
                type = getContentResolver().getType(uri);
            } catch (RuntimeException e) {
                // The sender's provider failed to answer; the item is still tried, and the copy
                // and clean decide what it is.
                SentryManager.logEvent("share", "An inbound item's type could not be read: "
                        + e.getClass().getSimpleName() + ".");
                type = null;
            }
            if (isUnsupportedMediaType(type)) {
                // A multi-item share's intent type can be image/* or */* while individual items
                // are PDFs, text or SVGs. Skip them up front instead of copying and failing later.
                session.unsupportedRejectCount.incrementAndGet();
                countUnsupported(type);
                SentryManager.logEvent("share", "An inbound item of a non-media type was skipped.");
                continue;
            }
            accepted.add(uri);
        }
        return accepted;
    }

    /**
     * Whether an item's own MIME type rules it out. Unknown or generic types are let through and
     * left to the decoders; vector images (SVG) are rejected because they have no pixels to decode.
     */
    @VisibleForTesting
    static boolean isUnsupportedMediaType(@Nullable String type) {
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase(Locale.US);
        if (lower.equals("application/octet-stream") || lower.equals("*/*")) {
            return false;
        }
        if (lower.startsWith("image/svg")) {
            return true;
        }
        return !lower.startsWith("image/") && !lower.startsWith("video/");
    }

    @Nullable
    private String displayNameFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        return mediaSelector.getFileName(uri);
    }

    /**
     * Counts a shared item Redact could not handle, by its MIME type alone (such as image/heic or
     * application/pdf), to show which formats people bring that the device cannot decode.
     */
    private static void countUnsupported(@Nullable String mimeType) {
        AppMetrics.unsupportedMedia(mimeTypeTag(mimeType));
    }

    /**
     * A MIME type reduced to "type/subtype" in lower case (a wildcard such as "image/*" included),
     * or "unknown" when it is not one.
     */
    @VisibleForTesting
    @NonNull
    static String mimeTypeTag(@Nullable String mimeType) {
        if (mimeType == null) {
            return "unknown";
        }
        String bare = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return bare.matches("([a-z]+|\\*)/([a-z0-9][a-z0-9.+-]{0,63}|\\*)") ? bare : "unknown";
    }

    private void finishWithError(String message) {
        SentryManager.logEvent("share", "The share flow finished with an error.");
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        dismissProgressDialog();
        cleanupProcessedFiles();
        finish();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed.set(true);
        // The export first, then the thread, as in cancelShareProcessing.
        ShareSession session = shareSession;
        if (session != null) {
            VideoMedia3Converter.cancelActiveTranscode(VideoMedia3Converter.shareOwner(session.id));
            session.stripper.requestCancellation();
        }
        Thread thread = processingThread;
        if (thread != null) {
            thread.interrupt();
        }
        if (session != null) {
            endShareSession(session);
        }
        LocalNotifications.stopProcessingForeground(
                getApplicationContext(), LocalNotifications.ForegroundJob.SHARE);
        dismissProgressDialog();
        if (isFinishing()) {
            if (!sharingInitiated) {
                cleanupProcessedFiles();
            } else {
                boolean hasProcessedFiles;
                synchronized (fileListLock) {
                    hasProcessedFiles = !processedFiles.isEmpty();
                }
                if (hasProcessedFiles) {
                    scheduleDelayedShareCleanup();
                }
            }
        }
        super.onDestroy();
    }
}
