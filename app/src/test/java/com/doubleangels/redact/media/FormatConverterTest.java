package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class FormatConverterTest {

    @Test
    public void testFormatRouting() {
        assertEquals(Bitmap.CompressFormat.JPEG, FormatConverter.formatAtIndex(0));
        assertEquals(Bitmap.CompressFormat.PNG, FormatConverter.formatAtIndex(1));
        assertEquals(Bitmap.CompressFormat.WEBP, FormatConverter.formatAtIndex(2));
        assertNotNull(FormatConverter.formatAtIndex(3)); // HEIC or JPEG fallback
    }

    @Test
    public void testQualityForFormat() {
        assertEquals(100, FormatConverter.qualityForFormat(Bitmap.CompressFormat.PNG));
        assertEquals(90, FormatConverter.qualityForFormat(Bitmap.CompressFormat.WEBP));
        assertEquals(92, FormatConverter.qualityForFormat(Bitmap.CompressFormat.JPEG));
    }
}
