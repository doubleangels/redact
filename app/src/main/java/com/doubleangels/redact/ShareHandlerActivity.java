package com.doubleangels.redact;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaProcessor;
import com.doubleangels.redact.media.MediaSelector;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for handling media files shared from other applications.
 *
 * This activity processes incoming shared media (images and videos),
 * removes metadata/sensitive information, and allows users to share
 * the cleaned version. It supports both single and multiple media items
 * shared via Android's standard sharing mechanism.
 */
public class ShareHandlerActivity extends AppCompatActivity {

    // Core components for media processing and selection
    private MediaProcessor mediaProcessor;
    private MediaSelector mediaSelector;

    // UI elements for showing progress to the user
    private AlertDialog progressDialog;
    private TextView progressMessageView;

    // URI of the media file received from the sharing application
    private Uri receivedUri;

    /**
     * Initializes the activity and handles incoming share intent.
     *
     * Sets up required components, creates the progress dialog,
     * and begins processing the shared media content.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down, this contains the data it most recently
     *     supplied in onSaveInstanceState(Bundle)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            // Initialize media processing components
            mediaProcessor = new MediaProcessor(this);
            mediaSelector = new MediaSelector(this, null);

            // Log activity creation for debugging purposes
            FirebaseCrashlytics.getInstance().log("ShareHandlerActivity created");

            // Create and show the progress dialog
            createProgressDialog();

            // Process the intent that launched this activity
            handleIntent(getIntent());
        } catch (Exception e) {
            // Record any initialization errors and finish the activity
            FirebaseCrashlytics.getInstance().recordException(e);
            finishWithError("Error during initialization: " + e.getMessage());
        }
    }

    /**
     * Creates and displays a progress dialog to show the user that media
     * processing is underway.
     *
     * The dialog is non-cancelable to prevent users from interrupting
     * the processing operation.
     */
    private void createProgressDialog() {
        // Inflate the custom dialog layout
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        progressMessageView = dialogView.findViewById(R.id.progress_message);

        // Create and configure the progress dialog
        progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Processing")
                .setView(dialogView)
                .setCancelable(false) // Prevent cancellation during processing
                .create();

        // Set initial message and display the dialog
        progressMessageView.setText(getString(R.string.status_processing));
        progressDialog.show();
    }

    /**
     * Updates the progress message shown to the user.
     *
     * @param message The progress message to display
     */
    private void updateProgressMessage(String message) {
        if (progressMessageView != null) {
            progressMessageView.setText(message);
        }
    }

