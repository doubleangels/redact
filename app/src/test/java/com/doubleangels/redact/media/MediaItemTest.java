package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MediaItemTest {

    @Test
    public void constructor_rejectsNullUri() {
        assertThrows(IllegalArgumentException.class, () -> new MediaItem(null, false, "name.jpg"));
    }

    @Test
    public void getters_returnConstructorValues() {
        Uri uri = Uri.parse("content://media/external/images/media/1");
        MediaItem item = new MediaItem(uri, true, "clip.mp4");
        assertEquals(uri, item.uri());
        assertTrue(item.isVideo());
        assertEquals("clip.mp4", item.fileName());
    }

    @Test
    public void fileName_mayBeNull() {
        MediaItem item = new MediaItem(Uri.parse("content://media/1"), false, null);
        assertEquals(null, item.fileName());
        assertFalse(item.isVideo());
    }

    @Test
    public void equals_reflectsAllThreeFields() {
        Uri uri = Uri.parse("content://media/external/images/media/1");
        MediaItem a = new MediaItem(uri, false, "a.jpg");
        MediaItem b = new MediaItem(uri, false, "a.jpg");
        assertEquals(a, b);

        assertNotEquals(a, new MediaItem(uri, true, "a.jpg"));
        assertNotEquals(a, new MediaItem(uri, false, "b.jpg"));
        assertNotEquals(a, new MediaItem(Uri.parse("content://media/2"), false, "a.jpg"));
        assertNotEquals(a, null);
        assertNotEquals(a, "not a media item");
    }

    @Test
    public void hashCode_isConsistentWithEquals() {
        Uri uri = Uri.parse("content://media/external/video/media/7");
        MediaItem a = new MediaItem(uri, true, "v.mp4");
        MediaItem b = new MediaItem(uri, true, "v.mp4");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a.hashCode(), new MediaItem(uri, false, "v.mp4").hashCode());
    }
}