package com.doubleangels.redact;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.metadata.MetadataStripper;
import java.io.File;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayList;
import java.util.List;

import io.sentry.ITransaction;
import io.sentry.SpanStatus;

public class ShareHandlerActivity extends AppCompatActivity {

    /** Maximum items accepted from ACTION_SEND_MULTIPLE. */
    private static final int MAX_SHARE_ITEMS = 20;
    /** Per-stream size cap (~200 MB) to limit resource exhaustion from other apps. */
    private static final long MAX_STREAM_BYTES = 200L * 1024L * 1024L;

    private MediaSelector mediaSelector;
    private MetadataStripper metadataStripper;
    
    private final List<File> processedFiles = new ArrayList<>();
    
    private boolean sharingInitiated = false;

    private AlertDialog progressDialog;
    private TextView progressMessageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        try {
            mediaSelector = new MediaSelector(this, null);
            metadataStripper = new MetadataStripper(this);
            
            metadataStripper.setProgressCallback(
                    (percent, message) -> runOnUiThread(() -> updateProgressMessage(message)));

            SentryManager.log("ShareHandlerActivity created");

            createProgressDialog();

            handleIntent(getIntent());
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError("Error during initialization: " + e.getMessage());
        }
    }

    private void createProgressDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.dialog_progress, null, false);
        progressMessageView = dialogView.findViewById(R.id.progress_message);

        progressDialog = builder
                .setTitle(R.string.share_processing_title)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        progressMessageView.setText(getString(R.string.status_processing));
        progressDialog.show();
    }

    private void updateProgressMessage(String message) {
        if (progressMessageView != null) {
            progressMessageView.setText(message);
        }
    }

    private void handleIntent(Intent intent) {
        try {
            String action = intent.getAction();
            String type = intent.getType();

            SentryManager.setCustomKey("intent_action", action != null ? action : "null");
            SentryManager.setCustomKey("intent_type", type != null ? type : "null");

            if (Intent.ACTION_SEND.equals(action) && type != null) {
                if (type.startsWith("image/")) {
                    SentryManager.log("Handling single image");
                    handleSentImage(intent);
                } else if (type.startsWith("video/")) {
                    SentryManager.log("Handling single video");
                    handleSentVideo(intent);
                } else {
                    SentryManager.setCustomKey("unsupported_type", type);
                    finishWithError(getString(R.string.share_error_unsupported_media));
                }
            }
            else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
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

    private void handleSentImage(Intent intent) {
        try {
            Uri receivedUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (receivedUri != null) {
                if (!isUriWithinSizeLimit(receivedUri)) {
                    finishWithError(getString(R.string.share_error_file_too_large));
                    return;
                }
                List<Uri> uris = new ArrayList<>();
                uris.add(receivedUri);
                processMediaItems(uris);
            } else {
                SentryManager.logEvent("share", "Received null image URI");
                finishWithError("Failed to receive image");
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError("Error handling image: " + e.getMessage());
        }
    }

    private void handleSentVideo(Intent intent) {
        try {
            Uri receivedUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (receivedUri != null) {
                if (!isUriWithinSizeLimit(receivedUri)) {
                    finishWithError(getString(R.string.share_error_file_too_large));
                    return;
                }
                List<Uri> uris = new ArrayList<>();
                uris.add(receivedUri);
                processMediaItems(uris);
            } else {
                SentryManager.logEvent("share", "Received null video URI");
                finishWithError("Failed to receive video");
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError("Error handling video: " + e.getMessage());
        }
    }

    private void handleMultipleMedia(Intent intent) {
        try {
            ArrayList<Uri> uris;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            }
            if (uris != null && !uris.isEmpty()) {
                if (uris.size() > MAX_SHARE_ITEMS) {
                    finishWithError(getString(R.string.share_error_too_many_items, MAX_SHARE_ITEMS));
                    return;
                }
                List<Uri> accepted = new ArrayList<>();
                for (Uri uri : uris) {
                    if (uri != null && isUriWithinSizeLimit(uri)) {
                        accepted.add(uri);
                    }
                }
                if (accepted.isEmpty()) {
                    finishWithError(getString(R.string.share_error_file_too_large));
                    return;
                }
                SentryManager.setCustomKey("media_count", accepted.size());
                processMediaItems(accepted);
            } else {
                SentryManager.logEvent("share", "Received empty media list");
                finishWithError("Failed to receive media");
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError("Error handling multiple media: " + e.getMessage());
        }
    }

    private void processMediaItems(List<Uri> uris) {
        new Thread(() -> {
            ITransaction transaction = SentryManager.startTransaction("share_cleanup", "task");
            ArrayList<Uri> processedUris = new ArrayList<>();
            boolean allSuccess = true;
            boolean hasVideo = false;
            boolean hasImage = false;

            try {
                for (int i = 0; i < uris.size(); i++) {
                    Uri uri = uris.get(i);
                    final int currentIndex = i + 1;
                    final int total = uris.size();
                    
                    runOnUiThread(() -> updateProgressMessage(getString(R.string.status_processing) + " (" + currentIndex + "/" + total + ")"));

                    String mimeType = getContentResolver().getType(uri);
                    boolean isVideo = mimeType != null && mimeType.startsWith("video/");
                    if (isVideo) hasVideo = true;
                    else hasImage = true;

                    String fileName = mediaSelector.getFileName(uri);

                    Uri processedUri = metadataStripper.stripMetadataForSharing(uri, fileName, isVideo);
                    if (processedUri != null) {
                        processedUris.add(processedUri);
                        try {
                            String lastSegment = processedUri.getLastPathSegment();
                            if (lastSegment != null) {
                                String resolvedFileName = lastSegment.contains("/")
                                        ? lastSegment.substring(lastSegment.lastIndexOf('/') + 1)
                                        : lastSegment;
                                File cacheDir = new File(getCacheDir(), "processed");
                                File candidate = new File(cacheDir, resolvedFileName);
                                if (candidate.exists() && candidate.isFile()) {
                                    processedFiles.add(candidate);
                                }
                            }
                        } catch (Exception e) {
                            SentryManager.log("Could not resolve processed file for cleanup: " + e.getMessage());
                        }
                    } else {
                        allSuccess = false;
                        break;
                    }
                }

                if (allSuccess && !processedUris.isEmpty()) {
                    transaction.setStatus(SpanStatus.OK);
                } else {
                    transaction.setStatus(SpanStatus.INTERNAL_ERROR);
                }
                transaction.finish();

                final boolean finalHasVideo = hasVideo;
                final boolean finalHasImage = hasImage;
                final boolean finalAllSuccess = allSuccess;

                runOnUiThread(() -> {
                    try {
                        dismissProgressDialog();

                        if (finalAllSuccess && !processedUris.isEmpty()) {
                            SentryManager.log("Media processing completed successfully");
                            if (processedUris.size() == 1) {
                                shareCleanFile(finalHasVideo, processedUris.get(0));
                            } else {
                                shareCleanFiles(finalHasVideo, finalHasImage, processedUris);
                            }
                        } else {
                            SentryManager.log("Media processing failed");
                            finishWithError("Processing failed");
                        }
                    } catch (Exception e) {
                        SentryManager.recordException(e);
                        finishWithError("Error in processing completion: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                if (!transaction.isFinished()) {
                    transaction.setStatus(SpanStatus.INTERNAL_ERROR);
                    transaction.finish();
                }
                SentryManager.recordException(e);
                runOnUiThread(() -> finishWithError("Failed to process media: " + e.getMessage()));
            }
        }).start();
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

    private static final int REQUEST_SHARE = 1001;

    private void shareCleanFile(boolean isVideo, Uri cleanedFileUri) {
        try {
            if (cleanedFileUri != null) {
                String mimeType = isVideo ? "video/*" : "image/*";

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mimeType);
                shareIntent.putExtra(Intent.EXTRA_STREAM, cleanedFileUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(shareIntent, getString(R.string.share_chooser_title));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                sharingInitiated = true;
                SentryManager.log("Launching single share intent");
                startActivityForResult(chooser, REQUEST_SHARE);
            } else {
                finishWithError("Failed to get cleaned file");
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError("Error sharing clean file: " + e.getMessage());
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
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(shareIntent, getString(R.string.share_chooser_title));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                sharingInitiated = true;
                SentryManager.log("Launching multiple share intent");
                startActivityForResult(chooser, REQUEST_SHARE);
            } else {
                finishWithError("Failed to get cleaned files");
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
            finishWithError("Error sharing clean files: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SHARE) {
            SentryManager.log("Share chooser returned, cleaning up");
            cleanupProcessedFiles();
            finish();
        }
    }

    private void cleanupProcessedFiles() {
        for (File processedFile : processedFiles) {
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
        processedFiles.clear();
    }

    private boolean isUriWithinSizeLimit(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    long size = cursor.getLong(idx);
                    return size <= 0 || size <= MAX_STREAM_BYTES;
                }
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
        return true;
    }

    private void finishWithError(String message) {
        SentryManager.logEvent("share", "Error");
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        dismissProgressDialog();
        finish();
    }

    @Override
    protected void onDestroy() {
        dismissProgressDialog();
        cleanupProcessedFiles();
        super.onDestroy();
    }
}
