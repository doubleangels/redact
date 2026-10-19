package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.MimeTypes;

import com.doubleangels.redact.AppPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
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
    public void savedVideos_areNamedAfterTheContainerTheyAreReallyIn() throws Exception {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        com.doubleangels.redact.FakeMediaStoreProvider.install(app);
        try {
            // A VP9 or AV1 transcode is MP4 (Transformer's muxer); only the fast transmux makes WebM.
            java.io.File transcode = java.io.File.createTempFile(
                    "vid_transform_", VideoMedia3Converter.TRANSCODE_EXTENSION, app.getCacheDir());
            java.io.File transmux = java.io.File.createTempFile("vid_transmux_", ".webm", app.getCacheDir());

            VideoMedia3Converter.copyToMoviesRedact(app, transcode, "clip.webm");
            VideoMedia3Converter.copyToMoviesRedact(app, transmux, "clip.webm");

            java.util.List<String> saved = new java.util.ArrayList<>();
            for (com.doubleangels.redact.FakeMediaStoreProvider.Entry entry
                    : com.doubleangels.redact.FakeMediaStoreProvider.entries().values()) {
                String name = entry.displayName();
                saved.add(name.substring(name.lastIndexOf('.')) + " " + entry.mimeType());
            }
            java.util.Collections.sort(saved);
            assertEquals(java.util.Arrays.asList(".mp4 video/mp4", ".webm video/webm"), saved);
            transcode.delete();
            transmux.delete();
        } finally {
            com.doubleangels.redact.FakeMediaStoreProvider.reset();
        }
    }
}
