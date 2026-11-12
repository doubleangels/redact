package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Saving a cleaned or converted video checks what landed in the output, as a picture's save does,
 * and leaves nothing behind when the save fails in any way.
 */
@RunWith(RobolectricTestRunner.class)
public class VideoOutputSafetyTest {

    private Application app;
    private File video;

    @Before
    public void setUp() throws IOException {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        video = File.createTempFile("vid_transform_", ".mp4", app.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(video)) {
            out.write(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
        }
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        video.delete();
    }

    @Test
    public void aSavedVideo_isExactlyTheCleanFile() throws IOException {
        Uri out = VideoMedia3Converter.copyToMoviesRedact(app, video, "clip.mp4");
        assertArrayEquals(Files.readAllBytes(video.toPath()),
                Files.readAllBytes(FakeMediaStoreProvider.only().file.toPath()));
        assertFalse(FakeMediaStoreProvider.only().isPending());
        assertEquals(out.getLastPathSegment(), String.valueOf(FakeMediaStoreProvider.only().id));
    }

    /**
     * A provider that does not truncate on "w" keeps the tail of an older, longer file, which can be
     * a full copy of an original with its metadata. The save must fail and publish nothing.
     */
    @Test
    public void aProviderThatKeepsAnOlderTail_failsTheSave_andPublishesNothing() {
        byte[] original = new byte[4096];
        java.util.Arrays.fill(original, (byte) 'x');
        FakeMediaStoreProvider.staleTail = original;

        assertThrows(IOException.class, () -> VideoMedia3Converter.copyToMoviesRedact(app, video, "clip.mp4"));
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }

    /** A SecurityException from the provider is not an IOException, and still leaves no entry. */
    @Test
    public void aProviderThatThrowsSecurityException_leavesNoEntry() {
        FakeMediaStoreProvider.denyWrite = true;

        assertThrows(SecurityException.class, () -> VideoMedia3Converter.copyToMoviesRedact(app, video, "clip.mp4"));
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }
}
