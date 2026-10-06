package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;

import com.doubleangels.redact.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ConvertFileAdapterTest {

    private Context context;
    private FrameLayout parent;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.setTheme(R.style.Theme_Redact);
        parent = new FrameLayout(context);
    }

    @Test
    public void adapter_bindsItemsCorrectly() {
        ConvertFileAdapter adapter = new ConvertFileAdapter();
        MediaItem imageItem = new MediaItem(Uri.parse("content://media/external/images/1"), false, "photo.jpg");
        MediaItem videoItem = new MediaItem(Uri.parse("content://media/external/video/2"), true, "video.mp4");

        adapter.setItems(List.of(imageItem, videoItem));
        assertEquals(2, adapter.getItemCount());

        ConvertFileAdapter.Holder holder0 = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder0, 0);
        assertEquals("photo.jpg", holder0.text.getText().toString());
        assertNotNull(holder0.subtitle);
        assertEquals(context.getString(R.string.convert_item_type_image), holder0.subtitle.getText().toString());
        assertEquals(View.GONE, holder0.videoIndicator.getVisibility());
        assertNotNull(holder0.divider);
        assertEquals(View.VISIBLE, holder0.divider.getVisibility());

        ConvertFileAdapter.Holder holder1 = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder1, 1);
        assertEquals("video.mp4", holder1.text.getText().toString());
        assertNotNull(holder1.subtitle);
        assertEquals(context.getString(R.string.convert_item_type_video), holder1.subtitle.getText().toString());
        assertEquals(View.VISIBLE, holder1.videoIndicator.getVisibility());
        assertNotNull(holder1.divider);
        assertEquals(View.GONE, holder1.divider.getVisibility()); // Hidden on the last item!
    }

    @Test
    public void adapter_removeButtonInvokesListener() {
        ConvertFileAdapter adapter = new ConvertFileAdapter();
        MediaItem item = new MediaItem(Uri.parse("content://media/external/images/1"), false, "photo.jpg");
        adapter.setItems(List.of(item));

        AtomicInteger removedPos = new AtomicInteger(-1);
        AtomicReference<MediaItem> removedItem = new AtomicReference<>();
        adapter.setOnItemRemoveListener((pos, it) -> {
            removedPos.set(pos);
            removedItem.set(it);
        });

        ConvertFileAdapter.Holder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);

        assertNotNull(holder.removeButton);
        assertEquals(View.VISIBLE, holder.removeButton.getVisibility());
        holder.removeButton.performClick();

        assertEquals(item, removedItem.get());
    }

    @Test
    public void adapter_setRemovableFalse_hidesRemoveButton() {
        ConvertFileAdapter adapter = new ConvertFileAdapter();
        MediaItem item = new MediaItem(Uri.parse("content://media/external/images/1"), false, "photo.jpg");
        adapter.setItems(List.of(item));
        adapter.setRemovable(false);

        ConvertFileAdapter.Holder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);

        assertNotNull(holder.removeButton);
        assertEquals(View.GONE, holder.removeButton.getVisibility());
    }
}
