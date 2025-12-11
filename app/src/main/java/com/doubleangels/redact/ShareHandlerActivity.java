package com.doubleangels.redact;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.metadata.MetadataStripper;
import java.io.File;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.sentry.Sentry;

import java.util.ArrayList;

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
    private MediaSelector mediaSelector;
    private MetadataStripper metadataStripper;
    
    // Track the processed file for cleanup after sharing
    private File processedFile;
    
    // Track whether sharing has been initiated
    private boolean sharingInitiated = false;

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

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        try {
            // Initialize media processing components
            mediaSelector = new MediaSelector(this, null);
            metadataStripper = new MetadataStripper(this);
            
            // Set up progress callback for metadata stripping
            metadataStripper.setProgressCallback((current, total, message) -> runOnUiThread(() -> updateProgressMessage(message)));

            // Log activity creation for debugging purposes
            Sentry.captureMessage("ShareHandlerActivity created");

            // Create and show the progress dialog
            createProgressDialog();

            // Process the intent that launched this activity
            handleIntent(getIntent());
        } catch (Exception e) {
            // Record any initialization errors and finish the activity
            Sentry.captureException(e);
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

            // Log intent details for debugging            // Handle single media item sharing
            if (Intent.ACTION_SEND.equals(action) && type != null) {
                if (type.startsWith("image/")) {
                    Sentry.captureMessage("Handling single image");
                    handleSentImage(intent);
                } else if (type.startsWith("video/")) {
                    Sentry.captureMessage("Handling single video");
                    handleSentVideo(intent);
                } else {
                    // Unsupported media type                    finishWithError("Unsupported media type");
                }
            }
            // Handle multiple media items sharing
            else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
                if (type.startsWith("image/") || type.startsWith("video/")) {
                    Sentry.captureMessage("Handling multiple media");
                    handleMultipleMedia(intent);
                } else {
                    // Unsupported media type                    finishWithError("Unsupported media type");
                }
            } else {
                // Unsupported action                finishWithError("Unsupported action");
            }
        } catch (Exception e) {
            // Log and handle any errors during intent processing
            Sentry.captureException(e);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (receivedUri != null) {
                // Log the URI and process the image                processMediaItem(receivedUri, false); // false indicates it's an image, not a video
            } else {
                // Handle case where URI is missing
                Sentry.captureMessage("Received null image URI");
                finishWithError("Failed to receive image");
            }
        } catch (Exception e) {
            // Log and handle any errors
            Sentry.captureException(e);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                receivedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (receivedUri != null) {
                // Log the URI and process the video                processMediaItem(receivedUri, true); // true indicates it's a video
            } else {
                // Handle case where URI is missing
                Sentry.captureMessage("Received null video URI");
                finishWithError("Failed to receive video");
            }
        } catch (Exception e) {
            // Log and handle any errors
            Sentry.captureException(e);
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
            ArrayList<Uri> uris;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            }
            if (uris != null && !uris.isEmpty()) {
                // Log the count of media items                // Process only the first item in the list
                receivedUri = uris.get(0);

                // Determine if it's a video or image by checking MIME type
                String mimeType = getContentResolver().getType(receivedUri);
                boolean isVideo = mimeType != null && mimeType.startsWith("video/");                // Process the first media item
                processMediaItem(receivedUri, isVideo);
            } else {
                // Handle case where URIs are missing
                Sentry.captureMessage("Received empty media list");
                finishWithError("Failed to receive media");
            }
        } catch (Exception e) {
            // Log and handle any errors
            Sentry.captureException(e);
            finishWithError("Error handling multiple media: " + e.getMessage());
        }
    }

    /**
     * Processes a media item by stripping metadata for sharing.
     *
     * Uses the "ForSharing" methods which save to cache directory instead of MediaStore,
     * and the file will be deleted after sharing.
     *
     * @param uri The URI of the media item to process
     * @param isVideo Whether the media item is a video (true) or image (false)
     */
    private void processMediaItem(Uri uri, boolean isVideo) {
        new Thread(() -> {
            try {
                // Get the file name from the URI
                String fileName = mediaSelector.getFileName(uri);                // Process using the "ForSharing" method which saves to cache
                Uri processedUri = metadataStripper.stripMetadataForSharing(uri, fileName, isVideo);

                runOnUiThread(() -> {
                    try {
                        // Dismiss the progress dialog
                        dismissProgressDialog();

                        if (processedUri != null) {
                            // Find the processed file for later cleanup
                            // Try to get the file path from the URI first, then fall back to finding most recent
                            try {
                            // Try to extract file path from the URI
                            String uriPath = processedUri.getPath();
                            if (uriPath != null) {
                                // Remove the authority prefix if present (e.g., "/cache/processed/filename.jpg")
                                if (uriPath.contains("/processed/")) {
                                    String extractedFileName = uriPath.substring(uriPath.lastIndexOf("/") + 1);
                                    File cacheDir = new File(getCacheDir(), "processed");
                                    File potentialFile = new File(cacheDir, extractedFileName);
                                    if (potentialFile.exists() && potentialFile.isFile()) {
                                        processedFile = potentialFile;
                                        Sentry.captureMessage("Found processed file by name: " + extractedFileName);
                                    }
                                }
                            }
                                
                                // Fallback: find the most recently modified file if we couldn't find by name
                                if (processedFile == null) {
                                    File cacheDir = new File(getCacheDir(), "processed");
                                    if (cacheDir.exists() && cacheDir.isDirectory()) {
                                        File[] files = cacheDir.listFiles();
                                        if (files != null && files.length > 0) {
                                            // Find the most recently modified file (should be the one we just created)
                                            File mostRecent = null;
                                            long mostRecentTime = 0;
                                            for (File f : files) {
                                                if (f.isFile() && f.lastModified() > mostRecentTime) {
                                                    mostRecentTime = f.lastModified();
                                                    mostRecent = f;
                                                }
                                            }
                                            if (mostRecent != null) {
                                                processedFile = mostRecent;
                                                Sentry.captureMessage("Found processed file by timestamp: " + mostRecent.getName());
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                Sentry.captureMessage("Could not find processed file for cleanup: " + e.getMessage());
                                Sentry.captureException(e);
                            }
                            
                            // Share the cleaned file
                            Sentry.captureMessage("Media processing completed successfully");
                            shareCleanFile(isVideo, processedUri);
                        } else {
                            // If processing failed, show an error
                            Sentry.captureMessage("Media processing failed");
                            finishWithError("Processing failed");
                        }
                    } catch (Exception e) {
                        // Handle any errors during completion
                        Sentry.captureException(e);
                        finishWithError("Error in processing completion: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                // Log and handle any errors during processing
                Sentry.captureException(e);
                runOnUiThread(() -> finishWithError("Failed to process media: " + e.getMessage()));
            }
        }).start();
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
                Sentry.captureException(e);
            }
        }
    }

    /**
     * Creates and launches a share intent for the processed (clean) media file.
     * The temporary file will be deleted after sharing completes (when activity resumes).
     *
     * @param isVideo Whether the processed file is a video (true) or image (false)
     * @param cleanedFileUri The URI of the processed file to share
     */
    private void shareCleanFile(boolean isVideo, Uri cleanedFileUri) {
        try {            if (cleanedFileUri != null) {
                // Create a share intent with the appropriate MIME type
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(isVideo ? "video/*" : "image/*");
                shareIntent.putExtra(Intent.EXTRA_STREAM, cleanedFileUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                // Mark that sharing has been initiated
                sharingInitiated = true;

                // Launch the share intent
                // Note: We don't finish() immediately so we can cleanup in onResume()
                Sentry.captureMessage("Launching share intent");
                startActivity(Intent.createChooser(shareIntent, "Share clean media file via"));
            } else {
                // Handle case where processing didn't produce a valid file
                Sentry.captureMessage("Cleaned file URI is null");
                finishWithError("Failed to get cleaned file");
            }
        } catch (Exception e) {
            // Log and handle any errors during sharing
            Sentry.captureException(e);
            finishWithError("Error sharing clean file: " + e.getMessage());
        }
    }
    
    /**
     * Called when the activity is paused (e.g., when share chooser is shown).
     * This is where we can detect that sharing has started.
     */
    @Override
    protected void onPause() {
        super.onPause();
        // When activity pauses, it means the share chooser or target app is shown
        // We'll cleanup when the activity resumes (user returns from sharing)
    }
    
    /**
     * Called when the activity resumes (e.g., when user returns from share chooser).
     * This is where we cleanup the temporary file after sharing is complete.
     */
    @Override
    protected void onResume() {
        super.onResume();
        
        // If sharing was initiated and we're resuming, it means the user has
        // interacted with the share chooser (either shared or cancelled)
        // Clean up the temporary file now
        if (sharingInitiated) {
            cleanupProcessedFile();
            // Finish the activity since sharing is complete
            finish();
        }
    }
    
    /**
     * Deletes the temporary processed file from disk.
     * This is called after sharing completes to avoid saving files to disk.
     */
    private void cleanupProcessedFile() {
        if (processedFile != null && processedFile.exists()) {
            try {
                if (processedFile.delete()) {
                    Sentry.captureMessage("Deleted temporary processed file after sharing");
                } else {
                    Sentry.captureMessage("Failed to delete temporary processed file");
                    // Try to delete on exit as fallback
                    processedFile.deleteOnExit();
                }
            } catch (Exception e) {
                Sentry.captureMessage("Error deleting temporary file: " + e.getMessage());
            }
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
        Sentry.captureMessage("Error: " + message);

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
     * Ensures the progress dialog is dismissed to prevent window leaks,
     * and deletes the temporary processed file if it still exists (fallback cleanup).
     */
    @Override
    protected void onDestroy() {
        // Make sure to dismiss the dialog to prevent window leaks
        dismissProgressDialog();
        
        // Clean up the temporary processed file if it still exists
        // This is a fallback in case onResume() wasn't called for some reason
        cleanupProcessedFile();
        
        super.onDestroy();
    }
}
