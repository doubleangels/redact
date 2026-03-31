package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class MetadataStripperTest {

    private MetadataStripper stripper;

    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        stripper = new MetadataStripper(context);
    }

    @After
    public void tearDown() {
        // Clean up temporary files generated natively by MetadataStripper on the JVM file system
        Context context = ApplicationProvider.getApplicationContext();
        File[] cacheDirs = new File[]{context.getCacheDir(), context.getExternalCacheDir(), new File(".")};
        for (File dir : cacheDirs) {
            if (dir != null && dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        String name = file.getName();
                        if (name.startsWith("vid_transmux_") || name.startsWith("temp_") || 
                            name.startsWith("verify_") || name.startsWith("r_")) {
                            file.delete();
                        }
                    }
                }
                File processedDir = new File(dir, "processed");
                if (processedDir.exists() && processedDir.isDirectory()) {
                    File[] processedFiles = processedDir.listFiles();
                    if (processedFiles != null) {
                        for (File f : processedFiles) f.delete();
                    }
                    processedDir.delete();
                }
            }
        }
    }

    @Test
    public void testDetectFormatFromFileName() {
        Uri mockUri = mock(Uri.class);
        
        assertEquals(3, stripper.detectVideoFormatIndex(mockUri, "movie.mkv"));
        assertEquals(3, stripper.detectVideoFormatIndex(mockUri, "MOVIE.MKV"));
        
        assertEquals(2, stripper.detectVideoFormatIndex(mockUri, "clip.webm"));
        assertEquals(2, stripper.detectVideoFormatIndex(mockUri, "clip.vp9"));
        assertEquals(2, stripper.detectVideoFormatIndex(mockUri, "CLIP.VP8"));
        
        // Unmatched should return 0 (or fall through to extractor, which fails to 0 in test environment without Shadow files)
        assertEquals(0, stripper.detectVideoFormatIndex(mockUri, "video.mp4"));
        assertEquals(0, stripper.detectVideoFormatIndex(mockUri, "video.xyz"));
    }

    @Test
    public void testVideoCleaningUsesRandomizedNaming() {
        Uri mockUri = mock(Uri.class);
        
        // Intercept static calls to prevent actual transcoder execution while checking arguments
        try (org.mockito.MockedStatic<com.doubleangels.redact.media.VideoMedia3Converter> mockedStatic = 
                org.mockito.Mockito.mockStatic(com.doubleangels.redact.media.VideoMedia3Converter.class)) {
            
            MetadataStripper spyStripper = org.mockito.Mockito.spy(stripper);
            
            // Execute the primary strip wrapper (which uses transcodeToGallery or copyToMoviesRedact natively)
            spyStripper.stripVideoMetadata(mockUri, "original_identifiable_name.mp4");
            
            // Verify that the file uniquely drops the parameter and executes the internal random name generator
            org.mockito.Mockito.verify(spyStripper, org.mockito.Mockito.atLeastOnce()).generateShortRandomName();
        }
    }
}
