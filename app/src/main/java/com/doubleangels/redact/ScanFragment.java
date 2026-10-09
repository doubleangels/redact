package com.doubleangels.redact;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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

import com.bumptech.glide.Glide;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaPickerContracts;
import com.doubleangels.redact.media.MediaSelector;
import com.doubleangels.redact.metadata.MetadataDisplayer;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.ui.Haptics;
import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.ui.ScanMetadataAdapter;
import com.doubleangels.redact.ui.ScanViewModel;
import com.doubleangels.redact.ui.SensitiveClipboard;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textfield.TextInputEditText;

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

    /** How long the metadata search waits after the last keystroke before filtering. */
    static final long SEARCH_DEBOUNCE_MS = 150;

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
    private View progressContainer;
    private MaterialButton selectMediaButton;

    private View emptyStateContainer;
    private MaterialButton emptyStateSelectButton;
    private View metadataContentContainer;

    private MaterialCardView heroCard;
    private ShapeableImageView heroThumbnail;
    private ImageView heroVideoIndicator;
    private TextView heroFileName;
    private TextView heroFormatBadge;
    private TextView heroFieldsCountBadge;
    private TextView heroSizeBadge;
    private TextView heroRiskBadge;

    private MaterialCardView locationPermissionBanner;
    private MaterialButton locationPermissionButton;

    private RecyclerView metadataItemsRecycler;
    private ScanMetadataAdapter scanMetadataAdapter;
    private TextView metadataFooter;
    private MaterialCardView metadataCard;
    private View metadataSearchLayout;
    private TextInputEditText metadataSearchInput;
    private TextView metadataNoMatches;
    /** Applies the search once typing pauses, so a fast typist does not re-filter on every key. */
    private final Runnable applyMetadataSearch = this::applyMetadataSearch;
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
    private ScanViewModel scanViewModel;
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
                        SentryManager.log("Media was selected successfully in ScanFragment.");
                        scanUri(uri);
                    } else {
                        SentryManager.log("Media selection was canceled or failed in ScanFragment.");
                    }
                });
    }

    private void scanUri(@NonNull Uri uri) {
        if (mediaSelector != null) {
            MediaItem item = mediaSelector.processMediaUri(uri);
            currentMediaItem = item;
            checkLocationPermissionAndDisplayMetadata(item.uri());
        } else {
            currentMediaItem = null;
            checkLocationPermissionAndDisplayMetadata(uri);
        }
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
        SentryManager.log("The ScanFragment view was created.");

        scanViewModel = new ViewModelProvider(requireActivity()).get(ScanViewModel.class);

        statusText = view.findViewById(R.id.statusText);
        progressText = view.findViewById(R.id.progressText);
        progressBar = view.findViewById(R.id.progressBar);
        progressContainer = view.findViewById(R.id.progressContainer);
        selectMediaButton = view.findViewById(R.id.selectButton);

        emptyStateContainer = view.findViewById(R.id.emptyStateContainer);
        emptyStateSelectButton = view.findViewById(R.id.emptyStateSelectButton);
        metadataContentContainer = view.findViewById(R.id.metadataContentContainer);

        heroCard = view.findViewById(R.id.heroCard);
        heroThumbnail = view.findViewById(R.id.heroThumbnail);
        heroVideoIndicator = view.findViewById(R.id.heroVideoIndicator);
        heroFileName = view.findViewById(R.id.heroFileName);
        heroFormatBadge = view.findViewById(R.id.heroFormatBadge);
        heroFieldsCountBadge = view.findViewById(R.id.heroFieldsCountBadge);
        heroSizeBadge = view.findViewById(R.id.heroSizeBadge);
        heroRiskBadge = view.findViewById(R.id.heroRiskBadge);

        locationPermissionBanner = view.findViewById(R.id.locationPermissionBanner);
        locationPermissionButton = view.findViewById(R.id.locationPermissionButton);

        metadataItemsRecycler = view.findViewById(R.id.metadataItemsRecycler);
        scanMetadataAdapter = new ScanMetadataAdapter();
        metadataItemsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        metadataItemsRecycler.setAdapter(scanMetadataAdapter);
        metadataItemsRecycler.setNestedScrollingEnabled(false);

        metadataFooter = view.findViewById(R.id.metadataFooter);
        metadataCard = view.findViewById(R.id.metadataCard);
        setUpMetadataSearch(view);
        scanActionCardsScroll = view.findViewById(R.id.scanActionCardsScroll);
        scanActionCardsContainer = view.findViewById(R.id.scanActionCardsContainer);

        showEmptyState(true);
        metadataCard.setVisibility(View.GONE);

        emptyStateSelectButton.setOnClickListener(v -> onSelectMediaClicked());

        locationPermissionButton.setOnClickListener(v -> {
            if (permissionManager != null) {
                permissionManager.requestLocationPermission();
            }
        });

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

        selectMediaButton.setOnClickListener(v -> onSelectMediaClicked());
        if (!isHidden()) {
            permissionManager.checkPermissions();
        }
        restoreScanUiIfNeeded();

        // Files opened from another app (ACTION_VIEW); delivered via MainActivity.
        scanViewModel.getPendingUri().observe(getViewLifecycleOwner(), pending -> {
            if (pending != null) {
                SentryManager.log("Media was opened from another app in ScanFragment.");
                scanUri(scanViewModel.consumePendingUri());
            }
        });
    }

    private void onSelectMediaClicked() {
        SentryManager.log("The user clicked the Select button in ScanFragment.");
        if (permissionManager != null && permissionManager.shouldRequestStorageBeforePicker()) {
            permissionManager.requestStoragePermission();
        } else {
            openMediaPicker();
        }
    }

    private void showEmptyState(boolean show) {
        if (emptyStateContainer != null) {
            emptyStateContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (metadataContentContainer != null) {
            metadataContentContainer.setVisibility(show ? View.GONE : View.VISIBLE);
        }
        if (selectMediaButton != null) {
            selectMediaButton.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void restoreScanUiIfNeeded() {
        if (scanViewModel == null || !scanViewModel.hasMetadataToRestore()) {
            return;
        }
        lastMetadataSections = scanViewModel.getMetadataSections() != null
                ? new HashMap<>(scanViewModel.getMetadataSections()) : null;
        lastMetadataRows = scanViewModel.getMetadataRows();
        lastMetadataPlainText = scanViewModel.getMetadataPlainText();
        lastMapLatitude = scanViewModel.getMapLatitude();
        lastMapLongitude = scanViewModel.getMapLongitude();
        currentMediaItem = scanViewModel.getCurrentMediaItem();
        String status = scanViewModel.getStatusMessage();
        if (status != null && !status.isEmpty()) {
            showStatus(status);
        }
        showProgress(false);
        if (lastMetadataSections != null) {
            displayCombinedMetadata(lastMetadataSections);
        }
    }

    private void syncScanStateToViewModel() {
        if (scanViewModel == null) {
            return;
        }
        scanViewModel.setMetadataSections(lastMetadataSections);
        scanViewModel.setMetadataRows(lastMetadataRows);
        scanViewModel.setMetadataPlainText(lastMetadataPlainText);
        if (!Double.isNaN(lastMapLatitude) && !Double.isNaN(lastMapLongitude)) {
            scanViewModel.setMapCoordinates(lastMapLatitude, lastMapLongitude);
        } else {
            scanViewModel.clearMapCoordinates();
        }
        scanViewModel.setCurrentMediaItem(currentMediaItem);
        if (statusText != null && statusText.getText() != null) {
            scanViewModel.setStatusMessage(statusText.getText().toString());
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

    void onHostPermissionFlowCompleted() {
        if (permissionManager != null) {
            permissionManager.applyPendingPermissionResultIfAny(
                    PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                    PermissionManager.LOCATION_PERMISSION_REQUEST_CODE);
        }
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

    @Override
    public void onDestroyView() {
        MetadataDisplayer.cancelActiveScan();
        if (metadataSearchInput != null) {
            metadataSearchInput.removeCallbacks(applyMetadataSearch);
        }
        if (heroThumbnail != null) {
            try {
                Glide.with(heroThumbnail).clear(heroThumbnail);
            } catch (Exception ignored) {
            }
        }
        super.onDestroyView();
    }

    private void openMediaPicker() {
        try {
            SentryManager.log("The app is launching the media picker in ScanFragment.");
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
                        SentryManager.logEvent("scan", "The location metadata refresh failed.");
                    }
                });
    }

    private void displayMetadata(Uri mediaUri) {
        try {
            final int generation = scanGeneration.incrementAndGet();
            showStatus(getString(R.string.status_analyzing));
            showProgress(true);

            clearMetadataUi();
            clearMetadataSearch();
            lastMetadataSections = null;
            metadataCard.setVisibility(View.GONE);
            clearCoordinateState();

            String mimeType = requireContext().getContentResolver().getType(mediaUri);
            String fileName = mediaSelector != null ? mediaSelector.getFileName(mediaUri) : null;
            boolean isVideo = MediaSelector.isVideoFromMimeAndName(mimeType, fileName);

            SentryManager.logEvent("scan", "The app is starting metadata extraction.");
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
                                syncScanStateToViewModel();

                                String locationSection = metadataSections.get(MetadataDisplayer.SECTION_LOCATION);
                                boolean hasLocationRows = locationSection != null && !locationSection.trim().isEmpty();
                                if (permissionManager.needsLocationPermission() && !hasLocationRows) {
                                    if (locationPermissionBanner != null) {
                                        locationPermissionBanner.setVisibility(View.VISIBLE);
                                    }
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
                        metadataSearchLayout.setVisibility(View.GONE);
                        updateNoMatches();
                        showEmptyState(false);
                        metadataCard.setVisibility(View.VISIBLE);
                        clearCoordinateState();
                        SentryManager.logEvent("scan", "Metadata extraction failed.");
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
        lastMetadataRows = allRows;
        lastMetadataPlainText = metadataPlainTextFromRows(allRows);

        if (allRows.isEmpty()) {
            showEmptyState(true);
            metadataCard.setVisibility(View.GONE);
            clearCoordinateState();
            syncScanStateToViewModel();
            return;
        }

        showEmptyState(false);
        updateHeroCard(currentMediaItem, allRows, sections);

        List<ScanMetadataAdapter.Entry> adapterEntries = new ArrayList<>();
        String[] orderedSectionIds = {
                MetadataDisplayer.SECTION_BASIC_INFO,
                MetadataDisplayer.SECTION_CAMERA_DETAILS,
                MetadataDisplayer.SECTION_LOCATION,
                MetadataDisplayer.SECTION_TECHNICAL
        };
        boolean isVideo = currentMediaItem != null && currentMediaItem.isVideo();
        Map<String, Integer> availableSections = new HashMap<>();

        for (String sectionId : orderedSectionIds) {
            String content = sections.get(sectionId);
            if (content != null && !content.trim().isEmpty()) {
                List<Pair<String, String>> rows = parseMetadataBlockToRows(content);
                Collections.sort(rows, METADATA_ROW_KEY_ORDER);
                if (!rows.isEmpty()) {
                    availableSections.put(sectionId, rows.size());
                    String title = getSectionTitle(sectionId, isVideo);
                    int iconRes = getSectionIcon(sectionId);
                    adapterEntries.add(ScanMetadataAdapter.Entry.header(sectionId, title, iconRes, rows.size()));
                    for (Pair<String, String> row : rows) {
                        adapterEntries.add(ScanMetadataAdapter.Entry.row(sectionId, row.first, row.second));
                    }
                }
            }
        }

        for (Map.Entry<String, String> entry : sections.entrySet()) {
            String sectionId = entry.getKey();
            boolean handled = false;
            for (String id : orderedSectionIds) {
                if (id.equals(sectionId)) {
                    handled = true;
                    break;
                }
            }
            if (!handled && entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                List<Pair<String, String>> rows = parseMetadataBlockToRows(entry.getValue());
                Collections.sort(rows, METADATA_ROW_KEY_ORDER);
                if (!rows.isEmpty()) {
                    availableSections.put(sectionId, rows.size());
                    adapterEntries.add(ScanMetadataAdapter.Entry.header(sectionId, formatCustomSectionTitle(sectionId), R.drawable.ic_scan, rows.size()));
                    for (Pair<String, String> row : rows) {
                        adapterEntries.add(ScanMetadataAdapter.Entry.row(sectionId, row.first, row.second));
                    }
                }
            }
        }

        scanMetadataAdapter.setEntries(adapterEntries);
        metadataSearchLayout.setVisibility(View.VISIBLE);
        updateNoMatches();
        metadataCard.setVisibility(View.VISIBLE);

        updateScanActionCards(allRows);
        syncScanStateToViewModel();
    }

    private void updateHeroCard(@Nullable MediaItem mediaItem,
                               @NonNull List<Pair<String, String>> allRows,
                               @NonNull Map<String, String> sections) {
        if (heroCard == null) {
            return;
        }
        heroCard.setVisibility(View.VISIBLE);

        if (mediaItem != null) {
            Glide.with(heroThumbnail)
                    .load(mediaItem.uri())
                    .override(180, 180)
                    .placeholder(R.drawable.placeholder_image)
                    .error(R.drawable.error_image)
                    .centerCrop()
                    .into(heroThumbnail);

            heroVideoIndicator.setVisibility(mediaItem.isVideo() ? View.VISIBLE : View.GONE);
            String name = mediaItem.fileName();
            if (name == null || name.isEmpty()) {
                name = mediaSelector != null ? mediaSelector.getFileName(mediaItem.uri())
                        : getString(R.string.scan_default_file_name);
            }
            heroFileName.setText(name);

            String format = resolveMediaFormat(mediaItem);
            heroFormatBadge.setText(format);
            heroFormatBadge.setVisibility(View.VISIBLE);
        } else {
            heroVideoIndicator.setVisibility(View.GONE);
            heroFileName.setText(R.string.scan_metadata);
            heroFormatBadge.setVisibility(View.GONE);
        }

        heroFieldsCountBadge.setText(getResources().getQuantityString(
                R.plurals.scan_hero_fields_count, allRows.size(), allRows.size()));
        heroFieldsCountBadge.setVisibility(allRows.isEmpty() ? View.GONE : View.VISIBLE);

        String sizeText = resolveFileSizeFromRows(allRows);
        if (sizeText != null && !sizeText.isEmpty()) {
            heroSizeBadge.setText(sizeText);
            heroSizeBadge.setVisibility(View.VISIBLE);
        } else {
            heroSizeBadge.setVisibility(View.GONE);
        }

        String locationSection = sections.get(MetadataDisplayer.SECTION_LOCATION);
        boolean hasLocationRows = locationSection != null && !locationSection.trim().isEmpty();
        List<String> risks = new ArrayList<>();
        if (hasLocationRows) {
            risks.add(getString(R.string.scan_risk_location));
        }
        for (Pair<String, String> row : allRows) {
            if (row.first != null && row.first.toLowerCase(Locale.ROOT).contains("serial")) {
                risks.add(getString(R.string.scan_risk_serial));
                break;
            }
        }
        heroRiskBadge.setText(android.text.TextUtils.join(" · ", risks));
        heroRiskBadge.setVisibility(risks.isEmpty() ? View.GONE : View.VISIBLE);

        if (permissionManager != null && permissionManager.needsLocationPermission() && !hasLocationRows) {
            if (locationPermissionBanner != null) {
                locationPermissionBanner.setVisibility(View.VISIBLE);
            }
        } else if (locationPermissionBanner != null) {
            locationPermissionBanner.setVisibility(View.GONE);
        }
    }

    private String resolveMediaFormat(@NonNull MediaItem item) {
        try {
            String mime = requireContext().getContentResolver().getType(item.uri());
            if (mime != null) {
                int slash = mime.indexOf('/');
                if (slash >= 0 && slash < mime.length() - 1) {
                    return mime.substring(slash + 1).toUpperCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) {
        }
        String name = item.fileName();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                return name.substring(dot + 1).toUpperCase(Locale.ROOT);
            }
        }
        return getString(item.isVideo() ? R.string.convert_item_type_video : R.string.convert_item_type_image)
                .toUpperCase(Locale.getDefault());
    }

    @Nullable
    private static String resolveFileSizeFromRows(List<Pair<String, String>> rows) {
        for (Pair<String, String> row : rows) {
            if (row.first != null) {
                String k = row.first.toUpperCase(Locale.ROOT);
                if ("FILE_SIZE".equals(k) || "FILE SIZE".equals(k) || "SIZE".equals(k)) {
                    return row.second;
                }
            }
        }
        return null;
    }

    private String getSectionTitle(String sectionId, boolean isVideo) {
        if (MetadataDisplayer.SECTION_BASIC_INFO.equals(sectionId)) {
            String raw = getString(isVideo ? R.string.metadata_video_properties_header : R.string.metadata_image_properties_header);
            return cleanHeaderString(raw);
        } else if (MetadataDisplayer.SECTION_CAMERA_DETAILS.equals(sectionId)) {
            return cleanHeaderString(getString(R.string.metadata_camera_information_header));
        } else if (MetadataDisplayer.SECTION_LOCATION.equals(sectionId)) {
            return cleanHeaderString(getString(R.string.metadata_location_information_header));
        } else if (MetadataDisplayer.SECTION_TECHNICAL.equals(sectionId)) {
            return cleanHeaderString(getString(R.string.metadata_technical_details_header));
        }
        return formatCustomSectionTitle(sectionId);
    }

    private static String cleanHeaderString(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.endsWith(":")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private int getSectionIcon(String sectionId) {
        if (MetadataDisplayer.SECTION_BASIC_INFO.equals(sectionId)) {
            return R.drawable.ic_info_outline_20;
        } else if (MetadataDisplayer.SECTION_CAMERA_DETAILS.equals(sectionId)) {
            return R.drawable.ic_camera_24;
        } else if (MetadataDisplayer.SECTION_LOCATION.equals(sectionId)) {
            return R.drawable.ic_map_24;
        } else if (MetadataDisplayer.SECTION_TECHNICAL.equals(sectionId)) {
            return R.drawable.ic_scan;
        }
        return R.drawable.ic_scan;
    }

    private String formatCustomSectionTitle(String sectionId) {
        if (sectionId == null || sectionId.isEmpty()) {
            return getString(R.string.scan_section_details);
        }
        String[] words = sectionId.replace('_', ' ').split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1).toLowerCase(Locale.ROOT));
                }
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * Filters the metadata list as the user types. The field restores its own text after a
     * rotation, which re-applies the filter; scanning a new file clears it.
     */
    private void setUpMetadataSearch(@NonNull View view) {
        metadataSearchLayout = view.findViewById(R.id.metadataSearchLayout);
        metadataSearchInput = view.findViewById(R.id.metadataSearchInput);
        metadataNoMatches = view.findViewById(R.id.metadataNoMatches);
        metadataSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                metadataSearchInput.removeCallbacks(applyMetadataSearch);
                metadataSearchInput.postDelayed(applyMetadataSearch, SEARCH_DEBOUNCE_MS);
            }
        });
        metadataSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            InputMethodManager imm = v.getContext().getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
            v.clearFocus();
            return true;
        });
    }

    private void applyMetadataSearch() {
        Editable text = metadataSearchInput.getText();
        scanMetadataAdapter.setQuery(text != null ? text.toString() : null);
        updateNoMatches();
    }

    private void clearMetadataSearch() {
        metadataSearchInput.setText(null);
        metadataSearchInput.clearFocus();
        // Apply now rather than after the pause, so the next file never shows through the old query.
        metadataSearchInput.removeCallbacks(applyMetadataSearch);
        applyMetadataSearch();
    }

    private void updateNoMatches() {
        metadataNoMatches.setVisibility(scanMetadataAdapter.hasQuery()
                && scanMetadataAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void clearMetadataUi() {
        scanMetadataAdapter.clear();
        lastMetadataRows = List.of();
        lastMetadataPlainText = "";
        metadataFooter.setVisibility(View.GONE);
        metadataFooter.setText("");
        if (heroCard != null) {
            heroCard.setVisibility(View.GONE);
        }
        if (locationPermissionBanner != null) {
            locationPermissionBanner.setVisibility(View.GONE);
        }
        clearScanActionCards();
        showEmptyState(true);
    }

    private void updateScanActionCards(List<Pair<String, String>> allRows) {
        clearScanActionCards();
        boolean added = false;

        addScanActionCard(R.drawable.ic_add_photo, getString(R.string.button_select_media),
                v -> onSelectMediaClicked());
        added = true;

        boolean hasCoords = !Double.isNaN(lastMapLatitude) && !Double.isNaN(lastMapLongitude)
                && MetadataDisplayer.isUsableMapCoordinate(lastMapLatitude, lastMapLongitude);
        if (hasCoords) {
            double latForMaps = lastMapLatitude;
            double lonForMaps = lastMapLongitude;
            addScanActionCard(R.drawable.ic_map_24, getString(R.string.scan_open_coordinates_in_maps),
                    v -> onOpenCoordinatesInMaps(latForMaps, lonForMaps));
            added = true;
        }

        if (currentMediaItem != null) {
            addScanActionCard(R.drawable.ic_clean, getString(R.string.scan_clean_this_file),
                    v -> openInCleanTab(currentMediaItem));
            addScanActionCard(R.drawable.ic_convert, getString(R.string.scan_convert_this_file),
                    v -> openInConvertTab(currentMediaItem));
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

        if (added) {
            scanActionCardsScroll.setVisibility(View.VISIBLE);
        }
    }

    private void addScanActionCard(int iconRes, String contentDescription, View.OnClickListener listener) {
        MaterialButton button = (MaterialButton) LayoutInflater.from(requireContext())
                .inflate(R.layout.item_scan_action_card, scanActionCardsContainer, false);
        button.setIconResource(iconRes);
        button.setText(contentDescription);
        button.setContentDescription(contentDescription);
        button.setOnClickListener(listener);
        scanActionCardsContainer.addView(button);
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
        if (SensitiveClipboard.copy(requireContext(), getString(R.string.scan_metadata), text)) {
            Haptics.confirm(getView());
            Toast.makeText(requireContext(), R.string.scan_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opening a coordinate in the maps app hands the GPS location to a separate, third-party
     * app, unlike every other Scan action, which stays entirely on-device, so the first use
     * requires explicit consent. Once given, it's remembered and this goes straight to Maps.
     */
    private void onOpenCoordinatesInMaps(double latitude, double longitude) {
        if (AppPreferences.hasConsentedToOpenLocationInMaps(requireContext())) {
            openCoordinatesInMaps(latitude, longitude);
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.scan_maps_consent_title)
                .setMessage(R.string.scan_maps_consent_message)
                .setPositiveButton(R.string.scan_maps_consent_continue, (dialog, which) -> {
                    AppPreferences.setConsentedToOpenLocationInMaps(requireContext(), true);
                    openCoordinatesInMaps(latitude, longitude);
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void openCoordinatesInMaps(double latitude, double longitude) {
        String coords = String.format(Locale.US, "%.7f,%.7f", latitude, longitude);
        Uri geoUri = Uri.parse("geo:" + coords + "?q=" + coords);
        Intent intent = new Intent(Intent.ACTION_VIEW, geoUri);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.scan_maps_not_available, Toast.LENGTH_SHORT).show();
        }
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
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (progressText != null) {
            progressText.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (progressContainer != null) {
            progressContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showStatus(String message) {
        statusText.setText(message);
        boolean isRoutineMessage = message == null || message.isEmpty()
                || message.equals(getString(R.string.scan_status_ready))
                || message.equals(getString(R.string.status_extraction_complete));
        statusText.setVisibility(isRoutineMessage ? View.GONE : View.VISIBLE);
    }
}
