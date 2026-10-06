package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ClipboardManager;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.media.ConvertFileAdapter;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.ui.ScanMetadataAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Text the user hears or sees must come from resources (so it is translated), not from Java literals. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HardCodedTextTest {

    private static Context themed() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Redact);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static View removeButtonFor(String fileName) {
        ConvertFileAdapter adapter = new ConvertFileAdapter();
        adapter.setItems(Arrays.asList(new MediaItem(android.net.Uri.parse("file:///x/" + fileName), false, fileName)));
        FrameLayout parent = new FrameLayout(themed());
        RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        ((RecyclerView.Adapter) adapter).onBindViewHolder(holder, 0);
        return holder.itemView.findViewById(R.id.convertItemRemove);
    }

    @Test
    public void removeButton_namesTheFileItRemoves() {
        assertEquals("Remove holiday.jpg", removeButtonFor("holiday.jpg").getContentDescription().toString());
    }

    @Test
    @Config(qualifiers = "de")
    public void removeButton_isLocalized() {
        assertEquals("holiday.jpg entfernen", removeButtonFor("holiday.jpg").getContentDescription().toString());
    }

    @Test
    @Config(qualifiers = "ru")
    public void clipboardLabelForARowWithoutAKey_isLocalized() {
        ScanMetadataAdapter adapter = new ScanMetadataAdapter();
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.row(null, "значение"));
        adapter.setEntries(entries);
        FrameLayout parent = new FrameLayout(themed());
        RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);

        holder.itemView.performClick();

        ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("Метаданные", clipboard.getPrimaryClip().getDescription().getLabel().toString());
        assertTrue(clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("значение"));
    }
}
