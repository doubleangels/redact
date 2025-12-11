package com.doubleangels.redact;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
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
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import io.sentry.Sentry;

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
                    Sentry.captureMessage("Returned from settings");
                    permissionManager.checkPermissions();
                } catch (Exception e) {
                    Sentry.captureException(e);
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
                        Sentry.captureMessage("Media selected successfully");
                        List<MediaItem> items = mediaSelector.processMediaResult(result.getData());
                        viewModel.setSelectedItems(items);
                    } else {
                        Sentry.captureMessage("Media selection canceled or failed");
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
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

            // Enable edge-to-edge display
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);
            
            // Handle window insets for edge-to-edge
            setupEdgeToEdgeInsets();

            Sentry.captureMessage("MainActivity created");

            // Initialize ViewModel to manage UI state
            viewModel = new ViewModelProvider(this).get(MainViewModel.class);

            // Set up UI components and event handlers
            setupViews();
            setupStatusBarColors();
            setupVersionNumber();

            // Initialize utility classes for permissions, media selection, etc.
            initUtilityClasses();

            // Set up LiveData observers
            setupObservers();

            // Check for required permissions
            permissionManager.checkPermissions();

        } catch (Exception e) {
            // Log any exceptions that might occur during initialization
            Sentry.captureException(e);
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

            // Set up bottom navigation
            BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
            bottomNavigationView.setSelectedItemId(R.id.navigation_clean);
            bottomNavigationView.setOnItemSelectedListener(item -> {
                try {
                    int itemId = item.getItemId();
                    Sentry.captureMessage("Bottom navigation item selected: " + itemId);

                    if (itemId == R.id.navigation_clean) {
                        // Already in MainActivity, do nothing
                        return true;
                    } else if (itemId == R.id.navigation_scan) {
                        // Open scan activity
                        Intent intent = new Intent(MainActivity.this, ScanActivity.class);
                        startActivity(intent);
                        return true;
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
                return false;
            });

            // Initially disable the strip button until media is selected
            stripButton.setEnabled(false);

            // Set up the RecyclerView with a grid layout for displaying media thumbnails
            mediaAdapter = new MediaAdapter(new ArrayList<>());
            selectedItemsGrid.setAdapter(mediaAdapter);
            selectedItemsGrid.setLayoutManager(new GridLayoutManager(this, 3));

            // Set up click listener for select button
            selectButton.setOnClickListener(v -> {
                try {
                    Sentry.captureMessage("Select button clicked");
                    if (permissionManager.needsPermissions()) {
                        // Request permissions if needed
                        Sentry.captureMessage("Requesting permissions");
                        permissionManager.requestStoragePermission();
                    } else {
                        // Launch media selector if permissions are granted
                        Sentry.captureMessage("Launching media selector");
                        mediaSelector.selectMedia();
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });

            // Set up click listener for strip button
            stripButton.setOnClickListener(v -> {
                try {
                    Sentry.captureMessage("Strip button clicked");
                    List<MediaItem> items = viewModel.getSelectedItems().getValue();
                    if (items != null && !items.isEmpty()) {
                        // Process selected media items
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
                                } catch (Exception e) {
                                    Sentry.captureException(e);
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
                                    Sentry.captureMessage("Processing completed");
                                    viewModel.setProcessedItemCount(processedCount);
                                    viewModel.setProcessingState(MainViewModel.ProcessingState.COMPLETED);
                                } catch (Exception e) {
                                    Sentry.captureException(e);
                                }
                            }
                        });
                    } else {
                        // Show status message if no items selected
                        Sentry.captureMessage("No items selected for processing");
                        uiStateManager.setFirstSelectMediaFilesStatus();
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });

            Sentry.captureMessage("Views setup complete");
        } catch (Exception e) {
            Sentry.captureException(e);
        }
    }


    /**
     * Sets up window insets handling for edge-to-edge display.
     * This ensures that content is properly padded to avoid system bars.
     */
    private void setupEdgeToEdgeInsets() {
        try {
            View rootView = findViewById(android.R.id.content);
            if (rootView != null) {
                ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                    androidx.core.graphics.Insets systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    androidx.core.graphics.Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
                    androidx.core.graphics.Insets navigationBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
                    
                    // Apply top/left/right padding to the root CoordinatorLayout for status bar
                    View coordinatorLayout = findViewById(R.id.root_layout);
                    if (coordinatorLayout != null) {
                        coordinatorLayout.setPadding(
                            systemBarInsets.left,
                            statusBarInsets.top,
                            systemBarInsets.right,
                            0  // Don't add bottom padding here
                        );
                    }
                    
                    // Apply bottom padding to the bottom navigation bar for navigation bar
                    com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = 
                        findViewById(R.id.bottomNavigation);
                    if (bottomNav != null) {
                        bottomNav.setPadding(
                            bottomNav.getPaddingLeft(),
                            bottomNav.getPaddingTop(),
                            bottomNav.getPaddingRight(),
                            navigationBarInsets.bottom
                        );
                    }
                    
                    return insets;
                });
            }
        } catch (Exception e) {
            Sentry.captureException(e);
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
                } else {
                    // Light mode: use light status bar with dark icons
                    insetsController.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            } else {
                Sentry.captureMessage("Insets controller is null");
            }
        } catch (Exception e) {
            Sentry.captureException(e);
        }
    }

    /**
     * Sets up the version number display in the UI.
     *
     * This method retrieves the app's version name from the package info
     * and displays it in the designated TextView. Any exceptions during
     * this process are logged to Sentry.
     */
    private void setupVersionNumber() {
        try {
            // Find the TextView that will display the version number
            TextView versionText = findViewById(R.id.versionText);

            // Get the app's package info to retrieve the version name
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);

            // Set the version name to the TextView
            versionText.setText(packageInfo.versionName);

            // Log the version name to Sentry for debugging purposes
            assert packageInfo.versionName != null;
        } catch (Exception e) {
            // Record any exceptions that occur during version setup
            Sentry.captureException(e);
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
                                Sentry.captureMessage("Permissions granted");
                                uiStateManager.setReadyStatus();
                            } catch (Exception e) {
                                Sentry.captureException(e);
                            }
                        }

                        /**
                         * Called when permissions are denied by the user.
                         */
                        @Override
                        public void onPermissionsDenied() {
                            try {
                                Sentry.captureMessage("Permissions denied");
                                uiStateManager.setPermissionsRequiredStatus();
                            } catch (Exception e) {
                                Sentry.captureException(e);
                            }
                        }

                        /**
                         * Called when a permission request is initiated.
                         */
                        @Override
                        public void onPermissionsRequestStarted() {
                            try {
                                Sentry.captureMessage("Permission request started");
                                uiStateManager.setPermissionRequestingStatus();
                            } catch (Exception e) {
                                Sentry.captureException(e);
                            }
                        }
                    }
            );

            // Initialize media selector for selecting media from device storage
            mediaSelector = new MediaSelector(this, mediaPickerLauncher);

            // Initialize media processor for processing selected media files
            mediaProcessor = new MediaProcessor(this);

            Sentry.captureMessage("Utility classes initialized");
        } catch (Exception e) {
            Sentry.captureException(e);
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
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });

            // Observe changes to processing state
            viewModel.getProcessingState().observe(this, state -> {
                try {
                    switch (state) {
                        case PROCESSING:
                            // Show progress UI when processing starts
                            Sentry.captureMessage("Processing state: PROCESSING");
                            uiStateManager.showProgress(true);
                            uiStateManager.setProcessingStatus();
                            break;

                        case COMPLETED:
                            // Hide progress UI and show completion status when finished
                            Sentry.captureMessage("Processing state: COMPLETED");
                            uiStateManager.showProgress(false);
                            Integer count = viewModel.getProcessedItemCount().getValue();
                            if (count != null) {
                                uiStateManager.setProcessedItemsStatus(count);
                            }
                            viewModel.setProcessingState(MainViewModel.ProcessingState.IDLE);
                            break;

                        case IDLE:
                        default:
                            // Hide progress UI when idle
                            Sentry.captureMessage("Processing state: IDLE");
                            uiStateManager.showProgress(false);
                            break;
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });

            // Observe changes to progress percentage
            viewModel.getProgressPercent().observe(this, percent -> {
                try {
                    progressBar.setProgress(percent);
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });

            // Observe changes to progress message
            viewModel.getProgressMessage().observe(this, message -> {
                try {
                    progressText.setText(message);
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });

            Sentry.captureMessage("Observers setup complete");
        } catch (Exception e) {
            Sentry.captureException(e);
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
            Sentry.captureMessage("Permission result received");
            permissionManager.handlePermissionResult(requestCode, permissions, grantResults);
        } catch (Exception e) {
            Sentry.captureException(e);
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
            Sentry.captureMessage("MainActivity resumed");
        } catch (Exception e) {
            Sentry.captureException(e);
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
            Sentry.captureMessage("MainActivity paused");
        } catch (Exception e) {
            Sentry.captureException(e);
        }
    }
}
