package com.doubleangels.redact;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.media.ConvertFileAdapter;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaPickerContracts;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.permission.PermissionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts images to JPEG, PNG, WebP, or HEIC and transcodes videos to MP4; saves under
 * Pictures/Redact or Movies/Redact respectively.
 */
public class ConvertFragment extends Fragment {

    private static final int MAX_PICK_ITEMS = 20;

    private PermissionManager permissionManager;
    private MediaSelector mediaSelector;
    private MainViewModel viewModel;

    private MaterialButton selectButton;
    private MaterialButton convertButton;
    private TextView statusText;
    private LinearLayout progressContainer;
    private TextView progressText;
    private LinearProgressIndicator progressBar;
    private ConvertFileAdapter convertFileAdapter;
    private View formatSection;
    private TextView formatLabel;
    private ChipGroup formatChipGroup;
    private Chip chipFormatJpeg;
    private Chip chipFormatPng;
    private Chip chipFormatWebp;
    private Chip chipFormatHeif;
    private int lastFormatNumImages = -1;
    private int lastFormatNumVideos = -1;

    private ActivityResultLauncher<String[]> mediaPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        if (getActivity() != null) {
            mediaSelector = new MediaSelector(requireActivity());
        }
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris == null || uris.isEmpty()) {
                        SentryManager.log("Media selection cancelled or failed in ConvertFragment");
                        return;
                    }
                    if (uris.size() > MAX_PICK_ITEMS) {
                        Toast.makeText(
                                        requireContext(),
                                        getString(R.string.media_picker_selection_capped, MAX_PICK_ITEMS),
                                        Toast.LENGTH_LONG)
                                .show();
                        uris = MediaPickerContracts.trimToMax(uris, MAX_PICK_ITEMS);
                    }
                    SentryManager.log("Media selected successfully in ConvertFragment");
                    List<MediaItem> items = new ArrayList<>();
                    for (android.net.Uri uri : uris) {
                        if (mediaSelector != null) {
                            items.add(mediaSelector.processMediaUri(uri));
                        }
                    }
                    viewModel.setConvertSelectedItems(items);
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_convert, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SentryManager.log("ConvertFragment view created");

        statusText = view.findViewById(R.id.statusText);
        progressContainer = view.findViewById(R.id.progressContainer);
        progressText = view.findViewById(R.id.progressText);
        progressBar = view.findViewById(R.id.progressBar);
        convertButton = view.findViewById(R.id.convertButton);
        selectButton = view.findViewById(R.id.selectButton);
        formatSection = view.findViewById(R.id.formatSection);
        formatLabel = view.findViewById(R.id.formatLabel);
        formatChipGroup = view.findViewById(R.id.formatChipGroup);
        chipFormatJpeg = view.findViewById(R.id.chipFormatJpeg);
        chipFormatPng = view.findViewById(R.id.chipFormatPng);
        chipFormatWebp = view.findViewById(R.id.chipFormatWebp);
        chipFormatHeif = view.findViewById(R.id.chipFormatHeif);

        RecyclerView recyclerView = view.findViewById(R.id.convertFileList);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        convertFileAdapter = new ConvertFileAdapter();
        recyclerView.setAdapter(convertFileAdapter);

        permissionManager = new PermissionManager(
                requireActivity(),
                new PermissionManager.PermissionCallback() {
                    @Override
                    public void onPermissionsGranted() {
                        statusText.setText(R.string.convert_status_ready);
                        selectButton.setEnabled(true);
                    }

                    @Override
                    public void onPermissionsDenied() {
                        if (permissionManager.isMediaPickerAvailable()) {
                            statusText.setText(R.string.convert_status_ready);
                            selectButton.setEnabled(true);
                        } else {
                            statusText.setText(R.string.status_storage_permissions_required);
                            selectButton.setEnabled(false);
                        }
                    }

                    @Override
                    public void onPermissionsRequestStarted() {
                        statusText.setText(R.string.status_requesting_permissions);
                    }
                });
        permissionManager.applyPendingPermissionResultIfAny(
                com.doubleangels.redact.permission.PermissionManager.STORAGE_PERMISSION_REQUEST_CODE);

        if (mediaSelector == null) {
            mediaSelector = new MediaSelector(requireActivity());
        }

        selectButton.setOnClickListener(v -> {
            SentryManager.log("Select button clicked in ConvertFragment");
            if (permissionManager.shouldRequestStorageBeforePicker()) {
                SentryManager.log("Requesting storage permissions in ConvertFragment");
                permissionManager.requestStoragePermission();
            } else {
                SentryManager.log("Launching media picker in ConvertFragment");
                openMediaPicker();
            }
        });

        convertButton.setOnClickListener(v -> {
            if (viewModel.getConvertProcessingState().getValue()
                    == MainViewModel.ProcessingState.PROCESSING) {
                SentryManager.log("Cancel convert requested");
                viewModel.cancelConversion();
                return;
            }
            SentryManager.log("Convert button clicked in ConvertFragment");
            runConversion();
        });

        setupObservers();
        List<MediaItem> restored = viewModel.getConvertSelectedItems().getValue();
        if (restored != null && !restored.isEmpty()) {
            convertFileAdapter.setItems(restored);
            convertButton.setEnabled(true);
            statusText.setText(getString(R.string.convert_selected_count, restored.size()));
            refreshFormatSectionForSelection(restored);
        } else {
            refreshFormatSectionForSelection(List.of());
        }
        if (!isHidden()) {
            syncSelectButtonForPickerAccess();
        }
    }

    private void syncSelectButtonForPickerAccess() {
        if (permissionManager == null || selectButton == null || statusText == null) {
            return;
        }
        if (permissionManager.isMediaPickerAvailable()) {
            statusText.setText(R.string.convert_status_ready);
            selectButton.setEnabled(true);
        } else {
            statusText.setText(R.string.status_storage_permissions_required);
            selectButton.setEnabled(false);
        }
    }

    private List<MediaItem> currentSelectedItems() {
        List<MediaItem> items = viewModel.getConvertSelectedItems().getValue();
        return items != null ? items : List.of();
    }

    /**
     * Shows format chips only after media is selected; labels match image-only, video-only, or
     * mixed selection.
     */
    private void refreshFormatSectionForSelection(List<MediaItem> selectedItems) {
        if (formatSection == null) {
            return;
        }
        int n = selectedItems.size();
        if (n == 0) {
            formatSection.setVisibility(View.GONE);
            return;
        }
        int numImages = 0;
        int numVideos = 0;
        for (MediaItem item : selectedItems) {
            if (item.isVideo()) {
                numVideos++;
            } else {
                numImages++;
            }
        }
        formatSection.setVisibility(View.VISIBLE);

        boolean showFourthChip;
        if (numVideos == 0) {
            showFourthChip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        } else if (numImages == 0) {
            showFourthChip = true;
        } else {
            showFourthChip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        }
        if (chipFormatHeif != null) {
            chipFormatHeif.setVisibility(showFourthChip ? View.VISIBLE : View.GONE);
            if (!showFourthChip && formatChipGroup.getCheckedChipId() == R.id.chipFormatHeif) {
                chipFormatJpeg.setChecked(true);
            }
        }

        if (numVideos == 0) {
            formatLabel.setText(R.string.convert_output_format_images);
            chipFormatJpeg.setText(R.string.convert_format_jpeg);
            chipFormatPng.setText(R.string.convert_format_png);
            chipFormatWebp.setText(R.string.convert_format_webp);
            if (chipFormatHeif != null) {
                chipFormatHeif.setText(R.string.convert_format_heif);
            }
        } else if (numImages == 0) {
            formatLabel.setText(R.string.convert_output_format_videos);
            chipFormatJpeg.setText(R.string.convert_format_h264);
            chipFormatPng.setText(R.string.convert_format_h265);
            chipFormatWebp.setText(R.string.convert_format_vp9);
            if (chipFormatHeif != null) {
                chipFormatHeif.setText(R.string.convert_format_av1);
            }
        } else {
            formatLabel.setText(R.string.convert_output_format_mixed);
            chipFormatJpeg.setText(getString(R.string.convert_format_mixed_pair,
                    getString(R.string.convert_format_jpeg), getString(R.string.convert_format_h264)));
            chipFormatPng.setText(getString(R.string.convert_format_mixed_pair,
                    getString(R.string.convert_format_png), getString(R.string.convert_format_h265)));
            chipFormatWebp.setText(getString(R.string.convert_format_mixed_pair,
                    getString(R.string.convert_format_webp), getString(R.string.convert_format_vp9)));
            if (chipFormatHeif != null) {
                chipFormatHeif.setText(getString(R.string.convert_format_mixed_pair,
                        getString(R.string.convert_format_heif), getString(R.string.convert_format_av1)));
            }
        }

        boolean compositionChanged = numImages != lastFormatNumImages || numVideos != lastFormatNumVideos;
        int checkedId = formatChipGroup.getCheckedChipId();
        boolean noChipChecked = checkedId == View.NO_ID;
        if (compositionChanged || noChipChecked) {
            int defaultIndex = SettingsFragment.defaultFormatIndexForSelection(
                    requireContext(), numImages, numVideos);
            if (chipFormatHeif == null || chipFormatHeif.getVisibility() != View.VISIBLE) {
                if (defaultIndex == 3) {
                    defaultIndex = 0;
                }
            }
            formatChipGroup.check(SettingsFragment.chipIdForFormatIndex(defaultIndex));
        }
        lastFormatNumImages = numImages;
        lastFormatNumVideos = numVideos;
    }

    private void openMediaPicker() {
        mediaPickerLauncher.launch(MediaPickerContracts.IMAGE_AND_VIDEO_MIME_TYPES);
    }

    private int getSelectedFormatIndex() {
        int checkedId = formatChipGroup.getCheckedChipId();
        if (checkedId == R.id.chipFormatPng) {
            return 1;
        }
        if (checkedId == R.id.chipFormatWebp) {
            return 2;
        }
        if (checkedId == R.id.chipFormatHeif) {
            return 3;
        }
        return 0;
    }

    private void setupObservers() {
        viewModel.getConvertSelectedItems().observe(getViewLifecycleOwner(), items -> {
            List<MediaItem> list = items != null ? items : List.of();
            convertFileAdapter.setItems(list);
            if (viewModel.getConvertProcessingState().getValue()
                    != MainViewModel.ProcessingState.PROCESSING) {
                convertButton.setEnabled(!list.isEmpty());
            }
            if (list.isEmpty()) {
                statusText.setText(R.string.convert_status_ready);
            } else if (viewModel.getConvertProcessingState().getValue()
                    != MainViewModel.ProcessingState.COMPLETED) {
                statusText.setText(getString(R.string.convert_selected_count, list.size()));
            }
            refreshFormatSectionForSelection(list);
        });

        viewModel.getConvertProcessingState().observe(getViewLifecycleOwner(), state -> {
            if (state == MainViewModel.ProcessingState.PROCESSING) {
                convertButton.setText(R.string.button_cancel);
                convertButton.setEnabled(true);
                selectButton.setEnabled(false);
                showProgress(true);
            } else if (state == MainViewModel.ProcessingState.CANCELLED) {
                convertButton.setText(R.string.convert_run);
                List<MediaItem> selected = currentSelectedItems();
                convertButton.setEnabled(!selected.isEmpty());
                selectButton.setEnabled(true);
                showProgress(false);
                statusText.setText(R.string.status_processing_cancelled);
                viewModel.setConvertProcessingState(MainViewModel.ProcessingState.IDLE);
            } else if (state == MainViewModel.ProcessingState.COMPLETED) {
                convertButton.setText(R.string.convert_run);
                List<MediaItem> selected = currentSelectedItems();
                convertButton.setEnabled(!selected.isEmpty());
                selectButton.setEnabled(true);
                showProgress(false);
                Integer okCount = viewModel.getConvertProcessedItemCount().getValue();
                Integer batchTotal = viewModel.getConvertBatchTotalCount().getValue();
                int total = batchTotal != null ? batchTotal : selected.size();
                if (okCount != null) {
                    if (okCount == total && okCount > 0) {
                        statusText.setText(getString(R.string.convert_done_all, okCount));
                        Toast.makeText(requireContext(), R.string.convert_saved_to_gallery, Toast.LENGTH_SHORT).show();
                    } else if (okCount > 0) {
                        statusText.setText(getString(R.string.convert_done_partial, okCount, total - okCount));
                    } else {
                        statusText.setText(R.string.convert_done_failed);
                    }
                }
                viewModel.setConvertProcessingState(MainViewModel.ProcessingState.IDLE);
            }
        });
        viewModel.getConvertProgressPercent().observe(getViewLifecycleOwner(), percent -> {
            if (progressBar.getVisibility() == View.VISIBLE) {
                progressBar.setProgress(percent);
            }
        });
        viewModel.getConvertProgressMessage().observe(getViewLifecycleOwner(), msg -> {
            if (progressText.getVisibility() == View.VISIBLE && msg != null) {
                progressText.setText(msg);
            }
        });
    }

    private void runConversion() {
        SentryManager.log("runConversion started");
        List<MediaItem> selectedItems = currentSelectedItems();
        if (selectedItems.isEmpty()) {
            SentryManager.log("runConversion aborted: nothing selected");
            statusText.setText(R.string.convert_nothing_selected);
            return;
        }
        int formatIndex = getSelectedFormatIndex();
        int imageFormatIndex = FormatConverter.effectiveImageFormatIndex(formatIndex);
        Bitmap.CompressFormat imageFormat = FormatConverter.formatAtIndex(imageFormatIndex);
        final boolean heicOutputFallback =
                formatIndex == 3 && imageFormatIndex != formatIndex;

        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        if (heicOutputFallback) {
            Toast.makeText(
                            requireContext(),
                            R.string.convert_heic_fallback_jpeg,
                            Toast.LENGTH_LONG)
                    .show();
        }
        if (containsAnimatedImage(selectedItems)) {
            Toast.makeText(
                            requireContext(),
                            R.string.animated_image_warning,
                            Toast.LENGTH_LONG)
                    .show();
        }
        viewModel.startConversion(selectedItems, formatIndex, imageFormatIndex, imageFormat);
    }

    private void showProgress(boolean show) {
        progressContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
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

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && permissionManager != null && viewModel != null) {
            MainViewModel.ProcessingState state = viewModel.getConvertProcessingState().getValue();
            if (state == MainViewModel.ProcessingState.PROCESSING) {
                return;
            }
            if (state == MainViewModel.ProcessingState.COMPLETED) {
                Integer ok = viewModel.getConvertProcessedItemCount().getValue();
                Integer total = viewModel.getConvertBatchTotalCount().getValue();
                if (ok != null && total != null) {
                    int fail = Math.max(0, total - ok);
                    if (fail > 0 && ok > 0) {
                        statusText.setText(getString(R.string.convert_done_partial, ok, fail));
                    } else if (ok > 0) {
                        statusText.setText(getString(R.string.convert_done_all, ok));
                    } else {
                        statusText.setText(R.string.convert_done_failed);
                    }
                }
                viewModel.setConvertProcessingState(MainViewModel.ProcessingState.IDLE);
                return;
            }
            syncSelectButtonForPickerAccess();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && permissionManager != null) {
            permissionManager.checkPermissions();
        }
    }

    void onHostPermissionFlowCompleted() {
        syncSelectButtonForPickerAccess();
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
}
