package com.doubleangels.redact;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.media.ProgressUpdateThrottler;
import com.doubleangels.redact.media.VideoMedia3Converter;
import com.doubleangels.redact.metadata.MetadataStripper;
import com.doubleangels.redact.notifications.LocalNotifications;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import com.doubleangels.redact.media.MediaSizeLimits;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.color.DynamicColors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.sentry.ITransaction;
import io.sentry.SpanStatus;

public class ShareHandlerActivity extends AppCompatActivity {

    /** Maximum items accepted from ACTION_SEND_MULTIPLE. */
    private static final int MAX_SHARE_ITEMS = 20;
    /** Per-stream size cap (~200 MB) to limit resource exhaustion from other apps. */
    private static final long MAX_STREAM_BYTES = 200L * 1024L * 1024L;
    /** Delay before deleting shared files so the target app can finish reading the URI. */
    private static final long SHARE_CLEANUP_DELAY_MS = 2L * 60L * 1000L;
    private static final String KEY_SHARING_INITIATED = "sharing_initiated";
    private static final String KEY_PROCESSING_ACTIVE = "processing_active";

    /** Tracks in-flight share processing so MainActivity does not delete temp files mid-share. */
    private static final java.util.concurrent.atomic.AtomicInteger activeShareSessions =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static boolean isShareProcessingActive() {
        return activeShareSessions.get() > 0;
    }

