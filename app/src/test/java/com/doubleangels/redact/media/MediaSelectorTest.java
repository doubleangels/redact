package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MediaSelectorTest {

    private MediaSelector mediaSelector;
    private Activity mockActivity;
    private ContentResolver mockResolver;
    private ActivityResultLauncher<Intent> mockLauncher;

    @Before
    public void setup() {
        mockActivity = mock(Activity.class);
        mockResolver = mock(ContentResolver.class);
        //noinspection unchecked
        mockLauncher = mock(ActivityResultLauncher.class);

        when(mockActivity.getContentResolver()).thenReturn(mockResolver);
        
        mediaSelector = new MediaSelector(mockActivity, mockLauncher);
    }

    @Test
    public void testSelectMediaLaunchesIntent() {
        mediaSelector.selectMedia();
        // Verifies no crashes when instantiating the Open Document intent mapping
    }

    @Test
    public void testProcessMediaResultSingle() {
        Intent intent = new Intent();
        Uri mockUri = mock(Uri.class);
        when(mockUri.getLastPathSegment()).thenReturn("test.mp4");
        intent.setData(mockUri);

        when(mockResolver.getType(any(Uri.class))).thenReturn("video/mp4");

        List<MediaItem> items = mediaSelector.processMediaResult(intent);
        assertEquals(1, items.size());
        assertEquals(true, items.get(0).isVideo());
        assertEquals("test.mp4", items.get(0).fileName());
    }

    @Test
    public void testProcessMediaResultMultiple() {
        Intent intent = new Intent();
        ClipData clipData = mock(ClipData.class);
        ClipData.Item clipItem1 = mock(ClipData.Item.class);
        ClipData.Item clipItem2 = mock(ClipData.Item.class);
        
        Uri mockUri1 = mock(Uri.class);
        Uri mockUri2 = mock(Uri.class);

        when(mockUri1.getLastPathSegment()).thenReturn("image.jpg");
        when(mockUri2.getLastPathSegment()).thenReturn("video.webm");

        when(clipItem1.getUri()).thenReturn(mockUri1);
        when(clipItem2.getUri()).thenReturn(mockUri2);

        when(clipData.getItemCount()).thenReturn(2);
        when(clipData.getItemAt(0)).thenReturn(clipItem1);
        when(clipData.getItemAt(1)).thenReturn(clipItem2);

        intent.setClipData(clipData);

        when(mockResolver.getType(mockUri1)).thenReturn("image/jpeg");
        when(mockResolver.getType(mockUri2)).thenReturn("video/webm");

        List<MediaItem> items = mediaSelector.processMediaResult(intent);
        assertEquals(2, items.size());
        assertEquals(false, items.get(0).isVideo());
        assertEquals(true, items.get(1).isVideo());
    }
}
