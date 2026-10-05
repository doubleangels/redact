package com.doubleangels.redact;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Bundle;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.media.MediaAdapter;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaPickerContracts;
import com.doubleangels.redact.media.ProcessingResourceWarnings;
import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.ui.UIStateManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts the Clean (strip metadata) UI and logic; shown in {@link MainActivity}'s fragment container.
 */
public class CleanFragment extends Fragment {

    private static final int MAX_PICK_ITEMS = 20;

    @Nullable
    private List<MediaItem> lastObservedSelectedItems;

    private MainViewModel viewModel;
    private PermissionManager permissionManager;
    private MediaSelector mediaSelector;
    private UIStateManager uiStateManager;

    private MaterialButton stripButton;
    private MaterialButton selectButton;
    private TextView statusText;
    private LinearLayout progressContainer;
    private TextView progressText;
    private LinearProgressIndicator progressBar;
    private MediaAdapter mediaAdapter;

    private View emptyStateContainer;
    private MaterialButton emptyStateSelectButton;
    private View mediaContentContainer;
    private TextView cleanSelectedCountText;
    private MaterialButton cleanClearButton;

    private ActivityResultLauncher<String[]> mediaPickerLauncher;
    private ActivityResultLauncher<IntentSenderRequest> trashRequestLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        if (getActivity() != null) {
            mediaSelector = new MediaSelector(requireActivity());
        }
        trashRequestLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> { });
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    try {
                        if (uris != null && !uris.isEmpty()) {
                            if (uris.size() > MAX_PICK_ITEMS) {
                                Toast.makeText(
                                                requireContext(),
                                                getString(R.string.media_picker_selection_capped, MAX_PICK_ITEMS),
                                                Toast.LENGTH_LONG)
                                        .show();
                                uris = MediaPickerContracts.trimToMax(uris, MAX_PICK_ITEMS);
                            }
                            SentryManager.log("Media was selected successfully.");
                            List<MediaItem> items = new ArrayList<>();
                            for (android.net.Uri uri : uris) {
                                if (mediaSelector != null) {
                                    items.add(mediaSelector.processMediaUri(uri));
                                }
                            }
                            SentryManager.setCustomKey("selected_media_count", items.size());
                            viewModel.setSelectedItems(items);
                        } else {
                            SentryManager.log("Media selection was canceled or failed.");
                        }
                    } catch (Exception e) {
                        SentryManager.recordException(e);
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_clean, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            SentryManager.log("The CleanFragment view was created.");
            viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
            setupViews(view);
            initUtilityClasses();
            setupObservers();
            if (!isHidden()) {
                syncPermissionUi();
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private void setupViews(View view) {
        try {
            RecyclerView selectedItemsGrid = view.findViewById(R.id.selectedItemsGrid);
            MaterialButton selectButton = view.findViewById(R.id.selectButton);
            this.selectButton = selectButton;
            stripButton = view.findViewById(R.id.stripButton);
            statusText = view.findViewById(R.id.statusText);
            progressContainer = view.findViewById(R.id.progressContainer);
            progressText = view.findViewById(R.id.progressText);
            progressBar = view.findViewById(R.id.progressBar);

            emptyStateContainer = view.findViewById(R.id.emptyStateContainer);
            emptyStateSelectButton = view.findViewById(R.id.emptyStateSelectButton);
            mediaContentContainer = view.findViewById(R.id.mediaContentContainer);
            cleanSelectedCountText = view.findViewById(R.id.cleanSelectedCountText);
            cleanClearButton = view.findViewById(R.id.cleanClearButton);

            if (emptyStateSelectButton != null) {
                emptyStateSelectButton.setOnClickListener(v -> onSelectMediaClicked());
            }
            if (cleanClearButton != null) {
                cleanClearButton.setOnClickListener(v -> {
                    if (viewModel.getCleanProcessingState().getValue()
                            != MainViewModel.ProcessingState.PROCESSING) {
                        viewModel.setSelectedItems(List.of());
                    }
                });
            }

            stripButton.setEnabled(false);

            mediaAdapter = new MediaAdapter(new ArrayList<>());
            selectedItemsGrid.setAdapter(mediaAdapter);
            selectedItemsGrid.setLayoutManager(new GridLayoutManager(requireContext(), 3));

            selectButton.setOnClickListener(v -> onSelectMediaClicked());

            stripButton.setOnClickListener(v -> {
                try {
                    if (viewModel.getCleanProcessingState().getValue()
                            == MainViewModel.ProcessingState.PROCESSING) {
                        SentryManager.log("The user requested that the clean be cancelled.");
                        viewModel.cancelCleaning();
                        return;
                    }
                    SentryManager.log("The user clicked the Strip button.");
                    List<MediaItem> items = viewModel.getSelectedItems().getValue();
                    if (items != null && !items.isEmpty()) {
                        SentryManager.setCustomKey("processing_items_count", items.size());
                        // Animated-image detection and resource assessment both do
                        // content-resolver I/O (up to 64KB reads and a cache-directory walk
                        // per selected item), which can be slow for cloud-backed URIs; run
                        // them off the main thread to avoid jank/ANRs.
                        Context appContext = requireContext().getApplicationContext();
                        new Thread(() -> {
                            try {
                                boolean hasAnimatedImage = containsAnimatedImage(items);
                                List<ProcessingResourceWarnings.Warning> warnings =
                                        ProcessingResourceWarnings.assess(appContext, items);
                                runOnUiThreadIfAdded(() -> {
                                    if (hasAnimatedImage) {
                                        Toast.makeText(
                                                        requireContext(),
                                                        R.string.animated_image_warning,
                                                        Toast.LENGTH_LONG)
                                                .show();
                                    }
                                    ProcessingResourceWarnings.runWithWarnings(
                                            requireActivity(),
                                            warnings,
                                            () -> viewModel.startCleaning(items));
                                });
                            } catch (Exception e) {
                                SentryManager.recordException(e);
                            }
                        }).start();
                    } else {
                        SentryManager.log("No items were selected for processing.");
                        uiStateManager.setFirstSelectMediaFilesStatus();
                    }
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private void initUtilityClasses() {
        try {
            uiStateManager = new UIStateManager(
                    requireActivity(),
                    statusText,
                    stripButton,
                    progressContainer,
                    progressBar,
                    progressText
            );

            permissionManager = new PermissionManager(
                    requireActivity(),
                    new PermissionManager.PermissionCallback() {
                        @Override
                        public void onPermissionsGranted() {
                            try {
                                SentryManager.log("Permissions were granted.");
                                SentryManager.setCustomKey("permissions_granted", true);
                                uiStateManager.setReadyStatus();
                            } catch (Exception e) {
                                SentryManager.recordException(e);
                            }
                        }

                        @Override
                        public void onPermissionsDenied() {
                            try {
                                SentryManager.log("Permissions were denied.");
                                SentryManager.setCustomKey("permissions_granted", false);
                                if (permissionManager.isMediaPickerAvailable()) {
                                    uiStateManager.setReadyStatus();
                                    if (selectButton != null) {
                                        selectButton.setEnabled(true);
                                    }
                                } else {
                                    uiStateManager.setPermissionsRequiredStatus();
                                    if (selectButton != null) {
                                        selectButton.setEnabled(false);
                                    }
                                }
                            } catch (Exception e) {
                                SentryManager.recordException(e);
                            }
                        }

                        @Override
                        public void onPermissionsRequestStarted() {
                            try {
                                SentryManager.log("The permission request started.");
                                uiStateManager.setPermissionRequestingStatus();
                            } catch (Exception e) {
                                SentryManager.recordException(e);
                            }
                        }
                    }
            );

            if (mediaSelector == null) {
                mediaSelector = new MediaSelector(requireActivity());
            }
            permissionManager.applyPendingPermissionResultIfAny(
                    com.doubleangels.redact.permission.PermissionManager.STORAGE_PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    /** Asks the system (with its own confirmation dialog) to trash the originals of cleaned items. */
    private void requestTrashOriginals(List<Uri> sources) {
        try {
            List<Uri> mediaUris = new ArrayList<>();
            for (Uri u : sources) {
                try {
                    Uri m = MediaStore.getMediaUri(requireContext(), u);
                    if (m != null) mediaUris.add(m);
                } catch (Exception ignored) {
                    // Non-media provider: leave the original alone.
                }
            }
            if (mediaUris.isEmpty()) return;
            trashRequestLauncher.launch(new IntentSenderRequest.Builder(
                    MediaStore.createTrashRequest(requireContext().getContentResolver(), mediaUris, true)
                            .getIntentSender()).build());
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    void onHostPermissionFlowCompleted() {
        syncPermissionUi();
    }

    private void syncPermissionUi() {
        if (permissionManager == null || uiStateManager == null) {
            return;
        }
        if (permissionManager.isMediaPickerAvailable()) {
            uiStateManager.setReadyStatus();
            if (selectButton != null) {
                selectButton.setEnabled(true);
            }
        } else {
            uiStateManager.setPermissionsRequiredStatus();
            if (selectButton != null) {
                selectButton.setEnabled(false);
            }
        }
    }

    private void setupObservers() {
        try {
            viewModel.getSelectedItems().observe(getViewLifecycleOwner(), items -> {
                try {
                    boolean hasItems = items != null && !items.isEmpty();
                    if (emptyStateContainer != null) {
                        emptyStateContainer.setVisibility(hasItems ? View.GONE : View.VISIBLE);
                    }
                    if (mediaContentContainer != null) {
                        mediaContentContainer.setVisibility(hasItems ? View.VISIBLE : View.GONE);
                    }
                    if (stripButton != null) {
                        stripButton.setVisibility(hasItems ? View.VISIBLE : View.GONE);
                    }
                    if (cleanSelectedCountText != null && hasItems) {
                        cleanSelectedCountText.setText(getString(R.string.clean_selected_count, items.size()));
                    }

                    mediaAdapter.updateItems(items);
                    if (viewModel.getCleanProcessingState().getValue()
                            != MainViewModel.ProcessingState.PROCESSING) {
                        uiStateManager.enableStripButton(hasItems);
                    }
                    List<MediaItem> previous = lastObservedSelectedItems;
                    boolean selectionChanged = previous != null
                            && !java.util.Objects.equals(previous, items);
                    if (selectionChanged
                            && viewModel.getCleanProcessingState().getValue()
                                    == MainViewModel.ProcessingState.COMPLETED) {
                        viewModel.setCleanProcessingState(MainViewModel.ProcessingState.IDLE);
                    }
                    lastObservedSelectedItems = items != null
                            ? new ArrayList<>(items) : new ArrayList<>();
                    uiStateManager.setSelectedItemsStatus(items != null ? items.size() : 0);
                    SentryManager.setCustomKey("selected_items_count", items != null ? items.size() : 0);
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });

            viewModel.getCleanProcessingState().observe(getViewLifecycleOwner(), state -> {
                try {
                    SentryManager.setCustomKey("processing_state", state.toString());
                    switch (state) {
                        case PROCESSING:
                            SentryManager.log("The processing state changed to PROCESSING.");
                            uiStateManager.showProgress(true);
                            uiStateManager.setProcessingStatus();
                            if (selectButton != null) {
                                selectButton.setEnabled(false);
                            }
                            stripButton.setText(R.string.button_cancel);
                            stripButton.setIconResource(R.drawable.ic_close);
                            stripButton.setEnabled(true);
                            break;

                        case CANCELLED:
                            SentryManager.log("The processing state changed to CANCELLED.");
                            uiStateManager.showProgress(false);
                            uiStateManager.setStatus(getString(R.string.status_processing_cancelled));
                            stripButton.setText(R.string.button_strip_exif_data);
                            stripButton.setIconResource(R.drawable.ic_clean);
                            if (selectButton != null) {
                                selectButton.setEnabled(true);
                            }
                            List<MediaItem> cancelledItems = viewModel.getSelectedItems().getValue();
                            uiStateManager.enableStripButton(cancelledItems != null && !cancelledItems.isEmpty());
                            viewModel.setCleanProcessingState(MainViewModel.ProcessingState.IDLE);
                            break;

                        case COMPLETED:
                            SentryManager.log("The processing state changed to COMPLETED.");
                            uiStateManager.showProgress(false);
                            Integer count = viewModel.getCleanProcessedItemCount().getValue();
                            Integer total = viewModel.getCleanBatchTotalCount().getValue();
                            if (count != null) {
                                SentryManager.setCustomKey("processed_items", count);
                                int batchTotal = total != null ? total : count;
                                uiStateManager.setProcessedItemsStatus(count, batchTotal);
                            }
                            stripButton.setText(R.string.button_strip_exif_data);
                            stripButton.setIconResource(R.drawable.ic_clean);
                            if (selectButton != null) {
                                selectButton.setEnabled(true);
                            }
                            List<MediaItem> items = viewModel.getSelectedItems().getValue();
                            uiStateManager.enableStripButton(items != null && !items.isEmpty());
                            if (AppPreferences.isDeleteOriginalsAfterClean(requireContext())) {
                                requestTrashOriginals(viewModel.takeCleanSucceededSources());
                            }
                            break;

                        case IDLE:
                        default:
                            SentryManager.log("The processing state changed to IDLE.");
                            uiStateManager.showProgress(false);
                            stripButton.setText(R.string.button_strip_exif_data);
                            stripButton.setIconResource(R.drawable.ic_clean);
                            if (selectButton != null) {
                                selectButton.setEnabled(true);
                            }
                            break;
                    }
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });

            viewModel.getCleanProgressPercent().observe(getViewLifecycleOwner(), percent -> {
                try {
                    progressBar.setProgress(percent);
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });

            viewModel.getCleanProgressMessage().observe(getViewLifecycleOwner(), message -> {
                try {
                    if (message != null && !message.isEmpty()) {
                        progressText.setText(message);
                    }
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && viewModel != null) {
            MainViewModel.ProcessingState state = viewModel.getCleanProcessingState().getValue();
            if (state == MainViewModel.ProcessingState.PROCESSING) {
                return;
            }
            if (state == MainViewModel.ProcessingState.COMPLETED && uiStateManager != null) {
                uiStateManager.showProgress(false);
                Integer count = viewModel.getCleanProcessedItemCount().getValue();
                Integer total = viewModel.getCleanBatchTotalCount().getValue();
                if (count != null) {
                    int batchTotal = total != null ? total : count;
                    uiStateManager.setProcessedItemsStatus(count, batchTotal);
                }
                stripButton.setText(R.string.button_strip_exif_data);
                if (selectButton != null) {
                    selectButton.setEnabled(true);
                }
                List<MediaItem> items = viewModel.getSelectedItems().getValue();
                uiStateManager.enableStripButton(items != null && !items.isEmpty());
            }
            syncPermissionUi();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && permissionManager != null) {
            permissionManager.checkPermissions();
        }
    }

    void handlePermissionResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        try {
            if (permissionManager != null) {
                permissionManager.handlePermissionResult(requestCode, permissions, grantResults);
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private boolean containsAnimatedImage(@NonNull List<MediaItem> items) {
        if (mediaSelector == null) {
            return false;
        }
        for (MediaItem item : items) {
            if (mediaSelector.isAnimatedImage(item)) {
                return true;
            }
        }
        return false;
    }

    private void onSelectMediaClicked() {
        try {
            SentryManager.log("The user clicked Select media.");
            if (viewModel.getCleanProcessingState().getValue()
                    == MainViewModel.ProcessingState.PROCESSING) {
                return;
            }
            if (viewModel.getCleanProcessingState().getValue()
                    == MainViewModel.ProcessingState.COMPLETED) {
                viewModel.setCleanProcessingState(MainViewModel.ProcessingState.IDLE);
            }
            if (permissionManager.shouldRequestStorageBeforePicker()) {
                SentryManager.log("The app is requesting permissions.");
                permissionManager.requestStoragePermission();
            } else {
                SentryManager.log("The app is launching the media selector.");
                mediaPickerLauncher.launch(MediaPickerContracts.IMAGE_AND_VIDEO_MIME_TYPES);
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private void runOnUiThreadIfAdded(Runnable action) {
        Activity activity = getActivity();
        if (activity == null || !isAdded()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (isAdded()) {
                action.run();
            }
        });
    }
}
