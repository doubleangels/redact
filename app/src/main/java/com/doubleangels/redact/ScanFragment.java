package com.doubleangels.redact;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaPickerContracts;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.metadata.MetadataDisplayer;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.ui.ScanMetadataAdapter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.sentry.ITransaction;
import io.sentry.SpanStatus;

/**
 * Scan tab: metadata inspection UI; hosted in {@link MainActivity}'s fragment container.
 */
public class ScanFragment extends Fragment {

    private static final Comparator<Pair<String, String>> METADATA_ROW_KEY_ORDER = (a, b) -> {
        String ka = a.first;
        String kb = b.first;
        boolean blankA = ka == null || ka.isEmpty();
        boolean blankB = kb == null || kb.isEmpty();
        if (blankA && blankB) {
            return 0;
        }
        if (blankA) {
            return 1;
        }
        if (blankB) {
            return -1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(ka, kb);
    };

    private TextView statusText;
    private TextView progressText;
    private View progressBar;
    private MaterialButton selectMediaButton;

    private RecyclerView metadataItemsRecycler;
    private ScanMetadataAdapter scanMetadataAdapter;
    private TextView metadataFooter;
    private MaterialCardView metadataCard;
    private HorizontalScrollView scanActionCardsScroll;
    private LinearLayout scanActionCardsContainer;

    private double lastMapLatitude = Double.NaN;
    private double lastMapLongitude = Double.NaN;
    private List<Pair<String, String>> lastMetadataRows = List.of();
    private String lastMetadataPlainText = "";
    @Nullable
    private Map<String, String> lastMetadataSections;
    private final AtomicInteger scanGeneration = new AtomicInteger(0);

    private ActivityResultLauncher<String[]> mediaPickerLauncher;
    private PermissionManager permissionManager;
    private com.doubleangels.redact.media.MediaSelector mediaSelector;
    @Nullable
    private MediaItem currentMediaItem;
    @Nullable
    private io.sentry.ITransaction activeScanTransaction;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        SentryManager.log("Media selected successfully in ScanFragment");
                        if (mediaSelector != null) {
                            MediaItem item = mediaSelector.processMediaUri(uri);
                            currentMediaItem = item;
                            checkLocationPermissionAndDisplayMetadata(item.uri());
                        } else {
                            currentMediaItem = null;
                            checkLocationPermissionAndDisplayMetadata(uri);
                        }
                    } else {
                        SentryManager.log("Media selection canceled or failed in ScanFragment");
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SentryManager.log("ScanFragment view created");

        statusText = view.findViewById(R.id.statusText);
        progressText = view.findViewById(R.id.progressText);
        progressBar = view.findViewById(R.id.progressBar);
        selectMediaButton = view.findViewById(R.id.selectButton);

        metadataItemsRecycler = view.findViewById(R.id.metadataItemsRecycler);
        scanMetadataAdapter = new ScanMetadataAdapter();
        metadataItemsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        metadataItemsRecycler.setAdapter(scanMetadataAdapter);
        metadataItemsRecycler.setNestedScrollingEnabled(false);
        metadataFooter = view.findViewById(R.id.metadataFooter);
        metadataCard = view.findViewById(R.id.metadataCard);
        scanActionCardsScroll = view.findViewById(R.id.scanActionCardsScroll);
        scanActionCardsContainer = view.findViewById(R.id.scanActionCardsContainer);

        metadataCard.setVisibility(View.GONE);

        mediaSelector = new com.doubleangels.redact.media.MediaSelector(requireActivity());

        permissionManager = new PermissionManager(requireActivity(),
                new PermissionManager.PermissionCallback() {
                    @Override
                    public void onPermissionsGranted() {
                        syncSelectButtonForPickerAccess();
                    }

                    @Override
                    public void onPermissionsDenied() {
                        syncSelectButtonForPickerAccess();
                    }

                    @Override
                    public void onPermissionsRequestStarted() {
                        showStatus(getString(R.string.status_requesting_permissions));
                    }

                    @Override
                    public void onLocationPermissionGranted() {
                        if (currentMediaItem != null && lastMetadataSections != null) {
                            refreshLocationSection(currentMediaItem.uri());
                        } else if (currentMediaItem != null) {
                            displayMetadata(currentMediaItem.uri());
                        }
                    }
                });
        permissionManager.applyPendingPermissionResultIfAny(
                com.doubleangels.redact.permission.PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                com.doubleangels.redact.permission.PermissionManager.LOCATION_PERMISSION_REQUEST_CODE);

        selectMediaButton.setOnClickListener(v -> {
            SentryManager.log("Select button clicked in ScanFragment");
            if (permissionManager.shouldRequestStorageBeforePicker()) {
                permissionManager.requestStoragePermission();
            } else {
                openMediaPicker();
            }
        });
        if (!isHidden()) {
            permissionManager.checkPermissions();
        }
    }

