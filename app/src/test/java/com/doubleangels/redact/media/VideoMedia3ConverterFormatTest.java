package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.MimeTypes;

import com.doubleangels.redact.AppPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VideoMedia3ConverterFormatTest {

    @Test
    public void videoMimeTypeForFormatIndex_mapsEachCodec() {
        assertEquals(MimeTypes.VIDEO_H264,
                VideoMedia3Converter.videoMimeTypeForFormatIndex(AppPreferences.FORMAT_INDEX_JPEG_H264));
        assertEquals(MimeTypes.VIDEO_H265,
                VideoMedia3Converter.videoMimeTypeForFormatIndex(AppPreferences.FORMAT_INDEX_PNG_H265));
        assertEquals(MimeTypes.VIDEO_VP9,
                VideoMedia3Converter.videoMimeTypeForFormatIndex(AppPreferences.FORMAT_INDEX_WEBP_VP9));
        assertEquals(MimeTypes.VIDEO_AV1,
                VideoMedia3Converter.videoMimeTypeForFormatIndex(AppPreferences.FORMAT_INDEX_HEIC_AV1));
    }

    @Test
    public void videoMimeTypeForFormatIndex_defaultsToH264ForUnknownIndex() {
        assertEquals(MimeTypes.VIDEO_H264, VideoMedia3Converter.videoMimeTypeForFormatIndex(-1));
        assertEquals(MimeTypes.VIDEO_H264, VideoMedia3Converter.videoMimeTypeForFormatIndex(99));
    }

    @Test
    public void extensionForFormatIndex_matchesContainer() {
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(0));
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(1));
        assertEquals(".webm", VideoMedia3Converter.extensionForFormatIndex(2));
        assertEquals(".mkv", VideoMedia3Converter.extensionForFormatIndex(3));
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(42));
    }

    @Test
    public void containerMimeForFormatIndex_matchesExtension() {
        assertEquals("video/mp4", VideoMedia3Converter.containerMimeForFormatIndex(0));
        assertEquals("video/mp4", VideoMedia3Converter.containerMimeForFormatIndex(1));
        assertEquals("video/webm", VideoMedia3Converter.containerMimeForFormatIndex(2));
        assertEquals("video/x-matroska", VideoMedia3Converter.containerMimeForFormatIndex(3));
        assertEquals("video/mp4", VideoMedia3Converter.containerMimeForFormatIndex(-5));
    }
}
