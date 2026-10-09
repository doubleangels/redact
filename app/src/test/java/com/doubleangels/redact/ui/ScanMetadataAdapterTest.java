package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ScanMetadataAdapterTest {

    private ScanMetadataAdapter adapter;
    private ViewGroup parent;

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Redact);
        adapter = new ScanMetadataAdapter();
        parent = new FrameLayout(context);
    }

    @Test
    public void emptyAdapter_hasZeroItems() {
        assertEquals(0, adapter.getItemCount());
    }

    @Test
    public void setEntries_populatesItemsAndPreservesTypes() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.header("camera", "Camera", 0, 2));
        entries.add(ScanMetadataAdapter.Entry.row("camera", "Make", "Google"));
        entries.add(ScanMetadataAdapter.Entry.row("camera", "Model", "Pixel 8 Pro"));

        adapter.setEntries(entries);
        assertEquals(3, adapter.getItemCount());
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_HEADER, adapter.getItemViewType(0));
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_ROW, adapter.getItemViewType(1));
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_ROW, adapter.getItemViewType(2));
    }

    @Test
    public void filterBySection_filtersCorrectly() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.header("file", "File", 0, 1));
        entries.add(ScanMetadataAdapter.Entry.row("file", "Name", "photo.jpg"));
        entries.add(ScanMetadataAdapter.Entry.header("camera", "Camera", 0, 1));
        entries.add(ScanMetadataAdapter.Entry.row("camera", "Make", "Google"));

        adapter.setEntries(entries);
        assertEquals(4, adapter.getItemCount());

        adapter.filterBySection("camera");
        assertEquals(2, adapter.getItemCount());

        adapter.filterBySection(null);
        assertEquals(4, adapter.getItemCount());
    }

    @Test
    public void clear_resetsAdapter() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.row("Key", "Val"));
        adapter.setEntries(entries);
        assertEquals(1, adapter.getItemCount());

        adapter.clear();
        assertEquals(0, adapter.getItemCount());
    }

    @Test
    public void onCreateAndBindViewHolder_headerAndRow() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.header("camera", "Camera Info", 0, 1));
        entries.add(ScanMetadataAdapter.Entry.row("camera", "Model", "Pixel"));
        adapter.setEntries(entries);

        RecyclerView.ViewHolder headerHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(headerHolder, 0);
        assertEquals("Camera Info", ((ScanMetadataAdapter.HeaderHolder) headerHolder).titleView.getText().toString());

        RecyclerView.ViewHolder rowHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1));
        adapter.onBindViewHolder(rowHolder, 1);
        assertEquals("Model", ((ScanMetadataAdapter.RowHolder) rowHolder).keyView.getText().toString());
        assertEquals("Pixel", ((ScanMetadataAdapter.RowHolder) rowHolder).valueView.getText().toString());
    }

    @Test
    public void onlyLocationMetadata_isFlagged() {
        // Everything in the Location section is location data.
        assertTrue(ScanMetadataAdapter.Entry.row("location", "Latitude", "37.7749").isLocation);
        assertTrue(ScanMetadataAdapter.Entry.row("LOCATION", "anything", "x").isLocation);
        // GPS and location keys are flagged even when they land in another section.
        assertTrue(ScanMetadataAdapter.Entry.row("technical", "GPS_LATITUDE", "40.5").isLocation);
        assertTrue(ScanMetadataAdapter.Entry.row("technical", "gps_altitude", "10").isLocation);
        assertTrue(ScanMetadataAdapter.Entry.row("basic_info", "LOCATION", "+40.5-73.9/").isLocation);
        assertTrue(ScanMetadataAdapter.Entry.row("GPS_DATE_STAMP", "1").isLocation);
    }

    @Test
    public void everythingElse_isNotFlagged() {
        // The earlier "identifying" heuristics (serials, owner, dates, camera, names...) no longer flag.
        for (String key : new String[]{
                "BODY_SERIAL_NUMBER", "CAMERA_OWNER_NAME", "MAKE", "MODEL", "LENS_MODEL", "SOFTWARE",
                "DATE_TIME_ORIGINAL", "ARTIST", "TITLE", "IMAGE_DESCRIPTION", "MAKER_NOTE", "XMP",
                "THUMBNAIL_IMAGE_LENGTH", "DISPLAY_NAME", "File Name", "APERTURE_VALUE", "EXPOSURE_TIME",
                "ORIENTATION", "IMAGE_WIDTH", "DURATION", "BITRATE", "Y_CB_CR_POSITIONING"}) {
            assertFalse(key, ScanMetadataAdapter.Entry.row("technical", key, "IMG_20240101_vacation.jpg").isLocation);
        }
        assertFalse(ScanMetadataAdapter.Entry.row(null, "Aperture", "f/1.8").isLocation);
        assertFalse(ScanMetadataAdapter.Entry.row("camera", null, "x").isLocation);
    }

    @Test
    public void onBindViewHolder_locationRowShowsTheLocationBadge() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.row("location", "GPS Latitude", "37.7749"));
        adapter.setEntries(entries);

        RecyclerView.ViewHolder rowHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(rowHolder, 0);

        ScanMetadataAdapter.RowHolder holder = (ScanMetadataAdapter.RowHolder) rowHolder;
        assertEquals(android.view.View.VISIBLE, holder.tintOverlay.getVisibility());
        assertEquals(android.view.View.VISIBLE, holder.riskIndicator.getVisibility());
        assertEquals("Location", holder.riskIndicator.getText().toString());
    }

    @Test
    public void onBindViewHolder_otherRowsHaveNoBadge() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.row("camera", "BODY_SERIAL_NUMBER", "SN123"));
        adapter.setEntries(entries);

        RecyclerView.ViewHolder rowHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(rowHolder, 0);

        ScanMetadataAdapter.RowHolder holder = (ScanMetadataAdapter.RowHolder) rowHolder;
        assertEquals(android.view.View.GONE, holder.tintOverlay.getVisibility());
        assertEquals(android.view.View.GONE, holder.riskIndicator.getVisibility());
    }

    private static List<ScanMetadataAdapter.Entry> cameraAndLocation() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.header("camera", "Camera", 0, 3));
        entries.add(ScanMetadataAdapter.Entry.row("camera", "Make", "Google"));
        entries.add(ScanMetadataAdapter.Entry.row("camera", "Model", "Pixel 8 Pro"));
        entries.add(ScanMetadataAdapter.Entry.row("camera", "ISO", "100"));
        entries.add(ScanMetadataAdapter.Entry.header("location", "Location", 0, 1));
        entries.add(ScanMetadataAdapter.Entry.row("location", "GPS", "40.5, -73.9"));
        return entries;
    }

    @Test
    public void setQuery_keepsMatchingRowsUnderTheirHeaderWithANarrowedCount() {
        adapter.setEntries(cameraAndLocation());

        adapter.setQuery("  PIXEL ");

        assertTrue(adapter.hasQuery());
        assertEquals(2, adapter.getItemCount());
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_HEADER, adapter.getItemViewType(0));
        RecyclerView.ViewHolder header = adapter.onCreateViewHolder(parent, ScanMetadataAdapter.Entry.VIEW_TYPE_HEADER);
        adapter.onBindViewHolder(header, 0);
        assertEquals("1", ((ScanMetadataAdapter.HeaderHolder) header).countView.getText().toString());
        RecyclerView.ViewHolder row = adapter.onCreateViewHolder(parent, ScanMetadataAdapter.Entry.VIEW_TYPE_ROW);
        adapter.onBindViewHolder(row, 1);
        assertEquals("Model", ((ScanMetadataAdapter.RowHolder) row).keyView.getText().toString());
    }

    @Test
    public void setQuery_matchesNamesAndValuesAcrossSections() {
        adapter.setEntries(cameraAndLocation());

        // "Google" matches by value in Camera, "GPS" by name in Location.
        adapter.setQuery("g");

        assertEquals(4, adapter.getItemCount());
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_HEADER, adapter.getItemViewType(0));
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_ROW, adapter.getItemViewType(1));
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_HEADER, adapter.getItemViewType(2));
        assertEquals(ScanMetadataAdapter.Entry.VIEW_TYPE_ROW, adapter.getItemViewType(3));
    }

    @Test
    public void setQuery_withNoMatches_isEmpty_andClearingRestoresEverything() {
        adapter.setEntries(cameraAndLocation());

        adapter.setQuery("nothing like this");
        assertEquals(0, adapter.getItemCount());

        adapter.setQuery(null);
        assertFalse(adapter.hasQuery());
        assertEquals(6, adapter.getItemCount());
    }

    @Test
    public void setQuery_survivesNewEntries() {
        adapter.setQuery("gps");
        adapter.setEntries(cameraAndLocation());

        assertEquals(2, adapter.getItemCount());
    }

    @Test
    public void setQuery_treatsSpacesAndUnderscoresAlike() {
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.header("location", "Location", 0, 2));
        entries.add(ScanMetadataAdapter.Entry.row("location", "GPS_ALTITUDE", "120/1"));
        entries.add(ScanMetadataAdapter.Entry.row("location", "GPS_LATITUDE", "40/1"));
        adapter.setEntries(entries);

        adapter.setQuery("gps  altitude");
        assertEquals(2, adapter.getItemCount());

        adapter.setQuery("GPS_ALTITUDE");
        assertEquals(2, adapter.getItemCount());
    }

    @Test
    public void setQuery_matchingASectionTitle_showsTheWholeSection() {
        adapter.setEntries(cameraAndLocation());

        adapter.setQuery("came");

        // The Camera header and all three of its rows, with the full count.
        assertEquals(4, adapter.getItemCount());
        RecyclerView.ViewHolder header = adapter.onCreateViewHolder(parent, ScanMetadataAdapter.Entry.VIEW_TYPE_HEADER);
        adapter.onBindViewHolder(header, 0);
        assertEquals("3", ((ScanMetadataAdapter.HeaderHolder) header).countView.getText().toString());
    }
}
