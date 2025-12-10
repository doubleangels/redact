package com.doubleangels.redact;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.doubleangels.redact.metadata.MetadataDisplayer;
import com.doubleangels.redact.permission.PermissionManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.navigation.NavigationBarView;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.Map;

/**
 * ScanActivity is responsible for handling the media scanning functionality of the application.
 * This activity allows users to select media files (images or videos) from their device and
 * displays the extracted metadata in categorized sections.
 *
 * The activity implements NavigationBarView.OnItemSelectedListener to handle bottom navigation
 * between different app sections.
 */
public class ScanActivity extends AppCompatActivity implements NavigationBarView.OnItemSelectedListener {

    // UI elements for status and progress display
    private TextView statusText;
    private TextView progressText;
    private View progressBar;
    private MaterialButton selectMediaButton;

    // Single TextView for displaying all metadata
    private TextView metadataText;
    private MaterialCardView metadataCard;

    // Launcher for media picker intent
    private ActivityResultLauncher<Intent> mediaPickerLauncher;

    // Manager for handling runtime permissions
    private PermissionManager permissionManager;

    // Currently selected media URI
    private Uri currentMediaUri;

    /**
     * Initializes the activity, sets up UI components, registers activity result launchers,
     * and configures permission management.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being
     *                           shut down, this Bundle contains the data it most recently supplied.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply Material You dynamic colors if available on the device
        DynamicColors.applyToActivityIfAvailable(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        // Initialize UI elements
        statusText = findViewById(R.id.statusText);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        selectMediaButton = findViewById(R.id.selectButton);

        // Initialize metadata display
        metadataText = findViewById(R.id.metadataText);
        metadataCard = findViewById(R.id.metadataCard);

        // Set up bottom navigation
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
        bottomNavigationView.setOnItemSelectedListener(this);
        bottomNavigationView.setSelectedItemId(R.id.navigation_scan); // Set the current tab as selected

        // Hide metadata card initially
        metadataCard.setVisibility(View.GONE);

        // Display app version in UI
        setupVersionNumber();

        // Register launcher for settings activity
        // This launcher is used when returning from app settings (e.g., after granting permissions)
        ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // If we have a media URI and location permission is granted, display metadata
                    if (currentMediaUri != null && !permissionManager.needsLocationPermission()) {
                        displayMetadata(currentMediaUri);
                    }
                });

        // Initialize permission manager with callbacks
        permissionManager = new PermissionManager(this, findViewById(R.id.root_layout), settingsLauncher,
                new PermissionManager.PermissionCallback() {
                    /**
                     * Called when all required permissions are granted.
                     * Enables the media selection button.
                     */
                    @Override
                    public void onPermissionsGranted() {
                        selectMediaButton.setEnabled(true);
                    }

                    /**
                     * Called when permissions are denied.
                     * Disables the media selection button and shows a status message.
                     */
                    @Override
                    public void onPermissionsDenied() {
                        selectMediaButton.setEnabled(false);
                        showStatus(getString(R.string.status_storage_permissions_required));
                    }

                    /**
                     * Called when permission request process starts.
                     * Updates the status text to inform the user.
                     */
                    @Override
                    public void onPermissionsRequestStarted() {
                        showStatus(getString(R.string.status_requesting_permissions));
                    }

