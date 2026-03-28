package com.doubleangels.redact;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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
import com.doubleangels.redact.media.MediaProcessor;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.ui.UIStateManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts the Clean (strip metadata) UI and logic; shown in {@link MainActivity}'s fragment container.
 */
public class CleanFragment extends Fragment {

    private MainViewModel viewModel;
    private PermissionManager permissionManager;
    private MediaSelector mediaSelector;
    private UIStateManager uiStateManager;
    private MediaProcessor mediaProcessor;

    private MaterialButton stripButton;
    private TextView statusText;
    private LinearLayout progressContainer;
    private TextView progressText;
    private LinearProgressIndicator progressBar;
    private MediaAdapter mediaAdapter;

    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<Intent> mediaPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    try {
                        FirebaseCrashlytics.getInstance().log("Returned from settings");
                        if (permissionManager != null) {
                            permissionManager.checkPermissions();
                        }
                    } catch (Exception e) {
                        FirebaseCrashlytics.getInstance().recordException(e);
                    }
                }
        );
        mediaPickerLauncher = registerForActivityResult(
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
            FirebaseCrashlytics.getInstance().log("CleanFragment view created");
            viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
            setupViews(view);
            initUtilityClasses();
            setupObservers();
            permissionManager.checkPermissions();
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    private void setupViews(View view) {
        try {
            RecyclerView selectedItemsGrid = view.findViewById(R.id.selectedItemsGrid);
            MaterialButton selectButton = view.findViewById(R.id.selectButton);
            stripButton = view.findViewById(R.id.stripButton);
            statusText = view.findViewById(R.id.statusText);
            progressContainer = view.findViewById(R.id.progressContainer);
            progressText = view.findViewById(R.id.progressText);
            progressBar = view.findViewById(R.id.progressBar);

            stripButton.setEnabled(false);

            mediaAdapter = new MediaAdapter(requireActivity(), new ArrayList<>());
            selectedItemsGrid.setAdapter(mediaAdapter);
            selectedItemsGrid.setLayoutManager(new GridLayoutManager(requireContext(), 3));

            selectButton.setOnClickListener(v -> {
                try {
                    FirebaseCrashlytics.getInstance().log("Select button clicked");
                    if (permissionManager.needsPermissions()) {
                        FirebaseCrashlytics.getInstance().log("Requesting permissions");
                        permissionManager.requestStoragePermission();
                    } else {
                        FirebaseCrashlytics.getInstance().log("Launching media selector");
                        mediaSelector.selectMedia();
                    }
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            stripButton.setOnClickListener(v -> {
                try {
                    FirebaseCrashlytics.getInstance().log("Strip button clicked");
                    List<MediaItem> items = viewModel.getSelectedItems().getValue();
                    if (items != null && !items.isEmpty()) {
                        FirebaseCrashlytics.getInstance().setCustomKey("processing_items_count", items.size());
                        viewModel.setProcessingState(MainViewModel.ProcessingState.PROCESSING);
                        mediaProcessor.processMediaItems(items, new MediaProcessor.ProcessingCallback() {
                            @Override
                            public void onProgress(int current, int total, String message) {
                                try {
                                    viewModel.updateProgress(current, total, message);
                                    if (total > 0) {
                                        FirebaseCrashlytics.getInstance().setCustomKey("processing_progress",
                                                (float) current / (float) total);
                                    }
                                } catch (Exception e) {
                                    FirebaseCrashlytics.getInstance().recordException(e);
                                }
                            }

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
                        FirebaseCrashlytics.getInstance().log("No items selected for processing");
                        uiStateManager.setFirstSelectMediaFilesStatus();
                    }
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
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
                    requireActivity().findViewById(android.R.id.content),
                    settingsLauncher,
                    new PermissionManager.PermissionCallback() {
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

            mediaSelector = new MediaSelector(requireActivity(), mediaPickerLauncher);
            mediaProcessor = new MediaProcessor(requireActivity());
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    private void setupObservers() {
        try {
            viewModel.getSelectedItems().observe(getViewLifecycleOwner(), items -> {
                try {
                    mediaAdapter.updateItems(items);
                    uiStateManager.enableStripButton(!items.isEmpty());
                    uiStateManager.setSelectedItemsStatus(items.size());
                    FirebaseCrashlytics.getInstance().setCustomKey("selected_items_count", items.size());
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            viewModel.getProcessingState().observe(getViewLifecycleOwner(), state -> {
                try {
                    FirebaseCrashlytics.getInstance().setCustomKey("processing_state", state.toString());
                    switch (state) {
                        case PROCESSING:
                            FirebaseCrashlytics.getInstance().log("Processing state: PROCESSING");
                            uiStateManager.showProgress(true);
                            uiStateManager.setProcessingStatus();
                            break;

                        case COMPLETED:
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
                            FirebaseCrashlytics.getInstance().log("Processing state: IDLE");
                            uiStateManager.showProgress(false);
                            break;
                    }
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            viewModel.getProgressPercent().observe(getViewLifecycleOwner(), percent -> {
                try {
                    progressBar.setProgress(percent);
                    FirebaseCrashlytics.getInstance().setCustomKey("progress_percent", percent);
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });

            viewModel.getProgressMessage().observe(getViewLifecycleOwner(), message -> {
                try {
                    progressText.setText(message);
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
            });
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    void handlePermissionResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        try {
            if (permissionManager != null) {
                permissionManager.handlePermissionResult(requestCode, permissions, grantResults);
            }
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }
}
