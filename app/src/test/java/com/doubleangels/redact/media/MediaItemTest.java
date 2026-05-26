package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MediaItemTest {

    @Test
    public void accessors() {
        Uri uri = Uri.parse("content://test/1");
        MediaItem item = new MediaItem(uri, true, "clip.mp4");
        assertEquals(uri, item.uri());
        assertTrue(item.isVideo());
        assertEquals("clip.mp4", item.fileName());
    }

    @Test
    public void equalsAndHashCode() {
        Uri uri = Uri.parse("content://test/1");
        MediaItem a = new MediaItem(uri, false, "a.jpg");
        MediaItem b = new MediaItem(uri, false, "a.jpg");

        assertTrue(a.equals(a));
        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());

        assertFalse(a.equals(null));
        assertFalse(a.equals("not-media"));

        MediaItem otherUri = new MediaItem(Uri.parse("content://different/authority/item"), false, "a.jpg");
        MediaItem video = new MediaItem(uri, true, "a.jpg");
        MediaItem otherName = new MediaItem(uri, false, "b.jpg");
        assertTrue(!a.equals(otherUri));
        assertTrue(!a.equals(video));
        assertTrue(!a.equals(otherName));
    }
}
