package com.doubleangels.redact.ui;

import android.app.Application;
import android.net.Uri;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.doubleangels.redact.media.MediaItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds Scan tab metadata state across configuration changes.
 */
public class ScanViewModel extends AndroidViewModel {

    @Nullable
    private Map<String, String> metadataSections;
    private List<Pair<String, String>> metadataRows = List.of();
    private String metadataPlainText = "";
    private double mapLatitude = Double.NaN;
    private double mapLongitude = Double.NaN;
    @Nullable
    private MediaItem currentMediaItem;
    @Nullable
    private String statusMessage;
    private final MutableLiveData<Uri> pendingUri = new MutableLiveData<>();

    public ScanViewModel(@NonNull Application application) {
        super(application);
    }

    public boolean hasMetadataToRestore() {
        return metadataSections != null && !metadataSections.isEmpty();
    }

    @Nullable
    public Map<String, String> getMetadataSections() {
        return metadataSections;
    }

    public void setMetadataSections(@Nullable Map<String, String> sections) {
        metadataSections = sections != null ? new HashMap<>(sections) : null;
    }

    @NonNull
    public List<Pair<String, String>> getMetadataRows() {
        return metadataRows;
    }

    public void setMetadataRows(@NonNull List<Pair<String, String>> rows) {
        metadataRows = rows;
    }

    @NonNull
    public String getMetadataPlainText() {
        return metadataPlainText;
    }

    public void setMetadataPlainText(@NonNull String plainText) {
        metadataPlainText = plainText != null ? plainText : "";
    }

    public double getMapLatitude() {
        return mapLatitude;
    }

    public double getMapLongitude() {
        return mapLongitude;
    }

    public void setMapCoordinates(double latitude, double longitude) {
        mapLatitude = latitude;
        mapLongitude = longitude;
    }

    public void clearMapCoordinates() {
        mapLatitude = Double.NaN;
        mapLongitude = Double.NaN;
    }

    @Nullable
    public MediaItem getCurrentMediaItem() {
        return currentMediaItem;
    }

    public void setCurrentMediaItem(@Nullable MediaItem item) {
        currentMediaItem = item;
    }

    @Nullable
    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(@Nullable String message) {
        statusMessage = message;
    }

    /** A file opened from outside the app (ACTION_VIEW) that Scan should show next. */
    public LiveData<Uri> getPendingUri() {
        return pendingUri;
    }

    public void setPendingUri(@Nullable Uri uri) {
        pendingUri.setValue(uri);
    }

    /** Returns the pending file and clears it so it is scanned exactly once. */
    @Nullable
    public Uri consumePendingUri() {
        Uri uri = pendingUri.getValue();
        pendingUri.setValue(null);
        return uri;
    }

    public void clearScanState() {
        metadataSections = null;
        metadataRows = List.of();
        metadataPlainText = "";
        clearMapCoordinates();
        currentMediaItem = null;
        statusMessage = null;
    }
}
