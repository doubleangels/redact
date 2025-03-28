package com.doubleangels.redact;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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

public class ScanActivity extends AppCompatActivity implements NavigationBarView.OnItemSelectedListener {

    private TextView statusText;
    private TextView progressText;
    private View progressBar;
    private MaterialButton selectMediaButton;

    // Metadata section TextViews
    private TextView basicInfoText;
    private TextView mediaDetailsText;
    private TextView locationText;
    private TextView technicalText;

    // Cards for each section
    private MaterialCardView basicInfoCard;
    private MaterialCardView mediaDetailsCard;
    private MaterialCardView locationCard;
    private MaterialCardView technicalCard;

    // Title TextViews that might need to be updated based on media type
    private TextView mediaDetailsTitle;
    private TextView technicalTitle;

    private ActivityResultLauncher<Intent> mediaPickerLauncher;

    // Permission manager
    private PermissionManager permissionManager;

    // Store the current media URI to refresh metadata after permission is granted
    private Uri currentMediaUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply Material You dynamic colors if available on the device
        DynamicColors.applyToActivityIfAvailable(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        // Initialize views
        statusText = findViewById(R.id.statusText);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        selectMediaButton = findViewById(R.id.selectMediaButton);

        // Initialize metadata section TextViews
        basicInfoText = findViewById(R.id.basicInfoText);
        mediaDetailsText = findViewById(R.id.mediaDetailsText);
        locationText = findViewById(R.id.locationText);
        technicalText = findViewById(R.id.technicalText);

        // Initialize cards
        basicInfoCard = findViewById(R.id.basicInfoCard);
        mediaDetailsCard = findViewById(R.id.mediaDetailsCard);
        locationCard = findViewById(R.id.locationCard);
        technicalCard = findViewById(R.id.technicalCard);

        // Initialize title TextViews
        mediaDetailsTitle = findViewById(R.id.mediaDetailsTitle);
        technicalTitle = findViewById(R.id.technicalTitle);

        // Initialize bottom navigation
        // Bottom navigation
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(this);
        bottomNavigationView.setSelectedItemId(R.id.navigation_scan); // Set the current tab as selected

        // Hide all cards initially
        hideAllCards();

        // Set up settings launcher
        // Check if permissions are now granted after returning from settings
        // If location permission is now granted, refresh metadata
        ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // Check if permissions are now granted after returning from settings
                    if (currentMediaUri != null && !permissionManager.needsLocationPermission()) {
                        // If location permission is now granted, refresh metadata
                        displayMetadata(currentMediaUri);
                    }
                });

        // Initialize permission manager
        permissionManager = new PermissionManager(this, findViewById(R.id.root_layout), settingsLauncher,
                new PermissionManager.PermissionCallback() {
                    @Override
                    public void onPermissionsGranted() {
                        // Basic permissions granted, enable media selection
                        selectMediaButton.setEnabled(true);
                    }

                    @Override
                    public void onPermissionsDenied() {
                        // Basic permissions denied, disable media selection
                        selectMediaButton.setEnabled(false);
                        showStatus("Storage permissions required");
                    }

                    @Override
                    public void onPermissionsRequestStarted() {
                        showStatus("Requesting permissions...");
                    }

                    @Override
                    public void onLocationPermissionGranted() {
                        // Location permission granted, refresh metadata if we have a current URI
                        if (currentMediaUri != null) {
                            displayMetadata(currentMediaUri);
                        }
                        Toast.makeText(ScanActivity.this,
                                "Location permission granted. Full location data will be shown.",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onLocationPermissionDenied() {
                        Toast.makeText(ScanActivity.this,
                                "Location permission denied. Location data will be limited.",
                                Toast.LENGTH_SHORT).show();
                    }
                });

        // Set up media picker launcher
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri mediaUri = result.getData().getData();
                        if (mediaUri != null) {
                            currentMediaUri = mediaUri;
                            checkLocationPermissionAndDisplayMetadata(mediaUri);
                        } else {
                            showStatus("Error: Could not get media URI");
                        }
                    }
                });

        // Set up button click listener
        selectMediaButton.setOnClickListener(v -> openMediaPicker());

        // Check basic permissions when activity starts
        permissionManager.checkPermissions();
    }

    /**
     * Checks if location permission is granted and displays metadata accordingly
     */
    private void checkLocationPermissionAndDisplayMetadata(Uri mediaUri) {
        boolean hasLocationPermission = !permissionManager.needsLocationPermission();

        // Log permission status for debugging
        Log.d("ScanActivity", "Has location permission: " + hasLocationPermission);
        FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);
        FirebaseCrashlytics.getInstance().setCustomKey("has_location_permission", hasLocationPermission);

        // Display metadata regardless of permission status
        // If permission is not granted, location data might be limited
        displayMetadata(mediaUri);

        if (!hasLocationPermission) {
            // Show a message that location data might be limited
            Toast.makeText(this,
                    "Location permission not granted. Location data may be limited.",
                    Toast.LENGTH_SHORT).show();

            // Request the permission
            permissionManager.requestLocationPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Let the permission manager handle the result
        permissionManager.handlePermissionResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();

        // Already in ScanActivity
        if (itemId == R.id.navigation_clean) {
            startActivity(new Intent(this, MainActivity.class));
            return true;
        } else return itemId == R.id.navigation_scan;
    }

    /**
     * Opens the media picker to select an image or video file.
     */
    private void openMediaPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            // Support both images and videos
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // This flag helps preserve more metadata
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            mediaPickerLauncher.launch(intent);
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
            showStatus("Error opening media picker");
        }
    }


    /**
     * Displays metadata for the selected media file.
     *
     * @param mediaUri URI of the selected media file
     */
    private void displayMetadata(Uri mediaUri) {
        try {
            // Show progress indicator
            showStatus("Analyzing metadata...");
            showProgress(true);

            // Reset all text views
            resetTextViews();

            // Hide all cards initially
            hideAllCards();

            // Determine if the selected file is a video or image
            String mimeType = getContentResolver().getType(mediaUri);
            boolean isVideo = mimeType != null && mimeType.startsWith("video/");

            // Log the media type for debugging
            FirebaseCrashlytics.getInstance().log("Processing media with MIME type: " + mimeType);
            FirebaseCrashlytics.getInstance().setCustomKey("media_type", mimeType != null ? mimeType : "unknown");

            // Log permission status for debugging
            boolean hasLocationPermission = !permissionManager.needsLocationPermission();
            FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);
            FirebaseCrashlytics.getInstance().setCustomKey("has_location_permission", hasLocationPermission);

            // Update progress text based on media type
            progressText.setText(isVideo ? "Extracting video metadata..." : "Extracting image metadata...");

            // Use MetadataDisplayer with sectioned callback to extract metadata in a background thread
            MetadataDisplayer.extractSectionedMetadata(this, mediaUri, new MetadataDisplayer.SectionedMetadataCallback() {
                @Override
                public void onMetadataExtracted(Map<String, String> metadataSections, boolean isVideo) {
                    runOnUiThread(() -> {
                        try {
                            // Hide progress indicator
                            showProgress(false);
                            showStatus("Metadata extraction complete");

                            // Update section titles based on media type
                            mediaDetailsTitle.setText(R.string.scan_camera_details);
                            technicalTitle.setText(R.string.scan_technical_details);

                            // Display each section
                            displaySections(metadataSections);

                            // Show a message if location data is missing and permission is not granted
                            if (!metadataSections.containsKey(MetadataDisplayer.SECTION_LOCATION) &&
                                    permissionManager.needsLocationPermission()) {
                                locationText.setText(R.string.scan_location_permission_missing);
                                locationCard.setVisibility(View.VISIBLE);
                            }
                        } catch (Exception e) {
                            FirebaseCrashlytics.getInstance().recordException(e);
                        }
                    });
                }

                @Override
                public void onExtractionFailed(String error) {
                    runOnUiThread(() -> {
                        showProgress(false);
                        showStatus("Error extracting metadata: " + error);
                        basicInfoText.setText(R.string.scan_extraction_fail);
                        basicInfoCard.setVisibility(View.VISIBLE);
                        FirebaseCrashlytics.getInstance().log("Metadata extraction failed: " + error);
                    });
                }
            });
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
            showProgress(false);
            showStatus("Error processing media file");
        }
    }

    /**
     * Displays the metadata sections in their respective TextViews.
     *
     * @param sections Map of section identifiers to their content
     */
    private void displaySections(Map<String, String> sections) {
        // Basic info section
        if (sections.containsKey(MetadataDisplayer.SECTION_BASIC_INFO)) {
            String content = sections.get(MetadataDisplayer.SECTION_BASIC_INFO);
            if (content != null && !content.isEmpty()) {
                basicInfoText.setText(content);
                basicInfoCard.setVisibility(View.VISIBLE);
            }
        }

        // Media details section
        if (sections.containsKey(MetadataDisplayer.SECTION_MEDIA_DETAILS)) {
            String content = sections.get(MetadataDisplayer.SECTION_MEDIA_DETAILS);
            if (content != null && !content.isEmpty()) {
                mediaDetailsText.setText(content);
                mediaDetailsCard.setVisibility(View.VISIBLE);
            }
        }

        // Location section
        if (sections.containsKey(MetadataDisplayer.SECTION_LOCATION)) {
            String content = sections.get(MetadataDisplayer.SECTION_LOCATION);
            if (content != null && !content.isEmpty()) {
                locationText.setText(content);
                locationCard.setVisibility(View.VISIBLE);
            }
        }

        // Technical section
        if (sections.containsKey(MetadataDisplayer.SECTION_TECHNICAL)) {
            String content = sections.get(MetadataDisplayer.SECTION_TECHNICAL);
            if (content != null && !content.isEmpty()) {
                technicalText.setText(content);
                technicalCard.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Resets all text views to empty.
     */
    private void resetTextViews() {
        basicInfoText.setText("");
        mediaDetailsText.setText("");
        locationText.setText("");
        technicalText.setText("");
    }

    /**
     * Hides all metadata cards.
     */
    private void hideAllCards() {
        basicInfoCard.setVisibility(View.GONE);
        mediaDetailsCard.setVisibility(View.GONE);
        locationCard.setVisibility(View.GONE);
        technicalCard.setVisibility(View.GONE);
    }

    /**
     * Shows or hides the progress indicator.
     *
     * @param show True to show, false to hide
     */
    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * Updates the status text.
     *
     * @param message Status message
     */
    private void showStatus(String message) {
        statusText.setText(message);
    }
}
