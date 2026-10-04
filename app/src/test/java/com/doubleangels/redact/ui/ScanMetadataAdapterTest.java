package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;

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
}
