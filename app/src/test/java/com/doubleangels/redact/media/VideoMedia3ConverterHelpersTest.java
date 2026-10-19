package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.media3.common.MimeTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** The converter's small decisions that run before Media3 does any work. */
@RunWith(RobolectricTestRunner.class)
public class VideoMedia3ConverterHelpersTest {

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = VideoMedia3Converter.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static int[] fallbackOrder(int requested) throws Exception {
        return (int[]) call("encoderFallbackOrder", new Class<?>[] {int.class}, requested);
    }

    @Test
    public void encoderFallbackOrder_triesTheRequestedCodecFirstThenSaferOnes() throws Exception {
        assertArrayEquals(new int[] {}, fallbackOrder(-1));
        assertArrayEquals(new int[] {3, 1, 0}, fallbackOrder(3));
        assertEquals(2, fallbackOrder(2)[0]);
        assertEquals(0, fallbackOrder(0)[0]);
    }

    @Test
    public void audioFollowsTheVideoCodecsUsualPairing() throws Exception {
        Class<?>[] types = {String.class};
        assertEquals(MimeTypes.AUDIO_OPUS, call("audioMimeTypeForVideoMime", types, MimeTypes.VIDEO_VP9));
        assertEquals(MimeTypes.AUDIO_OPUS, call("audioMimeTypeForVideoMime", types, MimeTypes.VIDEO_VP8));
        assertFalse(MimeTypes.AUDIO_OPUS.equals(call("audioMimeTypeForVideoMime", types, MimeTypes.VIDEO_H264)));
    }

    @Test
    public void parseIntOrZero_acceptsPaddedNumbersOnly() throws Exception {
        Class<?>[] types = {String.class};
        assertEquals(0, call("parseIntOrZero", types, (Object) null));
        assertEquals(0, call("parseIntOrZero", types, "wide"));
        assertEquals(1080, call("parseIntOrZero", types, " 1080 "));
    }

    @Test
    public void readSourceShortSide_usesTheSmallerKnownDimension() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File file = File.createTempFile("side_", ".mp4", app.getCacheDir());
        file.deleteOnExit();
        Uri uri = Uri.fromFile(file);
        Class<?>[] types = {android.content.Context.class, Uri.class};

        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app, uri),
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "3840");
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app, uri),
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "2160");
        assertEquals(2160, call("readSourceShortSide", types, app, uri));

        File unknown = File.createTempFile("side_", ".mp4", app.getCacheDir());
        unknown.deleteOnExit();
        assertEquals(0, call("readSourceShortSide", types, app, Uri.fromFile(unknown)));

        Uri broken = Uri.fromFile(new File(app.getCacheDir(), "missing.mp4"));
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(app, broken),
                new IllegalArgumentException("unreadable"));
        assertEquals(0, call("readSourceShortSide", types, app, broken));
    }

    @Test
    public void fallbackShortSides_onlyStepsDownFromTheSource() {
        for (int side : VideoMedia3Converter.fallbackShortSides(1080)) {
            assertTrue(side < 1080);
        }
        assertTrue(VideoMedia3Converter.fallbackShortSides(0).length > 0);
    }

    @Test
    public void transcodeToFile_whenItsFolderCannotBeMade_fails() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File blocker = File.createTempFile("blocker_", ".tmp", app.getCacheDir());
        blocker.deleteOnExit();
        File output = new File(new File(blocker, "sub"), "out.mp4");
        try {
            VideoMedia3Converter.transcodeToFile(app, Uri.fromFile(blocker), output, 0);
            throw new AssertionError("expected a failure");
        } catch (IOException expected) {
            assertEquals("Failed to create output directory", expected.getMessage());
        }
    }
}