                    /**
                     * Called when location permission is specifically granted.
                     * Refreshes metadata display if a media file is already selected.
                     */
                    @Override
                    public void onLocationPermissionGranted() {
                        if (currentMediaUri != null) {
                            displayMetadata(currentMediaUri);
                        }
                    }
                });

        // Register launcher for media picker
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri mediaUri = result.getData().getData();
                        if (mediaUri != null) {
                            // Store the selected media URI and process it
                            currentMediaUri = mediaUri;
                            checkLocationPermissionAndDisplayMetadata(mediaUri);
                        } else {
                            showStatus(getString(R.string.status_media_uri_fail));
                        }
                    }
                });

        // Set click listener for media selection button
        selectMediaButton.setOnClickListener(v -> openMediaPicker());

        // Check for required permissions on activity start
        permissionManager.checkPermissions();
    }

    /**
     * Checks if location permission is granted and displays metadata.
     * If location permission is not granted, requests it after displaying available metadata.
     *
     * @param mediaUri URI of the media file to analyze
     */
    private void checkLocationPermissionAndDisplayMetadata(Uri mediaUri) {
        boolean hasLocationPermission = !permissionManager.needsLocationPermission();

        // Log permission status for analytics and debugging
        FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);
        FirebaseCrashlytics.getInstance().setCustomKey("has_location_permission", hasLocationPermission);

        // Display metadata with whatever permissions we currently have
        displayMetadata(mediaUri);

        // Request location permission if not already granted
        if (!hasLocationPermission) {
            permissionManager.requestLocationPermission();
        }
    }

    /**
     * Handles the results of permission requests.
     * Delegates to the permission manager for processing.
     *
     * @param requestCode The request code passed in requestPermissions()
     * @param permissions The requested permissions
     * @param grantResults The grant results for the corresponding permissions
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Let the permission manager handle the result
        permissionManager.handlePermissionResult(requestCode, permissions, grantResults);
    }

    /**
     * Handles bottom navigation item selection.
     * Navigates to the appropriate activity based on the selected item.
     *
     * @param item The selected menu item
     * @return true if the item selection was handled, false otherwise
     */
    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();

        if (itemId == R.id.navigation_clean) {
            // Navigate to MainActivity when Clean tab is selected
            startActivity(new Intent(this, MainActivity.class));
            return true;
        } else return itemId == R.id.navigation_scan; // Return true if Scan tab is selected
    }

    /**
     * Opens the system media picker to allow the user to select an image or video.
     * Configures the intent to request read permission for the selected file.
     */
    private void openMediaPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            mediaPickerLauncher.launch(intent);
        } catch (Exception e) {
            // Log exception and show error message
            FirebaseCrashlytics.getInstance().recordException(e);
            showStatus(getString(R.string.status_media_picker_fail));
        }
    }

    /**
     * Extracts and displays metadata from the selected media file.
     * Shows progress indicators during extraction and organizes the results
     * into different categories.
     *
     * @param mediaUri URI of the media file to analyze
     */
    private void displayMetadata(Uri mediaUri) {
        try {
            // Update UI to show analysis is in progress
            showStatus(getString(R.string.status_analyzing));
            showProgress(true);

            // Clear previous metadata
            metadataText.setText("");
            metadataCard.setVisibility(View.GONE);

            // Determine if the selected file is a video
            String mimeType = getContentResolver().getType(mediaUri);
            boolean isVideo = mimeType != null && mimeType.startsWith("video/");

            // Log media type for analytics and debugging
            FirebaseCrashlytics.getInstance().log("Processing media with MIME type: " + mimeType);
            FirebaseCrashlytics.getInstance().setCustomKey("media_type", mimeType != null ? mimeType : "unknown");

            // Log permission status
            boolean hasLocationPermission = !permissionManager.needsLocationPermission();
            FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);
            FirebaseCrashlytics.getInstance().setCustomKey("has_location_permission", hasLocationPermission);

            // Update progress text based on media type
            progressText.setText(isVideo ? R.string.status_extracting_media : R.string.status_extracting_image);

            // Extract metadata using MetadataDisplayer utility
            MetadataDisplayer.extractSectionedMetadata(this, mediaUri, new MetadataDisplayer.SectionedMetadataCallback() {
                /**
                 * Called when metadata extraction is successful.
                 * Updates the UI with the extracted metadata.
                 *
                 * @param metadataSections Map containing different sections of metadata
                 * @param isVideo Whether the processed file is a video
                 */
                @Override
                public void onMetadataExtracted(Map<String, String> metadataSections, boolean isVideo) {
                    runOnUiThread(() -> {
                        try {
                            // Hide progress indicator
                            showProgress(false);
                            showStatus(getString(R.string.status_extraction_complete));

                            // Combine and display all metadata sections
                            displayCombinedMetadata(metadataSections);

                            // Show location permission message if needed
                            if (!metadataSections.containsKey(MetadataDisplayer.SECTION_LOCATION) &&
                                    permissionManager.needsLocationPermission()) {
                                String currentText = metadataText.getText().toString();
                                if (!currentText.isEmpty()) {
                                    metadataText.setText(currentText + "\n" + getString(R.string.scan_location_permission_missing));
                                } else {
                                    metadataText.setText(getString(R.string.scan_location_permission_missing));
                                }
                                metadataCard.setVisibility(View.VISIBLE);
                            }
                        } catch (Exception e) {
                            FirebaseCrashlytics.getInstance().recordException(e);
                        }
                    });
                }

                /**
                 * Called when metadata extraction fails.
                 * Updates the UI to show the error.
                 *
                 * @param error Error message describing the failure
                 */
                @Override
                public void onExtractionFailed(String error) {
                    runOnUiThread(() -> {
                        showProgress(false);
                        showStatus(getString(R.string.status_extraction_fail));
                        metadataText.setText(getString(R.string.scan_extraction_fail));
                        metadataCard.setVisibility(View.VISIBLE);
                        FirebaseCrashlytics.getInstance().log("Metadata extraction failed: " + error);
                    });
                }
            });
        } catch (Exception e) {
            // Log exception and show error message
            FirebaseCrashlytics.getInstance().recordException(e);
            showProgress(false);
            showStatus(getString(R.string.status_extraction_media_fail));
        }
    }

    /**
     * Displays all metadata in a single list (already combined and sorted).
     *
     * @param sections Map containing metadata (should have SECTION_BASIC_INFO with all metadata)
     */
    private void displayCombinedMetadata(Map<String, String> sections) {
        // All metadata is now in a single section (SECTION_BASIC_INFO)
        if (sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO)) {
            String content = sections.get(MetadataDisplayer.SECTION_BASIC_INFO);
            if (content != null && !content.trim().isEmpty()) {
                metadataText.setText(content);
                metadataCard.setVisibility(View.VISIBLE);
            } else {
                metadataCard.setVisibility(View.GONE);
            }
        } else {
            metadataCard.setVisibility(View.GONE);
        }
    }

    /**
     * Shows or hides the progress indicators based on the provided parameter.
     *
     * @param show true to show progress indicators, false to hide them
     */
    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * Updates the status text with the provided message.
     *
     * @param message The status message to display
     */
    private void showStatus(String message) {
        statusText.setText(message);
    }

    /**
     * Retrieves and displays the application version number in the UI.
     * Also logs the version to Firebase Crashlytics for debugging purposes.
     */
    private void setupVersionNumber() {
        try {
            TextView versionText = findViewById(R.id.versionText);

            // Get package info to retrieve version name
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);

            // Set version text in UI
            versionText.setText(packageInfo.versionName);

            // Log version to Crashlytics for debugging
            assert packageInfo.versionName != null;
            FirebaseCrashlytics.getInstance().setCustomKey("app_version", packageInfo.versionName);
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }
}