    private void checkLocationPermissionAndDisplayMetadata(Uri mediaUri) {
        displayMetadata(mediaUri);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (metadataCard != null && metadataCard.getVisibility() == View.VISIBLE) {
            updateScanActionCards(lastMetadataRows);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && permissionManager != null) {
            permissionManager.checkPermissions();
            if (metadataCard != null && metadataCard.getVisibility() == View.VISIBLE) {
                updateScanActionCards(lastMetadataRows);
            }
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

    @Override
    public void onDestroyView() {
        MetadataDisplayer.cancelActiveScan();
        super.onDestroyView();
    }

    private void openMediaPicker() {
        try {
            SentryManager.log("Launching media picker in ScanFragment");
            mediaPickerLauncher.launch(MediaPickerContracts.IMAGE_AND_VIDEO_MIME_TYPES);
        } catch (Exception e) {
            SentryManager.recordException(e);
            showStatus(getString(R.string.status_media_picker_fail));
        }
    }

    private void syncSelectButtonForPickerAccess() {
        if (permissionManager == null || selectMediaButton == null) {
            return;
        }
        if (permissionManager.isMediaPickerAvailable()) {
            selectMediaButton.setEnabled(true);
        } else {
            selectMediaButton.setEnabled(false);
            showStatus(getString(R.string.status_storage_permissions_required));
        }
    }

    private void refreshLocationSection(Uri mediaUri) {
        final int generation = scanGeneration.incrementAndGet();
        MetadataDisplayer.extractLocationSectionOnly(
                requireContext().getApplicationContext(),
                mediaUri,
                new MetadataDisplayer.LocationSectionCallback() {
                    @Override
                    public void onLocationSectionExtracted(@Nullable String locationSectionContent) {
                        if (generation != scanGeneration.get() || !isAdded() || lastMetadataSections == null) {
                            return;
                        }
                        Map<String, String> merged = new HashMap<>(lastMetadataSections);
                        if (locationSectionContent != null && !locationSectionContent.isEmpty()) {
                            merged.put(MetadataDisplayer.SECTION_LOCATION, locationSectionContent);
                        } else {
                            merged.remove(MetadataDisplayer.SECTION_LOCATION);
                        }
                        lastMetadataSections = merged;
                        double[] coords = MetadataDisplayer.resolveMapCoordinates(merged);
                        if (coords != null
                                && MetadataDisplayer.isUsableMapCoordinate(coords[0], coords[1])) {
                            lastMapLatitude = coords[0];
                            lastMapLongitude = coords[1];
                        }
                        displayCombinedMetadata(merged);
                        metadataFooter.setVisibility(View.GONE);
                    }

                    @Override
                    public void onExtractionFailed(String error) {
                        if (generation != scanGeneration.get() || !isAdded()) {
                            return;
                        }
                        SentryManager.logEvent("scan", "Location metadata refresh failed");
                    }
                });
    }

    private void displayMetadata(Uri mediaUri) {
        try {
            final int generation = scanGeneration.incrementAndGet();
            showStatus(getString(R.string.status_analyzing));
            showProgress(true);

            clearMetadataUi();
            lastMetadataSections = null;
            metadataCard.setVisibility(View.GONE);
            clearCoordinateState();

            String mimeType = requireContext().getContentResolver().getType(mediaUri);
            String fileName = mediaSelector != null ? mediaSelector.getFileName(mediaUri) : null;
            boolean isVideo = MediaSelector.isVideoFromMimeAndName(mimeType, fileName);

            SentryManager.logEvent("scan", "Starting metadata extraction");
            SentryManager.setCustomKey("media_type", mimeType != null ? mimeType : "unknown");
            SentryManager.setCustomKey("is_video", isVideo);

            boolean hasLocationPermission = !permissionManager.needsLocationPermission();
            SentryManager.setCustomKey("has_location_permission", hasLocationPermission);

            progressText.setText(isVideo ? R.string.status_extracting_media : R.string.status_extracting_image);

            finishActiveScanTransaction(io.sentry.SpanStatus.CANCELLED);
            final ITransaction transaction = SentryManager.startTransaction("extract_metadata", "task");
            activeScanTransaction = transaction;
            MetadataDisplayer.extractSectionedMetadata(
                    requireContext().getApplicationContext(), mediaUri, new MetadataDisplayer.SectionedMetadataCallback() {
                @Override
                public void onMetadataExtracted(Map<String, String> metadataSections, boolean isVideo) {
                    transaction.setStatus(SpanStatus.OK);
                    transaction.finish();
                    activeScanTransaction = null;
                    android.app.Activity activity = getActivity();
                    if (activity != null) {
                        activity.runOnUiThread(() -> {
                            if (!isAdded() || generation != scanGeneration.get()) return;
                            try {
                            lastMetadataSections = new HashMap<>(metadataSections);
                            showProgress(false);
                            showStatus(getString(R.string.status_extraction_complete));

                            double[] coords = MetadataDisplayer.resolveMapCoordinates(metadataSections);
                            if (coords != null
                                    && MetadataDisplayer.isUsableMapCoordinate(coords[0], coords[1])) {
                                lastMapLatitude = coords[0];
                                lastMapLongitude = coords[1];
                            } else {
                                clearMapPreviewCoordinatesOnly();
                            }
                            displayCombinedMetadata(metadataSections);

                            String locationSection = metadataSections.get(MetadataDisplayer.SECTION_LOCATION);
                            boolean hasLocationRows = locationSection != null && !locationSection.trim().isEmpty();
                            if (permissionManager.needsLocationPermission() && !hasLocationRows) {
                                metadataFooter.setVisibility(View.VISIBLE);
                                metadataFooter.setText(getString(R.string.scan_location_permission_missing));
                                metadataCard.setVisibility(View.VISIBLE);
                            } else if (permissionManager.needsLocationPermission()
                                    && locationSection != null
                                    && locationSection.contains(
                                            getString(R.string.metadata_location_permission_needed))) {
                                permissionManager.requestLocationPermission();
                            }
                        } catch (Exception e) {
                            SentryManager.recordException(e);
                        }
                    });
                }
            }

                @Override
                public void onExtractionFailed(String error) {
                    transaction.setStatus(SpanStatus.INTERNAL_ERROR);
                    transaction.finish();
                    activeScanTransaction = null;
                    android.app.Activity activity = getActivity();
                    if (activity == null) {
                        return;
                    }
                    activity.runOnUiThread(() -> {
                        if (!isAdded() || generation != scanGeneration.get()) {
                            return;
                        }
                        lastMetadataSections = null;
                        showProgress(false);
                        showStatus(getString(R.string.status_extraction_fail));
                        clearMetadataUi();
                        List<ScanMetadataAdapter.Entry> errorRow = new ArrayList<>();
                        errorRow.add(ScanMetadataAdapter.Entry.row(null, getString(R.string.scan_extraction_fail)));
                        scanMetadataAdapter.setEntries(errorRow);
                        metadataCard.setVisibility(View.VISIBLE);
                        clearCoordinateState();
                        SentryManager.logEvent("scan", "Metadata extraction failed");
                    });
                }
            });
        } catch (Exception e) {
            SentryManager.recordException(e);
            showProgress(false);
            showStatus(getString(R.string.status_extraction_media_fail));
        }
    }

    private void displayCombinedMetadata(Map<String, String> sections) {
        clearMetadataUi();

        List<Pair<String, String>> allRows = new ArrayList<>();
        for (Map.Entry<String, String> e : sections.entrySet()) {
            String content = e.getValue();
            if (content != null && !content.trim().isEmpty()) {
                allRows.addAll(parseMetadataBlockToRows(content));
            }
        }
        Collections.sort(allRows, METADATA_ROW_KEY_ORDER);

        List<ScanMetadataAdapter.Entry> adapterEntries = new ArrayList<>();
        for (Pair<String, String> row : allRows) {
            adapterEntries.add(ScanMetadataAdapter.Entry.row(row.first, row.second));
        }
        scanMetadataAdapter.setEntries(adapterEntries);
        lastMetadataRows = allRows;
        lastMetadataPlainText = metadataPlainTextFromRows(allRows);

        if (!adapterEntries.isEmpty()) {
            metadataCard.setVisibility(View.VISIBLE);
        } else {
            metadataCard.setVisibility(View.GONE);
            clearCoordinateState();
        }

        updateScanActionCards(allRows);
    }

    private void clearMetadataUi() {
        scanMetadataAdapter.clear();
        lastMetadataRows = List.of();
        lastMetadataPlainText = "";
        metadataFooter.setVisibility(View.GONE);
        metadataFooter.setText("");
        clearScanActionCards();
    }

    private void updateScanActionCards(List<Pair<String, String>> allRows) {
        clearScanActionCards();
        boolean added = false;

        boolean hasCoords = !Double.isNaN(lastMapLatitude) && !Double.isNaN(lastMapLongitude)
                && MetadataDisplayer.isUsableMapCoordinate(lastMapLatitude, lastMapLongitude);
        if (hasCoords) {
            String coordsLabel = String.format(Locale.US, "%.7f, %.7f", lastMapLatitude, lastMapLongitude);
            addScanActionCard(R.drawable.ic_map_24, getString(R.string.scan_copy_coordinates),
                    v -> copyPlainTextToClipboard(coordsLabel));
            added = true;
        }

        String cameraLabel = cameraLabelFromMetadataRows(allRows);
        if (cameraLabel != null && !cameraLabel.isEmpty()) {
            addScanActionCard(R.drawable.ic_camera_24, getString(R.string.scan_copy_camera),
                    v -> copyPlainTextToClipboard(cameraLabel));
            added = true;
        }

        if (lastMetadataPlainText != null && !lastMetadataPlainText.isEmpty()) {
            addScanActionCard(R.drawable.ic_content_copy_24, getString(R.string.scan_copy_all_metadata),
                    v -> copyPlainTextToClipboard(lastMetadataPlainText));
            added = true;
        }

        if (currentMediaItem != null) {
            addScanActionCard(R.drawable.ic_clean, getString(R.string.scan_clean_this_file),
                    v -> openInCleanTab(currentMediaItem));
            addScanActionCard(R.drawable.ic_convert, getString(R.string.scan_convert_this_file),
                    v -> openInConvertTab(currentMediaItem));
            added = true;
        }

        if (added) {
            scanActionCardsScroll.setVisibility(View.VISIBLE);
        }
    }

    private void addScanActionCard(int iconRes, String contentDescription, View.OnClickListener listener) {
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_scan_action_card, scanActionCardsContainer, false);
        AppCompatImageView icon = card.findViewById(R.id.actionCardIcon);
        icon.setImageResource(iconRes);
        card.setContentDescription(contentDescription);
        card.setOnClickListener(listener);
        scanActionCardsContainer.addView(card);
    }

    @Nullable
    private static String cameraLabelFromMetadataRows(List<Pair<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        String make = null;
        String model = null;
        for (Pair<String, String> row : rows) {
            if (row.first == null) {
                continue;
            }
            String key = row.first.toUpperCase(Locale.ROOT);
            String value = row.second;
            if (value == null) {
                continue;
            }
            value = value.trim();
            if (value.isEmpty()) {
                continue;
            }
            if ("MAKE".equals(key)) {
                make = value;
            } else if ("MODEL".equals(key)) {
                model = value;
            }
        }
        if (make == null && model == null) {
            return null;
        }
        if (make == null) {
            return model;
        }
        if (model == null) {
            return make;
        }
        return make + " " + model;
    }

    private void copyPlainTextToClipboard(@NonNull String text) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("metadata", text));
        Toast.makeText(requireContext(), R.string.scan_copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    private void openInCleanTab(@NonNull MediaItem item) {
        MainViewModel viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        viewModel.setSelectedItems(List.of(item));
        if (requireActivity() instanceof MainActivity mainActivity) {
            mainActivity.selectTab(R.id.navigation_clean);
        }
    }

    private void openInConvertTab(@NonNull MediaItem item) {
        MainViewModel viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        viewModel.setConvertSelectedItems(List.of(item));
        if (requireActivity() instanceof MainActivity mainActivity) {
            mainActivity.selectTab(R.id.navigation_convert);
        }
    }

    @NonNull
    private static String metadataPlainTextFromRows(@NonNull List<Pair<String, String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Pair<String, String> row : rows) {
            if (row.first != null && !row.first.isEmpty()) {
                sb.append(row.first).append(": ");
            }
            if (row.second != null) {
                sb.append(row.second);
            }
            sb.append('\n');
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }

    private void clearScanActionCards() {
        scanActionCardsContainer.removeAllViews();
        scanActionCardsScroll.setVisibility(View.GONE);
    }

    private static List<Pair<String, String>> parseMetadataBlockToRows(String block) {
        List<Pair<String, String>> rows = new ArrayList<>();
        if (block.indexOf('\u001e') >= 0) {
            for (String entry : block.split("\u001e", -1)) {
                if (entry.isEmpty()) {
                    continue;
                }
                int sep = entry.indexOf('\u001f');
                if (sep <= 0) {
                    rows.add(Pair.create(null, entry.trim()));
                    continue;
                }
                String key = entry.substring(0, sep).trim();
                String value = entry.substring(sep + 1);
                rows.add(Pair.create(key.isEmpty() ? null : key, value));
            }
        } else {
            for (String rawLine : block.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int sep = line.indexOf(':');
                if (sep <= 0) {
                    rows.add(Pair.create(null, line));
                    continue;
                }
                String key = line.substring(0, sep).trim();
                String value = line.substring(sep + 1).trim();
                rows.add(Pair.create(key.isEmpty() ? null : key, value));
            }
        }
        return rows;
    }

    private void clearMapPreviewCoordinatesOnly() {
        lastMapLatitude = Double.NaN;
        lastMapLongitude = Double.NaN;
    }

    private void clearCoordinateState() {
        clearMapPreviewCoordinatesOnly();
        clearScanActionCards();
    }

    private void finishActiveScanTransaction(io.sentry.SpanStatus status) {
        if (activeScanTransaction != null && !activeScanTransaction.isFinished()) {
            activeScanTransaction.setStatus(status);
            activeScanTransaction.finish();
        }
        activeScanTransaction = null;
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showStatus(String message) {
        statusText.setText(message);
    }
}