    /**
     * Processes the incoming intent to determine the type of shared content
     * and routes to appropriate handler methods.
     *
     * Handles single image/video sharing and multiple media sharing.
     *
     * @param intent The intent that started this activity
     */
    private void handleIntent(Intent intent) {
        try {
            String action = intent.getAction();
            String type = intent.getType();

            // Log intent details for debugging
            FirebaseCrashlytics.getInstance().setCustomKey("intent_action", action != null ? action : "null");
            FirebaseCrashlytics.getInstance().setCustomKey("intent_type", type != null ? type : "null");

            // Handle single media item sharing
            if (Intent.ACTION_SEND.equals(action) && type != null) {
                if (type.startsWith("image/")) {
                    FirebaseCrashlytics.getInstance().log("Handling single image");
                    handleSentImage(intent);
                } else if (type.startsWith("video/")) {
                    FirebaseCrashlytics.getInstance().log("Handling single video");
                    handleSentVideo(intent);
                } else {
                    // Unsupported media type
                    FirebaseCrashlytics.getInstance().setCustomKey("unsupported_type", type);
                    finishWithError("Unsupported media type");
                }
            }
            // Handle multiple media items sharing
            else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
                if (type.startsWith("image/") || type.startsWith("video/")) {
                    FirebaseCrashlytics.getInstance().log("Handling multiple media");
                    handleMultipleMedia(intent);
                } else {
                    // Unsupported media type
                    FirebaseCrashlytics.getInstance().setCustomKey("unsupported_type", type);
                    finishWithError("Unsupported media type");
                }
            } else {
                // Unsupported action
                FirebaseCrashlytics.getInstance().setCustomKey("unsupported_action", action != null ? action : "null");
                finishWithError("Unsupported action");
            }
        } catch (Exception e) {
            // Log and handle any errors during intent processing
            FirebaseCrashlytics.getInstance().recordException(e);
            finishWithError("Failed to process intent: " + e.getMessage());
        }
    }

    /**
     * Processes a single image received from another application.
     *
     * Extracts the image URI from the intent and initiates processing.
     *
     * @param intent The intent containing the image URI
     */
    private void handleSentImage(Intent intent) {
        try {
            // Extract the image URI from the intent
            receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (receivedUri != null) {
                // Log the URI and process the image
                FirebaseCrashlytics.getInstance().setCustomKey("received_uri", receivedUri.toString());
                processMediaItem(receivedUri, false); // false indicates it's an image, not a video
            } else {
                // Handle case where URI is missing
                FirebaseCrashlytics.getInstance().log("Received null image URI");
                finishWithError("Failed to receive image");
            }
        } catch (Exception e) {
            // Log and handle any errors
            FirebaseCrashlytics.getInstance().recordException(e);
            finishWithError("Error handling image: " + e.getMessage());
        }
    }

    /**
     * Processes a single video received from another application.
     *
     * Extracts the video URI from the intent and initiates processing.
     *
     * @param intent The intent containing the video URI
     */
    private void handleSentVideo(Intent intent) {
        try {
            // Extract the video URI from the intent
            receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (receivedUri != null) {
                // Log the URI and process the video
                FirebaseCrashlytics.getInstance().setCustomKey("received_uri", receivedUri.toString());
                processMediaItem(receivedUri, true); // true indicates it's a video
            } else {
                // Handle case where URI is missing
                FirebaseCrashlytics.getInstance().log("Received null video URI");
                finishWithError("Failed to receive video");
            }
        } catch (Exception e) {
            // Log and handle any errors
            FirebaseCrashlytics.getInstance().recordException(e);
            finishWithError("Error handling video: " + e.getMessage());
        }
    }

    /**
     * Processes multiple media items received from another application.
     *
     * Currently, this method only processes the first media item in the list.
     *
     * @param intent The intent containing multiple media URIs
     */
    private void handleMultipleMedia(Intent intent) {
        try {
            // Extract the list of media URIs from the intent
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) {
                // Log the count of media items
                FirebaseCrashlytics.getInstance().setCustomKey("media_count", uris.size());

                // Process only the first item in the list
                receivedUri = uris.get(0);

                // Determine if it's a video or image by checking MIME type
                String mimeType = getContentResolver().getType(receivedUri);
                boolean isVideo = mimeType != null && mimeType.startsWith("video/");
                FirebaseCrashlytics.getInstance().setCustomKey("is_video", isVideo);

                // Process the first media item
                processMediaItem(receivedUri, isVideo);
            } else {
                // Handle case where URIs are missing
                FirebaseCrashlytics.getInstance().log("Received empty media list");
                finishWithError("Failed to receive media");
            }
        } catch (Exception e) {
            // Log and handle any errors
            FirebaseCrashlytics.getInstance().recordException(e);
            finishWithError("Error handling multiple media: " + e.getMessage());
        }
    }

    /**
     * Processes a media item by creating a MediaItem and passing it to the MediaProcessor.
     *
     * Handles progress updates and completion of processing through callbacks.
     *
     * @param uri The URI of the media item to process
     * @param isVideo Whether the media item is a video (true) or image (false)
     */
    private void processMediaItem(Uri uri, boolean isVideo) {
        try {
            // Get the file name from the URI
            String fileName = mediaSelector.getFileName(uri);
            FirebaseCrashlytics.getInstance().setCustomKey("file_name", fileName);

            // Create a MediaItem object with the URI, type, and filename
            MediaItem mediaItem = new MediaItem(uri, isVideo, fileName);

            // Create a list with this single item (the processor expects a list)
            List<MediaItem> items = new ArrayList<>();
            items.add(mediaItem);

            // Process the media item with progress and completion callbacks
            mediaProcessor.processMediaItems(items, new MediaProcessor.ProcessingCallback() {
                /**
                 * Called periodically during processing with progress updates.
                 */
                @Override
                public void onProgress(int current, int total, String message) {
                    runOnUiThread(() -> {
                        try {
                            // Update the progress message on the UI thread
                            updateProgressMessage(message);
                        } catch (Exception e) {
                            FirebaseCrashlytics.getInstance().recordException(e);
                        }
                    });
                }

                /**
                 * Called when processing is complete.
                 */
                @Override
                public void onComplete(int processedCount) {
                    runOnUiThread(() -> {
                        try {
                            // Dismiss the progress dialog
                            dismissProgressDialog();

                            // Log the number of successfully processed items
                            FirebaseCrashlytics.getInstance().setCustomKey("processed_count", processedCount);

                            if (processedCount > 0) {
                                // If processing was successful, share the cleaned file
                                FirebaseCrashlytics.getInstance().log("Media processing completed successfully");
                                shareCleanFile(mediaItem.isVideo());
                            } else {
                                // If no items were processed, show an error
                                FirebaseCrashlytics.getInstance().log("Media processing failed - zero processed files");
                                finishWithError("Processing failed");
                            }
                        } catch (Exception e) {
                            // Handle any errors during completion
                            FirebaseCrashlytics.getInstance().recordException(e);
                            finishWithError("Error in processing completion: " + e.getMessage());
                        }
                    });
                }
            });
        } catch (Exception e) {
            // Log and handle any errors during processing setup
            FirebaseCrashlytics.getInstance().recordException(e);
            finishWithError("Failed to process media: " + e.getMessage());
        }
    }

    /**
     * Safely dismisses the progress dialog if it is showing.
     *
     * Handles any exceptions that might occur during dismissal.
     */
    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try {
                progressDialog.dismiss();
            } catch (Exception e) {
                // Log but don't crash if dialog dismissal fails
                FirebaseCrashlytics.getInstance().recordException(e);
            }
        }
    }

    /**
     * Creates and launches a share intent for the processed (clean) media file.
     *
     * @param isVideo Whether the processed file is a video (true) or image (false)
     */
    private void shareCleanFile(boolean isVideo) {
        try {
            // Get the URI of the processed file
            Uri cleanedFileUri = mediaProcessor.getLastProcessedFileUri();
            FirebaseCrashlytics.getInstance().setCustomKey("cleaned_uri", cleanedFileUri != null ? cleanedFileUri.toString() : "null");

            if (cleanedFileUri != null) {
                // Create a share intent with the appropriate MIME type
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(isVideo ? "video/*" : "image/*");
                shareIntent.putExtra(Intent.EXTRA_STREAM, cleanedFileUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                // Launch the share intent
                FirebaseCrashlytics.getInstance().log("Launching share intent");
                startActivity(Intent.createChooser(shareIntent, "Share clean media file via"));

                // Close this activity since its work is done
                finish();
            } else {
                // Handle case where processing didn't produce a valid file
                FirebaseCrashlytics.getInstance().log("Cleaned file URI is null");
                finishWithError("Failed to get cleaned file");
            }
        } catch (Exception e) {
            // Log and handle any errors during sharing
            FirebaseCrashlytics.getInstance().recordException(e);
            finishWithError("Error sharing clean file: " + e.getMessage());
        }
    }

    /**
     * Displays an error message to the user, logs it, and finishes the activity.
     *
     * This is the common error handling path for all failures.
     *
     * @param message The error message to show and log
     */
    private void finishWithError(String message) {
        // Log the error message
        FirebaseCrashlytics.getInstance().log("Error: " + message);

        // Show a toast with the error message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

        // Dismiss any showing dialogs
        dismissProgressDialog();

        // Finish this activity
        finish();
    }

    /**
     * Cleans up resources when the activity is destroyed.
     *
     * Ensures the progress dialog is dismissed to prevent window leaks.
     */
    @Override
    protected void onDestroy() {
        // Make sure to dismiss the dialog to prevent window leaks
        dismissProgressDialog();
        super.onDestroy();
    }
}
