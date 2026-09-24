package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;
import android.util.Pair;

import com.doubleangels.redact.media.MediaItem;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ScanViewModelTest {

    private ScanViewModel newViewModel() {
        return new ScanViewModel(RuntimeEnvironment.getApplication());
    }

    @Test
    public void hasMetadataToRestore_onlyWhenSectionsNonEmpty() {
        ScanViewModel viewModel = newViewModel();
        assertFalse(viewModel.hasMetadataToRestore());

        viewModel.setMetadataSections(new HashMap<>());
        assertFalse(viewModel.hasMetadataToRestore());

        Map<String, String> sections = new HashMap<>();
        sections.put("Exif", "value");
        viewModel.setMetadataSections(sections);
        assertTrue(viewModel.hasMetadataToRestore());
    }

    @Test
    public void setMetadataSections_defensivelyCopies() {
        ScanViewModel viewModel = newViewModel();
        Map<String, String> sections = new HashMap<>();
        sections.put("Exif", "value");
        viewModel.setMetadataSections(sections);

        sections.put("mutated", "after");
        assertFalse(viewModel.getMetadataSections().containsKey("mutated"));

        viewModel.setMetadataSections(null);
        assertNull(viewModel.getMetadataSections());
    }

    @Test
    public void metadataRows_areStoredAndReturned() {
        ScanViewModel viewModel = newViewModel();
        assertEquals(0, viewModel.getMetadataRows().size());

        viewModel.setMetadataRows(List.of(new Pair<>("Make", "Pixel"), new Pair<>("Model", "X")));
        assertEquals(2, viewModel.getMetadataRows().size());
        assertEquals("Make", viewModel.getMetadataRows().get(0).first);
    }

    @Test
    public void metadataPlainText_neverNull() {
        ScanViewModel viewModel = newViewModel();
        assertEquals("", viewModel.getMetadataPlainText());
        viewModel.setMetadataPlainText("plain text");
        assertEquals("plain text", viewModel.getMetadataPlainText());
        viewModel.setMetadataPlainText(null);
        assertEquals("", viewModel.getMetadataPlainText());
    }

    @Test
    public void mapCoordinates_areStoredAndCleared() {
        ScanViewModel viewModel = newViewModel();
        viewModel.setMapCoordinates(39.7392, -104.9903);
        assertEquals(39.7392, viewModel.getMapLatitude(), 0.0001);
        assertEquals(-104.9903, viewModel.getMapLongitude(), 0.0001);

        viewModel.clearMapCoordinates();
        assertTrue(Double.isNaN(viewModel.getMapLatitude()));
        assertTrue(Double.isNaN(viewModel.getMapLongitude()));
    }

    @Test
    public void currentMediaItem_andStatusMessage_roundTrip() {
        ScanViewModel viewModel = newViewModel();
        assertNull(viewModel.getCurrentMediaItem());
        assertNull(viewModel.getStatusMessage());

        MediaItem item = new MediaItem(Uri.parse("content://media/external/images/media/9"), false, "x.jpg");
        viewModel.setCurrentMediaItem(item);
        viewModel.setStatusMessage("Scanning…");
        assertEquals(item, viewModel.getCurrentMediaItem());
        assertEquals("Scanning…", viewModel.getStatusMessage());
    }

    @Test
    public void clearScanState_resetsEverything() {
        ScanViewModel viewModel = newViewModel();
        viewModel.setMetadataSections(Map.of("Exif", "v"));
        viewModel.setMetadataRows(List.of(new Pair<>("Make", "Pixel")));
        viewModel.setMetadataPlainText("text");
        viewModel.setMapCoordinates(1.0, 2.0);
        viewModel.setCurrentMediaItem(
                new MediaItem(Uri.parse("content://media/1"), false, "x.jpg"));
        viewModel.setStatusMessage("msg");

        viewModel.clearScanState();

        assertFalse(viewModel.hasMetadataToRestore());
        assertNull(viewModel.getMetadataSections());
        assertEquals(0, viewModel.getMetadataRows().size());
        assertEquals("", viewModel.getMetadataPlainText());
        assertTrue(Double.isNaN(viewModel.getMapLatitude()));
        assertNull(viewModel.getCurrentMediaItem());
        assertNull(viewModel.getStatusMessage());
    }
}