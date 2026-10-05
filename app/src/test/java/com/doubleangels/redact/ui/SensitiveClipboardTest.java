package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class SensitiveClipboardTest {

    private static ClipboardManager clipboard() {
        return (ClipboardManager) RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Test
    @Config(sdk = 34)
    public void copy_marksTheClipSensitive_onAndroid13AndNewer() {
        assertTrue(SensitiveClipboard.copy(RuntimeEnvironment.getApplication(), "GPS_LATITUDE", "40.5"));

        ClipData clip = clipboard().getPrimaryClip();
        assertEquals("40.5", clip.getItemAt(0).getText().toString());
        assertEquals("GPS_LATITUDE", clip.getDescription().getLabel().toString());
        PersistableBundle extras = clip.getDescription().getExtras();
        assertTrue(extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));
    }

    @Test
    @Config(sdk = 31)
    public void copy_stillWorksWhereTheFlagDoesNotExist() {
        assertTrue(SensitiveClipboard.copy(RuntimeEnvironment.getApplication(), "label", "value"));

        ClipData clip = clipboard().getPrimaryClip();
        assertEquals("value", clip.getItemAt(0).getText().toString());
        PersistableBundle extras = clip.getDescription().getExtras();
        assertTrue(extras == null || !extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));
    }

    @Test
    @Config(sdk = 34)
    public void tappingAMetadataRow_copiesItAsSensitive() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Redact);
        ScanMetadataAdapter adapter = new ScanMetadataAdapter();
        List<ScanMetadataAdapter.Entry> entries = new ArrayList<>();
        entries.add(ScanMetadataAdapter.Entry.row("location", "GPS_LATITUDE", "40.5"));
        adapter.setEntries(entries);
        FrameLayout parent = new FrameLayout(context);
        RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);

        holder.itemView.performClick();

        ClipData clip = clipboard().getPrimaryClip();
        assertEquals("40.5", clip.getItemAt(0).getText().toString());
        assertTrue(clip.getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));
    }
}
