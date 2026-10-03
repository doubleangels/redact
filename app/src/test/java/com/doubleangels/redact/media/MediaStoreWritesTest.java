package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;

import android.content.ContentValues;
import android.provider.MediaStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public class MediaStoreWritesTest {

    @Test
    @Config(sdk = 34)
    public void markPending_setsIsPendingOnQAndAbove() {
        ContentValues values = new ContentValues();
        MediaStoreWrites.markPending(values);
        assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
    }

    @Test
    @Config(sdk = 34)
    public void markPublished_ignoresNullUri() {
        MediaStoreWrites.markPublished(RuntimeEnvironment.getApplication().getContentResolver(), null);
    }
}
