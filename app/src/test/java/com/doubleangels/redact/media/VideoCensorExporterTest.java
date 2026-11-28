package com.doubleangels.redact.media;

import static com.doubleangels.redact.TestWaits.callOffMain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.media3.common.Effect;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Crop;

import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class VideoCensorExporterTest {

    private static final CensorRegion RECT = CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.4f);

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        VideoMedia3Converter.setAwaitTimeoutForTests(null);
    }

    @Test
    public void roundedOutward_widensEveryRange() {
        List<VideoCensorRegion> rounded = VideoCensorExporter.roundedOutward(List.of(
                VideoCensorRegion.of(RECT, 50_000L, 90_000L), VideoCensorRegion.wholeVideo(RECT)), 33_334L);
        assertEquals(33_334L, rounded.get(0).startUs());
        assertEquals(100_001L, rounded.get(0).endUs());
        assertTrue(rounded.get(1).coversWholeVideo());
    }

    @Test
    public void effects_coverBeforeCropping() {
        List<VideoCensorRegion> regions = List.of(VideoCensorRegion.wholeVideo(RECT));
        List<Effect> effects = VideoCensorExporter.effects(
                regions, CensorRegion.rect(0.25f, 0f, 0.75f, 0.5f), CensorEffects.Effect.BLUR, 33_334L);
        assertEquals(2, effects.size());
        VideoCensorEffect censor = (VideoCensorEffect) effects.get(0);
        assertEquals(regions, censor.regions());
        assertEquals(CensorEffects.Effect.BLUR, censor.defaultEffect());
        assertEquals(66_668L, censor.frameDurationUs());
        assertTrue(effects.get(1) instanceof Crop);
    }

    @Test
    public void effects_withoutACrop_onlyCover() {
        List<VideoCensorRegion> regions = List.of(VideoCensorRegion.wholeVideo(RECT));
        assertEquals(1, VideoCensorExporter.effects(regions, null, CensorEffects.Effect.BLACK, 0L).size());
        assertEquals(1, VideoCensorExporter.effects(
                regions, CensorRegion.rect(0.5f, 0f, 0.5f, 1f), CensorEffects.Effect.BLACK, 0L).size());
    }

    @Test
    public void frameMargin_coversTwoFrames_andALowRateFrame() {
        // 60 fps: two frames is less than one frame at 24 fps, which an export may drop to.
        assertEquals(41_667L, VideoCensorExporter.frameMarginUs(16_667L));
        // 24 fps: two of its own frames.
        assertEquals(83_334L, VideoCensorExporter.frameMarginUs(41_667L));
        assertEquals(VideoCensorEffect.DEFAULT_FRAME_DURATION_US, VideoCensorExporter.frameMarginUs(0L));
    }

    @Test
    public void formatIndex_matchesAnH265Source_andIsH264Otherwise() {
        assertEquals(1, VideoCensorExporter.formatIndexFor(MimeTypes.VIDEO_H265));
        assertEquals(0, VideoCensorExporter.formatIndexFor(MimeTypes.VIDEO_H264));
        assertEquals(0, VideoCensorExporter.formatIndexFor(MimeTypes.VIDEO_VP9));
        assertEquals(0, VideoCensorExporter.formatIndexFor(null));
    }

    @Test
    public void frameInterval_roundsUp_andIsZeroWhenUnknown() {
        assertEquals(33_334L, new VideoCensorExporter.Source(1, 1, 30, null).frameIntervalUs());
        assertEquals(16_667L, new VideoCensorExporter.Source(1, 1, 60, null).frameIntervalUs());
        assertEquals(0L, new VideoCensorExporter.Source(1, 1, 0, null).frameIntervalUs());
    }

    private Uri videoWith(String width, String height, String rotation) throws IOException {
        File file = File.createTempFile("censor_src_", ".mp4", app.getCacheDir());
        file.deleteOnExit();
        Uri uri = Uri.fromFile(file);
        DataSource source = DataSource.toDataSource(app, uri);
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, width);
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, height);
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, "300");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "10000");
        if (rotation != null) {
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, rotation);
        }
        return uri;
    }

    @Test
    public void readSource_turnsASidewaysVideoUpright() throws IOException {
        VideoCensorExporter.Source portrait = VideoCensorExporter.readSource(app, videoWith("1920", "1080", "90"));
        assertEquals(1080, portrait.width);
        assertEquals(1920, portrait.height);
        assertEquals(30, portrait.frameRate);

        VideoCensorExporter.Source flipped = VideoCensorExporter.readSource(app, videoWith("1920", "1080", "180"));
        assertEquals(1920, flipped.width);
        assertEquals(1080, flipped.height);

        VideoCensorExporter.Source unrotated = VideoCensorExporter.readSource(app, videoWith("1280", "720", null));
        assertEquals(1280, unrotated.width);
        assertEquals(720, unrotated.height);

        VideoCensorExporter.Source odd = VideoCensorExporter.readSource(app, videoWith("1280", "720", "sideways"));
        assertEquals(1280, odd.width);
    }

    @Test
    public void export_ofAVideoWithNoReadableSize_failsAndSavesNothing() throws IOException {
        File file = File.createTempFile("censor_src_", ".mp4", app.getCacheDir());
        file.deleteOnExit();
        int before = cacheFiles("vid_censor_");
        assertThrows(UnsupportedMediaException.class, () -> VideoCensorExporter.export(app, Uri.fromFile(file),
                "clip.mp4", List.of(VideoCensorRegion.wholeVideo(RECT)), null, CensorEffects.Effect.BLACK,
                null, -1L, null));
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        assertEquals(before, cacheFiles("vid_censor_"));
    }

    @Test
    public void export_cancelledBeforeItStarts_leavesNothing() throws IOException {
        Uri uri = videoWith("1280", "720", null);
        int before = cacheFiles("vid_censor_");
        assertThrows(ProcessingCancelledException.class, () -> VideoCensorExporter.export(app, uri,
                "clip.mp4", List.of(VideoCensorRegion.wholeVideo(RECT)), null, CensorEffects.Effect.PIXELATE,
                null, -1L, () -> true));
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        assertEquals(before, cacheFiles("vid_censor_"));
    }

    @Test(timeout = 30_000)
    public void export_thatFailsToEncode_deletesItsWorkingCopy() throws IOException {
        Uri uri = videoWith("1280", "720", null);
        int before = cacheFiles("vid_censor_");
        // Robolectric has no codecs, so Media3 never finishes: take the timeout path.
        VideoMedia3Converter.setAwaitTimeoutForTests(500L);
        assertThrows(Exception.class, () -> callOffMain(() -> VideoCensorExporter.export(app, uri,
                "clip.mp4", List.of(VideoCensorRegion.wholeVideo(RECT)), null, CensorEffects.Effect.PIXELATE,
                null, -1L, null)));
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        assertEquals(before, cacheFiles("vid_censor_"));
    }

    private int cacheFiles(String prefix) {
        File[] files = app.getCacheDir().listFiles((dir, name) -> name.startsWith(prefix));
        return files == null ? 0 : files.length;
    }

    @Test
    public void source_keepsWhatItRead() {
        VideoCensorExporter.Source source = new VideoCensorExporter.Source(640, 480, 25, MimeTypes.VIDEO_H264);
        assertEquals(MimeTypes.VIDEO_H264, source.mime);
        assertNull(new VideoCensorExporter.Source(0, 0, 0, null).mime);
    }
    @Test
    public void aCensoredExport_getsABitrateFloor_scaledWithItsSize() {
        assertEquals(921_600, VideoMedia3Converter.editedBitrateFloor(320, 240, 30, 0, 0));
        // Portrait is the same; an unknown frame rate counts as 30.
        assertEquals(921_600, VideoMedia3Converter.editedBitrateFloor(240, 320, 0, 0, 0));
        int hd = VideoMedia3Converter.editedBitrateFloor(1920, 1080, 30, 0, 0);
        assertTrue("1080p30 stays below what cameras record: " + hd, hd > 10_000_000 && hd < 12_000_000);
        // Exported smaller or at a lower rate: the floor follows the output.
        assertEquals(hd, VideoMedia3Converter.editedBitrateFloor(3840, 2160, 30, 1080, 0));
        assertTrue(VideoMedia3Converter.editedBitrateFloor(1920, 1080, 60, 0, 30) == hd);
        assertEquals(0, VideoMedia3Converter.editedBitrateFloor(0, 1080, 30, 0, 0));
    }
}
