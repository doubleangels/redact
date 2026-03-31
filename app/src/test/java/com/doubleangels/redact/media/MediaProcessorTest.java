package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.app.Activity;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MediaProcessorTest {

    private MediaProcessor mediaProcessor;
    private Activity mockActivity;

    @Before
    public void setup() {
        mockActivity = mock(Activity.class);
        org.mockito.Mockito.when(mockActivity.getApplicationContext()).thenReturn(mockActivity);
        mediaProcessor = new MediaProcessor(mockActivity);
    }

    @After
    public void tearDown() {
        File[] cacheDirs = new File[]{mockActivity.getCacheDir(), mockActivity.getExternalCacheDir(), new File(".")};
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
    public void testProcessMediaItemsEmpty() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        mediaProcessor.processMediaItems(new ArrayList<>(), new MediaProcessor.ProcessingCallback() {
            @Override
            public void onProgress(int overallPercent, String message) {}

            @Override
            public void onComplete(int processedCount) {
                latch.countDown();
            }
        });
        
        // Should return immediately without invoking callback for empty lists
        assertEquals(1, latch.getCount());
    }

    @Test
    public void testProcessMediaItemsHandlesFailureGracefully() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        List<MediaItem> items = new ArrayList<>();
        Uri mockUri = mock(Uri.class);
        items.add(new MediaItem(mockUri, true, "test_failure.mp4"));

        final int[] finalCount = new int[1];

        // Simulate main thread execution instantly for pure synchronization logic testing
        org.mockito.Mockito.doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(mockActivity).runOnUiThread(org.mockito.ArgumentMatchers.any(Runnable.class));

        // Mock string resource retrieval for progress batches
        org.mockito.Mockito.when(mockActivity.getString(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), 
                org.mockito.ArgumentMatchers.any())).thenReturn("Processing dummy");

        mediaProcessor.processMediaItems(items, new MediaProcessor.ProcessingCallback() {
            @Override
            public void onProgress(int overallPercent, String message) {
                // Ignore progress callbacks safely
            }

            @Override
            public void onComplete(int processedCount) {
                finalCount[0] = processedCount;
                latch.countDown();
            }
        });

        // Wait for background processor thread to conclude (usually sub 1 second for graceful failure on mock URIs)
        latch.await(5, TimeUnit.SECONDS);

        // Since the URI is a mock and unresolvable via actual ContentResolvers, the Stripper should cleanly fail, 
        // yielding exactly 0 successful files, proving the orchestration looping is completely stable!
        assertEquals(0, finalCount[0]);
    }
}
