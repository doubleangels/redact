package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** What the converter reads from a source's tracks and from the device's encoders. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class VideoMediaSourceTest {

    private final Application app = RuntimeEnvironment.getApplication();

    @After
    public void tearDown() {
        ShadowMediaCodecList.reset();
    }

    private Uri withTracks(MediaFormat... formats) throws Exception {
        File f = File.createTempFile("vid_", ".mp4", app.getFilesDir());
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[] {0, 0, 0, 8, 'f', 'r', 'e', 'e'});
        }
        Uri uri = Uri.fromFile(f);
        for (MediaFormat format : formats) {
            ShadowMediaExtractor.addTrack(DataSource.toDataSource(app, uri), format, new byte[] {1, 2, 3, 4});
        }
        return uri;
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = VideoMedia3Converter.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static MediaFormat video(String mime, int width, int height) {
        return MediaFormat.createVideoFormat(mime, width, height);
    }

    private static MediaFormat audio(String mime) {
        return MediaFormat.createAudioFormat(mime, 48000, 2);
    }

    private static void addEncoder(String name, String mime, boolean hardware, MediaFormat format) {
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(format)
                .setIsEncoder(true);
        if (mime.startsWith("video/")) {
            caps.setColorFormats(new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible});
        }
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(true)
                .setIsHardwareAccelerated(hardware)
                .setCapabilities(caps.build())
                .build());
    }

    @Test
    public void sourceHasAudio_readsTheTracks_andKeepsAudioWhenItCannot() throws Exception {
        Class<?>[] types = {android.content.Context.class, Uri.class};
        assertTrue((boolean) call("sourceHasAudio", types, app,
                withTracks(video(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240), audio(MediaFormat.MIMETYPE_AUDIO_AAC))));
        assertFalse((boolean) call("sourceHasAudio", types, app,
                withTracks(video(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240))));
        assertTrue("unreadable: keep the sound", (boolean) call("sourceHasAudio", types, app,
                Uri.fromFile(new File("/no/such/video.mp4"))));
    }

    @Test
    public void readSourceVideo_takesTheFirstVideoAndAudioTrack() throws Exception {
        MediaFormat v = video(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
        v.setFloat(MediaFormat.KEY_FRAME_RATE, 29.97f);
        v.setInteger(MediaFormat.KEY_BIT_RATE, 17_000_000);
        MediaFormat a = audio(MediaFormat.MIMETYPE_AUDIO_AAC);
        a.setInteger(MediaFormat.KEY_BIT_RATE, 256_000);
        MediaFormat second = audio(MediaFormat.MIMETYPE_AUDIO_AAC);
        second.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);

        VideoMedia3Converter.SourceVideo source = VideoMedia3Converter.readSourceVideo(app, withTracks(v, a, second));

        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, source.mime);
        assertEquals(1920, source.width);
        assertEquals(1080, source.height);
        assertEquals(30, source.frameRate);
        assertEquals(17_000_000, source.bitrate);
        assertEquals("only the first audio track counts", 256_000, source.audioBitrate);
    }

    @Test
    public void intOrZero_readsIntsAndFloats_andIsZeroOtherwise() throws Exception {
        Class<?>[] types = {MediaFormat.class, String.class};
        MediaFormat f = new MediaFormat();
        f.setInteger("i", 7);
        f.setFloat("f", 24.6f);
        f.setString("s", "text");
        assertEquals(7, (int) call("intOrZero", types, f, "i"));
        assertEquals(25, (int) call("intOrZero", types, f, "f"));
        assertEquals(0, (int) call("intOrZero", types, f, "s"));
        assertEquals(0, (int) call("intOrZero", types, f, "missing"));
    }

    @Test
    public void audioEncoderMaxBitrate_isTheBestEncodersLimit_orZero() throws Exception {
        Class<?>[] types = {String.class};
        assertEquals(0, (int) call("audioEncoderMaxBitrate", types, MediaFormat.MIMETYPE_AUDIO_OPUS));
        addEncoder("c2.test.aac.encoder", MediaFormat.MIMETYPE_AUDIO_AAC, false, audio(MediaFormat.MIMETYPE_AUDIO_AAC));
        // Robolectric's audio capabilities give no bitrate ceiling, so this only shows the encoder
        // is read without failing; a real device reports its limit.
        assertTrue((int) call("audioEncoderMaxBitrate", types, MediaFormat.MIMETYPE_AUDIO_AAC) >= 0);
    }

    @Test
    public void hardwareEncoderSupport_isNullWithoutAHardwareEncoder() throws Exception {
        Class<?>[] types = {String.class};
        assertNull(call("hardwareEncoderSupport", types, MediaFormat.MIMETYPE_VIDEO_AVC));
        addEncoder("c2.test.avc.software", MediaFormat.MIMETYPE_VIDEO_AVC, false, video(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080));
        assertNull("a software encoder is not what Media3 picks", call("hardwareEncoderSupport", types, MediaFormat.MIMETYPE_VIDEO_AVC));
    }

    @Test
    public void hardwareEncoderSupport_answersForSizesAndRates() throws Exception {
        addEncoder("c2.test.avc.hardware", MediaFormat.MIMETYPE_VIDEO_AVC, true, video(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080));
        VideoMedia3Converter.SizeRateSupport support = (VideoMedia3Converter.SizeRateSupport)
                call("hardwareEncoderSupport", new Class<?>[] {String.class}, MediaFormat.MIMETYPE_VIDEO_AVC);

        assertNotNull(support);
        // Whatever the shadow's limits, a tiny frame is supported either way round or not at all.
        assertEquals(support.supports(176, 144, 15), support.supports(144, 176, 15));
    }

    @Test
    public void targetVideoBitrate_followsTheSource_andIsZeroWhenUnknown() throws Exception {
        Class<?>[] types = {VideoMedia3Converter.SourceVideo.class, String.class, int.class, int.class};
        MediaFormat v = video(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
        v.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        v.setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000);
        VideoMedia3Converter.SourceVideo known = VideoMedia3Converter.readSourceVideo(app, withTracks(v));
        int full = (int) call("targetVideoBitrate", types, known, MediaFormat.MIMETYPE_VIDEO_AVC, 0, 0);
        int smaller = (int) call("targetVideoBitrate", types, known, MediaFormat.MIMETYPE_VIDEO_AVC, 720, 0);
        assertTrue(full > 0);
        assertTrue("a smaller output needs fewer bits", smaller < full);

        VideoMedia3Converter.SourceVideo unknown = VideoMedia3Converter.readSourceVideo(app,
                Uri.fromFile(new File("/no/such/video.mp4")));
        assertEquals(0, (int) call("targetVideoBitrate", types, unknown, MediaFormat.MIMETYPE_VIDEO_AVC, 0, 0));
    }
}
