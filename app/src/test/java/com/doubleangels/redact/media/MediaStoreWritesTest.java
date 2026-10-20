package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;

import android.content.ContentValues;
import android.provider.MediaStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class MediaStoreWritesTest {

    @Test
    public void markPending_setsIsPendingOnQAndAbove() {
        ContentValues values = new ContentValues();
        MediaStoreWrites.markPending(values);
        assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
    }

    @Test
    public void markPublished_ignoresNullUri() {
        MediaStoreWrites.markPublished(RuntimeEnvironment.getApplication().getContentResolver(), null);
    }
}
