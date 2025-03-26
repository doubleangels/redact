package com.doubleangels.redact;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.media.MediaAdapter;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaProcessor;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.ui.UIStateManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the Redact application.
 *
 * This activity allows users to select media files (images and videos) and process them
 * to remove metadata and other sensitive information. It manages the entire workflow from
 * requesting permissions, selecting media files, displaying thumbnails of selected items,
 * to processing these files and showing progress.
 *
 * The class uses MVVM architecture with a MainViewModel to manage UI state, and several
 * utility classes for handling specific functionality like permissions, media selection,
 * UI state management, and media processing.
 */
public class MainActivity extends AppCompatActivity {
    /** Manages UI state and business logic */
    private MainViewModel viewModel;

    /** Handles requesting and checking permissions */
    private PermissionManager permissionManager;

    /** Manages media selection from device storage */
    private MediaSelector mediaSelector;

    /** Manages UI states and transitions */
    private UIStateManager uiStateManager;

    /** Processes media items to strip metadata */
    private MediaProcessor mediaProcessor;

    /** Button to initiate metadata stripping process */
    private MaterialButton stripButton;

    /** Text showing current application status */
    private TextView statusText;

    /** Container for progress-related UI elements */
    private LinearLayout progressContainer;

    /** Text showing detailed progress information */
    private TextView progressText;

    /** Progress bar indicating processing progress */
    private LinearProgressIndicator progressBar;

    /** Adapter for displaying selected media items in a grid */
    private MediaAdapter mediaAdapter;

