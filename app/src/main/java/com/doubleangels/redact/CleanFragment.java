package com.doubleangels.redact;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.media.MediaAdapter;
import com.doubleangels.redact.media.MediaItem;
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

    private ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> mediaPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        if (getActivity() != null) {
            mediaSelector = new MediaSelector(requireActivity());
        }
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_ITEMS),
                uris -> {
                    try {
                        if (uris != null && !uris.isEmpty()) {
                            SentryManager.log("Media selected successfully");
                            List<MediaItem> items = new ArrayList<>();
                            for (android.net.Uri uri : uris) {
                                if (mediaSelector != null) {
                                    items.add(mediaSelector.processMediaUri(uri));
                                }
                            }
                            SentryManager.setCustomKey("selected_media_count", items.size());
                            viewModel.setSelectedItems(items);
                        } else {
                            SentryManager.log("Media selection canceled or failed");
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
            SentryManager.log("CleanFragment view created");
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

            stripButton.setEnabled(false);

            mediaAdapter = new MediaAdapter(new ArrayList<>());
            selectedItemsGrid.setAdapter(mediaAdapter);
            selectedItemsGrid.setLayoutManager(new GridLayoutManager(requireContext(), 3));

            selectButton.setOnClickListener(v -> {
                try {
                    SentryManager.log("Select button clicked");
                    if (viewModel.getCleanProcessingState().getValue()
                            == MainViewModel.ProcessingState.PROCESSING) {
                        return;
                    }
                    if (viewModel.getCleanProcessingState().getValue()
                            == MainViewModel.ProcessingState.COMPLETED) {
                        viewModel.setCleanProcessingState(MainViewModel.ProcessingState.IDLE);
                    }
                    if (permissionManager.shouldRequestStorageBeforePicker()) {
                        SentryManager.log("Requesting permissions");
                        permissionManager.requestStoragePermission();
                    } else {
                        SentryManager.log("Launching media selector");
                        mediaPickerLauncher.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                                .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                                .build());
                    }
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
            });

            stripButton.setOnClickListener(v -> {
                try {
                    if (viewModel.getCleanProcessingState().getValue()
                            == MainViewModel.ProcessingState.PROCESSING) {
                        SentryManager.log("Cancel clean requested");
                        viewModel.cancelCleaning();
                        return;
                    }
                    SentryManager.log("Strip button clicked");
                    List<MediaItem> items = viewModel.getSelectedItems().getValue();
                    if (items != null && !items.isEmpty()) {
                        if (containsAnimatedImage(items)) {
                            Toast.makeText(
                                            requireContext(),
                                            R.string.animated_image_warning,
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                        SentryManager.setCustomKey("processing_items_count", items.size());
                        viewModel.startCleaning(items);
                    } else {
                        SentryManager.log("No items selected for processing");
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
                                SentryManager.log("Permissions granted");
                                SentryManager.setCustomKey("permissions_granted", true);
                                uiStateManager.setReadyStatus();
                            } catch (Exception e) {
                                SentryManager.recordException(e);
                            }
                        }

                        @Override
                        public void onPermissionsDenied() {
                            try {
                                SentryManager.log("Permissions denied");
                                SentryManager.setCustomKey("permissions_granted", false);
                                uiStateManager.setPermissionsRequiredStatus();
                            } catch (Exception e) {
                                SentryManager.recordException(e);
                            }
                        }

                        @Override
                        public void onPermissionsRequestStarted() {
                            try {
                                SentryManager.log("Permission request started");
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

    private void syncPermissionUi() {
        if (permissionManager == null || uiStateManager == null) {
            return;
        }
        if (permissionManager.needsPermissions()) {
            uiStateManager.setPermissionsRequiredStatus();
            if (selectButton != null) {
                selectButton.setEnabled(false);
            }
        } else {
            uiStateManager.setReadyStatus();
            if (selectButton != null) {
                selectButton.setEnabled(true);
            }
        }
    }

    private void setupObservers() {
        try {
            viewModel.getSelectedItems().observe(getViewLifecycleOwner(), items -> {
                try {
                    mediaAdapter.updateItems(items);
                    uiStateManager.enableStripButton(!items.isEmpty());
                    if (viewModel.getCleanProcessingState().getValue()
                            == MainViewModel.ProcessingState.COMPLETED) {
                        viewModel.setCleanProcessingState(MainViewModel.ProcessingState.IDLE);
                    }
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
                            SentryManager.log("Processing state: PROCESSING");
                            uiStateManager.showProgress(true);
                            uiStateManager.setProcessingStatus();
                            if (selectButton != null) {
                                selectButton.setEnabled(false);
                            }
                            stripButton.setText(R.string.button_cancel);
                            stripButton.setEnabled(true);
                            break;

                        case CANCELLED:
                            SentryManager.log("Processing state: CANCELLED");
                            uiStateManager.showProgress(false);
                            uiStateManager.setStatus(getString(R.string.status_processing_cancelled));
                            stripButton.setText(R.string.button_strip_exif_data);
                            if (selectButton != null) {
                                selectButton.setEnabled(true);
                            }
                            List<MediaItem> cancelledItems = viewModel.getSelectedItems().getValue();
                            uiStateManager.enableStripButton(cancelledItems != null && !cancelledItems.isEmpty());
                            viewModel.setCleanProcessingState(MainViewModel.ProcessingState.IDLE);
                            break;

                        case COMPLETED:
                            SentryManager.log("Processing state: COMPLETED");
                            uiStateManager.showProgress(false);
                            Integer count = viewModel.getCleanProcessedItemCount().getValue();
                            Integer total = viewModel.getCleanBatchTotalCount().getValue();
                            if (count != null) {
                                SentryManager.setCustomKey("processed_items", count);
                                int batchTotal = total != null ? total : count;
                                uiStateManager.setProcessedItemsStatus(count, batchTotal);
                            }
                            stripButton.setText(R.string.button_strip_exif_data);
                            if (selectButton != null) {
                                selectButton.setEnabled(true);
                            }
                            List<MediaItem> items = viewModel.getSelectedItems().getValue();
                            uiStateManager.enableStripButton(items != null && !items.isEmpty());
                            break;

                        case IDLE:
                        default:
                            SentryManager.log("Processing state: IDLE");
                            uiStateManager.showProgress(false);
                            stripButton.setText(R.string.button_strip_exif_data);
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
        if (!hidden) {
            if (viewModel != null
                    && viewModel.getCleanProcessingState().getValue()
                            == MainViewModel.ProcessingState.PROCESSING) {
                return;
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
}
