package com.doubleangels.redact;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.Fragment;

import com.doubleangels.redact.metadata.MetadataDisplayer;
import com.doubleangels.redact.permission.PermissionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private LinearLayout metadataItemsContainer;
    private TextView metadataFooter;
    private MaterialCardView metadataCard;
    private HorizontalScrollView scanActionCardsScroll;
    private LinearLayout scanActionCardsContainer;

    private double lastMapLatitude = Double.NaN;
    private double lastMapLongitude = Double.NaN;

    private ActivityResultLauncher<Intent> mediaPickerLauncher;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private PermissionManager permissionManager;
    private Uri currentMediaUri;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (currentMediaUri != null && permissionManager != null
                            && !permissionManager.needsLocationPermission()) {
                        displayMetadata(currentMediaUri);
                    }
                });
        mediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                        Uri mediaUri = result.getData().getData();
                        if (mediaUri != null) {
                            currentMediaUri = mediaUri;
                            checkLocationPermissionAndDisplayMetadata(mediaUri);
                        } else {
                            showStatus(getString(R.string.status_media_uri_fail));
                        }
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

        statusText = view.findViewById(R.id.statusText);
        progressText = view.findViewById(R.id.progressText);
        progressBar = view.findViewById(R.id.progressBar);
        selectMediaButton = view.findViewById(R.id.selectButton);

        metadataItemsContainer = view.findViewById(R.id.metadataItemsContainer);
        metadataFooter = view.findViewById(R.id.metadataFooter);
        metadataCard = view.findViewById(R.id.metadataCard);
        scanActionCardsScroll = view.findViewById(R.id.scanActionCardsScroll);
        scanActionCardsContainer = view.findViewById(R.id.scanActionCardsContainer);

        metadataCard.setVisibility(View.GONE);

        permissionManager = new PermissionManager(requireActivity(), requireActivity().findViewById(R.id.root_layout),
                settingsLauncher,
                new PermissionManager.PermissionCallback() {
                    @Override
                    public void onPermissionsGranted() {
                        selectMediaButton.setEnabled(true);
                    }

                    @Override
                    public void onPermissionsDenied() {
                        selectMediaButton.setEnabled(false);
                        showStatus(getString(R.string.status_storage_permissions_required));
                    }

                    @Override
                    public void onPermissionsRequestStarted() {
                        showStatus(getString(R.string.status_requesting_permissions));
                    }

                    @Override
                    public void onLocationPermissionGranted() {
                        if (currentMediaUri != null) {
                            displayMetadata(currentMediaUri);
                        }
                    }
                });

        selectMediaButton.setOnClickListener(v -> openMediaPicker());
        permissionManager.checkPermissions();
    }

    private void checkLocationPermissionAndDisplayMetadata(Uri mediaUri) {
        boolean hasLocationPermission = !permissionManager.needsLocationPermission();

        FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);
        FirebaseCrashlytics.getInstance().setCustomKey("has_location_permission", hasLocationPermission);

        displayMetadata(mediaUri);

        if (!hasLocationPermission) {
            permissionManager.requestLocationPermission();
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

    private void openMediaPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            mediaPickerLauncher.launch(intent);
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
            showStatus(getString(R.string.status_media_picker_fail));
        }
    }

    private void displayMetadata(Uri mediaUri) {
        try {
            showStatus(getString(R.string.status_analyzing));
            showProgress(true);

            clearMetadataUi();
            metadataCard.setVisibility(View.GONE);
            clearMapPreviewAction();

            String mimeType = requireContext().getContentResolver().getType(mediaUri);
            boolean isVideo = mimeType != null && mimeType.startsWith("video/");

            FirebaseCrashlytics.getInstance().log("Processing media with MIME type: " + mimeType);
            FirebaseCrashlytics.getInstance().setCustomKey("media_type", mimeType != null ? mimeType : "unknown");

            boolean hasLocationPermission = !permissionManager.needsLocationPermission();
            FirebaseCrashlytics.getInstance().log("Has location permission: " + hasLocationPermission);
            FirebaseCrashlytics.getInstance().setCustomKey("has_location_permission", hasLocationPermission);

            progressText.setText(isVideo ? R.string.status_extracting_media : R.string.status_extracting_image);

            MetadataDisplayer.extractSectionedMetadata(requireContext(), mediaUri, new MetadataDisplayer.SectionedMetadataCallback() {
                @Override
                public void onMetadataExtracted(Map<String, String> metadataSections, boolean isVideo) {
                    requireActivity().runOnUiThread(() -> {
                        try {
                            showProgress(false);
                            showStatus(getString(R.string.status_extraction_complete));

                            double[] coords = parseCoordinatesFromSections(metadataSections);
                            if (coords != null) {
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
                            }
                        } catch (Exception e) {
                            FirebaseCrashlytics.getInstance().recordException(e);
                        }
                    });
                }

                @Override
                public void onExtractionFailed(String error) {
                    requireActivity().runOnUiThread(() -> {
                        showProgress(false);
                        showStatus(getString(R.string.status_extraction_fail));
                        clearMetadataUi();
                        addMetadataRow(null, getString(R.string.scan_extraction_fail));
                        metadataCard.setVisibility(View.VISIBLE);
                        clearMapPreviewAction();
                        FirebaseCrashlytics.getInstance().log("Metadata extraction failed: " + error);
                    });
                }
            });
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
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

        int firstLocationIndex = -1;
        for (int i = 0; i < allRows.size(); i++) {
            String k = allRows.get(i).first;
            if (k != null && MetadataDisplayer.isLocationMetadataKey(k)) {
                firstLocationIndex = i;
                break;
            }
        }

        for (int i = 0; i < allRows.size(); i++) {
            if (i == firstLocationIndex && firstLocationIndex >= 0) {
                View header = LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_scan_metadata_location_section_header, metadataItemsContainer, false);
                metadataItemsContainer.addView(header);
            }
            Pair<String, String> row = allRows.get(i);
            addMetadataRow(row.first, row.second);
        }

        if (metadataItemsContainer.getChildCount() > 0) {
            metadataCard.setVisibility(View.VISIBLE);
        } else {
            metadataCard.setVisibility(View.GONE);
            clearMapPreviewAction();
        }

        updateScanActionCards(allRows);
    }

    private void clearMetadataUi() {
        metadataItemsContainer.removeAllViews();
        metadataFooter.setVisibility(View.GONE);
        metadataFooter.setText("");
        clearScanActionCards();
    }

    private void updateScanActionCards(List<Pair<String, String>> allRows) {
        clearScanActionCards();
        boolean added = false;
        boolean hasCoords = !Double.isNaN(lastMapLatitude) && !Double.isNaN(lastMapLongitude);
        if (hasCoords) {
            addScanActionCard(R.drawable.ic_map_24, getString(R.string.scan_open_in_maps),
                    v -> openLocationInGoogleMaps(lastMapLatitude, lastMapLongitude));
            added = true;
        }
        String cameraLabel = cameraLabelFromMetadataRows(allRows);
        if (cameraLabel != null && !cameraLabel.isEmpty()) {
            addScanActionCard(R.drawable.ic_camera_24,
                    getString(R.string.scan_search_camera) + ". " + cameraLabel,
                    v -> openWebSearch(cameraLabel));
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

    private void openWebSearch(String query) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.scan_search_unavailable, Toast.LENGTH_SHORT).show();
        }
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

    private void addMetadataRow(@Nullable String key, String value) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_scan_metadata_row, metadataItemsContainer, false);
        TextView keyView = row.findViewById(R.id.metadataFieldKey);
        TextView valueView = row.findViewById(R.id.metadataFieldValue);
        if (key == null || key.isEmpty()) {
            keyView.setVisibility(View.GONE);
        } else {
            keyView.setText(key);
        }
        valueView.setText(value);
        metadataItemsContainer.addView(row);
    }

    @Nullable
    private static double[] parseCoordinatesFromSections(Map<String, String> sections) {
        String locationBlock = sections.get(MetadataDisplayer.SECTION_LOCATION);
        String basicBlock = sections.get(MetadataDisplayer.SECTION_BASIC_INFO);

        Double lat = parseMetadataCoordinate(locationBlock, "GPS_LATITUDE");
        if (lat == null) {
            lat = parseMetadataCoordinate(basicBlock, "GPS_LATITUDE");
        }
        if (lat == null) {
            lat = parseMetadataCoordinate(locationBlock, "Latitude");
        }
        if (lat == null) {
            lat = parseMetadataCoordinate(basicBlock, "Latitude");
        }

        Double lon = parseMetadataCoordinate(locationBlock, "GPS_LONGITUDE");
        if (lon == null) {
            lon = parseMetadataCoordinate(basicBlock, "GPS_LONGITUDE");
        }
        if (lon == null) {
            lon = parseMetadataCoordinate(locationBlock, "Longitude");
        }
        if (lon == null) {
            lon = parseMetadataCoordinate(basicBlock, "Longitude");
        }

        if (lat != null && lon != null) {
            return new double[]{lat, lon};
        }
        return null;
    }

    private static Double parseMetadataCoordinate(@Nullable String block, String key) {
        if (block == null || key == null) {
            return null;
        }
        if (block.indexOf('\u001e') >= 0) {
            for (String entry : block.split("\u001e", -1)) {
                if (entry.isEmpty()) {
                    continue;
                }
                int sep = entry.indexOf('\u001f');
                if (sep <= 0) {
                    continue;
                }
                String k = entry.substring(0, sep).trim();
                if (!k.equalsIgnoreCase(key)) {
                    continue;
                }
                String v = entry.substring(sep + 1).trim();
                try {
                    return Double.parseDouble(v);
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }
        for (String rawLine : block.split("\n")) {
            String line = rawLine.trim();
            int sep = line.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String k = line.substring(0, sep).trim();
            if (!k.equalsIgnoreCase(key)) {
                continue;
            }
            String v = line.substring(sep + 1).trim();
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private void clearMapPreviewCoordinatesOnly() {
        lastMapLatitude = Double.NaN;
        lastMapLongitude = Double.NaN;
    }

    private void clearMapPreviewAction() {
        clearMapPreviewCoordinatesOnly();
        clearScanActionCards();
    }

    private void openLocationInGoogleMaps(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return;
        }
        Uri geo = Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon);
        Intent intent = new Intent(Intent.ACTION_VIEW, geo);
        intent.setPackage("com.google.android.apps.maps");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e2) {
                Intent web = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=" + lat + "," + lon));
                try {
                    startActivity(web);
                } catch (ActivityNotFoundException e3) {
                    Toast.makeText(requireContext(), R.string.scan_maps_unavailable, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showStatus(String message) {
        statusText.setText(message);
    }
}