    /**
     * ActivityResultLauncher for handling returns from settings activity.
     * Used when user needs to enable permissions from settings after denying them.
     */
    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                try {
                    FirebaseCrashlytics.getInstance().log("Returned from settings");
                    permissionManager.checkPermissions();
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            }
    );

    /**
     * ActivityResultLauncher for handling returns from media picker.
     * Processes selected media items and updates the view model with the results.
     */
    private final ActivityResultLauncher<Intent> mediaPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                try {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        FirebaseCrashlytics.getInstance().log("Media selected successfully");
                        List<MediaItem> items = mediaSelector.processMediaResult(result.getData());
                        FirebaseCrashlytics.getInstance().setCustomKey("selected_media_count", items.size());
                        viewModel.setSelectedItems(items);
                    } else {
                        FirebaseCrashlytics.getInstance().log("Media selection canceled or failed");
                        FirebaseCrashlytics.getInstance().setCustomKey("media_result_code", result.getResultCode());
                    }
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            }
    );

    /**
     * Initializes the activity, sets up the UI, and initializes utility classes.
     *
     * This method applies dynamic colors, sets up view references, initializes utility classes,
     * sets up data observers, and checks for required permissions.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously
     *                           being shut down, this contains the data it most recently supplied.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            // Apply Material You dynamic colors if available on the device
            DynamicColors.applyToActivityIfAvailable(this);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            FirebaseCrashlytics.getInstance().log("MainActivity created");

            // Initialize ViewModel to manage UI state
            viewModel = new ViewModelProvider(this).get(MainViewModel.class);

            // Set up UI components and event handlers
            setupViews();
            setupStatusBarColors();

            // Initialize utility classes for permissions, media selection, etc.
            initUtilityClasses();

            // Set up LiveData observers
            setupObservers();

            // Check for required permissions
            permissionManager.checkPermissions();

            FirebaseCrashlytics.getInstance().setCustomKey("app_started", true);
        } catch (Exception e) {
            // Log any exceptions that might occur during initialization
            FirebaseCrashlytics.getInstance().recordException(e);
            try {
                // Attempt to recover by setting content view if it wasn't already set
                if (findViewById(android.R.id.content) == null) {
                    setContentView(R.layout.activity_main);
                }
            } catch (Exception ignored) {
                // Ignore any exceptions that occur during recovery attempt
            }
        }
    }

    /**
     * Sets up view references and event handlers.
     *
     * This method initializes UI components, sets up the RecyclerView for displaying selected media,
     * and configures click listeners for the select and strip buttons.
     */
    private void setupViews() {
        try {
            // Get references to UI components
            RecyclerView selectedItemsGrid = findViewById(R.id.selectedItemsGrid);
            MaterialButton selectButton = findViewById(R.id.selectButton);
            stripButton = findViewById(R.id.stripButton);
            statusText = findViewById(R.id.statusText);
            progressContainer = findViewById(R.id.progressContainer);
            progressText = findViewById(R.id.progressText);
            progressBar = findViewById(R.id.progressBar);

            // Initially disable the strip button until media is selected
            stripButton.setEnabled(false);

            // Set up the RecyclerView with a grid layout for displaying media thumbnails
            mediaAdapter = new MediaAdapter(new ArrayList<>());
            selectedItemsGrid.setAdapter(mediaAdapter);
            selectedItemsGrid.setLayoutManager(new GridLayoutManager(this, 3));

            // Set up click listener for select button
            selectButton.setOnClickListener(v -> {
                try {
                    FirebaseCrashlytics.getInstance().log("Select button clicked");
                    if (permissionManager.needsPermissions()) {
                        // Request permissions if needed
                        FirebaseCrashlytics.getInstance().log("Requesting permissions");
                        permissionManager.requestStoragePermission();
                    } else {
                        // Launch media selector if permissions are granted
                        FirebaseCrashlytics.getInstance().log("Launching media selector");
                        mediaSelector.selectMedia();
                    }
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            // Set up click listener for strip button
            stripButton.setOnClickListener(v -> {
                try {
                    FirebaseCrashlytics.getInstance().log("Strip button clicked");
                    List<MediaItem> items = viewModel.getSelectedItems().getValue();
                    if (items != null && !items.isEmpty()) {
                        // Process selected media items
                        FirebaseCrashlytics.getInstance().setCustomKey("processing_items_count", items.size());
                        viewModel.setProcessingState(MainViewModel.ProcessingState.PROCESSING);
                        mediaProcessor.processMediaItems(items, new MediaProcessor.ProcessingCallback() {
                            /**
                             * Called periodically to update processing progress.
                             *
                             * @param current Number of items processed so far
                             * @param total Total number of items to process
                             * @param message Status message describing current operation
                             */
                            @Override
                            public void onProgress(int current, int total, String message) {
                                try {
                                    viewModel.updateProgress(current, total, message);
                                    FirebaseCrashlytics.getInstance().setCustomKey("processing_progress", (float) current / total);
                                } catch (Exception e) {
                                    FirebaseCrashlytics.getInstance().recordException(e);
                                }
                            }

                            /**
                             * Called when processing is completed.
                             *
                             * @param processedCount Number of successfully processed items
                             */
                            @Override
                            public void onComplete(int processedCount) {
                                try {
                                    FirebaseCrashlytics.getInstance().log("Processing completed");
                                    FirebaseCrashlytics.getInstance().setCustomKey("processed_count", processedCount);
                                    viewModel.setProcessedItemCount(processedCount);
                                    viewModel.setProcessingState(MainViewModel.ProcessingState.COMPLETED);
                                } catch (Exception e) {
                                    FirebaseCrashlytics.getInstance().recordException(e);
                                }
                            }
                        });
                    } else {
                        // Show status message if no items selected
                        FirebaseCrashlytics.getInstance().log("No items selected for processing");
                        uiStateManager.setFirstSelectMediaFilesStatus();
                    }
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            FirebaseCrashlytics.getInstance().log("Views setup complete");
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Configures status bar colors based on the current theme (light/dark mode).
     *
     * This method detects the current theme and sets appropriate status bar appearance
     * to ensure good contrast and visibility.
     */
    private void setupStatusBarColors() {
        try {
            // Detect if device is in dark mode
            int nightModeFlags = getResources().getConfiguration().uiMode &
                    Configuration.UI_MODE_NIGHT_MASK;

            // Get the window insets controller to adjust system bars appearance
            WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null) {
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    // Dark mode: use dark status bar with light icons
                    insetsController.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    FirebaseCrashlytics.getInstance().setCustomKey("theme_mode", "dark");
                } else {
                    // Light mode: use light status bar with dark icons
                    insetsController.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    FirebaseCrashlytics.getInstance().setCustomKey("theme_mode", "light");
                }
            } else {
                FirebaseCrashlytics.getInstance().log("Insets controller is null");
            }
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Initializes utility classes used by the activity.
     *
     * This method creates instances of UIStateManager, PermissionManager,
     * MediaSelector, and MediaProcessor with appropriate configurations.
     */
    private void initUtilityClasses() {
        try {
            // Initialize UI state manager to handle UI state transitions
            uiStateManager = new UIStateManager(
                    this,
                    statusText,
                    stripButton,
                    progressContainer,
                    progressBar,
                    progressText
            );

            // Initialize permission manager to handle runtime permissions
            permissionManager = new PermissionManager(
                    this,
                    findViewById(android.R.id.content),
                    settingsLauncher,
                    new PermissionManager.PermissionCallback() {
                        /**
                         * Called when all required permissions are granted.
                         */
                        @Override
                        public void onPermissionsGranted() {
                            try {
                                FirebaseCrashlytics.getInstance().log("Permissions granted");
                                FirebaseCrashlytics.getInstance().setCustomKey("permissions_granted", true);
                                uiStateManager.setReadyStatus();
                            } catch (Exception e) {
                                FirebaseCrashlytics.getInstance().recordException(e);
                            }
                        }

                        /**
                         * Called when permissions are denied by the user.
                         */
                        @Override
                        public void onPermissionsDenied() {
                            try {
                                FirebaseCrashlytics.getInstance().log("Permissions denied");
                                FirebaseCrashlytics.getInstance().setCustomKey("permissions_granted", false);
                                uiStateManager.setPermissionsRequiredStatus();
                            } catch (Exception e) {
                                FirebaseCrashlytics.getInstance().recordException(e);
                            }
                        }

                        /**
                         * Called when a permission request is initiated.
                         */
                        @Override
                        public void onPermissionsRequestStarted() {
                            try {
                                FirebaseCrashlytics.getInstance().log("Permission request started");
                                uiStateManager.setPermissionRequestingStatus();
                            } catch (Exception e) {
                                FirebaseCrashlytics.getInstance().recordException(e);
                            }
                        }
                    }
            );

            // Initialize media selector for selecting media from device storage
            mediaSelector = new MediaSelector(this, mediaPickerLauncher);

            // Initialize media processor for processing selected media files
            mediaProcessor = new MediaProcessor(this);

            FirebaseCrashlytics.getInstance().log("Utility classes initialized");
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Sets up LiveData observers to respond to changes in the ViewModel.
     *
     * This method observes changes to selected items, processing state,
     * progress percentage, and progress message to update the UI accordingly.
     */
    private void setupObservers() {
        try {
            // Observe changes to selected media items
            viewModel.getSelectedItems().observe(this, items -> {
                try {
                    // Update the grid view with selected items
                    mediaAdapter.updateItems(items);
                    // Enable/disable strip button based on selection
                    uiStateManager.enableStripButton(!items.isEmpty());
                    // Update status text to show selection count
                    uiStateManager.setSelectedItemsStatus(items.size());
                    FirebaseCrashlytics.getInstance().setCustomKey("selected_items_count", items.size());
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            // Observe changes to processing state
            viewModel.getProcessingState().observe(this, state -> {
                try {
                    FirebaseCrashlytics.getInstance().setCustomKey("processing_state", state.toString());
                    switch (state) {
                        case PROCESSING:
                            // Show progress UI when processing starts
                            FirebaseCrashlytics.getInstance().log("Processing state: PROCESSING");
                            uiStateManager.showProgress(true);
                            uiStateManager.setProcessingStatus();
                            break;

                        case COMPLETED:
                            // Hide progress UI and show completion status when finished
                            FirebaseCrashlytics.getInstance().log("Processing state: COMPLETED");
                            uiStateManager.showProgress(false);
                            Integer count = viewModel.getProcessedItemCount().getValue();
                            if (count != null) {
                                FirebaseCrashlytics.getInstance().setCustomKey("processed_items", count);
                                uiStateManager.setProcessedItemsStatus(count);
                            }
                            viewModel.setProcessingState(MainViewModel.ProcessingState.IDLE);
                            break;

                        case IDLE:
                        default:
                            // Hide progress UI when idle
                            FirebaseCrashlytics.getInstance().log("Processing state: IDLE");
                            uiStateManager.showProgress(false);
                            break;
                    }
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            // Observe changes to progress percentage
            viewModel.getProgressPercent().observe(this, percent -> {
                try {
                    progressBar.setProgress(percent);
                    FirebaseCrashlytics.getInstance().setCustomKey("progress_percent", percent);
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            // Observe changes to progress message
            viewModel.getProgressMessage().observe(this, message -> {
                try {
                    progressText.setText(message);
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            FirebaseCrashlytics.getInstance().log("Observers setup complete");
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Processes the result of permission requests.
     *
     * This method is called when the user responds to a permission request dialog,
     * and delegates handling to the PermissionManager.
     *
     * @param requestCode The request code passed to requestPermissions
     * @param permissions The requested permissions
     * @param grantResults The grant results for the corresponding permissions
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        try {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            FirebaseCrashlytics.getInstance().log("Permission result received");
            FirebaseCrashlytics.getInstance().setCustomKey("permission_request_code", requestCode);
            permissionManager.handlePermissionResult(requestCode, permissions, grantResults);
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Called when the activity becomes visible to the user.
     *
     * This method logs activity resumption for analytics and debugging purposes.
     */
    @Override
    protected void onResume() {
        try {
            super.onResume();
            FirebaseCrashlytics.getInstance().log("MainActivity resumed");
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    /**
     * Called when the activity is no longer visible to the user.
     *
     * This method logs activity pausing for analytics and debugging purposes.
     */
    @Override
    protected void onPause() {
        try {
            super.onPause();
            FirebaseCrashlytics.getInstance().log("MainActivity paused");
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }
}