    private final ActivityResultLauncher<Intent> shareResultLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_CANCELED) {
                            SentryManager.log("Share chooser canceled, cleaning up immediately");
                            cleanupProcessedFiles();
                        } else {
                            scheduleDelayedShareCleanup();
                        }
                        finish();
                    });

    private MediaSelector mediaSelector;
    private MetadataStripper metadataStripper;
    
    private final List<File> processedFiles = new ArrayList<>();
    private final List<File> inboundSnapshotFiles = new ArrayList<>();
    private final List<String> processedDisplayNames = new ArrayList<>();
    
    private boolean sharingInitiated = false;
    private volatile boolean processingActive = false;
    private final AtomicBoolean activityDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean shareSessionEnded = new AtomicBoolean(false);
    private volatile Thread processingThread;

    private AlertDialog progressDialog;
    private TextView progressMessageView;
    private final ProgressUpdateThrottler shareProgressThrottler = new ProgressUpdateThrottler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        try {
            mediaSelector = new MediaSelector(this);
            metadataStripper = new MetadataStripper(this);
            
            metadataStripper.setProgressCallback((percent, message) ->
                    shareProgressThrottler.maybeRun(
                            () -> safeRunOnUiThread(() -> updateProgressMessage(message))));

            SentryManager.log("ShareHandlerActivity created");

            if (savedInstanceState != null) {
                sharingInitiated = savedInstanceState.getBoolean(KEY_SHARING_INITIATED, false);
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
        Thread thread = processingThread;
        if (thread != null) {
            thread.interrupt();
        }
        VideoMedia3Converter.cancelActiveTranscode();
        LocalNotifications.stopProcessingForeground(this);
        processingActive = false;
        sharingInitiated = false;
        shareSessionEnded.set(false);
        cleanupProcessedFiles();
        dismissProgressDialog();
        createProgressDialog();
        handleIntent(intent);
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

            SentryManager.setCustomKey("intent_action", action != null ? action : "null");
            SentryManager.setCustomKey("intent_type", type != null ? type : "null");

            if (Intent.ACTION_SEND.equals(action)) {
                if (type == null || type.startsWith("image/") || type.startsWith("video/")
                        || "*/*".equals(type)) {
                    SentryManager.log("Handling single media");
                    handleSentMedia(intent);
                } else {
                    SentryManager.setCustomKey("unsupported_type", type);
                    finishWithError(getString(R.string.share_error_unsupported_media));
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                if (type == null || type.startsWith("image/") || type.startsWith("video/")
                        || "*/*".equals(type)) {
                    SentryManager.log("Handling multiple media");
                    handleMultipleMedia(intent);
                } else {
                    SentryManager.setCustomKey("unsupported_type", type);
                    finishWithError(getString(R.string.share_error_unsupported_media));
                }
            } else {
                SentryManager.setCustomKey("unsupported_action", action != null ? action : "null");
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
                SentryManager.logEvent("share", "Received no media URI");
                finishWithError(getString(R.string.share_error_failed_receive_media));
                return;
            }
            if (uris.size() > MAX_SHARE_ITEMS) {
                finishWithError(getString(R.string.share_error_too_many_items, MAX_SHARE_ITEMS));
                return;
            }
            List<Uri> accepted = filterAcceptedUris(uris);
            if (accepted.isEmpty()) {
                finishWithError(getString(R.string.share_error_file_too_large));
                return;
            }
            maybeConfirmAndProcess(snapshotInboundUris(accepted));
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.share_error_generic));
        }
    }

    private void handleMultipleMedia(Intent intent) {
        try {
            List<Uri> uris = extractUrisFromIntent(intent);
            if (!uris.isEmpty()) {
                if (uris.size() > MAX_SHARE_ITEMS) {
                    finishWithError(getString(R.string.share_error_too_many_items, MAX_SHARE_ITEMS));
                    return;
                }
                int skipped = uris.size();
                List<Uri> accepted = filterAcceptedUris(uris);
                skipped -= accepted.size();
                if (accepted.isEmpty()) {
                    finishWithError(getString(R.string.share_error_file_too_large));
                    return;
                }
                if (skipped > 0) {
                    Toast.makeText(this, getString(R.string.share_error_skipped_oversized, skipped),
                            Toast.LENGTH_LONG).show();
                }
                SentryManager.setCustomKey("media_count", accepted.size());
                maybeConfirmAndProcess(snapshotInboundUris(accepted));
            } else {
                SentryManager.logEvent("share", "Received empty media list");
                finishWithError(getString(R.string.share_error_failed_receive_media));
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError(getString(R.string.share_error_generic));
        }
    }

    @NonNull
    private List<Uri> snapshotInboundUris(@NonNull List<Uri> uris) {
        List<Uri> stable = new ArrayList<>();
        for (Uri uri : uris) {
            String scheme = uri.getScheme();
            if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                try {
                    File snapshot = copyInboundUriToCache(uri);
                    inboundSnapshotFiles.add(snapshot);
                    stable.add(Uri.fromFile(snapshot));
                } catch (IOException e) {
                    SentryManager.recordException(e);
                    stable.add(uri);
                }
            } else {
                stable.add(uri);
            }
        }
        return stable;
    }

    @NonNull
    private File copyInboundUriToCache(@NonNull Uri uri) throws IOException {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                throw new IOException("Invalid file URI path");
            }
            File source = new File(path);
            String suffix = suffixForPath(path);
            File dest = new File(getCacheDir(), "inbound_" + System.nanoTime() + suffix);
            try (FileInputStream in = new FileInputStream(source);
                    FileOutputStream out = new FileOutputStream(dest)) {
                MediaSizeLimits.copyWithLimit(in, out, MAX_STREAM_BYTES);
            }
            return dest;
        }
        String suffix = ".bin";
        String type = getContentResolver().getType(uri);
        if (type != null && type.startsWith("video/")) {
            suffix = ".mp4";
        } else if (type != null && type.startsWith("image/")) {
            suffix = ".jpg";
        }
        File dest = new File(getCacheDir(), "inbound_" + System.nanoTime() + suffix);
        try (InputStream in = getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("Cannot open inbound stream");
            }
            MediaSizeLimits.copyWithLimit(in, out, MAX_STREAM_BYTES);
        }
        return dest;
    }

    @NonNull
    private static String suffixForPath(@NonNull String path) {
        String lower = path.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")) {
            return ".mp4";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif")
                || lower.endsWith(".gif")) {
            return ".jpg";
        }
        return ".bin";
    }

    private void maybeConfirmAndProcess(List<Uri> uris) {
        if (AppPreferences.isShareConfirmBeforeStrip(this)) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_share_confirm_dialog_title)
                    .setMessage(R.string.settings_share_confirm_dialog_message)
                    .setPositiveButton(R.string.settings_share_confirm_dialog_confirm, (d, w) -> {
                        createProgressDialog();
                        processMediaItems(uris);
                    })
                    .setNegativeButton(R.string.settings_share_confirm_dialog_cancel, (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        processMediaItems(uris);
    }

    private void processMediaItems(List<Uri> uris) {
        processingActive = true;
        shareSessionEnded.set(false);
        activeShareSessions.incrementAndGet();
        LocalNotifications.startProcessingForeground(this, getString(R.string.share_processing_title));
        Thread worker = new Thread(() -> {
            ITransaction transaction = SentryManager.startTransaction("share_cleanup", "task");
            ArrayList<Uri> processedUris = new ArrayList<>();
            int failCount = 0;
            boolean hasVideo = false;
            boolean hasImage = false;

            try {
                for (int i = 0; i < uris.size(); i++) {
                    if (activityDestroyed.get()
                            || Thread.currentThread().isInterrupted()
                            || shareSessionEnded.get()) {
                        break;
                    }
                    Uri uri = uris.get(i);
                    final int currentIndex = i + 1;
                    final int total = uris.size();

                    safeRunOnUiThread(() -> updateProgressMessage(
                            getString(R.string.status_processing) + " (" + currentIndex + "/" + total + ")"));

                    String mimeType = getContentResolver().getType(uri);
                    String fileName = mediaSelector.getFileName(uri);
                    boolean isVideo = MediaSelector.isVideoFromMimeAndName(mimeType, fileName);
                    if (isVideo) hasVideo = true;
                    else hasImage = true;

                    Uri processedUri = metadataStripper.stripMetadataForSharing(uri, fileName, isVideo);
                    if (processedUri != null) {
                        processedUris.add(processedUri);
                        try {
                            File outputFile = metadataStripper.getLastProcessedOutputFile();
                            if (outputFile != null && outputFile.exists() && outputFile.isFile()) {
                                processedFiles.add(outputFile);
                                processedDisplayNames.add(outputFile.getName());
                            }
                        } catch (Exception e) {
                            SentryManager.log("Could not resolve processed file for cleanup: " + e.getMessage());
                        }
                    } else {
                        failCount++;
                    }
                }

                if (!processedUris.isEmpty() && failCount == 0) {
                    transaction.setStatus(SpanStatus.OK);
                } else if (!processedUris.isEmpty()) {
                    transaction.setStatus(SpanStatus.OK);
                } else {
                    transaction.setStatus(SpanStatus.INTERNAL_ERROR);
                }
                transaction.finish();

                final boolean finalHasVideo = hasVideo;
                final boolean finalHasImage = hasImage;
                final int finalFailCount = failCount;
                final boolean cancelled = shareSessionEnded.get()
                        || Thread.currentThread().isInterrupted();

                safeRunOnUiThread(() -> {
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
                            SentryManager.log("Media processing completed successfully");
                            if (processedUris.size() == 1) {
                                shareCleanFile(finalHasVideo, processedUris.get(0));
                            } else {
                                shareCleanFiles(finalHasVideo, finalHasImage, processedUris);
                            }
                        } else {
                            SentryManager.log("Media processing failed");
                            finishWithError(getString(R.string.share_error_processing_failed));
                        }
                    } catch (Exception e) {
                        SentryManager.recordException(e);
                        finishWithError(getString(R.string.share_error_generic));
                    }
                });
            } catch (Exception e) {
                if (!transaction.isFinished()) {
                    transaction.setStatus(SpanStatus.INTERNAL_ERROR);
                    transaction.finish();
                }
                SentryManager.recordException(e);
                safeRunOnUiThread(() -> finishWithError(getString(R.string.share_error_generic)));
            } finally {
                endShareSession();
            }
        });
        processingThread = worker;
        worker.start();
    }

    private void endShareSession() {
        if (shareSessionEnded.compareAndSet(false, true)) {
            processingActive = false;
            activeShareSessions.decrementAndGet();
            LocalNotifications.stopProcessingForeground(ShareHandlerActivity.this);
        }
    }

    private void cancelShareProcessing() {
        Thread thread = processingThread;
        if (thread != null) {
            thread.interrupt();
        }
        VideoMedia3Converter.cancelActiveTranscode();
        if (metadataStripper != null) {
            metadataStripper.requestCancellation();
        }
        endShareSession();
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
    }

    private void shareCleanFile(boolean isVideo, Uri cleanedFileUri) {
        try {
            if (cleanedFileUri != null) {
                String mimeType = isVideo ? "video/*" : "image/*";
                String displayName = processedDisplayNames.isEmpty()
                        ? displayNameFromUri(cleanedFileUri)
                        : processedDisplayNames.get(0);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mimeType);
                shareIntent.putExtra(Intent.EXTRA_STREAM, cleanedFileUri);
                shareIntent.putExtra(Intent.EXTRA_TITLE, displayName);
                shareIntent.setClipData(ClipData.newRawUri(displayName, cleanedFileUri));
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(shareIntent, getString(R.string.share_chooser_title));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                sharingInitiated = true;
                SentryManager.log("Launching single share intent");
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

                Intent chooser = Intent.createChooser(shareIntent, getString(R.string.share_chooser_title));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                sharingInitiated = true;
                SentryManager.log("Launching multiple share intent");
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
        for (File file : inboundSnapshotFiles) {
            if (file != null && file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
        inboundSnapshotFiles.clear();
    }

    private void cleanupProcessedFiles() {
        cleanupInboundSnapshots();
        deleteProcessedFileList(processedFiles);
        processedFiles.clear();
        processedDisplayNames.clear();
    }

    private void scheduleDelayedShareCleanup() {
        if (processedFiles.isEmpty()) {
            return;
        }
        List<File> filesToDelete = new ArrayList<>(processedFiles);
        processedFiles.clear();
        processedDisplayNames.clear();
        Handler handler = new Handler(getApplicationContext().getMainLooper());
        handler.postDelayed(() -> deleteProcessedFileList(filesToDelete), SHARE_CLEANUP_DELAY_MS);
    }

    private static void deleteProcessedFileList(List<File> files) {
        for (File processedFile : files) {
            if (processedFile != null && processedFile.exists()) {
                try {
                    if (processedFile.delete()) {
                        SentryManager.log("Deleted temporary processed file after sharing");
                    } else {
                        SentryManager.log("Failed to delete temporary processed file");
                        processedFile.deleteOnExit();
                    }
                } catch (Exception e) {
                    SentryManager.log("Error deleting temporary file: " + e.getMessage());
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
            ArrayList<Uri> extraUris;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extraUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                extraUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            }
            if (extraUris != null) {
                for (Uri uri : extraUris) {
                    addInboundUri(uris, uri);
                }
            }
        } else {
            Uri streamUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
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

    private boolean isSupportedInboundUri(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("content".equalsIgnoreCase(scheme)) {
            return true;
        }
        return "file".equalsIgnoreCase(scheme);
    }

    @NonNull
    private List<Uri> filterAcceptedUris(List<Uri> uris) {
        List<Uri> accepted = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri != null && isUriWithinSizeLimit(uri)) {
                accepted.add(uri);
            }
        }
        return accepted;
    }

    @Nullable
    private String displayNameFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        return mediaSelector.getFileName(uri);
    }

    private boolean isUriWithinSizeLimit(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                return false;
            }
            long length = new File(path).length();
            return length <= 0 || length <= MAX_STREAM_BYTES;
        }
        long declared = MediaSizeLimits.declaredSizeBytes(getContentResolver(), uri);
        if (declared <= 0) {
            // Unknown size: allow entry; snapshot/processing paths enforce stream limits.
            return true;
        }
        return declared <= MAX_STREAM_BYTES;
    }

    private void finishWithError(String message) {
        SentryManager.logEvent("share", "Error");
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        dismissProgressDialog();
        cleanupProcessedFiles();
        finish();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed.set(true);
        Thread thread = processingThread;
        if (thread != null) {
            thread.interrupt();
        }
        VideoMedia3Converter.cancelActiveTranscode();
        if (metadataStripper != null) {
            metadataStripper.requestCancellation();
        }
        LocalNotifications.stopProcessingForeground(getApplicationContext());
        dismissProgressDialog();
        if (isFinishing()) {
            if (!sharingInitiated) {
                cleanupProcessedFiles();
            } else if (!processedFiles.isEmpty()) {
                scheduleDelayedShareCleanup();
            }
        }
        super.onDestroy();
    }
}
