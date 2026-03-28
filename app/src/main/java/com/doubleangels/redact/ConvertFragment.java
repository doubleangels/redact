package com.doubleangels.redact;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.media.ConvertFileAdapter;
import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.permission.PermissionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Converts images to JPEG, PNG, WebP, or HEIC and transcodes videos to MP4; saves under
 * Pictures/Redact or Movies/Redact respectively.
 */
public class ConvertFragment extends Fragment {

    private PermissionManager permissionManager;
    private MediaSelector mediaSelector;
    private final List<MediaItem> selectedItems = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<Intent> mediaPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (permissionManager != null) {
                        permissionManager.checkPermissions();
                    }
                });
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        return;
                    }
                    List<MediaItem> items = mediaSelector.processMediaResult(result.getData());
                    selectedItems.clear();
                    List<String> names = new ArrayList<>();
                    for (MediaItem item : items) {
                        selectedItems.add(item);
                        names.add(item.fileName());
                    }
                    convertFileAdapter.setFileNames(names);
                    convertButton.setEnabled(!selectedItems.isEmpty());
                    statusText.setText(getString(R.string.convert_selected_count, selectedItems.size()));
                    refreshFormatSectionForSelection();
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
                requireActivity().findViewById(android.R.id.content),
                settingsLauncher,
                new PermissionManager.PermissionCallback() {
                    @Override
                    public void onPermissionsGranted() {
                        statusText.setText(R.string.convert_status_ready);
                    }

                    @Override
                    public void onPermissionsDenied() {
                        statusText.setText(R.string.status_storage_permissions_required);
                    }

                    @Override
                    public void onPermissionsRequestStarted() {
                        statusText.setText(R.string.status_requesting_permissions);
                    }
                });

        mediaSelector = new MediaSelector(requireActivity(), mediaPickerLauncher);

        selectButton.setOnClickListener(v -> {
            if (permissionManager.needsPermissions()) {
                permissionManager.requestStoragePermission();
            } else {
                openMediaPicker();
            }
        });

        convertButton.setOnClickListener(v -> runConversion());

        refreshFormatSectionForSelection();
        permissionManager.checkPermissions();
    }

    /**
     * Shows format chips only after media is selected; labels match image-only, video-only, or
     * mixed selection.
     */
    private void refreshFormatSectionForSelection() {
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

        // Fourth chip: HEIC (images, API 34+), AV1 (videos), or HEIC·AV1 (mixed, API 34+). On API 33
        // and below, mixed selection uses three pairs only (no AV1 in the UI).
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
    }

    private void openMediaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        mediaPickerLauncher.launch(intent);
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

    private void runConversion() {
        if (selectedItems.isEmpty()) {
            statusText.setText(R.string.convert_nothing_selected);
            return;
        }
        int formatIndex = getSelectedFormatIndex();
        Bitmap.CompressFormat format = FormatConverter.formatAtIndex(formatIndex);
        convertButton.setEnabled(false);
        selectButton.setEnabled(false);
        showProgress(true);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        final int total = selectedItems.size();
        executor.execute(() -> {
            int ok = 0;
            int fail = 0;
            for (int i = 0; i < total; i++) {
                MediaItem mediaItem = selectedItems.get(i);
                Uri uri = mediaItem.uri();
                final int index = i + 1;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    int overallStart = total > 0 ? ((index - 1) * 100) / total : 0;
                    progressBar.setProgress(overallStart);
                    String itemLine = getString(R.string.convert_progress_item, index, total);
                    progressText.setText(itemLine);
                    LocalNotifications.updateConversionProgress(
                            requireContext(), overallStart, itemLine);
                });
                try {
                    String name = mediaSelector.getFileName(uri);
                    if (mediaItem.isVideo()) {
                        FormatConverter.convertVideoToMovies(
                                requireContext(),
                                uri,
                                name,
                                formatIndex,
                                p ->
                                        requireActivity()
                                                .runOnUiThread(
                                                        () -> {
                                                            if (!isAdded()) {
                                                                return;
                                                            }
                                                            int overall =
                                                                    total > 0
                                                                            ? ((index - 1) * 100 + p)
                                                                                    / total
                                                                            : 0;
                                                            progressBar.setProgress(overall);
                                                            String detail =
                                                                    getString(
                                                                            R.string
                                                                                    .convert_progress_detail,
                                                                            index,
                                                                            total,
                                                                            name,
                                                                            getString(
                                                                                    R.string
                                                                                            .convert_transcoding_percent,
                                                                                    p));
                                                            progressText.setText(detail);
                                                            LocalNotifications.updateConversionProgress(
                                                                    requireContext(), overall, detail);
                                                        }));
                    } else {
                        requireActivity()
                                .runOnUiThread(
                                        () -> {
                                            if (isAdded()) {
                                                int overallImage =
                                                        total > 0
                                                                ? ((index - 1) * 100) / total
                                                                : 0;
                                                String detail =
                                                        getString(
                                                                R.string.convert_progress_detail,
                                                                index,
                                                                total,
                                                                name,
                                                                getString(
                                                                        R.string
                                                                                .convert_encoding_image));
                                                progressText.setText(detail);
                                                LocalNotifications.updateConversionProgress(
                                                        requireContext(), overallImage, detail);
                                            }
                                        });
                        FormatConverter.convertImageToPictures(requireContext(), uri, format, name);
                    }
                    ok++;
                } catch (Exception e) {
                    fail++;
                    SentryManager.recordException(e);
                }
                final int done = index;
                requireActivity().runOnUiThread(() -> {
                    if (isAdded()) {
                        int overallDone = total > 0 ? (done * 100) / total : 0;
                        progressBar.setProgress(overallDone);
                        LocalNotifications.updateConversionProgress(
                                requireContext(),
                                overallDone,
                                getString(R.string.convert_progress_item, done, total));
                    }
                });
            }
            final int okCount = ok;
            final int failCount = fail;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                showProgress(false);
                convertButton.setEnabled(true);
                selectButton.setEnabled(true);
                if (failCount == 0) {
                    statusText.setText(getString(R.string.convert_done_all, okCount));
                    Toast.makeText(requireContext(), R.string.convert_saved_to_gallery, Toast.LENGTH_SHORT).show();
                } else if (okCount != 0) {
                    statusText.setText(getString(R.string.convert_done_partial, okCount, failCount));
                } else {
                    statusText.setText(R.string.convert_done_failed);
                }
                LocalNotifications.showConversionComplete(requireContext(), okCount, failCount);
            });
        });
    }

    private void showProgress(boolean show) {
        progressContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
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

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
