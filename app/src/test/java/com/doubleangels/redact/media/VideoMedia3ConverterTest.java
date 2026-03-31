package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class VideoMedia3ConverterTest {

    @Test
    public void testFormatExtensionMapping() {
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(0)); // H264
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(1)); // H265
        assertEquals(".webm", VideoMedia3Converter.extensionForFormatIndex(2)); // VP9
        assertEquals(".mkv", VideoMedia3Converter.extensionForFormatIndex(3)); // AV1
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(VideoMedia3Converter.FORMAT_STRIP_METADATA));
    }

    @Test
    public void testMimeTypeMapping() {
        assertEquals("video/mp4", VideoMedia3Converter.containerMimeForFormatIndex(0));
        assertEquals("video/mp4", VideoMedia3Converter.containerMimeForFormatIndex(1));
        assertEquals("video/webm", VideoMedia3Converter.containerMimeForFormatIndex(2));
        assertEquals("video/x-matroska", VideoMedia3Converter.containerMimeForFormatIndex(3));
    }
    
    @Test
    public void testVideoMimeMapping() {
        assertEquals("video/avc", VideoMedia3Converter.videoMimeTypeForFormatIndex(0));
        assertEquals("video/hevc", VideoMedia3Converter.videoMimeTypeForFormatIndex(1));
        assertEquals("video/x-vnd.on2.vp9", VideoMedia3Converter.videoMimeTypeForFormatIndex(2));
        assertEquals("video/av01", VideoMedia3Converter.videoMimeTypeForFormatIndex(3));
    }
}
